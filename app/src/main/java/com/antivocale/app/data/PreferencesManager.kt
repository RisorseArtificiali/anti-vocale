package com.antivocale.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "localai_preferences")

/**
 * Manages persistent app preferences using DataStore.
 */
class PreferencesManager(private val context: Context) {

    companion object {
        private val MODEL_PATH = stringPreferencesKey("model_path")
        private val KEEP_ALIVE_TIMEOUT = stringPreferencesKey("keep_alive_timeout")
        // Kept for migration purposes only
        private val LANGUAGE_PREFERENCE = stringPreferencesKey("language_preference")
        private val THEME_PREFERENCE = stringPreferencesKey("theme_preference")
        // Transcription backend preference
        private val TRANSCRIPTION_BACKEND = stringPreferencesKey("transcription_backend")
        // Parakeet model path (for sherpa-onnx backend)
        private val PARAKEET_MODEL_PATH = stringPreferencesKey("parakeet_model_path")
        // Whisper model path (for sherpa-onnx Whisper backend)
        private val WHISPER_MODEL_PATH = stringPreferencesKey("whisper_model_path")
        // Auto-copy transcription results to clipboard
        private val AUTO_COPY_ENABLED = booleanPreferencesKey("auto_copy_enabled")
        // VAD silence stripping
        private val VAD_ENABLED = booleanPreferencesKey("vad_enabled")
        // Default prompt for transcription
        private val DEFAULT_PROMPT = stringPreferencesKey("default_prompt")
    }

    /**
     * Flow of the saved model path.
     */
    val modelPath: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[MODEL_PATH]
    }

    /**
     * Saves the model path.
     */
    suspend fun saveModelPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[MODEL_PATH] = path
        }
    }

    /**
     * Clears the saved model path.
     */
    suspend fun clearModelPath() {
        context.dataStore.edit { preferences ->
            preferences.remove(MODEL_PATH)
        }
    }

    /**
     * Flow of the keep-alive timeout in minutes.
     */
    val keepAliveTimeout: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEEP_ALIVE_TIMEOUT]?.toIntOrNull() ?: 5 // Default 5 minutes
    }

    /**
     * Saves the keep-alive timeout.
     */
    suspend fun saveKeepAliveTimeout(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEEP_ALIVE_TIMEOUT] = minutes.toString()
        }
    }

    /**
     * Reads the legacy language preference for migration purposes.
     * Returns "system" by default.
     */
    suspend fun getLegacyLanguagePreference(): String {
        return context.dataStore.data.map { preferences ->
            preferences[LANGUAGE_PREFERENCE] ?: "system"
        }.first()
    }

    /**
     * Flow of the saved theme preference.
     * Returns "DEFAULT" by default.
     */
    val themePreference: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[THEME_PREFERENCE] ?: "DEFAULT"
    }

    /**
     * Saves the theme preference.
     */
    suspend fun saveThemePreference(theme: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_PREFERENCE] = theme
        }
    }

    /**
     * Flow of the selected transcription backend.
     * Returns "llm" by default (LiteRT-LM backend).
     */
    val transcriptionBackend: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[TRANSCRIPTION_BACKEND] ?: "llm"
    }

    /**
     * Saves the transcription backend preference.
     */
    suspend fun saveTranscriptionBackend(backendId: String) {
        context.dataStore.edit { preferences ->
            preferences[TRANSCRIPTION_BACKEND] = backendId
        }
    }

    /**
     * Flow of the Parakeet model path (for sherpa-onnx backend).
     */
    val parakeetModelPath: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PARAKEET_MODEL_PATH]
    }

    /**
     * Saves the Parakeet model path.
     */
    suspend fun saveParakeetModelPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[PARAKEET_MODEL_PATH] = path
        }
    }

    /**
     * Clears the Parakeet model path.
     */
    suspend fun clearParakeetModelPath() {
        context.dataStore.edit { preferences ->
            preferences.remove(PARAKEET_MODEL_PATH)
        }
    }

    /**
     * Flow of the Whisper model path (for sherpa-onnx Whisper backend).
     */
    val whisperModelPath: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[WHISPER_MODEL_PATH]
    }

    /**
     * Saves the Whisper model path.
     */
    suspend fun saveWhisperModelPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[WHISPER_MODEL_PATH] = path
        }
    }

    /**
     * Clears the Whisper model path.
     */
    suspend fun clearWhisperModelPath() {
        context.dataStore.edit { preferences ->
            preferences.remove(WHISPER_MODEL_PATH)
        }
    }

    /**
     * Flow of auto-copy enabled preference.
     * Returns false by default (user must manually copy).
     */
    val autoCopyEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_COPY_ENABLED] ?: false
    }

    /**
     * Saves the auto-copy enabled preference.
     */
    suspend fun saveAutoCopyEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_COPY_ENABLED] = enabled
        }
    }

    /**
     * Flow of VAD silence stripping enabled preference.
     * Returns false by default (VAD disabled, user must opt in).
     */
    val vadEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[VAD_ENABLED] ?: false
    }

    /**
     * Saves the VAD enabled preference.
     */
    suspend fun saveVadEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VAD_ENABLED] = enabled
        }
    }

    /**
     * Flow of the default prompt for transcription.
     * Returns empty string by default (use system default).
     */
    val defaultPrompt: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DEFAULT_PROMPT] ?: ""
    }

    /**
     * Saves the default prompt.
     * Enforces a maximum length of 500 characters.
     */
    suspend fun saveDefaultPrompt(prompt: String) {
        context.dataStore.edit { preferences ->
            preferences[DEFAULT_PROMPT] = prompt.take(500)
        }
    }
}
