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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "localai_preferences")

class PreferencesManagerImpl(
    private val context: Context,
    injectedDataStore: DataStore<Preferences>? = null,
) : PreferencesManager {

    private val dataStore: DataStore<Preferences> = injectedDataStore ?: context.dataStore

    companion object {
        private val MODEL_PATH = stringPreferencesKey("model_path")
        private val KEEP_ALIVE_TIMEOUT = intPreferencesKey("keep_alive_timeout_v2")
        private val KEEP_ALIVE_TIMEOUT_LEGACY = stringPreferencesKey("keep_alive_timeout")
        private val LANGUAGE_PREFERENCE = stringPreferencesKey("language_preference")
        private val THEME_PREFERENCE = stringPreferencesKey("theme_preference")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val TRANSCRIPTION_BACKEND = stringPreferencesKey("transcription_backend")
        private val SHERPA_MODEL_PATH_PREFIX = "sherpa_model_path_"
        /**
         * Data-store keys of the pre-consolidation per-model path preferences, mapped to
         * their catalog entry id. Read as a legacy fallback so previously downloaded model
         * paths survive the consolidation; the new keyed preference is written on save.
         */
        private val LEGACY_MODEL_PATH_KEYS: Map<String, androidx.datastore.preferences.core.Preferences.Key<String>> = mapOf(
            "sherpa-onnx" to stringPreferencesKey("parakeet_model_path"),
            "whisper" to stringPreferencesKey("whisper_model_path"),
            "qwen3-asr" to stringPreferencesKey("qwen3_asr_model_path"),
            "nemotron-streaming" to stringPreferencesKey("nemotron_model_path"),
            "gigaam" to stringPreferencesKey("gigaam_model_path"),
        )
        private fun sherpaModelPathKey(entryId: String) = stringPreferencesKey("$SHERPA_MODEL_PATH_PREFIX$entryId")
        private val CUSTOM_TRANSDUCER_MODEL_PATH = stringPreferencesKey("custom_transducer_model_path")
        private val CUSTOM_TRANSDUCER_MODEL_TYPE = stringPreferencesKey("custom_transducer_model_type")
        private val WHISPER_MODEL_PATH = stringPreferencesKey("whisper_model_path")
        private val QWEN3_ASR_MODEL_PATH = stringPreferencesKey("qwen3_asr_model_path")
        private val NEMOTRON_MODEL_PATH = stringPreferencesKey("nemotron_model_path")
        private val GIGAAM_MODEL_PATH = stringPreferencesKey("gigaam_model_path")
        private val EXTERNAL_CATALOG_URL = stringPreferencesKey("external_catalog_url")
        private val EXTERNAL_MIGRATION_DONE = booleanPreferencesKey("external_migration_done")
        private val GGUF_MODEL_PATH = stringPreferencesKey("gguf_model_path")
        private val AUTO_COPY_ENABLED = booleanPreferencesKey("auto_copy_enabled")
        private val OUTPUT_FOLDER_URI = stringPreferencesKey("output_folder_uri")
        private val VAD_ENABLED = booleanPreferencesKey("vad_enabled")
        private val PROGRESSIVE_TRANSCRIPTION = booleanPreferencesKey("progressive_transcription")
        private val DEFAULT_PROMPT = stringPreferencesKey("default_prompt")
        private val PUNCTUATION_MODE = stringPreferencesKey("punctuation_mode")
        private val PUNCTUATION_PROMPT = stringPreferencesKey("punctuation_prompt")
        private val THREAD_COUNT = intPreferencesKey("thread_count")
        private val INFERENCE_PROVIDER = stringPreferencesKey("inference_provider")
        private val TRANSCRIPTION_LANGUAGE = stringPreferencesKey("transcription_language")
        private val SWIPE_ACTION_MODE = stringPreferencesKey("swipe_action_mode")
        private val BENCHMARK_RESULTS = stringPreferencesKey("benchmark_results")
        private val VAD_ADVISORY_DISMISSED = booleanPreferencesKey("vad_advisory_dismissed")
        private val GROUP_LOGS_BY_CONVERSATION = booleanPreferencesKey("group_logs_by_conversation")
        private val ADVANCED_SHARING_ENABLED = booleanPreferencesKey("advanced_sharing_enabled")
        private val SHOW_RETRANSCRIBE_BUTTON = booleanPreferencesKey("show_retranscribe_button")
        private val FORCE_MODEL_LOAD = booleanPreferencesKey("force_model_load")
        private val COMPACT_RESULT_ACTIONS = booleanPreferencesKey("compact_result_actions")
        private val SHOW_TASK_DETAILS = booleanPreferencesKey("show_task_details")
        private val PARTIAL_TRANSCRIPTION_TEXT = stringPreferencesKey("partial_transcription_text")
        private val PARTIAL_TRANSCRIPTION_TIMESTAMP = longPreferencesKey("partial_transcription_timestamp")
        private val EXTERNAL_MODELS_JSON = stringPreferencesKey("external_models_json")
    }

    private val cache = AtomicReference(CachedPreferences())

    private data class CachedPreferences(
        val modelPath: String? = null,
        val keepAliveTimeout: Int = PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT,
        val themePreference: String = PreferencesManager.DEFAULT_THEME,
        val themeMode: String = PreferencesManager.DEFAULT_THEME_MODE,
        val transcriptionBackend: String = PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND,
        val sherpaModelPaths: Map<String, String?> = emptyMap(),
        val customTransducerModelPath: String? = null,
        val customTransducerModelType: String = PreferencesManager.DEFAULT_CUSTOM_TRANSDUCER_MODEL_TYPE,
        val externalMigrationDone: Boolean = false,
        val externalCatalogUrl: String = PreferencesManager.DEFAULT_EXTERNAL_CATALOG_URL,
        val ggufModelPath: String? = null,
        val autoCopyEnabled: Boolean = PreferencesManager.DEFAULT_AUTO_COPY_ENABLED,
        val outputFolderUri: String? = null,
        val vadEnabled: Boolean = PreferencesManager.DEFAULT_VAD_ENABLED,
        val progressiveTranscription: Boolean = PreferencesManager.DEFAULT_PROGRESSIVE_TRANSCRIPTION,
        val defaultPrompt: String = PreferencesManager.DEFAULT_PROMPT_VALUE,
        val punctuationMode: String = PreferencesManager.DEFAULT_PUNCTUATION_MODE,
        val punctuationPrompt: String = "",
        val threadCount: Int = PreferencesManager.DEFAULT_THREAD_COUNT,
        val inferenceProvider: String = PreferencesManager.DEFAULT_INFERENCE_PROVIDER,
        val transcriptionLanguage: String = PreferencesManager.DEFAULT_TRANSCRIPTION_LANGUAGE,
        val swipeActionMode: String = PreferencesManager.DEFAULT_SWIPE_ACTION_MODE,
        val vadAdvisoryDismissed: Boolean = false,
        val groupLogsByConversation: Boolean = PreferencesManager.DEFAULT_GROUP_LOGS_BY_CONVERSATION,
        val advancedSharingEnabled: Boolean = PreferencesManager.DEFAULT_ADVANCED_SHARING_ENABLED,
        val showRetranscribeButton: Boolean = PreferencesManager.DEFAULT_SHOW_RETRANSCRIBE_BUTTON,
        val forceModelLoad: Boolean = PreferencesManager.DEFAULT_FORCE_MODEL_LOAD,
        val compactResultActions: Boolean = PreferencesManager.DEFAULT_COMPACT_RESULT_ACTIONS,
        val showTaskDetails: Boolean = PreferencesManager.DEFAULT_SHOW_TASK_DETAILS,
        val externalModelsJson: String? = null
    )

    private fun Preferences.toCached() = CachedPreferences(
        modelPath = this[MODEL_PATH],
        keepAliveTimeout = this[KEEP_ALIVE_TIMEOUT]
            ?: this[KEEP_ALIVE_TIMEOUT_LEGACY]?.toIntOrNull()
            ?: PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT,
        themePreference = this[THEME_PREFERENCE] ?: PreferencesManager.DEFAULT_THEME,
        themeMode = this[THEME_MODE] ?: PreferencesManager.DEFAULT_THEME_MODE,
        transcriptionBackend = this[TRANSCRIPTION_BACKEND] ?: PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND,
        sherpaModelPaths = LEGACY_MODEL_PATH_KEYS.entries.associate { (entryId, legacyKey) ->
            entryId to (this[sherpaModelPathKey(entryId)] ?: this[legacyKey])
        },
        customTransducerModelPath = this[CUSTOM_TRANSDUCER_MODEL_PATH],
        customTransducerModelType = this[CUSTOM_TRANSDUCER_MODEL_TYPE]
            ?: PreferencesManager.DEFAULT_CUSTOM_TRANSDUCER_MODEL_TYPE,
        externalMigrationDone = this[EXTERNAL_MIGRATION_DONE] ?: false,
        externalCatalogUrl = this[EXTERNAL_CATALOG_URL] ?: PreferencesManager.DEFAULT_EXTERNAL_CATALOG_URL,
        ggufModelPath = this[GGUF_MODEL_PATH],
        autoCopyEnabled = this[AUTO_COPY_ENABLED] ?: PreferencesManager.DEFAULT_AUTO_COPY_ENABLED,
        outputFolderUri = this[OUTPUT_FOLDER_URI],
        vadEnabled = this[VAD_ENABLED] ?: PreferencesManager.DEFAULT_VAD_ENABLED,
        progressiveTranscription = this[PROGRESSIVE_TRANSCRIPTION] ?: PreferencesManager.DEFAULT_PROGRESSIVE_TRANSCRIPTION,
        defaultPrompt = this[DEFAULT_PROMPT] ?: PreferencesManager.DEFAULT_PROMPT_VALUE,
        punctuationMode = this[PUNCTUATION_MODE] ?: PreferencesManager.DEFAULT_PUNCTUATION_MODE,
        punctuationPrompt = this[PUNCTUATION_PROMPT] ?: "",
        threadCount = this[THREAD_COUNT] ?: PreferencesManager.DEFAULT_THREAD_COUNT,
        inferenceProvider = this[INFERENCE_PROVIDER] ?: PreferencesManager.DEFAULT_INFERENCE_PROVIDER,
        transcriptionLanguage = this[TRANSCRIPTION_LANGUAGE] ?: PreferencesManager.DEFAULT_TRANSCRIPTION_LANGUAGE,
        swipeActionMode = this[SWIPE_ACTION_MODE] ?: PreferencesManager.DEFAULT_SWIPE_ACTION_MODE,
        vadAdvisoryDismissed = this[VAD_ADVISORY_DISMISSED] ?: false,
        groupLogsByConversation = this[GROUP_LOGS_BY_CONVERSATION] ?: PreferencesManager.DEFAULT_GROUP_LOGS_BY_CONVERSATION,
        advancedSharingEnabled = this[ADVANCED_SHARING_ENABLED] ?: PreferencesManager.DEFAULT_ADVANCED_SHARING_ENABLED,
        showRetranscribeButton = this[SHOW_RETRANSCRIBE_BUTTON] ?: PreferencesManager.DEFAULT_SHOW_RETRANSCRIBE_BUTTON,
        forceModelLoad = this[FORCE_MODEL_LOAD] ?: PreferencesManager.DEFAULT_FORCE_MODEL_LOAD,
        compactResultActions = this[COMPACT_RESULT_ACTIONS] ?: PreferencesManager.DEFAULT_COMPACT_RESULT_ACTIONS,
        showTaskDetails = this[SHOW_TASK_DETAILS] ?: PreferencesManager.DEFAULT_SHOW_TASK_DETAILS,
        externalModelsJson = this[EXTERNAL_MODELS_JSON]
    )

    fun initialize() {
        runBlocking {
            cache.set(dataStore.data.first().toCached())
        }
    }

    override val modelPath: Flow<String?> = dataStore.data.map { it[MODEL_PATH] }
        .onStart { emit(cache.get().modelPath) }

    override suspend fun saveModelPath(path: String) {
        dataStore.edit { preferences ->
            preferences[MODEL_PATH] = path
        }
        cache.updateAndGet { it.copy(modelPath = path) }
    }

    override suspend fun clearModelPath() {
        dataStore.edit { preferences ->
            preferences.remove(MODEL_PATH)
        }
        cache.updateAndGet { it.copy(modelPath = null) }
    }

    override val keepAliveTimeout: Flow<Int> = dataStore.data.map {
        it[KEEP_ALIVE_TIMEOUT] ?: it[KEEP_ALIVE_TIMEOUT_LEGACY]?.toIntOrNull() ?: PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT
    }.onStart { emit(cache.get().keepAliveTimeout) }

    override suspend fun saveKeepAliveTimeout(minutes: Int) {
        dataStore.edit { preferences ->
            preferences[KEEP_ALIVE_TIMEOUT] = minutes
            preferences.remove(KEEP_ALIVE_TIMEOUT_LEGACY)
        }
        cache.updateAndGet { it.copy(keepAliveTimeout = minutes) }
    }

    override suspend fun getLegacyLanguagePreference(): String {
        return dataStore.data.map { preferences ->
            preferences[LANGUAGE_PREFERENCE] ?: PreferencesManager.DEFAULT_LANGUAGE
        }.first()
    }

    override val themePreference: Flow<String> = dataStore.data.map { it[THEME_PREFERENCE] ?: PreferencesManager.DEFAULT_THEME }
        .onStart { emit(cache.get().themePreference) }

    override suspend fun saveThemePreference(theme: String) {
        dataStore.edit { preferences ->
            preferences[THEME_PREFERENCE] = theme
        }
        cache.updateAndGet { it.copy(themePreference = theme) }
    }

    override val themeMode: Flow<String> = dataStore.data.map { it[THEME_MODE] ?: PreferencesManager.DEFAULT_THEME_MODE }
        .onStart { emit(cache.get().themeMode) }

    override suspend fun saveThemeMode(mode: String) {
        dataStore.edit { preferences ->
            preferences[THEME_MODE] = mode
        }
        cache.updateAndGet { it.copy(themeMode = mode) }
    }

    override val transcriptionBackend: Flow<String> = dataStore.data.map { it[TRANSCRIPTION_BACKEND] ?: PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND }
        .onStart { emit(cache.get().transcriptionBackend) }
        // Same rationale as externalModelsJson: unrelated preference writes re-emit the
        // identical value and every collector (ActiveModelRepository's flatMapLatest,
        // ModelViewModel's activeBackendId) would restart on it for nothing.
        .distinctUntilChanged()

    override suspend fun saveTranscriptionBackend(backendId: String) {
        dataStore.edit { preferences ->
            preferences[TRANSCRIPTION_BACKEND] = backendId
        }
        cache.updateAndGet { it.copy(transcriptionBackend = backendId) }
    }

    override fun sherpaModelPath(entryId: String): Flow<String?> {
        val legacyKey = LEGACY_MODEL_PATH_KEYS[entryId]
        return dataStore.data.map { prefs ->
            prefs[sherpaModelPathKey(entryId)] ?: legacyKey?.let { prefs[it] }
        }.onStart { emit(cache.get().sherpaModelPaths[entryId]) }
    }

    override suspend fun saveSherpaModelPath(entryId: String, path: String) {
        dataStore.edit { preferences ->
            preferences[sherpaModelPathKey(entryId)] = path
            LEGACY_MODEL_PATH_KEYS[entryId]?.let { preferences.remove(it) }
        }
        cache.updateAndGet { it.copy(sherpaModelPaths = it.sherpaModelPaths + (entryId to path)) }
    }

    override suspend fun clearSherpaModelPath(entryId: String) {
        dataStore.edit { preferences ->
            preferences.remove(sherpaModelPathKey(entryId))
            LEGACY_MODEL_PATH_KEYS[entryId]?.let { preferences.remove(it) }
        }
        cache.updateAndGet { it.copy(sherpaModelPaths = it.sherpaModelPaths - entryId) }
    }

    override val customTransducerModelPath: Flow<String?> = dataStore.data.map { it[CUSTOM_TRANSDUCER_MODEL_PATH] }
        .onStart { emit(cache.get().customTransducerModelPath) }

    override val customTransducerModelType: Flow<String> = dataStore.data
        .map { it[CUSTOM_TRANSDUCER_MODEL_TYPE] ?: PreferencesManager.DEFAULT_CUSTOM_TRANSDUCER_MODEL_TYPE }
        .onStart { emit(cache.get().customTransducerModelType) }

    override val ggufModelPath: Flow<String?> = dataStore.data.map { it[GGUF_MODEL_PATH] }
        .onStart { emit(cache.get().ggufModelPath) }

    override val externalMigrationDone: Flow<Boolean> = dataStore.data.map { it[EXTERNAL_MIGRATION_DONE] ?: false }
        .onStart { emit(cache.get().externalMigrationDone) }

    override val externalCatalogUrl: Flow<String> = dataStore.data.map { it[EXTERNAL_CATALOG_URL] ?: PreferencesManager.DEFAULT_EXTERNAL_CATALOG_URL }
        .onStart { emit(cache.get().externalCatalogUrl) }

    override suspend fun saveExternalCatalogUrl(url: String) {
        dataStore.edit { preferences ->
            preferences[EXTERNAL_CATALOG_URL] = url
        }
        cache.updateAndGet { it.copy(externalCatalogUrl = url) }
    }

    override suspend fun saveExternalMigrationDone(done: Boolean) {
        dataStore.edit { preferences ->
            preferences[EXTERNAL_MIGRATION_DONE] = done
        }
        cache.updateAndGet { it.copy(externalMigrationDone = done) }
    }

    override suspend fun saveGgufModelPath(path: String) {
        dataStore.edit { preferences ->
            preferences[GGUF_MODEL_PATH] = path
        }
        cache.updateAndGet { it.copy(ggufModelPath = path) }
    }

    override suspend fun clearGgufModelPath() {
        dataStore.edit { preferences ->
            preferences.remove(GGUF_MODEL_PATH)
        }
        cache.updateAndGet { it.copy(ggufModelPath = null) }
    }

    override val autoCopyEnabled: Flow<Boolean> = dataStore.data.map { it[AUTO_COPY_ENABLED] ?: PreferencesManager.DEFAULT_AUTO_COPY_ENABLED }
        .onStart { emit(cache.get().autoCopyEnabled) }

    override suspend fun saveAutoCopyEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUTO_COPY_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(autoCopyEnabled = enabled) }
    }

    override val outputFolderUri: Flow<String?> = dataStore.data.map { it[OUTPUT_FOLDER_URI] }
        .onStart { emit(cache.get().outputFolderUri) }

    override suspend fun saveOutputFolderUri(uri: String?) {
        dataStore.edit { preferences ->
            if (uri == null) {
                preferences.remove(OUTPUT_FOLDER_URI)
            } else {
                preferences[OUTPUT_FOLDER_URI] = uri
            }
        }
        cache.updateAndGet { it.copy(outputFolderUri = uri) }
    }

    override val vadEnabled: Flow<Boolean> = dataStore.data.map { it[VAD_ENABLED] ?: PreferencesManager.DEFAULT_VAD_ENABLED }
        .onStart { emit(cache.get().vadEnabled) }

    override suspend fun saveVadEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VAD_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(vadEnabled = enabled) }
    }

    override val vadAdvisoryDismissed: Flow<Boolean> = dataStore.data.map { it[VAD_ADVISORY_DISMISSED] ?: false }
        .onStart { emit(cache.get().vadAdvisoryDismissed) }

    override suspend fun saveVadAdvisoryDismissed(dismissed: Boolean) {
        dataStore.edit { preferences ->
            preferences[VAD_ADVISORY_DISMISSED] = dismissed
        }
        cache.updateAndGet { it.copy(vadAdvisoryDismissed = dismissed) }
    }

    override val progressiveTranscription: Flow<Boolean> = dataStore.data.map { it[PROGRESSIVE_TRANSCRIPTION] ?: PreferencesManager.DEFAULT_PROGRESSIVE_TRANSCRIPTION }
        .onStart { emit(cache.get().progressiveTranscription) }

    override suspend fun saveProgressiveTranscription(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PROGRESSIVE_TRANSCRIPTION] = enabled
        }
        cache.updateAndGet { it.copy(progressiveTranscription = enabled) }
    }

    override val defaultPrompt: Flow<String> = dataStore.data.map { it[DEFAULT_PROMPT] ?: PreferencesManager.DEFAULT_PROMPT_VALUE }
        .onStart { emit(cache.get().defaultPrompt) }

    override suspend fun saveDefaultPrompt(prompt: String) {
        val truncated = prompt.take(500)
        dataStore.edit { preferences ->
            preferences[DEFAULT_PROMPT] = truncated
        }
        cache.updateAndGet { it.copy(defaultPrompt = truncated) }
    }

    override val punctuationMode: Flow<String> = dataStore.data.map { it[PUNCTUATION_MODE] ?: PreferencesManager.DEFAULT_PUNCTUATION_MODE }
        .onStart { emit(cache.get().punctuationMode) }

    override suspend fun savePunctuationMode(mode: String) {
        dataStore.edit { preferences ->
            preferences[PUNCTUATION_MODE] = mode
        }
        cache.updateAndGet { it.copy(punctuationMode = mode) }
    }

    override val punctuationPrompt: Flow<String> = dataStore.data.map { it[PUNCTUATION_PROMPT] ?: "" }
        .onStart { emit(cache.get().punctuationPrompt) }

    override suspend fun savePunctuationPrompt(prompt: String) {
        // Same 500-char cap as the default transcription prompt: one
        // instruction paragraph, not an essay (TASK-276).
        val truncated = prompt.take(500)
        dataStore.edit { preferences ->
            preferences[PUNCTUATION_PROMPT] = truncated
        }
        cache.updateAndGet { it.copy(punctuationPrompt = truncated) }
    }

    override val threadCount: Flow<Int> = dataStore.data.map { it[THREAD_COUNT] ?: PreferencesManager.DEFAULT_THREAD_COUNT }
        .onStart { emit(cache.get().threadCount) }

    override suspend fun saveThreadCount(threads: Int) {
        dataStore.edit { preferences ->
            preferences[THREAD_COUNT] = threads
        }
        cache.updateAndGet { it.copy(threadCount = threads) }
    }

    override val inferenceProvider: Flow<String> = dataStore.data.map { it[INFERENCE_PROVIDER] ?: PreferencesManager.DEFAULT_INFERENCE_PROVIDER }
        .onStart { emit(cache.get().inferenceProvider) }

    override suspend fun saveInferenceProvider(provider: String) {
        dataStore.edit { preferences ->
            preferences[INFERENCE_PROVIDER] = provider
        }
        cache.updateAndGet { it.copy(inferenceProvider = provider) }
    }

    override val transcriptionLanguage: Flow<String> = dataStore.data.map { it[TRANSCRIPTION_LANGUAGE] ?: PreferencesManager.DEFAULT_TRANSCRIPTION_LANGUAGE }
        .onStart { emit(cache.get().transcriptionLanguage) }

    override suspend fun saveTranscriptionLanguage(language: String) {
        dataStore.edit { preferences ->
            preferences[TRANSCRIPTION_LANGUAGE] = language
        }
        cache.updateAndGet { it.copy(transcriptionLanguage = language) }
    }

    override val swipeActionMode: Flow<String> = dataStore.data.map { it[SWIPE_ACTION_MODE] ?: PreferencesManager.DEFAULT_SWIPE_ACTION_MODE }
        .onStart { emit(cache.get().swipeActionMode) }

    override suspend fun saveSwipeActionMode(mode: String) {
        dataStore.edit { preferences ->
            preferences[SWIPE_ACTION_MODE] = mode
        }
        cache.updateAndGet { it.copy(swipeActionMode = mode) }
    }

    override suspend fun saveBenchmarkResult(modelId: String, jsonResult: String) {
        dataStore.edit { preferences ->
            val existing = preferences[BENCHMARK_RESULTS] ?: "{}"
            val obj = runCatching { org.json.JSONObject(existing) }.getOrDefault(org.json.JSONObject())
            val results = obj.optJSONObject("results") ?: org.json.JSONObject()
            results.put(modelId, jsonResult)
            obj.put("results", results)
            preferences[BENCHMARK_RESULTS] = obj.toString()
        }
    }

    override fun getBenchmarkResult(modelId: String): Flow<String?> =
        dataStore.data.map { prefs ->
            val all = prefs[BENCHMARK_RESULTS] ?: "{}"
            runCatching {
                org.json.JSONObject(all).optJSONObject("results")?.optString(modelId)
            }.getOrNull()
        }

    override fun getAllBenchmarkResults(): Flow<Map<String, String>> =
        dataStore.data.map { prefs ->
            val all = prefs[BENCHMARK_RESULTS] ?: "{}"
            runCatching {
                val results = org.json.JSONObject(all).optJSONObject("results") ?: org.json.JSONObject()
                results.keys().asSequence().associateWith { results.getString(it) }
            }.getOrDefault(emptyMap())
        }

    override suspend fun clearBenchmarkResult(modelId: String) {
        dataStore.edit { preferences ->
            val existing = preferences[BENCHMARK_RESULTS] ?: "{}"
            val obj = runCatching { org.json.JSONObject(existing) }.getOrDefault(org.json.JSONObject())
            val results = obj.optJSONObject("results")
            results?.remove(modelId)
            preferences[BENCHMARK_RESULTS] = obj.toString()
        }
    }

    override suspend fun clearAllBenchmarkResults() {
        dataStore.edit { preferences ->
            preferences.remove(BENCHMARK_RESULTS)
        }
    }

    override val partialTranscriptionText: Flow<String?> = dataStore.data.map { it[PARTIAL_TRANSCRIPTION_TEXT] }

    override val partialTranscriptionTimestamp: Flow<Long?> = dataStore.data.map { it[PARTIAL_TRANSCRIPTION_TIMESTAMP] }

    override suspend fun savePartialTranscriptionState(text: String) {
        dataStore.edit { preferences ->
            preferences[PARTIAL_TRANSCRIPTION_TEXT] = text
            preferences[PARTIAL_TRANSCRIPTION_TIMESTAMP] = System.currentTimeMillis()
        }
    }

    override suspend fun clearPartialTranscriptionState() {
        dataStore.edit { preferences ->
            preferences.remove(PARTIAL_TRANSCRIPTION_TEXT)
            preferences.remove(PARTIAL_TRANSCRIPTION_TIMESTAMP)
        }
    }

    override val groupLogsByConversation: Flow<Boolean> = dataStore.data.map { it[GROUP_LOGS_BY_CONVERSATION] ?: PreferencesManager.DEFAULT_GROUP_LOGS_BY_CONVERSATION }
        .onStart { emit(cache.get().groupLogsByConversation) }

    override suspend fun saveGroupLogsByConversation(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[GROUP_LOGS_BY_CONVERSATION] = enabled
        }
        cache.updateAndGet { it.copy(groupLogsByConversation = enabled) }
    }

    override val advancedSharingEnabled: Flow<Boolean> = dataStore.data.map { it[ADVANCED_SHARING_ENABLED] ?: PreferencesManager.DEFAULT_ADVANCED_SHARING_ENABLED }
        .onStart { emit(cache.get().advancedSharingEnabled) }

    override suspend fun saveAdvancedSharingEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[ADVANCED_SHARING_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(advancedSharingEnabled = enabled) }
    }

    override val showRetranscribeButton: Flow<Boolean> = dataStore.data.map { it[SHOW_RETRANSCRIBE_BUTTON] ?: PreferencesManager.DEFAULT_SHOW_RETRANSCRIBE_BUTTON }
        .onStart { emit(cache.get().showRetranscribeButton) }

    override suspend fun saveShowRetranscribeButton(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SHOW_RETRANSCRIBE_BUTTON] = enabled
        }
        cache.updateAndGet { it.copy(showRetranscribeButton = enabled) }
    }

    override val forceModelLoad: Flow<Boolean> = dataStore.data.map { it[FORCE_MODEL_LOAD] ?: PreferencesManager.DEFAULT_FORCE_MODEL_LOAD }
        .onStart { emit(cache.get().forceModelLoad) }

    override val compactResultActions: Flow<Boolean> = dataStore.data.map { it[COMPACT_RESULT_ACTIONS] ?: PreferencesManager.DEFAULT_COMPACT_RESULT_ACTIONS }
        .onStart { emit(cache.get().compactResultActions) }
    override val showTaskDetails: Flow<Boolean> = dataStore.data.map { it[SHOW_TASK_DETAILS] ?: PreferencesManager.DEFAULT_SHOW_TASK_DETAILS }
        .onStart { emit(cache.get().showTaskDetails) }

    override suspend fun saveShowTaskDetails(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SHOW_TASK_DETAILS] = enabled
        }
        cache.updateAndGet { it.copy(showTaskDetails = enabled) }
    }
    override suspend fun saveCompactResultActions(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[COMPACT_RESULT_ACTIONS] = enabled
        }
        cache.updateAndGet { it.copy(compactResultActions = enabled) }
    }

    override suspend fun saveForceModelLoad(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[FORCE_MODEL_LOAD] = enabled
        }
        cache.updateAndGet { it.copy(forceModelLoad = enabled) }
    }

    override val externalModelsJson: Flow<String?> = dataStore.data.map { it[EXTERNAL_MODELS_JSON] }
        .onStart { emit(cache.get().externalModelsJson) }
        // The JSON string is the natural key: unrelated preference writes re-emit the
        // same value, and every downstream consumer re-decodes it. Skip the duplicates.
        .distinctUntilChanged()

    override suspend fun saveExternalModelsJson(json: String) {
        dataStore.edit { preferences ->
            preferences[EXTERNAL_MODELS_JSON] = json
        }
        cache.updateAndGet { it.copy(externalModelsJson = json) }
    }
}
