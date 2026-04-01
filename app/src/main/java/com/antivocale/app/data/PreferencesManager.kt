package com.antivocale.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "localai_preferences")

/**
 * Manages persistent app preferences using DataStore.
 */
class PreferencesManager(private val context: Context) {

    companion object {
        private val MODEL_PATH = stringPreferencesKey("model_path")
        private val KEEP_ALIVE_TIMEOUT = intPreferencesKey("keep_alive_timeout_v2")
        // Legacy key for migration from string-based storage
        private val KEEP_ALIVE_TIMEOUT_LEGACY = stringPreferencesKey("keep_alive_timeout")
        // Kept for migration purposes only
        private val LANGUAGE_PREFERENCE = stringPreferencesKey("language_preference")
        private val THEME_PREFERENCE = stringPreferencesKey("theme_preference")
        // Transcription backend preference
        private val TRANSCRIPTION_BACKEND = stringPreferencesKey("transcription_backend")
        // Parakeet model path (for sherpa-onnx backend)
        private val PARAKEET_MODEL_PATH = stringPreferencesKey("parakeet_model_path")
        // Whisper model path (for sherpa-onnx Whisper backend)
        private val WHISPER_MODEL_PATH = stringPreferencesKey("whisper_model_path")
        // Qwen3-ASR model path (for sherpa-onnx Qwen3-ASR backend)
        private val QWEN3_ASR_MODEL_PATH = stringPreferencesKey("qwen3_asr_model_path")
        // Auto-copy transcription results to clipboard
        private val AUTO_COPY_ENABLED = booleanPreferencesKey("auto_copy_enabled")
        // VAD silence stripping
        private val VAD_ENABLED = booleanPreferencesKey("vad_enabled")
        // Default prompt for transcription
        private val DEFAULT_PROMPT = stringPreferencesKey("default_prompt")
        // Inference thread count
        private val THREAD_COUNT = intPreferencesKey("thread_count")

        // Default values (single source of truth)
        const val DEFAULT_KEEP_ALIVE_TIMEOUT = 5
        val DEFAULT_THREAD_COUNT = maxOf(2, Runtime.getRuntime().availableProcessors() - 2)
        const val DEFAULT_AUTO_COPY_ENABLED = false
        const val DEFAULT_VAD_ENABLED = false
        const val DEFAULT_PROMPT_VALUE = ""
        const val DEFAULT_THEME = "DEFAULT"
        const val DEFAULT_TRANSCRIPTION_BACKEND = "llm"
        const val DEFAULT_LANGUAGE = "system"
    }

    /**
     * In-memory cache of all preferences, populated eagerly at startup.
     * Eliminates UI flicker by providing synchronous initial values via .onStart.
     */
    private val cache = AtomicReference(CachedPreferences())

    private data class CachedPreferences(
        val modelPath: String? = null,
        val keepAliveTimeout: Int = DEFAULT_KEEP_ALIVE_TIMEOUT,
        val themePreference: String = DEFAULT_THEME,
        val transcriptionBackend: String = DEFAULT_TRANSCRIPTION_BACKEND,
        val parakeetModelPath: String? = null,
        val whisperModelPath: String? = null,
        val qwen3AsrModelPath: String? = null,
        val autoCopyEnabled: Boolean = DEFAULT_AUTO_COPY_ENABLED,
        val vadEnabled: Boolean = DEFAULT_VAD_ENABLED,
        val defaultPrompt: String = DEFAULT_PROMPT_VALUE,
        val threadCount: Int = DEFAULT_THREAD_COUNT
    )

