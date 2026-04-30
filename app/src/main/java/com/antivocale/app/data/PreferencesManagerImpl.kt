package com.antivocale.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "localai_preferences")

class PreferencesManagerImpl(
    private val context: Context
) : PreferencesManager {

    companion object {
        private val MODEL_PATH = stringPreferencesKey("model_path")
        private val KEEP_ALIVE_TIMEOUT = intPreferencesKey("keep_alive_timeout_v2")
        private val KEEP_ALIVE_TIMEOUT_LEGACY = stringPreferencesKey("keep_alive_timeout")
        private val LANGUAGE_PREFERENCE = stringPreferencesKey("language_preference")
        private val THEME_PREFERENCE = stringPreferencesKey("theme_preference")
        private val TRANSCRIPTION_BACKEND = stringPreferencesKey("transcription_backend")
        private val PARAKEET_MODEL_PATH = stringPreferencesKey("parakeet_model_path")
        private val WHISPER_MODEL_PATH = stringPreferencesKey("whisper_model_path")
        private val QWEN3_ASR_MODEL_PATH = stringPreferencesKey("qwen3_asr_model_path")
        private val GGUF_MODEL_PATH = stringPreferencesKey("gguf_model_path")
        private val AUTO_COPY_ENABLED = booleanPreferencesKey("auto_copy_enabled")
        private val VAD_ENABLED = booleanPreferencesKey("vad_enabled")
        private val PROGRESSIVE_TRANSCRIPTION = booleanPreferencesKey("progressive_transcription")
        private val DEFAULT_PROMPT = stringPreferencesKey("default_prompt")
        private val THREAD_COUNT = intPreferencesKey("thread_count")
        private val INFERENCE_PROVIDER = stringPreferencesKey("inference_provider")
        private val TRANSCRIPTION_LANGUAGE = stringPreferencesKey("transcription_language")
        private val SWIPE_ACTION_MODE = stringPreferencesKey("swipe_action_mode")
        private val BENCHMARK_RESULTS = stringPreferencesKey("benchmark_results")
        private val VAD_ADVISORY_DISMISSED = booleanPreferencesKey("vad_advisory_dismissed")
        private val GROUP_LOGS_BY_CONVERSATION = booleanPreferencesKey("group_logs_by_conversation")
        private val PARTIAL_TRANSCRIPTION_TEXT = stringPreferencesKey("partial_transcription_text")
        private val PARTIAL_TRANSCRIPTION_TIMESTAMP = longPreferencesKey("partial_transcription_timestamp")
    }

    private val cache = AtomicReference(CachedPreferences())

    private data class CachedPreferences(
        val modelPath: String? = null,
        val keepAliveTimeout: Int = PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT,
        val themePreference: String = PreferencesManager.DEFAULT_THEME,
        val transcriptionBackend: String = PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND,
        val parakeetModelPath: String? = null,
        val whisperModelPath: String? = null,
        val qwen3AsrModelPath: String? = null,
        val ggufModelPath: String? = null,
        val autoCopyEnabled: Boolean = PreferencesManager.DEFAULT_AUTO_COPY_ENABLED,
        val vadEnabled: Boolean = PreferencesManager.DEFAULT_VAD_ENABLED,
        val progressiveTranscription: Boolean = PreferencesManager.DEFAULT_PROGRESSIVE_TRANSCRIPTION,
        val defaultPrompt: String = PreferencesManager.DEFAULT_PROMPT_VALUE,
        val threadCount: Int = PreferencesManager.DEFAULT_THREAD_COUNT,
        val inferenceProvider: String = PreferencesManager.DEFAULT_INFERENCE_PROVIDER,
        val transcriptionLanguage: String = PreferencesManager.DEFAULT_TRANSCRIPTION_LANGUAGE,
        val swipeActionMode: String = PreferencesManager.DEFAULT_SWIPE_ACTION_MODE,
        val vadAdvisoryDismissed: Boolean = false,
        val groupLogsByConversation: Boolean = PreferencesManager.DEFAULT_GROUP_LOGS_BY_CONVERSATION
    )

    private fun Preferences.toCached() = CachedPreferences(
        modelPath = this[MODEL_PATH],
        keepAliveTimeout = this[KEEP_ALIVE_TIMEOUT]
            ?: this[KEEP_ALIVE_TIMEOUT_LEGACY]?.toIntOrNull()
            ?: PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT,
        themePreference = this[THEME_PREFERENCE] ?: PreferencesManager.DEFAULT_THEME,
        transcriptionBackend = this[TRANSCRIPTION_BACKEND] ?: PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND,
        parakeetModelPath = this[PARAKEET_MODEL_PATH],
        whisperModelPath = this[WHISPER_MODEL_PATH],
        qwen3AsrModelPath = this[QWEN3_ASR_MODEL_PATH],
        ggufModelPath = this[GGUF_MODEL_PATH],
        autoCopyEnabled = this[AUTO_COPY_ENABLED] ?: PreferencesManager.DEFAULT_AUTO_COPY_ENABLED,
        vadEnabled = this[VAD_ENABLED] ?: PreferencesManager.DEFAULT_VAD_ENABLED,
        progressiveTranscription = this[PROGRESSIVE_TRANSCRIPTION] ?: PreferencesManager.DEFAULT_PROGRESSIVE_TRANSCRIPTION,
        defaultPrompt = this[DEFAULT_PROMPT] ?: PreferencesManager.DEFAULT_PROMPT_VALUE,
        threadCount = this[THREAD_COUNT] ?: PreferencesManager.DEFAULT_THREAD_COUNT,
        inferenceProvider = this[INFERENCE_PROVIDER] ?: PreferencesManager.DEFAULT_INFERENCE_PROVIDER,
        transcriptionLanguage = this[TRANSCRIPTION_LANGUAGE] ?: PreferencesManager.DEFAULT_TRANSCRIPTION_LANGUAGE,
        swipeActionMode = this[SWIPE_ACTION_MODE] ?: PreferencesManager.DEFAULT_SWIPE_ACTION_MODE,
        vadAdvisoryDismissed = this[VAD_ADVISORY_DISMISSED] ?: false,
        groupLogsByConversation = this[GROUP_LOGS_BY_CONVERSATION] ?: PreferencesManager.DEFAULT_GROUP_LOGS_BY_CONVERSATION
    )

    fun initialize() {
        runBlocking {
            cache.set(context.dataStore.data.first().toCached())
        }
    }

    override val modelPath: Flow<String?> = context.dataStore.data.map { it[MODEL_PATH] }
        .onStart { emit(cache.get().modelPath) }

    override suspend fun saveModelPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[MODEL_PATH] = path
        }
        cache.updateAndGet { it.copy(modelPath = path) }
    }

    override suspend fun clearModelPath() {
        context.dataStore.edit { preferences ->
            preferences.remove(MODEL_PATH)
        }
        cache.updateAndGet { it.copy(modelPath = null) }
    }

    override val keepAliveTimeout: Flow<Int> = context.dataStore.data.map {
        it[KEEP_ALIVE_TIMEOUT] ?: it[KEEP_ALIVE_TIMEOUT_LEGACY]?.toIntOrNull() ?: PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT
    }.onStart { emit(cache.get().keepAliveTimeout) }

    override suspend fun saveKeepAliveTimeout(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEEP_ALIVE_TIMEOUT] = minutes
            preferences.remove(KEEP_ALIVE_TIMEOUT_LEGACY)
        }
        cache.updateAndGet { it.copy(keepAliveTimeout = minutes) }
    }

    override suspend fun getLegacyLanguagePreference(): String {
        return context.dataStore.data.map { preferences ->
            preferences[LANGUAGE_PREFERENCE] ?: PreferencesManager.DEFAULT_LANGUAGE
        }.first()
    }

    override val themePreference: Flow<String> = context.dataStore.data.map { it[THEME_PREFERENCE] ?: PreferencesManager.DEFAULT_THEME }
        .onStart { emit(cache.get().themePreference) }

    override suspend fun saveThemePreference(theme: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_PREFERENCE] = theme
        }
        cache.updateAndGet { it.copy(themePreference = theme) }
    }

    override val transcriptionBackend: Flow<String> = context.dataStore.data.map { it[TRANSCRIPTION_BACKEND] ?: PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND }
        .onStart { emit(cache.get().transcriptionBackend) }

    override suspend fun saveTranscriptionBackend(backendId: String) {
        context.dataStore.edit { preferences ->
            preferences[TRANSCRIPTION_BACKEND] = backendId
        }
        cache.updateAndGet { it.copy(transcriptionBackend = backendId) }
    }

    override val parakeetModelPath: Flow<String?> = context.dataStore.data.map { it[PARAKEET_MODEL_PATH] }
        .onStart { emit(cache.get().parakeetModelPath) }

    override suspend fun saveParakeetModelPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[PARAKEET_MODEL_PATH] = path
        }
        cache.updateAndGet { it.copy(parakeetModelPath = path) }
    }

    override suspend fun clearParakeetModelPath() {
        context.dataStore.edit { preferences ->
            preferences.remove(PARAKEET_MODEL_PATH)
        }
        cache.updateAndGet { it.copy(parakeetModelPath = null) }
    }

    override val whisperModelPath: Flow<String?> = context.dataStore.data.map { it[WHISPER_MODEL_PATH] }
        .onStart { emit(cache.get().whisperModelPath) }

    override suspend fun saveWhisperModelPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[WHISPER_MODEL_PATH] = path
        }
        cache.updateAndGet { it.copy(whisperModelPath = path) }
    }

    override suspend fun clearWhisperModelPath() {
        context.dataStore.edit { preferences ->
            preferences.remove(WHISPER_MODEL_PATH)
        }
        cache.updateAndGet { it.copy(whisperModelPath = null) }
    }

    override val qwen3AsrModelPath: Flow<String?> = context.dataStore.data.map { it[QWEN3_ASR_MODEL_PATH] }
        .onStart { emit(cache.get().qwen3AsrModelPath) }

    override suspend fun saveQwen3AsrModelPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[QWEN3_ASR_MODEL_PATH] = path
        }
        cache.updateAndGet { it.copy(qwen3AsrModelPath = path) }
    }

    override suspend fun clearQwen3AsrModelPath() {
        context.dataStore.edit { preferences ->
            preferences.remove(QWEN3_ASR_MODEL_PATH)
        }
        cache.updateAndGet { it.copy(qwen3AsrModelPath = null) }
    }

    override val ggufModelPath: Flow<String?> = context.dataStore.data.map { it[GGUF_MODEL_PATH] }
        .onStart { emit(cache.get().ggufModelPath) }

    override suspend fun saveGgufModelPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[GGUF_MODEL_PATH] = path
        }
        cache.updateAndGet { it.copy(ggufModelPath = path) }
    }

    override suspend fun clearGgufModelPath() {
        context.dataStore.edit { preferences ->
            preferences.remove(GGUF_MODEL_PATH)
        }
        cache.updateAndGet { it.copy(ggufModelPath = null) }
    }

    override val autoCopyEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTO_COPY_ENABLED] ?: PreferencesManager.DEFAULT_AUTO_COPY_ENABLED }
        .onStart { emit(cache.get().autoCopyEnabled) }

    override suspend fun saveAutoCopyEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_COPY_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(autoCopyEnabled = enabled) }
    }

    override val vadEnabled: Flow<Boolean> = context.dataStore.data.map { it[VAD_ENABLED] ?: PreferencesManager.DEFAULT_VAD_ENABLED }
        .onStart { emit(cache.get().vadEnabled) }

    override suspend fun saveVadEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VAD_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(vadEnabled = enabled) }
    }

    override val vadAdvisoryDismissed: Flow<Boolean> = context.dataStore.data.map { it[VAD_ADVISORY_DISMISSED] ?: false }
        .onStart { emit(cache.get().vadAdvisoryDismissed) }

    override suspend fun saveVadAdvisoryDismissed(dismissed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VAD_ADVISORY_DISMISSED] = dismissed
        }
        cache.updateAndGet { it.copy(vadAdvisoryDismissed = dismissed) }
    }

    override val progressiveTranscription: Flow<Boolean> = context.dataStore.data.map { it[PROGRESSIVE_TRANSCRIPTION] ?: PreferencesManager.DEFAULT_PROGRESSIVE_TRANSCRIPTION }
        .onStart { emit(cache.get().progressiveTranscription) }

    override suspend fun saveProgressiveTranscription(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PROGRESSIVE_TRANSCRIPTION] = enabled
        }
        cache.updateAndGet { it.copy(progressiveTranscription = enabled) }
    }

    override val defaultPrompt: Flow<String> = context.dataStore.data.map { it[DEFAULT_PROMPT] ?: PreferencesManager.DEFAULT_PROMPT_VALUE }
        .onStart { emit(cache.get().defaultPrompt) }

    override suspend fun saveDefaultPrompt(prompt: String) {
        val truncated = prompt.take(500)
        context.dataStore.edit { preferences ->
            preferences[DEFAULT_PROMPT] = truncated
        }
        cache.updateAndGet { it.copy(defaultPrompt = truncated) }
    }

    override val threadCount: Flow<Int> = context.dataStore.data.map { it[THREAD_COUNT] ?: PreferencesManager.DEFAULT_THREAD_COUNT }
        .onStart { emit(cache.get().threadCount) }

    override suspend fun saveThreadCount(threads: Int) {
        context.dataStore.edit { preferences ->
            preferences[THREAD_COUNT] = threads
        }
        cache.updateAndGet { it.copy(threadCount = threads) }
    }

    override val inferenceProvider: Flow<String> = context.dataStore.data.map { it[INFERENCE_PROVIDER] ?: PreferencesManager.DEFAULT_INFERENCE_PROVIDER }
        .onStart { emit(cache.get().inferenceProvider) }

    override suspend fun saveInferenceProvider(provider: String) {
        context.dataStore.edit { preferences ->
            preferences[INFERENCE_PROVIDER] = provider
        }
        cache.updateAndGet { it.copy(inferenceProvider = provider) }
    }

    override val transcriptionLanguage: Flow<String> = context.dataStore.data.map { it[TRANSCRIPTION_LANGUAGE] ?: PreferencesManager.DEFAULT_TRANSCRIPTION_LANGUAGE }
        .onStart { emit(cache.get().transcriptionLanguage) }

    override suspend fun saveTranscriptionLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[TRANSCRIPTION_LANGUAGE] = language
        }
        cache.updateAndGet { it.copy(transcriptionLanguage = language) }
    }

    override val swipeActionMode: Flow<String> = context.dataStore.data.map { it[SWIPE_ACTION_MODE] ?: PreferencesManager.DEFAULT_SWIPE_ACTION_MODE }
        .onStart { emit(cache.get().swipeActionMode) }

    override suspend fun saveSwipeActionMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[SWIPE_ACTION_MODE] = mode
        }
        cache.updateAndGet { it.copy(swipeActionMode = mode) }
    }

    override suspend fun saveBenchmarkResult(modelId: String, jsonResult: String) {
        context.dataStore.edit { preferences ->
            val existing = preferences[BENCHMARK_RESULTS] ?: "{}"
            val obj = runCatching { org.json.JSONObject(existing) }.getOrDefault(org.json.JSONObject())
            val results = obj.optJSONObject("results") ?: org.json.JSONObject()
            results.put(modelId, jsonResult)
            obj.put("results", results)
            preferences[BENCHMARK_RESULTS] = obj.toString()
        }
    }

    override fun getBenchmarkResult(modelId: String): Flow<String?> =
        context.dataStore.data.map { prefs ->
            val all = prefs[BENCHMARK_RESULTS] ?: "{}"
            runCatching {
                org.json.JSONObject(all).optJSONObject("results")?.optString(modelId)
            }.getOrNull()
        }

    override fun getAllBenchmarkResults(): Flow<Map<String, String>> =
        context.dataStore.data.map { prefs ->
            val all = prefs[BENCHMARK_RESULTS] ?: "{}"
            runCatching {
                val results = org.json.JSONObject(all).optJSONObject("results") ?: org.json.JSONObject()
                results.keys().asSequence().associateWith { results.getString(it) }
            }.getOrDefault(emptyMap())
        }

    override suspend fun clearBenchmarkResult(modelId: String) {
        context.dataStore.edit { preferences ->
            val existing = preferences[BENCHMARK_RESULTS] ?: "{}"
            val obj = runCatching { org.json.JSONObject(existing) }.getOrDefault(org.json.JSONObject())
            val results = obj.optJSONObject("results")
            results?.remove(modelId)
            preferences[BENCHMARK_RESULTS] = obj.toString()
        }
    }

    override suspend fun clearAllBenchmarkResults() {
        context.dataStore.edit { preferences ->
            preferences.remove(BENCHMARK_RESULTS)
        }
    }

    override val partialTranscriptionText: Flow<String?> = context.dataStore.data.map { it[PARTIAL_TRANSCRIPTION_TEXT] }

    override val partialTranscriptionTimestamp: Flow<Long?> = context.dataStore.data.map { it[PARTIAL_TRANSCRIPTION_TIMESTAMP] }

    override suspend fun savePartialTranscriptionState(text: String) {
        context.dataStore.edit { preferences ->
            preferences[PARTIAL_TRANSCRIPTION_TEXT] = text
            preferences[PARTIAL_TRANSCRIPTION_TIMESTAMP] = System.currentTimeMillis()
        }
    }

    override suspend fun clearPartialTranscriptionState() {
        context.dataStore.edit { preferences ->
            preferences.remove(PARTIAL_TRANSCRIPTION_TEXT)
            preferences.remove(PARTIAL_TRANSCRIPTION_TIMESTAMP)
        }
    }

    override val groupLogsByConversation: Flow<Boolean> = context.dataStore.data.map { it[GROUP_LOGS_BY_CONVERSATION] ?: PreferencesManager.DEFAULT_GROUP_LOGS_BY_CONVERSATION }
        .onStart { emit(cache.get().groupLogsByConversation) }

    override suspend fun saveGroupLogsByConversation(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[GROUP_LOGS_BY_CONVERSATION] = enabled
        }
        cache.updateAndGet { it.copy(groupLogsByConversation = enabled) }
    }
}
