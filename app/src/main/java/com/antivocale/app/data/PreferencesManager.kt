package com.antivocale.app.data

import com.antivocale.app.BuildConfig

import kotlinx.coroutines.flow.Flow

interface PreferencesManager {

    val modelPath: Flow<String?>
    val keepAliveTimeout: Flow<Int>

    /** TASK-515: minutes the subtitles-or-transcribe choice waits before the
     *  timed WorkManager fallback transcribes automatically. */
    val subtitleChoiceTimeoutMinutes: Flow<Int>
    val themePreference: Flow<String>
    /** TASK-576: app text-size step (TextScale name); SYSTEM = no extra scaling. */
    val textScalePreference: Flow<String>
    suspend fun saveTextScale(value: String)

    /** GH #43: two-pass transcription (fast streaming preview, then refine). */
    val refinementEnabled: Flow<Boolean>

    /** GH #83: post-transcription speaker labeling on the cue timeline. */
    val speakerLabelsEnabled: Flow<Boolean>
    suspend fun saveRefinementEnabled(enabled: Boolean)

    /** GH #83. */
    suspend fun saveSpeakerLabelsEnabled(enabled: Boolean)
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
    /** TASK-643: reset by REMOVING the key, so the default re-resolves per build. */
    suspend fun clearExternalCatalogUrl()
    val autoCopyEnabled: Flow<Boolean>

    /** TASK-647: AI-disclaimer signature applied to every exit surface (copy/share/export). */
    val signatureEnabled: Flow<Boolean>
    val signatureText: Flow<String>
    val signaturePosition: Flow<String>

    val outputFolderUri: Flow<String?>
    /** GH #92: auto-save file format, a [SubtitleFormatter.Format] name. Default TXT. */
    val transcriptExportFormat: Flow<String>
    val vadEnabled: Flow<Boolean>
    val vadAdvisoryDismissed: Flow<Boolean>

    /**
     * TASK-491: the first-install welcome tour has been completed (or
     * skipped). NOT version-keyed: an app update must never replay the tour;
     * it resets only by a fresh install (DataStore cleared) or the explicit
     * Settings replay row.
     */
    val onboardingCompleted: Flow<Boolean>
    val progressiveTranscription: Flow<Boolean>
    val defaultPrompt: Flow<String>
    /** TASK-276 punctuation pass mode: "off" | "auto" | "always"; default "auto". */
    val punctuationMode: Flow<String>
    /** TASK-276 user override of the punctuation prompt; blank = the localized curated default. */
    val punctuationPrompt: Flow<String>
    /** TASK-121.4 smart-summary pass toggle: attach a Gemma summary to long transcripts. Default off. */
    val summarizeEnabled: Flow<Boolean>

    /** TASK-483: editable override of the summary-pass prompt. Blank = the built-in 2-3 sentence default. */
    val summaryPrompt: Flow<String>
    val threadCount: Flow<Int>
    val inferenceProvider: Flow<String>
    val transcriptionLanguage: Flow<String>
    val swipeActionMode: Flow<String>
    val groupLogsByConversation: Flow<Boolean>
    /** TASK-616: render the technical processing-context line on expanded entries. */
    val showTechnicalDetails: Flow<Boolean>
    val advancedSharingEnabled: Flow<Boolean>
    val showRetranscribeButton: Flow<Boolean>
    val memoryProtection: Flow<Boolean>
    val compactResultActions: Flow<Boolean>
    /** TASK-546: show the detected-language chip on results. */
    val languageChipEnabled: Flow<Boolean>

    val externalModelsJson: Flow<String?>
    suspend fun saveExternalModelsJson(json: String)

    suspend fun saveModelPath(path: String)
    suspend fun clearModelPath()
    suspend fun saveKeepAliveTimeout(minutes: Int)

    /** TASK-515: see [subtitleChoiceTimeoutMinutes]. */
    suspend fun saveSubtitleChoiceTimeoutMinutes(minutes: Int)
    suspend fun saveThemePreference(theme: String)
    suspend fun saveThemeMode(mode: String)
    suspend fun saveTranscriptionBackend(backendId: String)
    suspend fun saveSherpaModelPath(entryId: String, path: String)
    suspend fun clearSherpaModelPath(entryId: String)