    /**
     * Maps a DataStore [Preferences] snapshot to [CachedPreferences],
     * applying defaults and legacy migration in one place.
     */
    private fun Preferences.toCached() = CachedPreferences(
        modelPath = this[MODEL_PATH],
        keepAliveTimeout = this[KEEP_ALIVE_TIMEOUT]
            ?: this[KEEP_ALIVE_TIMEOUT_LEGACY]?.toIntOrNull()
            ?: DEFAULT_KEEP_ALIVE_TIMEOUT,
        themePreference = this[THEME_PREFERENCE] ?: DEFAULT_THEME,
        transcriptionBackend = this[TRANSCRIPTION_BACKEND] ?: DEFAULT_TRANSCRIPTION_BACKEND,
        parakeetModelPath = this[PARAKEET_MODEL_PATH],
        whisperModelPath = this[WHISPER_MODEL_PATH],
        qwen3AsrModelPath = this[QWEN3_ASR_MODEL_PATH],
        autoCopyEnabled = this[AUTO_COPY_ENABLED] ?: DEFAULT_AUTO_COPY_ENABLED,
        vadEnabled = this[VAD_ENABLED] ?: DEFAULT_VAD_ENABLED,
        defaultPrompt = this[DEFAULT_PROMPT] ?: DEFAULT_PROMPT_VALUE,
        threadCount = this[THREAD_COUNT] ?: DEFAULT_THREAD_COUNT
    )

    /**
     * Eagerly reads all preferences from DataStore and caches them in memory.
     * Must be called once during app startup (from AppContainer.initialize()).
     */
    fun initialize() {
        runBlocking {
            cache.set(context.dataStore.data.first().toCached())
        }
    }

    /**
     * Flow of the saved model path.
     */
    val modelPath: Flow<String?> = context.dataStore.data.map { it.toCached().modelPath }
        .onStart { emit(cache.get().modelPath) }

