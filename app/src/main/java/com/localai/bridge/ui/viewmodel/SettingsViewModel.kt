package com.localai.bridge.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.localai.bridge.data.DiscoveredModel
import com.localai.bridge.data.HuggingFaceApiClient
import com.localai.bridge.data.HuggingFaceAuthManager
import com.localai.bridge.data.HuggingFaceOAuthConfig
import com.localai.bridge.data.HuggingFaceTokenManager
import com.localai.bridge.data.ModelDiscovery
import com.localai.bridge.data.PreferencesManager
import com.localai.bridge.di.AppContainer
import com.localai.bridge.manager.LlmManager
import com.localai.bridge.transcription.TranscriptionBackendManager
import com.localai.bridge.ui.theme.ThemeType
import com.localai.bridge.util.LocaleManager
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
class SettingsViewModel(
    application: Application,
    private val preferencesManager: PreferencesManager,
    private val huggingFaceTokenManager: HuggingFaceTokenManager,
    private val huggingFaceAuthManager: HuggingFaceAuthManager
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    // Keep-alive timeout options in minutes
    val timeoutOptions = listOf(1, 2, 5, 10, 15, 30, 60)

    // Language options with display names
    data class LanguageOption(val code: String, val displayName: String)
    val languageOptions = listOf(
        LanguageOption("system", "System Default"),
        LanguageOption("en", "English"),
        LanguageOption("it", "Italiano")
    )

    // Theme options
    val themeOptions = ThemeType.entries

    // Current keep-alive timeout from preferences
    val keepAliveTimeout: StateFlow<Int> = preferencesManager.keepAliveTimeout
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 5
        )

    // Auto-copy transcription results preference
    val autoCopyEnabled: StateFlow<Boolean> = preferencesManager.autoCopyEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    // Current language from Per-App Language API (not DataStore)
    private val _currentLanguage = MutableStateFlow(LocaleManager.getCurrentLocaleCode())
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    // Current theme from preferences
    private val _currentTheme = MutableStateFlow(ThemeType.DEFAULT)
    val currentTheme: StateFlow<ThemeType> = _currentTheme.asStateFlow()

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
        val transcriptionBackend: String = "llm"
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
                if (LlmManager.isReady()) {
                    LlmManager.setKeepAliveTimeout(minutes)
                }

                _uiState.update { it.copy(isSaving = false, saveSuccess = true) }

                // Clear success message after delay
                kotlinx.coroutines.delay(2000)
                _uiState.update { it.copy(saveSuccess = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isSaving = false,
                    saveSuccess = false,
                    errorMessage = e.message ?: "Failed to save settings"
                )}
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
     * Unloads the currently loaded model.
     * Works for both LLM backend and other backends via TranscriptionBackendManager.
     */
    fun unloadModel() {
        viewModelScope.launch {
            // Unload via LlmManager (for LLM backend)
            if (LlmManager.isReady()) {
                LlmManager.unload()
            }
            // Also unload via backend manager (covers all backends)
            TranscriptionBackendManager.unloadActiveBackend()

            Log.i(TAG, "Model unloaded manually")
        }
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
                errorMessage = e.message ?: "Failed to save language preference"
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
                    errorMessage = e.message ?: "Failed to save theme preference"
                )}
            }
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
            _uiState.update { it.copy(errorMessage = "Token cannot be empty") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isValidatingToken = true, errorMessage = null) }
            huggingFaceTokenManager.setTokenState(HuggingFaceTokenManager.TokenState.Validating)

            val apiClient = AppContainer.huggingFaceApiClient
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
                    _uiState.update { it.copy(errorMessage = "Authentication cancelled") }
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
     * Loads the current model path from preferences.
     * Checks backend preference to determine which model to show.
     */
    fun loadCurrentModel() {
        viewModelScope.launch {
            // Check which backend is selected
            val backend = preferencesManager.transcriptionBackend.first()
            _uiState.update { it.copy(transcriptionBackend = backend) }

            when (backend) {
                "sherpa-onnx" -> {
                    // Show Parakeet model
                    preferencesManager.parakeetModelPath.collect { path ->
                        _uiState.update { it.copy(
                            currentModelPath = path,
                            currentModelName = if (!path.isNullOrBlank()) "Parakeet TDT" else null
                        )}
                    }
                }
                "whisper" -> {
                    // Show Whisper model
                    preferencesManager.whisperModelPath.collect { path ->
                        val modelName = if (!path.isNullOrBlank()) {
                            val modelDir = java.io.File(path)
                            val model = com.localai.bridge.transcription.WhisperModelManager.validateModelDirectory(modelDir)
                            model?.variant?.let { v ->
                                getApplication<Application>().getString(v.titleResId)
                            } ?: path.substringAfterLast("/")
                        } else null
                        _uiState.update { it.copy(
                            currentModelPath = path,
                            currentModelName = modelName
                        )}
                    }
                }
                else -> {
                    // Show LLM model (Gemma, etc.)
                    preferencesManager.modelPath.collect { path ->
                        _uiState.update { it.copy(
                            currentModelPath = path,
                            currentModelName = path?.let { File(it).name }
                        )}
                    }
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
            preferencesManager.saveTranscriptionBackend("llm")
            // Refresh the list to update current selection
            scanAvailableModels()
            // Update current model display
            _uiState.update { it.copy(
                currentModelPath = model.path,
                currentModelName = model.name
            )}
        }
    }

    /**
     * Factory for creating SettingsViewModel with dependencies.
     */
    class Factory(
        private val application: Application,
        private val preferencesManager: PreferencesManager,
        private val huggingFaceTokenManager: HuggingFaceTokenManager,
        private val huggingFaceAuthManager: HuggingFaceAuthManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                return SettingsViewModel(
                    application,
                    preferencesManager,
                    huggingFaceTokenManager,
                    huggingFaceAuthManager
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