    suspend fun saveExternalMigrationDone(done: Boolean)

    suspend fun saveAutoCopyEnabled(enabled: Boolean)
    suspend fun saveSignatureEnabled(enabled: Boolean)
    suspend fun saveSignatureText(text: String)
    suspend fun saveSignaturePosition(position: String)

    suspend fun saveOutputFolderUri(uri: String?)
    /** GH #92: see [transcriptExportFormat]. */
    suspend fun saveTranscriptExportFormat(format: String)
    suspend fun saveVadEnabled(enabled: Boolean)
    suspend fun saveVadAdvisoryDismissed(dismissed: Boolean)

    /** TASK-491: marks the welcome tour done; false re-arms it. */
    suspend fun saveOnboardingCompleted(completed: Boolean)
    suspend fun saveProgressiveTranscription(enabled: Boolean)
    suspend fun saveDefaultPrompt(prompt: String)
    suspend fun savePunctuationMode(mode: String)
    suspend fun savePunctuationPrompt(prompt: String)
    suspend fun saveSummarizeEnabled(enabled: Boolean)
    suspend fun saveSummaryPrompt(prompt: String)
    suspend fun saveThreadCount(threads: Int)
    suspend fun saveInferenceProvider(provider: String)
    suspend fun saveTranscriptionLanguage(language: String)
    suspend fun saveSwipeActionMode(mode: String)
    suspend fun saveGroupLogsByConversation(enabled: Boolean)
    /** TASK-616: see [showTechnicalDetails]. */
    suspend fun saveShowTechnicalDetails(enabled: Boolean)
    suspend fun saveAdvancedSharingEnabled(enabled: Boolean)
    suspend fun saveShowRetranscribeButton(enabled: Boolean)
    suspend fun saveMemoryProtection(enabled: Boolean)
    suspend fun saveCompactResultActions(enabled: Boolean)
    suspend fun saveLanguageChipEnabled(enabled: Boolean)

    suspend fun saveBenchmarkResult(modelId: String, jsonResult: String)
    fun getBenchmarkResult(modelId: String): Flow<String?>
    fun getAllBenchmarkResults(): Flow<Map<String, String>>
    suspend fun clearBenchmarkResult(modelId: String)
    suspend fun clearAllBenchmarkResults()

    /**
     * TASK-575 / GH #106: measured per-model load footprints (model key ->
     * record). The load pre-flight prefers these over the disk-size estimate
     * once a model has run on this device.
     */
    val measuredModelMemory: Flow<Map<String, com.antivocale.app.transcription.MeasuredModelMemory.Record>>

    /**
     * Merges one load sample into the record for [key] INSIDE the storage
     * transaction (read-modify-write races would silently lose the max;
     * review F5). No-op when the sample proves nothing (warm no-op load).
     */
    suspend fun mergeMeasuredModelMemorySample(
        key: String,
        loadDeltaBytes: Long,
        modelSizeBytes: Long,
    )

    /** Drops records whose key is not in [validKeys] (dead model dirs; review F3). */
    suspend fun pruneMeasuredModelMemory(validKeys: Set<String>)

    suspend fun getLegacyLanguagePreference(): String

    val partialTranscriptionText: Flow<String?>
    val partialTranscriptionTimestamp: Flow<Long?>

    /**
     * TASK-640: the backend id whose native load is in flight. Set before the
     * recognizer construction, cleared when it returns; a native death leaves
     * it set, and the next launch quarantines the model (external ids) instead
     * of walking into the same crash again.
     */
    val pendingBackendLoad: Flow<String?>
    suspend fun savePendingBackendLoad(backendId: String?)
    suspend fun savePartialTranscriptionState(text: String)
    suspend fun clearPartialTranscriptionState()