    /**
     * Saves the model path.
     */
    suspend fun saveModelPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[MODEL_PATH] = path
        }
        cache.updateAndGet { it.copy(modelPath = path) }
    }

    /**
     * Clears the saved model path.
     */
    suspend fun clearModelPath() {
        context.dataStore.edit { preferences ->
            preferences.remove(MODEL_PATH)
        }
        cache.updateAndGet { it.copy(modelPath = null) }
    }

    /**
     * Flow of the keep-alive timeout in minutes.
     */
    val keepAliveTimeout: Flow<Int> = context.dataStore.data.map { it.toCached().keepAliveTimeout }
        .onStart { emit(cache.get().keepAliveTimeout) }

    /**
     * Saves the keep-alive timeout.
     */
    suspend fun saveKeepAliveTimeout(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEEP_ALIVE_TIMEOUT] = minutes
            preferences.remove(KEEP_ALIVE_TIMEOUT_LEGACY)
        }
        cache.updateAndGet { it.copy(keepAliveTimeout = minutes) }
    }

    /**
     * Reads the legacy language preference for migration purposes.
     * Returns "system" by default.
     */
    suspend fun getLegacyLanguagePreference(): String {
        return context.dataStore.data.map { preferences ->
            preferences[LANGUAGE_PREFERENCE] ?: DEFAULT_LANGUAGE
        }.first()
    }

    /**
     * Flow of the saved theme preference.
     * Returns "DEFAULT" by default.
     */
    val themePreference: Flow<String> = context.dataStore.data.map { it.toCached().themePreference }
        .onStart { emit(cache.get().themePreference) }

    /**
     * Saves the theme preference.
     */
    suspend fun saveThemePreference(theme: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_PREFERENCE] = theme
        }
        cache.updateAndGet { it.copy(themePreference = theme) }
    }

    /**
     * Flow of the selected transcription backend.
     * Returns "llm" by default (LiteRT-LM backend).
     */
    val transcriptionBackend: Flow<String> = context.dataStore.data.map { it.toCached().transcriptionBackend }
        .onStart { emit(cache.get().transcriptionBackend) }

    /**
     * Saves the transcription backend preference.
     */
    suspend fun saveTranscriptionBackend(backendId: String) {
        context.dataStore.edit { preferences ->
            preferences[TRANSCRIPTION_BACKEND] = backendId
        }
        cache.updateAndGet { it.copy(transcriptionBackend = backendId) }
    }

    /**
     * Flow of the Parakeet model path (for sherpa-onnx backend).
     */
    val parakeetModelPath: Flow<String?> = context.dataStore.data.map { it.toCached().parakeetModelPath }
        .onStart { emit(cache.get().parakeetModelPath) }

    /**
     * Saves the Parakeet model path.
     */
    suspend fun saveParakeetModelPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[PARAKEET_MODEL_PATH] = path
        }
        cache.updateAndGet { it.copy(parakeetModelPath = path) }
    }

    /**
     * Clears the Parakeet model path.
     */
    suspend fun clearParakeetModelPath() {
        context.dataStore.edit { preferences ->
            preferences.remove(PARAKEET_MODEL_PATH)
        }
        cache.updateAndGet { it.copy(parakeetModelPath = null) }
    }

    /**
     * Flow of the Whisper model path (for sherpa-onnx Whisper backend).
     */
    val whisperModelPath: Flow<String?> = context.dataStore.data.map { it.toCached().whisperModelPath }
        .onStart { emit(cache.get().whisperModelPath) }

    /**
     * Saves the Whisper model path.
     */
    suspend fun saveWhisperModelPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[WHISPER_MODEL_PATH] = path
        }
        cache.updateAndGet { it.copy(whisperModelPath = path) }
    }

    /**
     * Clears the Whisper model path.
     */
    suspend fun clearWhisperModelPath() {
        context.dataStore.edit { preferences ->
            preferences.remove(WHISPER_MODEL_PATH)
        }
        cache.updateAndGet { it.copy(whisperModelPath = null) }
    }

    /**
     * Flow of the Qwen3-ASR model path (for sherpa-onnx Qwen3-ASR backend).
     */
    val qwen3AsrModelPath: Flow<String?> = context.dataStore.data.map { it.toCached().qwen3AsrModelPath }
        .onStart { emit(cache.get().qwen3AsrModelPath) }

    /**
     * Saves the Qwen3-ASR model path.
     */
    suspend fun saveQwen3AsrModelPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[QWEN3_ASR_MODEL_PATH] = path
        }
        cache.updateAndGet { it.copy(qwen3AsrModelPath = path) }
    }

    /**
     * Clears the Qwen3-ASR model path.
     */
    suspend fun clearQwen3AsrModelPath() {
        context.dataStore.edit { preferences ->
            preferences.remove(QWEN3_ASR_MODEL_PATH)
        }
        cache.updateAndGet { it.copy(qwen3AsrModelPath = null) }
    }

    /**
     * Flow of auto-copy enabled preference.
     * Returns false by default (user must manually copy).
     */
    val autoCopyEnabled: Flow<Boolean> = context.dataStore.data.map { it.toCached().autoCopyEnabled }
        .onStart { emit(cache.get().autoCopyEnabled) }

    /**
     * Saves the auto-copy enabled preference.
     */
    suspend fun saveAutoCopyEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_COPY_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(autoCopyEnabled = enabled) }
    }

    /**
     * Flow of VAD silence stripping enabled preference.
     * Returns false by default (VAD disabled, user must opt in).
     */
    val vadEnabled: Flow<Boolean> = context.dataStore.data.map { it.toCached().vadEnabled }
        .onStart { emit(cache.get().vadEnabled) }

    /**
     * Saves the VAD enabled preference.
     */
    suspend fun saveVadEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VAD_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(vadEnabled = enabled) }
    }

    /**
     * Flow of the default prompt for transcription.
     * Returns empty string by default (use system default).
     */
    val defaultPrompt: Flow<String> = context.dataStore.data.map { it.toCached().defaultPrompt }
        .onStart { emit(cache.get().defaultPrompt) }

    /**
     * Saves the default prompt.
     * Enforces a maximum length of 500 characters.
     */
    suspend fun saveDefaultPrompt(prompt: String) {
        val truncated = prompt.take(500)
        context.dataStore.edit { preferences ->
            preferences[DEFAULT_PROMPT] = truncated
        }
        cache.updateAndGet { it.copy(defaultPrompt = truncated) }
    }

    /**
     * Flow of the inference thread count.
     * Returns auto-detected value on first launch.
     */
    val threadCount: Flow<Int> = context.dataStore.data.map { it.toCached().threadCount }
        .onStart { emit(cache.get().threadCount) }

    /**
     * Saves the inference thread count.
     */
    suspend fun saveThreadCount(threads: Int) {
        context.dataStore.edit { preferences ->
            preferences[THREAD_COUNT] = threads
        }
        cache.updateAndGet { it.copy(threadCount = threads) }
    }
}
