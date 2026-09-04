package com.antivocale.app.data

import kotlinx.coroutines.flow.Flow

interface PreferencesManager {

    val modelPath: Flow<String?>
    val keepAliveTimeout: Flow<Int>
    val themePreference: Flow<String>
    val themeMode: Flow<String>
    val transcriptionBackend: Flow<String>
    /**
     * Saved model-path preference for a built-in sherpa-onnx catalog entry,
     * keyed by the entry id (BackendRegistry descriptors delegate to this).
     * The per-model preference keys of older app versions are read as a
     * legacy fallback until the value is re-saved.
     */
    fun sherpaModelPath(entryId: String): Flow<String?>
    // Migration-only readers (custom-transducer -> external-model, v2a Task 9): the backend
    // and its mutators are gone; CustomTransducerMigrator is the last consumer of these.
    val customTransducerModelPath: Flow<String?>
    val customTransducerModelType: Flow<String>
    /** One-shot custom-transducer -> external-model migration marker (v2a Task 9). */
    val externalMigrationDone: Flow<Boolean>
    /** TASK-401: source index for the community catalog dialog. Default = the
     *  index published in our repo; an override persists until changed back. */
    val externalCatalogUrl: Flow<String>
    suspend fun saveExternalCatalogUrl(url: String)
    val ggufModelPath: Flow<String?>
    val autoCopyEnabled: Flow<Boolean>
    val outputFolderUri: Flow<String?>
    val vadEnabled: Flow<Boolean>
    val vadAdvisoryDismissed: Flow<Boolean>
    val progressiveTranscription: Flow<Boolean>
    val defaultPrompt: Flow<String>
    /** TASK-276 punctuation pass mode: "off" | "auto" | "always"; default "auto". */
    val punctuationMode: Flow<String>
    /** TASK-276 user override of the punctuation prompt; blank = the localized curated default. */
    val punctuationPrompt: Flow<String>
    val threadCount: Flow<Int>
    val inferenceProvider: Flow<String>
    val transcriptionLanguage: Flow<String>
    val swipeActionMode: Flow<String>
    val groupLogsByConversation: Flow<Boolean>
    val advancedSharingEnabled: Flow<Boolean>
    val showRetranscribeButton: Flow<Boolean>
    val forceModelLoad: Flow<Boolean>
    val compactResultActions: Flow<Boolean>
    /** GH #45 follow-up: show the task-id detail line on log entries. Default off. */
    val showTaskDetails: Flow<Boolean>

    val externalModelsJson: Flow<String?>
    suspend fun saveExternalModelsJson(json: String)

    suspend fun saveModelPath(path: String)
    suspend fun clearModelPath()
    suspend fun saveKeepAliveTimeout(minutes: Int)
    suspend fun saveThemePreference(theme: String)
    suspend fun saveThemeMode(mode: String)
    suspend fun saveTranscriptionBackend(backendId: String)
    suspend fun saveSherpaModelPath(entryId: String, path: String)
    suspend fun clearSherpaModelPath(entryId: String)

    suspend fun saveExternalMigrationDone(done: Boolean)

    suspend fun saveGgufModelPath(path: String)
    suspend fun clearGgufModelPath()
    suspend fun saveAutoCopyEnabled(enabled: Boolean)
    suspend fun saveOutputFolderUri(uri: String?)
    suspend fun saveVadEnabled(enabled: Boolean)
    suspend fun saveVadAdvisoryDismissed(dismissed: Boolean)
    suspend fun saveProgressiveTranscription(enabled: Boolean)
    suspend fun saveDefaultPrompt(prompt: String)
    suspend fun savePunctuationMode(mode: String)
    suspend fun savePunctuationPrompt(prompt: String)
    suspend fun saveThreadCount(threads: Int)
    suspend fun saveInferenceProvider(provider: String)
    suspend fun saveTranscriptionLanguage(language: String)
    suspend fun saveSwipeActionMode(mode: String)
    suspend fun saveGroupLogsByConversation(enabled: Boolean)
    suspend fun saveAdvancedSharingEnabled(enabled: Boolean)
    suspend fun saveShowRetranscribeButton(enabled: Boolean)
    suspend fun saveForceModelLoad(enabled: Boolean)
    suspend fun saveCompactResultActions(enabled: Boolean)
    suspend fun saveShowTaskDetails(enabled: Boolean)

    suspend fun saveBenchmarkResult(modelId: String, jsonResult: String)
    fun getBenchmarkResult(modelId: String): Flow<String?>
    fun getAllBenchmarkResults(): Flow<Map<String, String>>
    suspend fun clearBenchmarkResult(modelId: String)
    suspend fun clearAllBenchmarkResults()

    suspend fun getLegacyLanguagePreference(): String

    val partialTranscriptionText: Flow<String?>
    val partialTranscriptionTimestamp: Flow<Long?>
    suspend fun savePartialTranscriptionState(text: String)
    suspend fun clearPartialTranscriptionState()

    companion object {
        const val DEFAULT_KEEP_ALIVE_TIMEOUT = 5
        val DEFAULT_THREAD_COUNT = maxOf(2, Runtime.getRuntime().availableProcessors() - 2).coerceAtMost(8)
        const val DEFAULT_AUTO_COPY_ENABLED = false
        const val DEFAULT_VAD_ENABLED = false
        const val DEFAULT_PROGRESSIVE_TRANSCRIPTION = true
        const val DEFAULT_PROMPT_VALUE = ""
        /** TASK-276: the AUTO mode trusts the per-model punctuatesOutput flag. */
        const val DEFAULT_PUNCTUATION_MODE = "auto"
        const val DEFAULT_THEME = "DEFAULT"
        const val DEFAULT_THEME_MODE = "SYSTEM"
        const val DEFAULT_TRANSCRIPTION_BACKEND = "sherpa-onnx"

        // Backend id for user-imported sherpa-onnx transducer models (Strada B sideload).
        // Default model architecture type for custom imports. Covers GigaAM-ru and Parakeet.
        // A wrong modelType causes an uncatchable native exit(255); user can change it in the import UI.
        const val DEFAULT_CUSTOM_TRANSDUCER_MODEL_TYPE = "nemo_transducer"
        const val DEFAULT_LANGUAGE = "system"
        /**
         * TASK-434: the untouched default follows the app/UI locale on variants
         * flagged `preferUiLanguage` (Whisper Small), else auto-detects. "auto"
         * remains a selectable, explicit model-side auto-detection choice; see
         * TranscriptionLanguagePolicy.
         */
        const val DEFAULT_TRANSCRIPTION_LANGUAGE = "system"
        const val DEFAULT_SWIPE_ACTION_MODE = "REVEAL"
        const val DEFAULT_INFERENCE_PROVIDER = "auto"
        const val DEFAULT_GROUP_LOGS_BY_CONVERSATION = true
        const val DEFAULT_ADVANCED_SHARING_ENABLED = false
        const val DEFAULT_SHOW_RETRANSCRIBE_BUTTON = true
        const val DEFAULT_FORCE_MODEL_LOAD = false
        const val DEFAULT_COMPACT_RESULT_ACTIONS = true
        const val DEFAULT_SHOW_TASK_DETAILS = false

        /** The maintained community index, published from this repo. */
        const val DEFAULT_EXTERNAL_CATALOG_URL =
            "https://raw.githubusercontent.com/RisorseArtificiali/anti-vocale/main/app/src/main/assets/external-catalog/index.json"
    }
}
