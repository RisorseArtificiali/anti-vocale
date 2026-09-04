package com.antivocale.app.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.antivocale.app.R
import com.antivocale.app.data.DiscoveredModel
import com.antivocale.app.data.HuggingFaceApiClient
import com.antivocale.app.data.HuggingFaceAuthManager
import com.antivocale.app.data.HuggingFaceOAuthConfig
import com.antivocale.app.data.HuggingFaceTokenManager
import com.antivocale.app.data.ModelDiscovery
import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.ShareTargetManager
import com.antivocale.app.data.TranscriptionCalibrator
import com.antivocale.app.transcription.InferenceProvider
import com.antivocale.app.transcription.PunctuationPolicy
import com.antivocale.app.transcription.TranscriptionLanguagePolicy
import com.antivocale.app.manager.LlmManager
// GGUF: import com.antivocale.app.transcription.Gemma4GgufBackend
// GGUF: import com.antivocale.app.transcription.Gemma4GgufModelManager
import com.antivocale.app.transcription.TranscriptionBackendManager
import com.antivocale.app.ui.theme.ThemeMode
import com.antivocale.app.ui.theme.ThemeType
import com.antivocale.app.util.LanguageNames
import com.antivocale.app.util.LocaleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Settings screen.
 *
 * Manages:
 * - Keep-alive timeout configuration
 * - Model auto-unload settings
 * - HuggingFace token management (manual and OAuth)
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val preferencesManager: PreferencesManager,
    private val logDao: com.antivocale.app.data.local.LogDao,
    private val huggingFaceTokenManager: HuggingFaceTokenManager,
    val huggingFaceAuthManager: HuggingFaceAuthManager,
    private val huggingFaceApiClient: HuggingFaceApiClient,
    val perAppPreferencesManager: PerAppPreferencesManager,
    val transcriptionCalibrator: TranscriptionCalibrator,
    private val backendManager: TranscriptionBackendManager,
    private val llmManager: LlmManager,
    private val shareTargetManager: ShareTargetManager,
    private val activeModelRepository: ActiveModelRepository
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    val llmIsReadyFlow: StateFlow<Boolean> = llmManager.isReadyFlow
    val llmRemainingTimeSeconds: Long? get() = llmManager.getRemainingTimeSeconds()

    // Keep-alive timeout options in minutes
    val timeoutOptions = listOf(1, 2, 5, 10, 15, 30, 60)

    // Language options with display names (native names: users find their
    // language by its own name). TASK-353: sorted at READ time per the active
    // app locale; see languageOptionsFor below.
    val languageOptions: List<LanguageOption> =
        languageOptionsFor(LocaleManager.effectiveLocale())

    val transcriptionLanguageOptions: List<LanguageOption> =
        transcriptionOptionsFor(LocaleManager.effectiveLocale())

    // Theme options
    val themeOptions = ThemeType.entries
    val themeModeOptions = ThemeMode.entries

    // Current keep-alive timeout from preferences
    val keepAliveTimeout: StateFlow<Int> = preferencesManager.keepAliveTimeout
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT
        )

    // Auto-copy transcription results preference
    val autoCopyEnabled: StateFlow<Boolean> = preferencesManager.autoCopyEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.DEFAULT_AUTO_COPY_ENABLED
        )

    // Output folder for auto-saving transcripts as .txt (issue #14). null = disabled.
    val outputFolderUri: StateFlow<String?> = preferencesManager.outputFolderUri
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // VAD silence stripping preference
    val vadEnabled: StateFlow<Boolean> = preferencesManager.vadEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.DEFAULT_VAD_ENABLED
        )

    // Progressive transcription display preference
    val progressiveTranscription: StateFlow<Boolean> = preferencesManager.progressiveTranscription
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.DEFAULT_PROGRESSIVE_TRANSCRIPTION
        )

    // Inference thread count
    val threadCount: StateFlow<Int> = preferencesManager.threadCount
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.DEFAULT_THREAD_COUNT
        )

    // Inference provider (auto/nnapi/cpu)
    val inferenceProvider: StateFlow<String> = preferencesManager.inferenceProvider
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.DEFAULT_INFERENCE_PROVIDER
        )

    // Auto-detected thread count (fixed at init time)
    val autoDetectedThreadCount: Int = PreferencesManager.DEFAULT_THREAD_COUNT

    // Default prompt for transcription
    val defaultPrompt: StateFlow<String> = preferencesManager.defaultPrompt
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.DEFAULT_PROMPT_VALUE
        )

    // Transcription language preference
    val currentTranscriptionLanguage: StateFlow<String> = preferencesManager.transcriptionLanguage
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.DEFAULT_TRANSCRIPTION_LANGUAGE
        )

    // TASK-276: punctuation pass mode + user prompt override.
    val punctuationModeOptions: List<String> = PunctuationPolicy.MODE_PREFS
    val currentPunctuationMode: StateFlow<String> = preferencesManager.punctuationMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.DEFAULT_PUNCTUATION_MODE
        )
    val currentPunctuationPrompt: StateFlow<String> = preferencesManager.punctuationPrompt
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    // TASK-336: background-kill detection (cold-start sweep marker rows) for the
    // battery-exemption card. Only re-offered after a NEW interruption.
    private val _backgroundKills = MutableStateFlow(0)
    val backgroundKills: StateFlow<Int> = _backgroundKills.asStateFlow()

    // GH #45 follow-up: opt-in task-id detail line on log entries
    val showTaskDetails: StateFlow<Boolean> = preferencesManager.showTaskDetails
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.DEFAULT_SHOW_TASK_DETAILS
        )

    fun refreshBackgroundKills() {
        viewModelScope.launch {
            // Look back 30 days: enough history to matter, bounded so the card
            // does not haunt users forever after one old incident.
            val since = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
            _backgroundKills.value = runCatching {
                logDao.countInterruptedSince(since)
            }.getOrDefault(0)
        }
    }

    fun saveShowTaskDetails(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveShowTaskDetails(enabled)
        }
    }

    // Swipe action mode preference
    val swipeActionMode: StateFlow<String> = preferencesManager.swipeActionMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.DEFAULT_SWIPE_ACTION_MODE
        )

    val groupLogsByConversation: StateFlow<Boolean> = preferencesManager.groupLogsByConversation
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.DEFAULT_GROUP_LOGS_BY_CONVERSATION
        )

    fun saveGroupLogsByConversation(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveGroupLogsByConversation(enabled)
        }
    }

    val advancedSharingEnabled: StateFlow<Boolean> = preferencesManager.advancedSharingEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.DEFAULT_ADVANCED_SHARING_ENABLED
        )

    fun saveAdvancedSharingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveAdvancedSharingEnabled(enabled)
            shareTargetManager.setAdvancedSharingEnabled(enabled)
        }
    }

    val showRetranscribeButton: StateFlow<Boolean> = preferencesManager.showRetranscribeButton
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.DEFAULT_SHOW_RETRANSCRIBE_BUTTON
        )

    fun saveShowRetranscribeButton(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveShowRetranscribeButton(enabled)
        }
    }

    val forceModelLoad: StateFlow<Boolean> = preferencesManager.forceModelLoad
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.DEFAULT_FORCE_MODEL_LOAD
        )

    fun saveForceModelLoad(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveForceModelLoad(enabled)
        }
    }

    val compactResultActions: StateFlow<Boolean> = preferencesManager.compactResultActions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.DEFAULT_COMPACT_RESULT_ACTIONS
        )

    fun saveCompactResultActions(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveCompactResultActions(enabled)
        }
    }

    // Current language from Per-App Language API (not DataStore)
    private val _currentLanguage = MutableStateFlow(LocaleManager.getCurrentLocaleCode())
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    // Current theme from preferences
    private val _currentTheme = MutableStateFlow(ThemeType.DEFAULT)
    val currentTheme: StateFlow<ThemeType> = _currentTheme.asStateFlow()

    // Current theme mode (System / Dark / Light) from preferences
    private val _currentThemeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val currentThemeMode: StateFlow<ThemeMode> = _currentThemeMode.asStateFlow()

    // HuggingFace token state
    val tokenState = huggingFaceTokenManager.tokenState

    // OAuth configuration status
    val isOAuthConfigured: Boolean
        get() = HuggingFaceOAuthConfig.isConfigured()

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    private val _tokenInput = MutableStateFlow("")
    val tokenInput = _tokenInput.asStateFlow()

    // OAuth flow state
    private val _oauthState = MutableStateFlow<OAuthState>(OAuthState.Idle)
    val oauthState: StateFlow<OAuthState> = _oauthState.asStateFlow()

    init {
        // Load theme from preferences
        viewModelScope.launch {
            preferencesManager.themePreference.collect { themeName ->
                _currentTheme.value = try {
                    ThemeType.valueOf(themeName)
                } catch (e: IllegalArgumentException) {
                    ThemeType.DEFAULT
                }
            }
        }
        // Load theme mode from preferences
        viewModelScope.launch {
            preferencesManager.themeMode.collect { modeName ->
                _currentThemeMode.value = try {
                    ThemeMode.valueOf(modeName)
                } catch (e: IllegalArgumentException) {
                    ThemeMode.SYSTEM
                }
            }
        }
    }

    /**
     * OAuth flow state.
     */
    sealed class OAuthState {
        data object Idle : OAuthState()
        data object InProgress : OAuthState()
        data class Success(val username: String) : OAuthState()
        data class Error(val message: String) : OAuthState()
    }

    data class SettingsUiState(
        val isSaving: Boolean = false,
        val saveSuccess: Boolean? = null,
        val errorMessage: String? = null,
        val isValidatingToken: Boolean = false,
        // Model selection state
        val currentModelPath: String? = null,
        val currentModelName: String? = null,
        val availableModels: List<DiscoveredModel> = emptyList(),
        // Backend preference
        val transcriptionBackend: String = PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND
    )

    /**
     * Saves the keep-alive timeout.
     * Also applies it to the current LlmManager if a model is loaded.
     */
    fun saveKeepAliveTimeout(minutes: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveSuccess = null, errorMessage = null) }

            try {
                // Save to preferences
                preferencesManager.saveKeepAliveTimeout(minutes)

                // Apply to LlmManager if model is loaded
                if (llmManager.isReady()) {
                    llmManager.setKeepAliveTimeout(minutes)
                }

                _uiState.update { it.copy(isSaving = false, saveSuccess = true) }

                // Clear success message after delay
                kotlinx.coroutines.delay(2000)
                _uiState.update { it.copy(saveSuccess = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isSaving = false,
                    saveSuccess = false,
                    errorMessage = e.message ?: getApplication<Application>().getString(R.string.error_save_settings)
                )}
            }
        }
    }

    /**
     * Saves the inference thread count.
     */
    fun saveThreadCount(threads: Int) {
        viewModelScope.launch {
            try {
                preferencesManager.saveThreadCount(threads)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save thread count", e)
            }
        }
    }

    fun saveInferenceProvider(provider: String) {
        viewModelScope.launch {
            try {
                preferencesManager.saveInferenceProvider(provider)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save inference provider", e)
            }
        }
    }

    /**
     * Saves the auto-copy enabled preference.
     */
    fun saveAutoCopyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveAutoCopyEnabled(enabled)
        }
    }

    /**
     * Sets the output folder URI for transcript auto-save. Pass null to clear (disables).
     */
    fun saveOutputFolderUri(uri: String?) {
        viewModelScope.launch {
            preferencesManager.saveOutputFolderUri(uri)
        }
    }

    /**
     * Saves the VAD enabled preference.
     */
    fun saveVadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveVadEnabled(enabled)
        }
    }

    /**
     * Saves the progressive transcription preference.
     */
    fun saveProgressiveTranscription(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveProgressiveTranscription(enabled)
        }
    }

    /**
     * Saves the transcription language preference.
     */
    fun saveTranscriptionLanguage(language: String) {
        viewModelScope.launch {
            preferencesManager.saveTranscriptionLanguage(language)
        }
    }

    fun savePunctuationMode(mode: String) {
        viewModelScope.launch {
            preferencesManager.savePunctuationMode(mode)
        }
    }

    fun savePunctuationPrompt(prompt: String) {
        viewModelScope.launch {
            preferencesManager.savePunctuationPrompt(prompt)
        }
    }


    /**
     * Saves the swipe action mode preference.
     */
    fun saveSwipeActionMode(mode: String) {
        viewModelScope.launch {
            preferencesManager.saveSwipeActionMode(mode)
        }
    }

    /**
     * Saves the default prompt for transcription.
     * Enforces a maximum length of 500 characters.
     */
    fun saveDefaultPrompt(prompt: String) {
        viewModelScope.launch {
            Log.d(TAG, "Saving default prompt: '$prompt'")
            preferencesManager.saveDefaultPrompt(prompt)
            _uiState.update { it.copy(saveSuccess = true) }

            // Clear success message after delay
            kotlinx.coroutines.delay(2000)
            _uiState.update { it.copy(saveSuccess = null) }
        }
    }

    /**
     * Unloads the currently loaded model.
     * Works for both LLM backend and other backends via TranscriptionBackendManager.
     */
    fun unloadModel() {
        backendManager.unloadAll()
        Log.i(TAG, "Model unloaded manually")
    }

    /**
     * Saves the language preference using Per-App Language API.
     * Changes take effect immediately without app restart.
     */
    fun saveLanguagePreference(language: String) {
        _uiState.update { it.copy(isSaving = true, saveSuccess = null, errorMessage = null) }

        try {
            LocaleManager.setLocale(language)
            _currentLanguage.value = language
            _uiState.update { it.copy(isSaving = false, saveSuccess = true) }

            // Clear success message after delay
            viewModelScope.launch {
                kotlinx.coroutines.delay(2000)
                _uiState.update { it.copy(saveSuccess = null) }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(
                isSaving = false,
                saveSuccess = false,
                errorMessage = e.message ?: getApplication<Application>().getString(R.string.error_save_language)
            )}
        }
    }

    /**
     * Saves the theme preference.
     * Changes take effect immediately via StateFlow.
     */
    fun saveThemePreference(theme: ThemeType) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveSuccess = null, errorMessage = null) }

            try {
                preferencesManager.saveThemePreference(theme.name)
                _currentTheme.value = theme
                _uiState.update { it.copy(isSaving = false, saveSuccess = true) }

                // Clear success message after delay
                kotlinx.coroutines.delay(2000)
                _uiState.update { it.copy(saveSuccess = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isSaving = false,
                    saveSuccess = false,
                    errorMessage = e.message ?: getApplication<Application>().getString(R.string.error_save_theme)
                )}
            }
        }
    }

    /**
     * Saves the theme mode (System / Dark / Light). Takes effect immediately via StateFlow.
     */
    fun saveThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferencesManager.saveThemeMode(mode.name)
            _currentThemeMode.value = mode
        }
    }

    // ========== HuggingFace Token Management ==========

    /**
     * Updates the token input field.
     */
    fun onTokenInputChanged(input: String) {
        _tokenInput.value = input
    }

    /**
     * Validates and saves the HuggingFace token.
     */
    fun validateAndSaveToken() {
        val token = _tokenInput.value.trim()
        if (token.isEmpty()) {
            _uiState.update { it.copy(errorMessage = getApplication<Application>().getString(R.string.error_token_empty)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isValidatingToken = true, errorMessage = null) }
            huggingFaceTokenManager.setTokenState(HuggingFaceTokenManager.TokenState.Validating)

            val apiClient = huggingFaceApiClient
            when (val result = apiClient.validateToken(token)) {
                is HuggingFaceApiClient.ValidationResult.Success -> {
                    huggingFaceTokenManager.saveToken(token)
                    huggingFaceTokenManager.saveUsername(result.username)
                    huggingFaceTokenManager.setTokenState(
                        HuggingFaceTokenManager.TokenState.Valid(
                            username = result.username,
                            maskedToken = huggingFaceTokenManager.maskToken(token)
                        )
                    )
                    _tokenInput.value = ""
                    _uiState.update { it.copy(isValidatingToken = false, saveSuccess = true) }
                    kotlinx.coroutines.delay(2000)
                    _uiState.update { it.copy(saveSuccess = null) }
                }
                is HuggingFaceApiClient.ValidationResult.Error -> {
                    huggingFaceTokenManager.setTokenState(
                        HuggingFaceTokenManager.TokenState.Invalid(result.message)
                    )
                    _uiState.update { it.copy(
                        isValidatingToken = false,
                        errorMessage = result.message
                    )}
                }
            }
        }
    }

    /**
     * Clears the stored HuggingFace token.
     */
    fun clearToken() {
        huggingFaceTokenManager.clearToken()
        _uiState.update { it.copy(saveSuccess = true) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            _uiState.update { it.copy(saveSuccess = null) }
        }
    }

    // ========== OAuth Authentication ==========

    /**
     * Handles the OAuth callback result.
     * Should be called from the ActivityResult callback.
     *
     * @param data The intent data from the OAuth callback
     */
    fun handleOAuthResult(data: Intent?) {
        Log.i(TAG, "Handling OAuth result")
        _oauthState.value = OAuthState.InProgress

        huggingFaceAuthManager.handleAuthResult(data) { result ->
            when (result) {
                is HuggingFaceAuthManager.AuthResult.Success -> {
                    Log.i(TAG, "OAuth successful for user: ${result.username}")
                    // The tokens are already saved by the callback
                    // Now we need to get them from the token response and save them
                    handleOAuthSuccess(result.username)
                }
                is HuggingFaceAuthManager.AuthResult.Cancelled -> {
                    Log.w(TAG, "OAuth cancelled: ${result.reason}")
                    _oauthState.value = OAuthState.Idle
                    _uiState.update { it.copy(errorMessage = getApplication<Application>().getString(R.string.error_auth_cancelled)) }
                }
                is HuggingFaceAuthManager.AuthResult.Error -> {
                    Log.e(TAG, "OAuth error: ${result.message}")
                    _oauthState.value = OAuthState.Error(result.message)
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
            }
        }
    }

    /**
     * Handles successful OAuth authentication.
     * The tokens should already be extracted from the response.
     */
    private fun handleOAuthSuccess(username: String) {
        _oauthState.value = OAuthState.Success(username)
        _uiState.update { it.copy(saveSuccess = true) }

        // Clear success message after delay
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            _oauthState.value = OAuthState.Idle
            _uiState.update { it.copy(saveSuccess = null) }
        }
    }

    /**
     * Saves OAuth tokens to the token manager.
     *
     * @param accessToken The OAuth access token
     * @param refreshToken The OAuth refresh token
     * @param expiresAt Token expiration timestamp in milliseconds
     * @param username The authenticated user's name
     */
    fun saveOAuthTokens(
        accessToken: String,
        refreshToken: String?,
        expiresAt: Long,
        username: String
    ) {
        huggingFaceTokenManager.saveOAuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken ?: "",
            expiresAt = expiresAt,
            username = username
        )
        _oauthState.value = OAuthState.Success(username)
        _uiState.update { it.copy(saveSuccess = true) }

        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            _oauthState.value = OAuthState.Idle
            _uiState.update { it.copy(saveSuccess = null) }
        }
    }

    /**
     * Clears OAuth state (e.g., when dismissing error dialog).
     */
    fun clearOAuthState() {
        _oauthState.value = OAuthState.Idle
    }

    /**
     * Clears any error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ========== Model Selection ==========

    /**
     * Collects the active model from [ActiveModelRepository], which derives
     * backend, path and display name reactively from preferences; the uiState
     * stays in sync with any backend or model-path change without a manual reload.
     */
    fun loadCurrentModel() {
        viewModelScope.launch {
            activeModelRepository.activeModelFlow.collect { active ->
                _uiState.update {
                    it.copy(
                        transcriptionBackend = active.backendId,
                        currentModelPath = active.modelPath,
                        currentModelName = active.modelName
                    )
                }
            }
        }
    }

    /**
     * Scans for available models from all sources.
     */
    fun scanAvailableModels() {
        viewModelScope.launch {
            val models = ModelDiscovery.discoverAvailableModels(getApplication())
            _uiState.update { it.copy(availableModels = models) }
        }
    }

    /**
     * Selects a model and saves it to preferences.
     */
    fun selectModel(model: DiscoveredModel) {
        viewModelScope.launch {
            preferencesManager.saveModelPath(model.path)
            // Switch to LLM backend when selecting an LLM model
            preferencesManager.saveTranscriptionBackend(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND)
            // Refresh the list to update current selection
            scanAvailableModels()
            // Update current model display
            _uiState.update { it.copy(
                currentModelPath = model.path,
                currentModelName = model.name
            )}
        }
    }
}