    companion object {
        const val DEFAULT_KEEP_ALIVE_TIMEOUT = 5
        /** TASK-515: the stored default; the dropdown's offered set is
         *  presentation data and lives on SettingsViewModel. */
        const val DEFAULT_SUBTITLE_CHOICE_TIMEOUT_MINUTES = 5
        val DEFAULT_THREAD_COUNT = maxOf(2, Runtime.getRuntime().availableProcessors() - 2).coerceAtMost(8)
        const val DEFAULT_AUTO_COPY_ENABLED = false
        /** TASK-647: blank text = use the localized default at assembly time. */
        const val DEFAULT_SIGNATURE_ENABLED = false
        const val DEFAULT_SIGNATURE_TEXT = ""
        const val DEFAULT_SIGNATURE_POSITION = "append"
        val SIGNATURE_POSITIONS = listOf("prepend", "append")

        /** GH #92: plain .txt is the default; timed formats are strictly opt-in. */
        const val DEFAULT_TRANSCRIPT_EXPORT_FORMAT = "TXT"
        const val DEFAULT_VAD_ENABLED = false
        const val DEFAULT_PROGRESSIVE_TRANSCRIPTION = true
        const val DEFAULT_PROMPT_VALUE = ""

        /**
         * Character cap shared by every prompt editor and saver (TASK-485:
         * the 500 literal had drifted across 7 unlinked take() sites).
         */
        const val PROMPT_CAP = 500
        /** TASK-276: the AUTO mode trusts the per-model punctuatesOutput flag. */
        const val DEFAULT_PUNCTUATION_MODE = "auto"
        /** TASK-121.4: opt-in; a second inference per long transcript must be a choice. */
        const val DEFAULT_SUMMARIZE_ENABLED = false
        const val DEFAULT_THEME = "DEFAULT"
        const val DEFAULT_THEME_MODE = "SYSTEM"
        const val DEFAULT_TEXT_SCALE = "SYSTEM"
        const val DEFAULT_TRANSCRIPTION_BACKEND = "sherpa-onnx"

        // Backend id for user-imported sherpa-onnx transducer models (Strada B sideload).
        // Default model architecture type for custom imports. Covers GigaAM-ru and Parakeet.
        // A wrong modelType causes an uncatchable native exit(255); user can change it in the import UI.
        const val DEFAULT_CUSTOM_TRANSDUCER_MODEL_TYPE = "nemo_transducer"
        const val DEFAULT_LANGUAGE = "system"
        /**
         * The untouched default. TASK-457 removed the app-locale pinning it used
         * to carry: "system" now resolves exactly like "auto" (model-side
         * detection), and survives only as the stored default so existing
         * installs keep resolving without a preference migration; see
         * TranscriptionLanguagePolicy.
         */
        const val DEFAULT_TRANSCRIPTION_LANGUAGE = "system"
        const val DEFAULT_SWIPE_ACTION_MODE = "REVEAL"

        /** The Logs swipe dropdown's exact option set (SettingsTab + the test SPI both pin to this). */
        val SWIPE_ACTION_MODES = com.antivocale.app.ui.tabs.SwipeActionMode.NAMES
        const val DEFAULT_INFERENCE_PROVIDER = "auto"
        const val DEFAULT_GROUP_LOGS_BY_CONVERSATION = true
        /** TASK-616: the technical line is diagnostic detail; hidden unless asked for. */
        const val DEFAULT_SHOW_TECHNICAL_DETAILS = false
        const val DEFAULT_ADVANCED_SHARING_ENABLED = false
        const val DEFAULT_SHOW_RETRANSCRIBE_BUTTON = true
        /** TASK-631: protection is opt-in; by default the app never refuses a load on its own. */
        const val DEFAULT_MEMORY_PROTECTION = false
        const val DEFAULT_COMPACT_RESULT_ACTIONS = true
        /** TASK-546: the chip mitigates invisible wrong-language detection; on by default. */
        const val DEFAULT_LANGUAGE_CHIP_ENABLED = true

        /**
         * The maintained community index, published from this repo.
         * TASK-643: version-scoped (index-<versionName>.json, derived by
         * gradle from the release bump). Apps <=1.13.x keep reading the
         * unsuffixed index.json, which is frozen: new entries land only in
         * the newest version's file, so an entry never reaches an app older
         * than the modelType it requires.
         */
        val DEFAULT_EXTERNAL_CATALOG_URL: String get() = BuildConfig.CATALOG_INDEX_URL
    }
}