// ---- TASK-353: locale-aware language option ordering ----
// Alphabetical order is locale-dependent, so the sort runs at READ time with a
// Collator for the active app locale (what the Android system language picker
// does, frameworks/opt/localepicker LocaleHelper). Display names come from the
// platform ICU/CLDR data via [LanguageNames] (native names: users find their
// language by its own name; see util/LanguageNames.kt). The sentinel entries
// (app language / auto-detect) stay pinned first; their labels are genuinely
// translatable and resolved from string resources at the UI layer, so their
// displayName here is an unused placeholder.
data class LanguageOption(val code: String, val displayName: String)

private val appLanguageCodes =
    listOf("de", "en", "es", "fr", "hi", "it", "pt-BR", "ru")

private val transcriptionLanguageCodes =
    listOf("ar", "de", "en", "es", "fr", "it", "ja", "pt", "zh")

private fun optionsFor(
    sentinelCodes: List<String>,
    codes: List<String>,
    locale: java.util.Locale,
): List<LanguageOption> {
    val collator = java.text.Collator.getInstance(locale)
    val entries = codes
        .map { LanguageOption(it, LanguageNames.nativeLanguageName(it)) }
        .sortedWith { a, b -> collator.compare(a.displayName, b.displayName) }
    return sentinelCodes.map { LanguageOption(it, "") } + entries
}

internal fun languageOptionsFor(locale: java.util.Locale): List<LanguageOption> =
    optionsFor(listOf("system"), appLanguageCodes, locale)

// TASK-434: "system" (the untouched default: follow the app locale where the
// variant supports it) pins first, then explicit "auto" (model-side detection).
internal fun transcriptionOptionsFor(locale: java.util.Locale): List<LanguageOption> =
    optionsFor(
        listOf(TranscriptionLanguagePolicy.PREF_SYSTEM, TranscriptionLanguagePolicy.PREF_AUTO),
        transcriptionLanguageCodes,
        locale,
    )
