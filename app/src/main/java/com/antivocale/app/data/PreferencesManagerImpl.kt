package com.antivocale.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.antivocale.app.transcription.SummaryPreset
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "localai_preferences",
    // TASK-908: see reportingReplaceCorruptionHandler.
    corruptionHandler = reportingReplaceCorruptionHandler("preferences"))

// TASK-1009: every preference flow below rides the prefFlow builder family
// or carries its own guard with a site rationale; the builders' KDoc owns
// the re-emission hazard. Flows feeding only stateIn UI collectors are
// technically exempt, but no naked flow remains.
class PreferencesManagerImpl(
    private val context: Context,
    injectedDataStore: DataStore<Preferences>? = null,
) : PreferencesManager {

    private val dataStore: DataStore<Preferences> = injectedDataStore ?: context.dataStore

    /**
     * TASK-1009: the canonical preference-flow shape. DataStore re-emits its
     * whole snapshot on EVERY write to ANY key, so a plain map{} flow
     * re-emits an UNCHANGED value on every unrelated write; a
     * change-reactive collector on the other end (the TASK-1008 class)
     * then restarts real work for nothing. The builder makes the three
     * disciplines structural: map the key, seed from the cache in onStart,
     * and drop re-emitted equal values with distinctUntilChanged. Flows
     * whose shape is NOT this canonical triple stay hand-built and carry
     * their own rationale.
     */
    private fun <T : Any> prefFlow(
        key: Preferences.Key<T>,
        default: T,
        cached: () -> T,
    ): Flow<T> = dataStore.data
        .map { it[key] ?: default }
        .onStart { emit(cached()) }
        .distinctUntilChanged()

    /** The nullable-key twin ([prefFlow] for keys with no stored default). */
    private fun <T : Any> prefFlowNullable(
        key: Preferences.Key<T>,
        cached: () -> T?,
    ): Flow<T?> = dataStore.data
        .map { it[key] }
        .onStart { emit(cached()) }
        .distinctUntilChanged()

    /** The unseeded twins: keys with no cache mirror ([prefFlow] minus the
     *  onStart seed; the guard stays structural). */
    private fun <T : Any> prefFlowUnseeded(
        key: Preferences.Key<T>,
        default: T,
    ): Flow<T> = dataStore.data
        .map { it[key] ?: default }
        .distinctUntilChanged()

    private fun <T : Any> prefFlowUnseededNullable(
        key: Preferences.Key<T>,
    ): Flow<T?> = dataStore.data
        .map { it[key] }
        .distinctUntilChanged()

    companion object {
        private val MODEL_PATH = stringPreferencesKey("model_path")
        private val KEEP_ALIVE_TIMEOUT = intPreferencesKey("keep_alive_timeout_v2")
        private val SUBTITLE_CHOICE_TIMEOUT = intPreferencesKey("subtitle_choice_timeout")
        private val KEEP_ALIVE_TIMEOUT_LEGACY = stringPreferencesKey("keep_alive_timeout")
        private val LANGUAGE_PREFERENCE = stringPreferencesKey("language_preference")
        private val THEME_PREFERENCE = stringPreferencesKey("theme_preference")
        private val TEXT_SCALE = stringPreferencesKey("text_scale")
        private val REFINEMENT_ENABLED = booleanPreferencesKey("refinement_enabled")
        private val SETTINGS_TRANSCRIPTION_ADVANCED_EXPANDED =
            booleanPreferencesKey("settings_transcription_advanced_expanded")
        private val SPEAKER_LABELS_ENABLED = booleanPreferencesKey("speaker_labels_enabled")
        // TASK-670 (GH #83): the named-labels privacy gate, default off.
        private val SPEAKER_ID_ENABLED = booleanPreferencesKey("speaker_id_enabled")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val TRANSCRIPTION_BACKEND = stringPreferencesKey("transcription_backend")
        private val REFINEMENT_MODEL_BACKEND_ID = stringPreferencesKey("refinement_model_backend_id")
        private val WIPED_REPAIR_CAPTURED_ACTIVE =
            stringPreferencesKey("wiped_repair_captured_active")
        private val WIPED_REPAIR_CAPTURED_PIN =
            stringPreferencesKey("wiped_repair_captured_pin")
        // TASK-973: record ids whose source proved dead (the native set
        // form, same as DEMOTED_BACKENDS above).
        private val WIPED_REPAIR_DEAD_SOURCES =
            stringSetPreferencesKey("wiped_repair_dead_sources")
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
        private val PENDING_BACKEND_LOAD = stringPreferencesKey("pending_backend_load")
        private val AUTO_COPY_ENABLED = booleanPreferencesKey("auto_copy_enabled")
        private val SIGNATURE_ENABLED = booleanPreferencesKey("signature_enabled")
        private val SIGNATURE_TEXT = stringPreferencesKey("signature_text")
        private val SIGNATURE_POSITION = stringPreferencesKey("signature_position")
        private val OUTPUT_FOLDER_URI = stringPreferencesKey("output_folder_uri")
        private val TRANSCRIPT_EXPORT_FORMAT = stringPreferencesKey("transcript_export_format")
        private val VAD_ENABLED = booleanPreferencesKey("vad_enabled")
        private val FREEZE_PROTECTION_ENABLED = booleanPreferencesKey("freeze_protection_enabled")
        private val PROGRESSIVE_TRANSCRIPTION = booleanPreferencesKey("progressive_transcription")
        // TASK-186: the early-preview gate on pipelined runs.
        private val EARLY_PREVIEW_ENABLED = booleanPreferencesKey("early_preview_enabled")
        private val INTERRUPTED_RUN_NOTIFICATIONS = booleanPreferencesKey("interrupted_run_notifications")
        private val DEFAULT_PROMPT = stringPreferencesKey("default_prompt")
        private val PUNCTUATION_MODE = stringPreferencesKey("punctuation_mode")
        private val PUNCTUATION_PROMPT = stringPreferencesKey("punctuation_prompt")
        private val KEYWORD_BIASING = stringPreferencesKey("keyword_biasing")
        private val BIASING_SCORE = floatPreferencesKey("biasing_score")
        private val SUMMARIZE_ENABLED = booleanPreferencesKey("summarize_enabled")
        private val SUMMARY_PROMPT = stringPreferencesKey("summary_prompt")
        private val SUMMARY_PRESET = stringPreferencesKey("summary_preset")
        private val THREAD_COUNT = intPreferencesKey("thread_count")
        private val INFERENCE_PROVIDER = stringPreferencesKey("inference_provider")
        private val TRANSCRIPTION_LANGUAGE = stringPreferencesKey("transcription_language")
        private val SWIPE_ACTION_MODE = stringPreferencesKey("swipe_action_mode")
        private val BENCHMARK_RESULTS = stringPreferencesKey("benchmark_results")
        private val MEASURED_MODEL_MEMORY = stringPreferencesKey("measured_model_memory")
        private val VAD_ADVISORY_DISMISSED = booleanPreferencesKey("vad_advisory_dismissed")
        private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val GROUP_LOGS_BY_CONVERSATION = booleanPreferencesKey("group_logs_by_conversation")
        private val SHOW_TECHNICAL_DETAILS = booleanPreferencesKey("show_technical_details")
        private val ADVANCED_SHARING_ENABLED = booleanPreferencesKey("advanced_sharing_enabled")
        private val SHOW_RETRANSCRIBE_BUTTON = booleanPreferencesKey("show_retranscribe_button")
        // TASK-939: the word-tap seek read-along gate.
        private val WORD_TAP_SEEK = booleanPreferencesKey("word_tap_seek")
        private val ADVANCED_SHARE_FORMATS = booleanPreferencesKey("advanced_share_formats")
        // TASK-631: replaces the old force-load key with NO migration; that key's only
        // signal was an opt-out of the block, which the new default (no block) grants
        // to everyone, so a stored old value is deliberately never read again.
        private val MEMORY_PROTECTION = booleanPreferencesKey("memory_protection")
        // TASK-274: consent gate for the exported automation receivers.
        private val EXTERNAL_AUTOMATION_ENABLED = booleanPreferencesKey("external_automation_enabled")
        // TASK-785: consent gate for the Open Transcribe provider service.
        private val OPEN_TRANSCRIBE_ENABLED = booleanPreferencesKey("open_transcribe_enabled")
        private val VOICE_NOTE_IDENTITY_ENABLED = booleanPreferencesKey("voice_note_identity_enabled")
        // TASK-271: the voice-note detection gate (offer notifications).
        private val VOICE_NOTE_DETECTION_ENABLED = booleanPreferencesKey("voice_note_detection_enabled")
        private val VOICE_NOTE_TREE_URI = stringPreferencesKey("voice_note_tree_uri")
        // WhatsApp Business support: the same pair for the Business client.
        private val VOICE_NOTE_BUSINESS_DETECTION_ENABLED = booleanPreferencesKey("voice_note_business_detection_enabled")
        private val VOICE_NOTE_BUSINESS_TREE_URI = stringPreferencesKey("voice_note_business_tree_uri")
        // TASK-966: the same pair for the generic watched-folder source
        // (no watermark twin: that source has no keyed catch-up).
        private val VOICE_NOTE_WATCHED_DETECTION_ENABLED = booleanPreferencesKey("voice_note_watched_detection_enabled")
        private val VOICE_NOTE_WATCHED_TREE_URI = stringPreferencesKey("voice_note_watched_tree_uri")
        // TASK-778: the persisted voice-note watermark (the durable dedup
        // boundary the cold-start catch-up sweep compares against).
        private val VOICE_NOTE_WATERMARK = stringPreferencesKey("voice_note_watermark")
        // TASK-891: the Business client's own watermark (independent counter
        // tree); the WhatsApp entry keeps the legacy key above, so released
        // installs (which never ran a Business detector) migrate as-is.
        private val VOICE_NOTE_BUSINESS_WATERMARK = stringPreferencesKey("voice_note_business_watermark")
        // TASK-1047: the watched-folder source's mtime watermark (the
        // unkeyed sweep's own key: the newest EMITTED mtime as epoch-millis
        // string).
        private val VOICE_NOTE_WATCHED_WATERMARK = stringPreferencesKey("voice_note_watched_watermark")
        // TASK-681: the LAN-offload (OmniVoice) consent gate and its config triple.
        private val REMOTE_OMNIVOICE_ENABLED = booleanPreferencesKey("remote_omnivoice_enabled")
        private val REMOTE_OMNIVOICE_ENDPOINT = stringPreferencesKey("remote_omnivoice_endpoint")
        private val REMOTE_OMNIVOICE_API_KEY = stringPreferencesKey("remote_omnivoice_api_key")
        private val REMOTE_OMNIVOICE_MODEL = stringPreferencesKey("remote_omnivoice_model")
        private val COMPACT_RESULT_ACTIONS = booleanPreferencesKey("compact_result_actions")
        private val LANGUAGE_CHIP_ENABLED = booleanPreferencesKey("language_chip_enabled")
        private val PARTIAL_TRANSCRIPTION_TEXT = stringPreferencesKey("partial_transcription_text")
        private val PARTIAL_TRANSCRIPTION_TIMESTAMP = longPreferencesKey("partial_transcription_timestamp")
        private val EXTERNAL_MODELS_JSON = stringPreferencesKey("external_models_json")
        // TASK-741: the scheduled folder watch (records + dedup snapshots).
        private val SCHEDULED_FOLDERS_JSON = stringPreferencesKey("scheduled_folders_json")
        // TASK-675: silent-model demotion set (backend ids).
        private val DEMOTED_BACKENDS = stringSetPreferencesKey("demoted_backends")
        // TASK-685: the Models-filter favorite; key absence = untouched.
        private val MODEL_FILTER_LANGUAGE = stringPreferencesKey("model_filter_language")
        // TASK-929: the What's-New popup's once-per-update marker.
        private val WHATS_NEW_LAST_SHOWN_VERSION = intPreferencesKey("whats_new_last_shown_version")
    }

    private val cache = AtomicReference(CachedPreferences())

    private data class CachedPreferences(
        val modelPath: String? = null,
        val keepAliveTimeout: Int = PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT,
        val subtitleChoiceTimeout: Int = PreferencesManager.DEFAULT_SUBTITLE_CHOICE_TIMEOUT_MINUTES,
        val themePreference: String = PreferencesManager.DEFAULT_THEME,
        val themeMode: String = PreferencesManager.DEFAULT_THEME_MODE,
        val textScale: String = PreferencesManager.DEFAULT_TEXT_SCALE,
        val transcriptionBackend: String = PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND,
        val refinementModelBackendId: String = "",
        val sherpaModelPaths: Map<String, String?> = emptyMap(),
        val customTransducerModelPath: String? = null,
        val customTransducerModelType: String = PreferencesManager.DEFAULT_CUSTOM_TRANSDUCER_MODEL_TYPE,
        val externalMigrationDone: Boolean = false,
        val pendingBackendLoad: String? = null,
        val externalCatalogUrl: String = PreferencesManager.DEFAULT_EXTERNAL_CATALOG_URL,
        val autoCopyEnabled: Boolean = PreferencesManager.DEFAULT_AUTO_COPY_ENABLED,
        val signatureEnabled: Boolean = PreferencesManager.DEFAULT_SIGNATURE_ENABLED,
        val signatureText: String = PreferencesManager.DEFAULT_SIGNATURE_TEXT,
        val signaturePosition: String = PreferencesManager.DEFAULT_SIGNATURE_POSITION,
        val outputFolderUri: String? = null,
        val transcriptExportFormat: String = PreferencesManager.DEFAULT_TRANSCRIPT_EXPORT_FORMAT,
        val vadEnabled: Boolean = PreferencesManager.DEFAULT_VAD_ENABLED,
        val freezeProtectionEnabled: Boolean = PreferencesManager.DEFAULT_FREEZE_PROTECTION_ENABLED,
        val progressiveTranscription: Boolean = PreferencesManager.DEFAULT_PROGRESSIVE_TRANSCRIPTION,
        val earlyPreviewEnabled: Boolean = PreferencesManager.DEFAULT_EARLY_PREVIEW,
        val interruptedRunNotifications: Boolean = PreferencesManager.DEFAULT_INTERRUPTED_RUN_NOTIFICATIONS,
        val defaultPrompt: String = PreferencesManager.DEFAULT_PROMPT_VALUE,
        val punctuationMode: String = PreferencesManager.DEFAULT_PUNCTUATION_MODE,
        val punctuationPrompt: String = "",
        val keywordBiasing: String = "",
        val biasingScore: Float = PreferencesManager.DEFAULT_BIASING_SCORE,
        val summaryPrompt: String = "",
        val summaryPreset: String = PreferencesManager.DEFAULT_SUMMARY_PRESET,
        val summarizeEnabled: Boolean = PreferencesManager.DEFAULT_SUMMARIZE_ENABLED,
        val threadCount: Int = PreferencesManager.DEFAULT_THREAD_COUNT,
        val inferenceProvider: String = PreferencesManager.DEFAULT_INFERENCE_PROVIDER,
        val transcriptionLanguage: String = PreferencesManager.DEFAULT_TRANSCRIPTION_LANGUAGE,
        // TASK-685: null = untouched (the onboarding seed may fire), "" =
        // explicitly cleared, a code = the Models-filter favorite.
        val modelFilterLanguage: String? = null,
        val swipeActionMode: String = PreferencesManager.DEFAULT_SWIPE_ACTION_MODE,
        val vadAdvisoryDismissed: Boolean = false,
        val onboardingCompleted: Boolean = false,
        val groupLogsByConversation: Boolean = PreferencesManager.DEFAULT_GROUP_LOGS_BY_CONVERSATION,
        val showTechnicalDetails: Boolean = PreferencesManager.DEFAULT_SHOW_TECHNICAL_DETAILS,
        val advancedSharingEnabled: Boolean = PreferencesManager.DEFAULT_ADVANCED_SHARING_ENABLED,
        val showRetranscribeButton: Boolean = PreferencesManager.DEFAULT_SHOW_RETRANSCRIBE_BUTTON,
        val advancedShareFormats: Boolean = PreferencesManager.DEFAULT_ADVANCED_SHARE_FORMATS,
        val wordTapSeek: Boolean = PreferencesManager.DEFAULT_WORD_TAP_SEEK,
        val memoryProtection: Boolean = PreferencesManager.DEFAULT_MEMORY_PROTECTION,
        val externalAutomationEnabled: Boolean = PreferencesManager.DEFAULT_EXTERNAL_AUTOMATION_ENABLED,
        val openTranscribeEnabled: Boolean = PreferencesManager.DEFAULT_OPEN_TRANSCRIBE_ENABLED,
        val voiceNoteIdentityEnabled: Boolean = PreferencesManager.DEFAULT_VOICE_NOTE_IDENTITY_ENABLED,
        val voiceNoteDetectionEnabled: Boolean = PreferencesManager.DEFAULT_VOICE_NOTE_DETECTION_ENABLED,
        val voiceNoteTreeUri: String? = null,
        val voiceNoteBusinessDetectionEnabled: Boolean = PreferencesManager.DEFAULT_VOICE_NOTE_DETECTION_ENABLED,
        val voiceNoteBusinessTreeUri: String? = null,
        val voiceNoteWatchedDetectionEnabled: Boolean = PreferencesManager.DEFAULT_VOICE_NOTE_DETECTION_ENABLED,
        val voiceNoteWatchedTreeUri: String? = null,
        val voiceNoteWatermark: String? = null,
        val voiceNoteBusinessWatermark: String? = null,
        val voiceNoteWatchedWatermark: String? = null,
        val remoteOmnivoiceEnabled: Boolean = PreferencesManager.DEFAULT_REMOTE_OMNIVOICE_ENABLED,
        val remoteOmnivoiceEndpoint: String = PreferencesManager.DEFAULT_REMOTE_OMNIVOICE_ENDPOINT,
        val remoteOmnivoiceApiKey: String = PreferencesManager.DEFAULT_REMOTE_OMNIVOICE_API_KEY,
        val remoteOmnivoiceModel: String = PreferencesManager.DEFAULT_REMOTE_OMNIVOICE_MODEL,
        val compactResultActions: Boolean = PreferencesManager.DEFAULT_COMPACT_RESULT_ACTIONS,
        val languageChipEnabled: Boolean = PreferencesManager.DEFAULT_LANGUAGE_CHIP_ENABLED,
        val externalModelsJson: String? = null,
        val wipedRepairDeadSources: Set<String> = emptySet(),
        val scheduledFoldersJson: String? = null,
        val whatsNewLastShownVersion: Int = PreferencesManager.DEFAULT_WHATS_NEW_LAST_SHOWN_VERSION
    )

    private fun Preferences.toCached() = CachedPreferences(
        modelPath = this[MODEL_PATH],
        keepAliveTimeout = this[KEEP_ALIVE_TIMEOUT]
            ?: this[KEEP_ALIVE_TIMEOUT_LEGACY]?.toIntOrNull()
            ?: PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT,
        subtitleChoiceTimeout = this[SUBTITLE_CHOICE_TIMEOUT]
            ?: PreferencesManager.DEFAULT_SUBTITLE_CHOICE_TIMEOUT_MINUTES,
        themePreference = this[THEME_PREFERENCE] ?: PreferencesManager.DEFAULT_THEME,
        themeMode = this[THEME_MODE] ?: PreferencesManager.DEFAULT_THEME_MODE,
        textScale = this[TEXT_SCALE] ?: PreferencesManager.DEFAULT_TEXT_SCALE,
        transcriptionBackend = this[TRANSCRIPTION_BACKEND] ?: PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND,
        refinementModelBackendId = this[REFINEMENT_MODEL_BACKEND_ID] ?: "",
        sherpaModelPaths = LEGACY_MODEL_PATH_KEYS.entries.associate { (entryId, legacyKey) ->
            entryId to (this[sherpaModelPathKey(entryId)] ?: this[legacyKey])
        },
        customTransducerModelPath = this[CUSTOM_TRANSDUCER_MODEL_PATH],
        customTransducerModelType = this[CUSTOM_TRANSDUCER_MODEL_TYPE]
            ?: PreferencesManager.DEFAULT_CUSTOM_TRANSDUCER_MODEL_TYPE,
        externalMigrationDone = this[EXTERNAL_MIGRATION_DONE] ?: false,
        pendingBackendLoad = this[PENDING_BACKEND_LOAD],
        externalCatalogUrl = this[EXTERNAL_CATALOG_URL] ?: PreferencesManager.DEFAULT_EXTERNAL_CATALOG_URL,
        autoCopyEnabled = this[AUTO_COPY_ENABLED] ?: PreferencesManager.DEFAULT_AUTO_COPY_ENABLED,
        signatureEnabled = this[SIGNATURE_ENABLED] ?: PreferencesManager.DEFAULT_SIGNATURE_ENABLED,
        signatureText = this[SIGNATURE_TEXT] ?: PreferencesManager.DEFAULT_SIGNATURE_TEXT,
        signaturePosition = this[SIGNATURE_POSITION] ?: PreferencesManager.DEFAULT_SIGNATURE_POSITION,
        outputFolderUri = this[OUTPUT_FOLDER_URI],
        transcriptExportFormat = this[TRANSCRIPT_EXPORT_FORMAT] ?: PreferencesManager.DEFAULT_TRANSCRIPT_EXPORT_FORMAT,
        vadEnabled = this[VAD_ENABLED] ?: PreferencesManager.DEFAULT_VAD_ENABLED,
        freezeProtectionEnabled = this[FREEZE_PROTECTION_ENABLED] ?: PreferencesManager.DEFAULT_FREEZE_PROTECTION_ENABLED,
        progressiveTranscription = this[PROGRESSIVE_TRANSCRIPTION] ?: PreferencesManager.DEFAULT_PROGRESSIVE_TRANSCRIPTION,
        earlyPreviewEnabled = this[EARLY_PREVIEW_ENABLED] ?: PreferencesManager.DEFAULT_EARLY_PREVIEW,
        // TASK-684 follow-up (2026-09-30 review round): this key was MISSING
        // from toCached, the actual bug behind the cold-start opt-out reading
        // the default (the cache is warm in production, AppModule primes it;
        // it just carried no value for this key until a save touched it).
        interruptedRunNotifications = this[INTERRUPTED_RUN_NOTIFICATIONS] ?: PreferencesManager.DEFAULT_INTERRUPTED_RUN_NOTIFICATIONS,
        defaultPrompt = this[DEFAULT_PROMPT] ?: PreferencesManager.DEFAULT_PROMPT_VALUE,
        punctuationMode = this[PUNCTUATION_MODE] ?: PreferencesManager.DEFAULT_PUNCTUATION_MODE,
        punctuationPrompt = this[PUNCTUATION_PROMPT] ?: "",
        keywordBiasing = this[KEYWORD_BIASING] ?: "",
        biasingScore = this[BIASING_SCORE] ?: PreferencesManager.DEFAULT_BIASING_SCORE,
        summaryPrompt = this[SUMMARY_PROMPT] ?: "",
        summaryPreset = this[SUMMARY_PRESET] ?: PreferencesManager.DEFAULT_SUMMARY_PRESET,
        summarizeEnabled = this[SUMMARIZE_ENABLED] ?: PreferencesManager.DEFAULT_SUMMARIZE_ENABLED,
        threadCount = this[THREAD_COUNT] ?: PreferencesManager.DEFAULT_THREAD_COUNT,
        inferenceProvider = this[INFERENCE_PROVIDER] ?: PreferencesManager.DEFAULT_INFERENCE_PROVIDER,
        transcriptionLanguage = this[TRANSCRIPTION_LANGUAGE] ?: PreferencesManager.DEFAULT_TRANSCRIPTION_LANGUAGE,
        modelFilterLanguage = this[MODEL_FILTER_LANGUAGE],
        swipeActionMode = this[SWIPE_ACTION_MODE] ?: PreferencesManager.DEFAULT_SWIPE_ACTION_MODE,
        vadAdvisoryDismissed = this[VAD_ADVISORY_DISMISSED] ?: false,
        onboardingCompleted = this[ONBOARDING_COMPLETED] ?: false,
        groupLogsByConversation = this[GROUP_LOGS_BY_CONVERSATION] ?: PreferencesManager.DEFAULT_GROUP_LOGS_BY_CONVERSATION,
        showTechnicalDetails = this[SHOW_TECHNICAL_DETAILS] ?: PreferencesManager.DEFAULT_SHOW_TECHNICAL_DETAILS,
        advancedSharingEnabled = this[ADVANCED_SHARING_ENABLED] ?: PreferencesManager.DEFAULT_ADVANCED_SHARING_ENABLED,
        showRetranscribeButton = this[SHOW_RETRANSCRIBE_BUTTON] ?: PreferencesManager.DEFAULT_SHOW_RETRANSCRIBE_BUTTON,
        advancedShareFormats = this[ADVANCED_SHARE_FORMATS] ?: PreferencesManager.DEFAULT_ADVANCED_SHARE_FORMATS,
        wordTapSeek = this[WORD_TAP_SEEK] ?: PreferencesManager.DEFAULT_WORD_TAP_SEEK,
        memoryProtection = this[MEMORY_PROTECTION] ?: PreferencesManager.DEFAULT_MEMORY_PROTECTION,
        externalAutomationEnabled = this[EXTERNAL_AUTOMATION_ENABLED] ?: PreferencesManager.DEFAULT_EXTERNAL_AUTOMATION_ENABLED,
        openTranscribeEnabled = this[OPEN_TRANSCRIBE_ENABLED] ?: PreferencesManager.DEFAULT_OPEN_TRANSCRIBE_ENABLED,
        voiceNoteIdentityEnabled = this[VOICE_NOTE_IDENTITY_ENABLED] ?: PreferencesManager.DEFAULT_VOICE_NOTE_IDENTITY_ENABLED,
        voiceNoteDetectionEnabled = this[VOICE_NOTE_DETECTION_ENABLED] ?: PreferencesManager.DEFAULT_VOICE_NOTE_DETECTION_ENABLED,
        voiceNoteTreeUri = this[VOICE_NOTE_TREE_URI],
        voiceNoteBusinessDetectionEnabled = this[VOICE_NOTE_BUSINESS_DETECTION_ENABLED] ?: PreferencesManager.DEFAULT_VOICE_NOTE_DETECTION_ENABLED,
        voiceNoteBusinessTreeUri = this[VOICE_NOTE_BUSINESS_TREE_URI],
        voiceNoteWatchedDetectionEnabled = this[VOICE_NOTE_WATCHED_DETECTION_ENABLED] ?: PreferencesManager.DEFAULT_VOICE_NOTE_DETECTION_ENABLED,
        voiceNoteWatchedTreeUri = this[VOICE_NOTE_WATCHED_TREE_URI],
        voiceNoteWatermark = this[VOICE_NOTE_WATERMARK],
        voiceNoteBusinessWatermark = this[VOICE_NOTE_BUSINESS_WATERMARK],
        voiceNoteWatchedWatermark = this[VOICE_NOTE_WATCHED_WATERMARK],
        remoteOmnivoiceEnabled = this[REMOTE_OMNIVOICE_ENABLED] ?: PreferencesManager.DEFAULT_REMOTE_OMNIVOICE_ENABLED,
        remoteOmnivoiceEndpoint = this[REMOTE_OMNIVOICE_ENDPOINT] ?: PreferencesManager.DEFAULT_REMOTE_OMNIVOICE_ENDPOINT,
        remoteOmnivoiceApiKey = this[REMOTE_OMNIVOICE_API_KEY] ?: PreferencesManager.DEFAULT_REMOTE_OMNIVOICE_API_KEY,
        remoteOmnivoiceModel = this[REMOTE_OMNIVOICE_MODEL]
            ?: PreferencesManager.DEFAULT_REMOTE_OMNIVOICE_MODEL,
        compactResultActions = this[COMPACT_RESULT_ACTIONS] ?: PreferencesManager.DEFAULT_COMPACT_RESULT_ACTIONS,
        languageChipEnabled = this[LANGUAGE_CHIP_ENABLED] ?: PreferencesManager.DEFAULT_LANGUAGE_CHIP_ENABLED,
        externalModelsJson = this[EXTERNAL_MODELS_JSON],
        wipedRepairDeadSources = this[WIPED_REPAIR_DEAD_SOURCES] ?: emptySet(),
        scheduledFoldersJson = this[SCHEDULED_FOLDERS_JSON],
        whatsNewLastShownVersion =
            this[WHATS_NEW_LAST_SHOWN_VERSION] ?: PreferencesManager.DEFAULT_WHATS_NEW_LAST_SHOWN_VERSION
    )

    fun initialize() {
        // TASK-899 (ANR audit MEDIUM-3, accept-and-document): this
        // runBlocking runs at DI-graph build on the main thread and pays
        // the process's first DataStore file read there. Deliberate: the
        // cache it primes is what every later blocking first() depends on
        // staying warm (the TaskerRequestReceiver comment documents that
        // coupling). The cost is one small file today; the growth axes are
        // externalModelsJson and scheduledFoldersJson. If either grows
        // large, revisit by priming on the application scope with a
        // runBlocking fallback only for the cold-start synchronous
        // consumers; until then the ms-scale read is not worth the churn.
        runBlocking {
            cache.set(dataStore.data.first().toCached())
        }
    }

    override val modelPath: Flow<String?> = prefFlowNullable(MODEL_PATH,
            cached = { cache.get().modelPath })

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
        // TASK-1009: the two-key legacy fallback blocks prefFlow, not the
        // guard (same shape as sherpaModelPath).
        .distinctUntilChanged()

    override val subtitleChoiceTimeoutMinutes: Flow<Int> = prefFlow(SUBTITLE_CHOICE_TIMEOUT, default = PreferencesManager.DEFAULT_SUBTITLE_CHOICE_TIMEOUT_MINUTES,
            cached = { cache.get().subtitleChoiceTimeout })

    override suspend fun saveKeepAliveTimeout(minutes: Int) {
        dataStore.edit { preferences ->
            preferences[KEEP_ALIVE_TIMEOUT] = minutes
            preferences.remove(KEEP_ALIVE_TIMEOUT_LEGACY)
        }
        cache.updateAndGet { it.copy(keepAliveTimeout = minutes) }
    }

    override suspend fun saveSubtitleChoiceTimeoutMinutes(minutes: Int) {
        dataStore.edit { preferences ->
            preferences[SUBTITLE_CHOICE_TIMEOUT] = minutes
        }
        cache.updateAndGet { it.copy(subtitleChoiceTimeout = minutes) }
    }

    override val whatsNewLastShownVersion: Flow<Int> = prefFlow(WHATS_NEW_LAST_SHOWN_VERSION, default = PreferencesManager.DEFAULT_WHATS_NEW_LAST_SHOWN_VERSION,
            cached = { cache.get().whatsNewLastShownVersion })

    override suspend fun saveWhatsNewLastShownVersion(versionCode: Int) {
        dataStore.edit { preferences ->
            preferences[WHATS_NEW_LAST_SHOWN_VERSION] = versionCode
        }
        cache.updateAndGet { it.copy(whatsNewLastShownVersion = versionCode) }
    }

    override suspend fun getLegacyLanguagePreference(): String {
        return dataStore.data.map { preferences ->
            preferences[LANGUAGE_PREFERENCE] ?: PreferencesManager.DEFAULT_LANGUAGE
        }.first()
    }

    override val themePreference: Flow<String> = prefFlow(THEME_PREFERENCE, default = PreferencesManager.DEFAULT_THEME,
            cached = { cache.get().themePreference })

    override val textScalePreference: Flow<String> = prefFlow(TEXT_SCALE, default = PreferencesManager.DEFAULT_TEXT_SCALE,
            cached = { cache.get().textScale })

    override suspend fun saveTextScale(value: String) {
        dataStore.edit { preferences ->
            preferences[TEXT_SCALE] = value
        }
        cache.updateAndGet { it.copy(textScale = value) }
    }

    override val refinementEnabled: Flow<Boolean> = prefFlowUnseeded(REFINEMENT_ENABLED, default = false)

    override suspend fun saveRefinementEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[REFINEMENT_ENABLED] = enabled
        }
    }

    // TASK-588.1: default collapsed.
    override val settingsTranscriptionAdvancedExpanded: Flow<Boolean> = prefFlowUnseeded(SETTINGS_TRANSCRIPTION_ADVANCED_EXPANDED, default = false)

    override suspend fun saveSettingsTranscriptionAdvancedExpanded(expanded: Boolean) {
        dataStore.edit { preferences ->
            preferences[SETTINGS_TRANSCRIPTION_ADVANCED_EXPANDED] = expanded
        }
    }

    override val speakerLabelsEnabled: Flow<Boolean> = prefFlowUnseeded(SPEAKER_LABELS_ENABLED, default = false)

    override suspend fun saveSpeakerLabelsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SPEAKER_LABELS_ENABLED] = enabled
        }
    }

    override val speakerIdEnabled: Flow<Boolean> = prefFlowUnseeded(SPEAKER_ID_ENABLED, default = PreferencesManager.DEFAULT_SPEAKER_ID_ENABLED)

    override suspend fun saveSpeakerIdEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SPEAKER_ID_ENABLED] = enabled
        }
    }

    override suspend fun saveThemePreference(theme: String) {
        dataStore.edit { preferences ->
            preferences[THEME_PREFERENCE] = theme
        }
        cache.updateAndGet { it.copy(themePreference = theme) }
    }

    override val themeMode: Flow<String> = prefFlow(THEME_MODE, default = PreferencesManager.DEFAULT_THEME_MODE,
            cached = { cache.get().themeMode })

    override suspend fun saveThemeMode(mode: String) {
        dataStore.edit { preferences ->
            preferences[THEME_MODE] = mode
        }
        cache.updateAndGet { it.copy(themeMode = mode) }
    }

    override val transcriptionBackend: Flow<String> = prefFlow(TRANSCRIPTION_BACKEND, default = PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND,
            cached = { cache.get().transcriptionBackend })

    override val refinementModelBackendId: Flow<String> = prefFlow(REFINEMENT_MODEL_BACKEND_ID, default = "",
            cached = { cache.get().refinementModelBackendId })

    override suspend fun saveRefinementModelBackendId(backendId: String) {
        dataStore.edit { preferences ->
            preferences[REFINEMENT_MODEL_BACKEND_ID] = backendId
        }
        cache.updateAndGet { it.copy(refinementModelBackendId = backendId) }
    }

    override suspend fun saveTranscriptionBackend(backendId: String) {
        dataStore.edit { preferences ->
            preferences[TRANSCRIPTION_BACKEND] = backendId
        }
        cache.updateAndGet { it.copy(transcriptionBackend = backendId) }
    }

    // TASK-963: the wiped-repair pending snapshot. No cache entry: the only
    // reader is the repairer's restore, which reads it at tap time, and the
    // distinctUntilChanged concern does not apply (no UI collector).
    override val wipedRepairCapturedActive: Flow<String> =
        dataStore.data.map { it[WIPED_REPAIR_CAPTURED_ACTIVE] ?: "" }
            .distinctUntilChanged()

    override suspend fun saveWipedRepairCapturedActive(backendId: String) {
        dataStore.edit { preferences ->
            preferences[WIPED_REPAIR_CAPTURED_ACTIVE] = backendId
        }
    }

    override val wipedRepairDeadSources: Flow<Set<String>> =
        dataStore.data.map { preferences ->
            preferences[WIPED_REPAIR_DEAD_SOURCES] ?: emptySet()
        }
            // The warm cache first (same discipline as externalModelsJson:
            // the external-models state chain combines this flow with the
            // records, and must not wait a cold DataStore read to show the
            // imported cards), then the same-value skip: DataStore re-emits
            // the whole map on every unrelated write and downstream would
            // re-derive on each.
            .onStart { emit(cache.get().wipedRepairDeadSources) }
            .distinctUntilChanged()

    override suspend fun saveWipedRepairDeadSources(ids: Set<String>) {
        dataStore.edit { preferences ->
            if (ids.isEmpty()) preferences.remove(WIPED_REPAIR_DEAD_SOURCES)
            else preferences[WIPED_REPAIR_DEAD_SOURCES] = ids
        }
        // Cache refresh, the sibling saves' rule: the flow's onStart warm
        // emit makes every in-process first() read the CACHE, so a save
        // without this would leave the repairer's next scan and Remove
        // tap reading a stale dead-set (the review round's finding).
        cache.updateAndGet { it.copy(wipedRepairDeadSources = ids) }
    }

    override val wipedRepairCapturedPin: Flow<String> =
        dataStore.data.map { it[WIPED_REPAIR_CAPTURED_PIN] ?: "" }
            .distinctUntilChanged()

    override suspend fun saveWipedRepairCapturedPin(backendId: String) {
        dataStore.edit { preferences ->
            preferences[WIPED_REPAIR_CAPTURED_PIN] = backendId
        }
    }

    override fun sherpaModelPath(entryId: String): Flow<String?> {
        // TASK-1009: the two-key legacy fallback blocks prefFlow, not the
        // guard. This flow feeds the app's hottest change-reactive chains
        // (ActiveModelRepository's flatMapLatest, installedBackendsFlow's
        // combine over every built-in backend), so unrelated preference
        // writes must not re-emit it (the review's pre_existing pick).
        val legacyKey = LEGACY_MODEL_PATH_KEYS[entryId]
        return dataStore.data.map { prefs ->
            prefs[sherpaModelPathKey(entryId)] ?: legacyKey?.let { prefs[it] }
        }.onStart { emit(cache.get().sherpaModelPaths[entryId]) }
            .distinctUntilChanged()
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

    override val customTransducerModelPath: Flow<String?> = prefFlowNullable(CUSTOM_TRANSDUCER_MODEL_PATH,
            cached = { cache.get().customTransducerModelPath })

    override val customTransducerModelType: Flow<String> = prefFlow(CUSTOM_TRANSDUCER_MODEL_TYPE, default = PreferencesManager.DEFAULT_CUSTOM_TRANSDUCER_MODEL_TYPE,
            cached = { cache.get().customTransducerModelType })

    override val externalMigrationDone: Flow<Boolean> = prefFlow(EXTERNAL_MIGRATION_DONE, default = false,
            cached = { cache.get().externalMigrationDone })

    override val pendingBackendLoad: Flow<String?> = prefFlowNullable(PENDING_BACKEND_LOAD,
            cached = { cache.get().pendingBackendLoad })

    override val externalCatalogUrl: Flow<String> = prefFlow(EXTERNAL_CATALOG_URL, default = PreferencesManager.DEFAULT_EXTERNAL_CATALOG_URL,
            cached = { cache.get().externalCatalogUrl })

    override suspend fun saveExternalCatalogUrl(url: String) {
        dataStore.edit { preferences ->
            preferences[EXTERNAL_CATALOG_URL] = url
        }
        cache.updateAndGet { it.copy(externalCatalogUrl = url) }
    }

    override suspend fun clearExternalCatalogUrl() {
        dataStore.edit { preferences -> preferences.remove(EXTERNAL_CATALOG_URL) }
        cache.updateAndGet { it.copy(externalCatalogUrl = PreferencesManager.DEFAULT_EXTERNAL_CATALOG_URL) }
    }

    override suspend fun savePendingBackendLoad(backendId: String?) {
        dataStore.edit { preferences ->
            if (backendId == null) preferences.remove(PENDING_BACKEND_LOAD)
            else preferences[PENDING_BACKEND_LOAD] = backendId
        }
        cache.updateAndGet { it.copy(pendingBackendLoad = backendId) }
    }

    override suspend fun saveExternalMigrationDone(done: Boolean) {
        dataStore.edit { preferences ->
            preferences[EXTERNAL_MIGRATION_DONE] = done
        }
        cache.updateAndGet { it.copy(externalMigrationDone = done) }
    }

    override val autoCopyEnabled: Flow<Boolean> = prefFlow(AUTO_COPY_ENABLED, default = PreferencesManager.DEFAULT_AUTO_COPY_ENABLED,
            cached = { cache.get().autoCopyEnabled })

    override val signatureEnabled: Flow<Boolean> = prefFlow(SIGNATURE_ENABLED, default = PreferencesManager.DEFAULT_SIGNATURE_ENABLED,
            cached = { cache.get().signatureEnabled })
    override val signatureText: Flow<String> = prefFlow(SIGNATURE_TEXT, default = PreferencesManager.DEFAULT_SIGNATURE_TEXT,
            cached = { cache.get().signatureText })
    override val signaturePosition: Flow<String> = prefFlow(SIGNATURE_POSITION, default = PreferencesManager.DEFAULT_SIGNATURE_POSITION,
            cached = { cache.get().signaturePosition })

    override suspend fun saveAutoCopyEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUTO_COPY_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(autoCopyEnabled = enabled) }
    }

    override suspend fun saveSignatureEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[SIGNATURE_ENABLED] = enabled }
        cache.updateAndGet { it.copy(signatureEnabled = enabled) }
    }

    override suspend fun saveSignatureText(text: String) {
        val trimmed = text.trim()
        dataStore.edit { preferences -> preferences[SIGNATURE_TEXT] = trimmed }
        cache.updateAndGet { it.copy(signatureText = trimmed) }
    }

    override suspend fun saveSignaturePosition(position: String) {
        require(position in PreferencesManager.SIGNATURE_POSITIONS) { "unknown signature position: $position" }
        dataStore.edit { preferences -> preferences[SIGNATURE_POSITION] = position }
        cache.updateAndGet { it.copy(signaturePosition = position) }
    }

    override val outputFolderUri: Flow<String?> = prefFlowNullable(OUTPUT_FOLDER_URI,
            cached = { cache.get().outputFolderUri })

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

    override val transcriptExportFormat: Flow<String> = prefFlow(TRANSCRIPT_EXPORT_FORMAT, default = PreferencesManager.DEFAULT_TRANSCRIPT_EXPORT_FORMAT,
            cached = { cache.get().transcriptExportFormat })

    override suspend fun saveTranscriptExportFormat(format: String) {
        dataStore.edit { preferences ->
            preferences[TRANSCRIPT_EXPORT_FORMAT] = format
        }
        cache.updateAndGet { it.copy(transcriptExportFormat = format) }
    }

    override val vadEnabled: Flow<Boolean> = prefFlow(VAD_ENABLED, default = PreferencesManager.DEFAULT_VAD_ENABLED,
            cached = { cache.get().vadEnabled })

    override val freezeProtectionEnabled: Flow<Boolean> = prefFlow(FREEZE_PROTECTION_ENABLED, default = PreferencesManager.DEFAULT_FREEZE_PROTECTION_ENABLED,
            cached = { cache.get().freezeProtectionEnabled })

    override suspend fun saveFreezeProtectionEnabled(enabled: Boolean) {
        dataStore.edit { it[FREEZE_PROTECTION_ENABLED] = enabled }
        cache.updateAndGet { c -> c.copy(freezeProtectionEnabled = enabled) }
    }

    override suspend fun saveVadEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VAD_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(vadEnabled = enabled) }
    }

    override val vadAdvisoryDismissed: Flow<Boolean> = prefFlow(VAD_ADVISORY_DISMISSED, default = false,
            cached = { cache.get().vadAdvisoryDismissed })

    override suspend fun saveVadAdvisoryDismissed(dismissed: Boolean) {
        dataStore.edit { preferences ->
            preferences[VAD_ADVISORY_DISMISSED] = dismissed
        }
        cache.updateAndGet { it.copy(vadAdvisoryDismissed = dismissed) }
    }

    override val onboardingCompleted: Flow<Boolean> = prefFlow(ONBOARDING_COMPLETED, default = false,
            cached = { cache.get().onboardingCompleted })

    override suspend fun saveOnboardingCompleted(completed: Boolean) {
        dataStore.edit { preferences ->
            preferences[ONBOARDING_COMPLETED] = completed
        }
        cache.updateAndGet { it.copy(onboardingCompleted = completed) }
    }

    override val progressiveTranscription: Flow<Boolean> = prefFlow(PROGRESSIVE_TRANSCRIPTION, default = PreferencesManager.DEFAULT_PROGRESSIVE_TRANSCRIPTION,
            cached = { cache.get().progressiveTranscription })

    override suspend fun saveProgressiveTranscription(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PROGRESSIVE_TRANSCRIPTION] = enabled
        }
        cache.updateAndGet { it.copy(progressiveTranscription = enabled) }
    }

    override val earlyPreviewEnabled: Flow<Boolean> = prefFlow(EARLY_PREVIEW_ENABLED, default = PreferencesManager.DEFAULT_EARLY_PREVIEW,
            cached = { cache.get().earlyPreviewEnabled })

    override suspend fun saveEarlyPreviewEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[EARLY_PREVIEW_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(earlyPreviewEnabled = enabled) }
    }

    override val interruptedRunNotifications: Flow<Boolean> = prefFlow(INTERRUPTED_RUN_NOTIFICATIONS, default = PreferencesManager.DEFAULT_INTERRUPTED_RUN_NOTIFICATIONS,
            cached = { cache.get().interruptedRunNotifications })

    override suspend fun saveInterruptedRunNotifications(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[INTERRUPTED_RUN_NOTIFICATIONS] = enabled
        }
        cache.updateAndGet { it.copy(interruptedRunNotifications = enabled) }
    }

    override suspend fun getInterruptedRunNotifications(): Boolean {
        // Reads the store directly on purpose: the flow's onStart emits the
        // process cache, and a key missing from toCached would mask the
        // persisted value behind the default (the original TASK-684 bug;
        // the mapping is restored, this read stays independent of it).
        return dataStore.data.map { preferences ->
            preferences[INTERRUPTED_RUN_NOTIFICATIONS] ?: PreferencesManager.DEFAULT_INTERRUPTED_RUN_NOTIFICATIONS
        }.first()
    }

    override val defaultPrompt: Flow<String> = prefFlow(DEFAULT_PROMPT, default = PreferencesManager.DEFAULT_PROMPT_VALUE,
            cached = { cache.get().defaultPrompt })

    override suspend fun saveDefaultPrompt(prompt: String) {
        val truncated = prompt.take(PreferencesManager.PROMPT_CAP)
        dataStore.edit { preferences ->
            preferences[DEFAULT_PROMPT] = truncated
        }
        cache.updateAndGet { it.copy(defaultPrompt = truncated) }
    }

    override val punctuationMode: Flow<String> = prefFlow(PUNCTUATION_MODE, default = PreferencesManager.DEFAULT_PUNCTUATION_MODE,
            cached = { cache.get().punctuationMode })

    override suspend fun savePunctuationMode(mode: String) {
        dataStore.edit { preferences ->
            preferences[PUNCTUATION_MODE] = mode
        }
        cache.updateAndGet { it.copy(punctuationMode = mode) }
    }

    override val punctuationPrompt: Flow<String> = prefFlow(PUNCTUATION_PROMPT, default = "",
            cached = { cache.get().punctuationPrompt })

    override val summaryPrompt: Flow<String> = prefFlow(SUMMARY_PROMPT, default = "",
            cached = { cache.get().summaryPrompt })

    override val summaryPreset: Flow<String> = prefFlow(SUMMARY_PRESET, default = PreferencesManager.DEFAULT_SUMMARY_PRESET,
            cached = { cache.get().summaryPreset })

    override suspend fun savePunctuationPrompt(prompt: String) {
        // Same 500-char cap as the default transcription prompt: one
        // instruction paragraph, not an essay (TASK-276).
        val truncated = prompt.take(PreferencesManager.PROMPT_CAP)
        dataStore.edit { preferences ->
            preferences[PUNCTUATION_PROMPT] = truncated
        }
        cache.updateAndGet { it.copy(punctuationPrompt = truncated) }
    }

    override val keywordBiasing: Flow<String> = prefFlow(KEYWORD_BIASING, default = "",
            cached = { cache.get().keywordBiasing })
        // TASK-1008's incident flow: its collector once unloaded the backend on
        // every unrelated write before the guard (now structural in prefFlow).

    override val biasingScore: Flow<Float> =
        dataStore.data.map { it[BIASING_SCORE] ?: PreferencesManager.DEFAULT_BIASING_SCORE }
            .onStart { emit(cache.get().biasingScore) }
            // Same TASK-1008 class, closed for the feature pair: a future
            // change-reactive collector on the knob must not fire on
            // unrelated writes either.
            .distinctUntilChanged()

    override suspend fun saveBiasingScore(value: Float) {
        val clamped = value.coerceIn(PreferencesManager.MIN_BIASING_SCORE, PreferencesManager.MAX_BIASING_SCORE)
        dataStore.edit { preferences ->
            preferences[BIASING_SCORE] = clamped
        }
        cache.updateAndGet { it.copy(biasingScore = clamped) }
    }

    override suspend fun saveKeywordBiasing(terms: String) {
        val truncated = terms.take(PreferencesManager.PROMPT_CAP)
        dataStore.edit { preferences ->
            preferences[KEYWORD_BIASING] = truncated
        }
        cache.updateAndGet { it.copy(keywordBiasing = truncated) }
    }

    override val summarizeEnabled: Flow<Boolean> = prefFlow(SUMMARIZE_ENABLED, default = PreferencesManager.DEFAULT_SUMMARIZE_ENABLED,
            cached = { cache.get().summarizeEnabled })

    override suspend fun saveSummaryPrompt(prompt: String) {
        val truncated = prompt.take(PreferencesManager.PROMPT_CAP)
        dataStore.edit { preferences ->
            preferences[SUMMARY_PROMPT] = truncated
        }
        cache.updateAndGet { it.copy(summaryPrompt = truncated) }
    }

    override suspend fun saveSummaryPreset(preset: String) {
        val normalized = SummaryPreset.fromKey(preset)
        dataStore.edit { preferences ->
            preferences[SUMMARY_PRESET] = normalized.key
        }
        cache.updateAndGet { it.copy(summaryPreset = normalized.key) }
    }

    override suspend fun reconcileLegacySummaryPrompt() {
        // One-shot, idempotent: only a NEVER-SET preset key plus a saved
        // prompt counts as legacy (a deliberate non-custom choice stores
        // the key and permanently opts out). See the interface KDoc.
        val legacy = dataStore.data.first().let { it[SUMMARY_PRESET] == null && !(it[SUMMARY_PROMPT] ?: "").isBlank() }
        if (!legacy) return
        dataStore.edit { it[SUMMARY_PRESET] = SummaryPreset.CUSTOM.key }
        cache.updateAndGet { it.copy(summaryPreset = SummaryPreset.CUSTOM.key) }
    }

    override suspend fun saveSummarizeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SUMMARIZE_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(summarizeEnabled = enabled) }
    }

    override val threadCount: Flow<Int> = prefFlow(THREAD_COUNT, default = PreferencesManager.DEFAULT_THREAD_COUNT,
            cached = { cache.get().threadCount })

    override suspend fun saveThreadCount(threads: Int) {
        dataStore.edit { preferences ->
            preferences[THREAD_COUNT] = threads
        }
        cache.updateAndGet { it.copy(threadCount = threads) }
    }

    override val inferenceProvider: Flow<String> = prefFlow(INFERENCE_PROVIDER, default = PreferencesManager.DEFAULT_INFERENCE_PROVIDER,
            cached = { cache.get().inferenceProvider })

    override suspend fun saveInferenceProvider(provider: String) {
        dataStore.edit { preferences ->
            preferences[INFERENCE_PROVIDER] = provider
        }
        cache.updateAndGet { it.copy(inferenceProvider = provider) }
    }

    override val transcriptionLanguage: Flow<String> = prefFlow(TRANSCRIPTION_LANGUAGE, default = PreferencesManager.DEFAULT_TRANSCRIPTION_LANGUAGE,
            cached = { cache.get().transcriptionLanguage })

    override suspend fun saveTranscriptionLanguage(language: String) {
        dataStore.edit { preferences ->
            preferences[TRANSCRIPTION_LANGUAGE] = language
        }
        cache.updateAndGet { it.copy(transcriptionLanguage = language) }
    }

    // TASK-685: key absence (null) is load-bearing, so the clear writes ""
    // instead of removing the key: it distinguishes "user cleared the
    // suggestion" from "never touched", which is the seed's guard.
    override val modelFilterLanguage: Flow<String?> = prefFlowNullable(MODEL_FILTER_LANGUAGE,
            cached = { cache.get().modelFilterLanguage })

    override suspend fun saveModelFilterLanguage(code: String) {
        dataStore.edit { preferences ->
            preferences[MODEL_FILTER_LANGUAGE] = code
        }
        cache.updateAndGet { it.copy(modelFilterLanguage = code) }
    }

    override val swipeActionMode: Flow<String> = prefFlow(SWIPE_ACTION_MODE, default = PreferencesManager.DEFAULT_SWIPE_ACTION_MODE,
            cached = { cache.get().swipeActionMode })

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
        dataStore.data.map { prefs -> prefs[BENCHMARK_RESULTS] }
            // TASK-1009: guard the RAW blob (a projection, not a cache-seeded
            // preference), so unrelated writes skip the decode below too.
            .distinctUntilChanged()
            .map { all ->
                runCatching {
                    org.json.JSONObject(all ?: "{}").optJSONObject("results")?.optString(modelId)
                }.getOrNull()
            }

    override fun getAllBenchmarkResults(): Flow<Map<String, String>> =
        dataStore.data.map { prefs -> prefs[BENCHMARK_RESULTS] }
            // TASK-1009: guard the RAW blob (a projection, not a cache-seeded
            // preference), so unrelated writes skip the decode below too.
            .distinctUntilChanged()
            .map { all ->
                runCatching {
                    val results = org.json.JSONObject(all ?: "{}").optJSONObject("results") ?: org.json.JSONObject()
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

    override val measuredModelMemory: Flow<Map<String, com.antivocale.app.transcription.MeasuredModelMemory.Record>> =
        dataStore.data.map { prefs -> prefs[MEASURED_MODEL_MEMORY] }
            // TASK-1009: guard the RAW blob (a decoded projection, not a
            // cache-seeded preference), so unrelated writes skip the decode
            // below too.
            .distinctUntilChanged()
            .map { blob ->
                com.antivocale.app.transcription.MeasuredModelMemory.decode(blob)
            }

    override suspend fun mergeMeasuredModelMemorySample(
        key: String,
        loadDeltaBytes: Long,
        modelSizeBytes: Long,
    ) {
        dataStore.edit { preferences ->
            val m = com.antivocale.app.transcription.MeasuredModelMemory
            val records = m.decode(preferences[MEASURED_MODEL_MEMORY]).toMutableMap()
            val merged = m.merge(records[key], loadDeltaBytes, modelSizeBytes, System.currentTimeMillis())
            if (merged != null) {
                records[key] = merged
                preferences[MEASURED_MODEL_MEMORY] = m.encode(records)
            }
        }
    }

    override suspend fun pruneMeasuredModelMemory(validKeys: Set<String>) {
        dataStore.edit { preferences ->
            val m = com.antivocale.app.transcription.MeasuredModelMemory
            val records = m.decode(preferences[MEASURED_MODEL_MEMORY])
            if (records.keys.any { it !in validKeys }) {
                preferences[MEASURED_MODEL_MEMORY] =
                    m.encode(records.filterKeys { it in validKeys })
            }
        }
    }

    override val partialTranscriptionText: Flow<String?> = prefFlowUnseededNullable(PARTIAL_TRANSCRIPTION_TEXT)

    override val partialTranscriptionTimestamp: Flow<Long?> = prefFlowUnseededNullable(PARTIAL_TRANSCRIPTION_TIMESTAMP)

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

    override val groupLogsByConversation: Flow<Boolean> = prefFlow(GROUP_LOGS_BY_CONVERSATION, default = PreferencesManager.DEFAULT_GROUP_LOGS_BY_CONVERSATION,
            cached = { cache.get().groupLogsByConversation })

    override suspend fun saveGroupLogsByConversation(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[GROUP_LOGS_BY_CONVERSATION] = enabled
        }
        cache.updateAndGet { it.copy(groupLogsByConversation = enabled) }
    }

    override val showTechnicalDetails: Flow<Boolean> = prefFlow(SHOW_TECHNICAL_DETAILS, default = PreferencesManager.DEFAULT_SHOW_TECHNICAL_DETAILS,
            cached = { cache.get().showTechnicalDetails })

    override suspend fun saveShowTechnicalDetails(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SHOW_TECHNICAL_DETAILS] = enabled
        }
        cache.updateAndGet { it.copy(showTechnicalDetails = enabled) }
    }

    override val advancedSharingEnabled: Flow<Boolean> = prefFlow(ADVANCED_SHARING_ENABLED, default = PreferencesManager.DEFAULT_ADVANCED_SHARING_ENABLED,
            cached = { cache.get().advancedSharingEnabled })

    override suspend fun saveAdvancedSharingEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[ADVANCED_SHARING_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(advancedSharingEnabled = enabled) }
    }

    override val showRetranscribeButton: Flow<Boolean> = prefFlow(SHOW_RETRANSCRIBE_BUTTON, default = PreferencesManager.DEFAULT_SHOW_RETRANSCRIBE_BUTTON,
            cached = { cache.get().showRetranscribeButton })

    override suspend fun saveShowRetranscribeButton(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SHOW_RETRANSCRIBE_BUTTON] = enabled
        }
        cache.updateAndGet { it.copy(showRetranscribeButton = enabled) }
    }

    override val wordTapSeek: Flow<Boolean> = prefFlow(WORD_TAP_SEEK, default = PreferencesManager.DEFAULT_WORD_TAP_SEEK,
            cached = { cache.get().wordTapSeek })

    override suspend fun saveWordTapSeek(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[WORD_TAP_SEEK] = enabled
        }
        cache.updateAndGet { it.copy(wordTapSeek = enabled) }
    }

    override val advancedShareFormats: Flow<Boolean> = prefFlow(ADVANCED_SHARE_FORMATS, default = PreferencesManager.DEFAULT_ADVANCED_SHARE_FORMATS,
            cached = { cache.get().advancedShareFormats })

    override suspend fun saveAdvancedShareFormats(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[ADVANCED_SHARE_FORMATS] = enabled
        }
        cache.updateAndGet { it.copy(advancedShareFormats = enabled) }
    }

    override val memoryProtection: Flow<Boolean> = prefFlow(MEMORY_PROTECTION, default = PreferencesManager.DEFAULT_MEMORY_PROTECTION,
            cached = { cache.get().memoryProtection })

    override val externalAutomationEnabled: Flow<Boolean> = prefFlow(EXTERNAL_AUTOMATION_ENABLED, default = PreferencesManager.DEFAULT_EXTERNAL_AUTOMATION_ENABLED,
            cached = { cache.get().externalAutomationEnabled })

    override val openTranscribeEnabled: Flow<Boolean> = prefFlow(OPEN_TRANSCRIBE_ENABLED, default = PreferencesManager.DEFAULT_OPEN_TRANSCRIBE_ENABLED,
            cached = { cache.get().openTranscribeEnabled })

    override val voiceNoteIdentityEnabled: Flow<Boolean> = prefFlow(VOICE_NOTE_IDENTITY_ENABLED, default = PreferencesManager.DEFAULT_VOICE_NOTE_IDENTITY_ENABLED,
            cached = { cache.get().voiceNoteIdentityEnabled })

    override val voiceNoteDetectionEnabled: Flow<Boolean> = prefFlow(VOICE_NOTE_DETECTION_ENABLED, default = PreferencesManager.DEFAULT_VOICE_NOTE_DETECTION_ENABLED,
            cached = { cache.get().voiceNoteDetectionEnabled })
    override val voiceNoteTreeUri: Flow<String?> = prefFlowNullable(VOICE_NOTE_TREE_URI,
            cached = { cache.get().voiceNoteTreeUri })
    override val voiceNoteWatermark: Flow<String?> = prefFlowNullable(VOICE_NOTE_WATERMARK,
            cached = { cache.get().voiceNoteWatermark })

    override suspend fun saveVoiceNoteTreeUri(uri: String?) {
        dataStore.edit { preferences ->
            if (uri == null) preferences.remove(VOICE_NOTE_TREE_URI)
            else preferences[VOICE_NOTE_TREE_URI] = uri
        }
        cache.updateAndGet { it.copy(voiceNoteTreeUri = uri) }
    }

    override val voiceNoteBusinessDetectionEnabled: Flow<Boolean> = prefFlow(VOICE_NOTE_BUSINESS_DETECTION_ENABLED, default = PreferencesManager.DEFAULT_VOICE_NOTE_DETECTION_ENABLED,
            cached = { cache.get().voiceNoteBusinessDetectionEnabled })
    override val voiceNoteBusinessTreeUri: Flow<String?> = prefFlowNullable(VOICE_NOTE_BUSINESS_TREE_URI,
            cached = { cache.get().voiceNoteBusinessTreeUri })

    override suspend fun saveVoiceNoteBusinessDetectionEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VOICE_NOTE_BUSINESS_DETECTION_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(voiceNoteBusinessDetectionEnabled = enabled) }
    }

    override suspend fun saveVoiceNoteBusinessTreeUri(uri: String?) {
        dataStore.edit { preferences ->
            if (uri == null) preferences.remove(VOICE_NOTE_BUSINESS_TREE_URI)
            else preferences[VOICE_NOTE_BUSINESS_TREE_URI] = uri
        }
        cache.updateAndGet { it.copy(voiceNoteBusinessTreeUri = uri) }
    }

    // TASK-966: the watched-folder source's pair, the twin of the blocks
    // above over its own keys (a user-picked folder instead of a fixed
    // WhatsApp tree; no watermark twin, that source has no keyed catch-up).
    override val voiceNoteWatchedDetectionEnabled: Flow<Boolean> = prefFlow(VOICE_NOTE_WATCHED_DETECTION_ENABLED, default = PreferencesManager.DEFAULT_VOICE_NOTE_DETECTION_ENABLED,
            cached = { cache.get().voiceNoteWatchedDetectionEnabled })
    override val voiceNoteWatchedTreeUri: Flow<String?> = prefFlowNullable(VOICE_NOTE_WATCHED_TREE_URI,
            cached = { cache.get().voiceNoteWatchedTreeUri })

    override suspend fun saveVoiceNoteWatchedDetectionEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VOICE_NOTE_WATCHED_DETECTION_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(voiceNoteWatchedDetectionEnabled = enabled) }
    }

    override suspend fun saveVoiceNoteWatchedTreeUri(uri: String?) {
        dataStore.edit { preferences ->
            if (uri == null) preferences.remove(VOICE_NOTE_WATCHED_TREE_URI)
            else preferences[VOICE_NOTE_WATCHED_TREE_URI] = uri
        }
        cache.updateAndGet { it.copy(voiceNoteWatchedTreeUri = uri) }
    }

    override suspend fun bumpVoiceNoteWatermark(candidate: String) {
        dataStore.edit { preferences ->
            val current = preferences[VOICE_NOTE_WATERMARK]
            if (current == null || candidate > current) {
                preferences[VOICE_NOTE_WATERMARK] = candidate
            }
        }
        cache.updateAndGet { cached ->
            if (cached.voiceNoteWatermark == null || candidate > cached.voiceNoteWatermark) {
                cached.copy(voiceNoteWatermark = candidate)
            } else {
                cached
            }
        }
    }

    override suspend fun resetVoiceNoteWatermark() {
        dataStore.edit { it.remove(VOICE_NOTE_WATERMARK) }
        cache.updateAndGet { it.copy(voiceNoteWatermark = null) }
    }

    // TASK-1047: the watched-folder watermark trio, the twin of the
    // WhatsApp block over the mtime key (the unkeyed sweep's own anchor).
    override val voiceNoteWatchedWatermark: Flow<String?> = prefFlowNullable(VOICE_NOTE_WATCHED_WATERMARK,
            cached = { cache.get().voiceNoteWatchedWatermark })

    override suspend fun bumpVoiceNoteWatchedWatermark(candidate: String) {
        dataStore.edit { preferences ->
            val current = preferences[VOICE_NOTE_WATCHED_WATERMARK]
            if (current == null || candidate > current) {
                preferences[VOICE_NOTE_WATCHED_WATERMARK] = candidate
            }
        }
        cache.updateAndGet { cached ->
            if (cached.voiceNoteWatchedWatermark == null || candidate > cached.voiceNoteWatchedWatermark) {
                cached.copy(voiceNoteWatchedWatermark = candidate)
            } else {
                cached
            }
        }
    }

    override suspend fun resetVoiceNoteWatchedWatermark() {
        dataStore.edit { it.remove(VOICE_NOTE_WATCHED_WATERMARK) }
        cache.updateAndGet { it.copy(voiceNoteWatchedWatermark = null) }
    }

    // TASK-891: the Business watermark trio, the twin of the WhatsApp
    // block above over its own key (independent counter tree).
    override val voiceNoteBusinessWatermark: Flow<String?> = prefFlowNullable(VOICE_NOTE_BUSINESS_WATERMARK,
            cached = { cache.get().voiceNoteBusinessWatermark })

    override suspend fun bumpVoiceNoteBusinessWatermark(candidate: String) {
        dataStore.edit { preferences ->
            val current = preferences[VOICE_NOTE_BUSINESS_WATERMARK]
            if (current == null || candidate > current) {
                preferences[VOICE_NOTE_BUSINESS_WATERMARK] = candidate
            }
        }
        cache.updateAndGet { cached ->
            if (cached.voiceNoteBusinessWatermark == null || candidate > cached.voiceNoteBusinessWatermark) {
                cached.copy(voiceNoteBusinessWatermark = candidate)
            } else {
                cached
            }
        }
    }

    override suspend fun resetVoiceNoteBusinessWatermark() {
        dataStore.edit { it.remove(VOICE_NOTE_BUSINESS_WATERMARK) }
        cache.updateAndGet { it.copy(voiceNoteBusinessWatermark = null) }
    }

    // TASK-681: the LAN-offload gate and its config triple. Re-emits on
    // unrelated writes are fine here, like the siblings above: the
    // collectors only compare values.
    override val remoteOmnivoiceEnabled: Flow<Boolean> = prefFlow(REMOTE_OMNIVOICE_ENABLED, default = PreferencesManager.DEFAULT_REMOTE_OMNIVOICE_ENABLED,
            cached = { cache.get().remoteOmnivoiceEnabled })

    override val remoteOmnivoiceEndpoint: Flow<String> = prefFlow(REMOTE_OMNIVOICE_ENDPOINT, default = PreferencesManager.DEFAULT_REMOTE_OMNIVOICE_ENDPOINT,
            cached = { cache.get().remoteOmnivoiceEndpoint })

    override val remoteOmnivoiceApiKey: Flow<String> = prefFlow(REMOTE_OMNIVOICE_API_KEY, default = PreferencesManager.DEFAULT_REMOTE_OMNIVOICE_API_KEY,
            cached = { cache.get().remoteOmnivoiceApiKey })

    override val remoteOmnivoiceModel: Flow<String> = prefFlow(REMOTE_OMNIVOICE_MODEL, default = PreferencesManager.DEFAULT_REMOTE_OMNIVOICE_MODEL,
            cached = { cache.get().remoteOmnivoiceModel })

    /**
     * TASK-681: the gate write carries the coupled invariant: disabling the
     * service removes it from the selectable space, so a persisted selection
     * pointing at it resets to the default IN THE SAME transaction (every
     * writer gets the reset, and there is no torn disabled-but-selected
     * state between two writes).
     */
    override suspend fun saveRemoteOmnivoiceEnabled(enabled: Boolean) {
        var resetBackend = false
        dataStore.edit { preferences ->
            preferences[REMOTE_OMNIVOICE_ENABLED] = enabled
            if (!enabled && preferences[TRANSCRIPTION_BACKEND] == com.antivocale.app.transcription.RemoteOmnivoiceBackend.BACKEND_ID) {
                preferences[TRANSCRIPTION_BACKEND] = PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND
                resetBackend = true
            }
        }
        cache.updateAndGet {
            it.copy(
                remoteOmnivoiceEnabled = enabled,
                transcriptionBackend = if (resetBackend) PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND else it.transcriptionBackend,
            )
        }
    }

    /** TASK-681: one user action, one transaction; see the interface KDoc. */
    override suspend fun saveRemoteOmnivoiceConfig(endpoint: String, apiKey: String, model: String) {
        val endpointValue = endpoint.trim()
        val keyValue = apiKey.trim()
        val modelValue = model.trim()
        dataStore.edit { preferences ->
            preferences[REMOTE_OMNIVOICE_ENDPOINT] = endpointValue
            preferences[REMOTE_OMNIVOICE_API_KEY] = keyValue
            preferences[REMOTE_OMNIVOICE_MODEL] = modelValue
        }
        cache.updateAndGet {
            it.copy(
                remoteOmnivoiceEndpoint = endpointValue,
                remoteOmnivoiceApiKey = keyValue,
                remoteOmnivoiceModel = modelValue,
            )
        }
    }

    override suspend fun saveRemoteOmnivoiceEndpoint(url: String) {
        val trimmed = url.trim()
        dataStore.edit { preferences ->
            preferences[REMOTE_OMNIVOICE_ENDPOINT] = trimmed
        }
        cache.updateAndGet { it.copy(remoteOmnivoiceEndpoint = trimmed) }
    }

    override suspend fun saveRemoteOmnivoiceApiKey(key: String) {
        val trimmed = key.trim()
        dataStore.edit { preferences ->
            preferences[REMOTE_OMNIVOICE_API_KEY] = trimmed
        }
        cache.updateAndGet { it.copy(remoteOmnivoiceApiKey = trimmed) }
    }

    override suspend fun saveRemoteOmnivoiceModel(model: String) {
        val trimmed = model.trim()
        dataStore.edit { preferences ->
            preferences[REMOTE_OMNIVOICE_MODEL] = trimmed
        }
        cache.updateAndGet { it.copy(remoteOmnivoiceModel = trimmed) }
    }

    override val compactResultActions: Flow<Boolean> = prefFlow(COMPACT_RESULT_ACTIONS, default = PreferencesManager.DEFAULT_COMPACT_RESULT_ACTIONS,
            cached = { cache.get().compactResultActions })
    override val languageChipEnabled: Flow<Boolean> = prefFlow(LANGUAGE_CHIP_ENABLED, default = PreferencesManager.DEFAULT_LANGUAGE_CHIP_ENABLED,
            cached = { cache.get().languageChipEnabled })

    override suspend fun saveCompactResultActions(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[COMPACT_RESULT_ACTIONS] = enabled
        }
        cache.updateAndGet { it.copy(compactResultActions = enabled) }
    }

    override suspend fun saveLanguageChipEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[LANGUAGE_CHIP_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(languageChipEnabled = enabled) }
    }

    override suspend fun saveMemoryProtection(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[MEMORY_PROTECTION] = enabled
        }
        cache.updateAndGet { it.copy(memoryProtection = enabled) }
    }

    override suspend fun saveExternalAutomationEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[EXTERNAL_AUTOMATION_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(externalAutomationEnabled = enabled) }
    }

    override suspend fun saveOpenTranscribeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[OPEN_TRANSCRIBE_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(openTranscribeEnabled = enabled) }
    }

    override suspend fun saveVoiceNoteIdentityEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VOICE_NOTE_IDENTITY_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(voiceNoteIdentityEnabled = enabled) }
    }

    override suspend fun saveVoiceNoteDetectionEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VOICE_NOTE_DETECTION_ENABLED] = enabled
        }
        cache.updateAndGet { it.copy(voiceNoteDetectionEnabled = enabled) }
    }

    override val externalModelsJson: Flow<String?> = prefFlowNullable(EXTERNAL_MODELS_JSON,
            cached = { cache.get().externalModelsJson })

    override val scheduledFoldersJson: Flow<String?> = prefFlowNullable(SCHEDULED_FOLDERS_JSON,
            cached = { cache.get().scheduledFoldersJson })

    override suspend fun saveExternalModelsJson(json: String) {
        dataStore.edit { preferences ->
            preferences[EXTERNAL_MODELS_JSON] = json
        }
        cache.updateAndGet { it.copy(externalModelsJson = json) }
    }

    override suspend fun saveScheduledFoldersJson(json: String?) {
        dataStore.edit { preferences ->
            if (json == null) preferences.remove(SCHEDULED_FOLDERS_JSON)
            else preferences[SCHEDULED_FOLDERS_JSON] = json
        }
        cache.updateAndGet { it.copy(scheduledFoldersJson = json) }
    }

    // TASK-675: the demotion set is read-modify-written INSIDE the edit
    // transaction (the mergeMeasuredModelMemorySample rule), so a demotion
    // landing while the user re-selects the model cannot lose the clear (or
    // vice versa).
    override val demotedBackends: Flow<Set<String>> = prefFlowUnseeded(DEMOTED_BACKENDS, default = emptySet())

    override suspend fun markBackendDemoted(backendId: String) {
        dataStore.edit { preferences ->
            preferences[DEMOTED_BACKENDS] = (preferences[DEMOTED_BACKENDS] ?: emptySet()) + backendId
        }
    }

    override suspend fun clearDemotedBackend(backendId: String) {
        dataStore.edit { preferences ->
            val next = (preferences[DEMOTED_BACKENDS] ?: emptySet()) - backendId
            // An empty set removes the key: a fresh install and a fully
            // cleared state read identically.
            if (next.isEmpty()) preferences.remove(DEMOTED_BACKENDS) else preferences[DEMOTED_BACKENDS] = next
        }
    }
    // ---- TASK-843: the portable-settings backup (GH #158) ----

    /**
     * The whitelisted settings as raw values for the backup archive
     * (BackupArchiveCodec.build derives each entry's type tag from the
     * runtime class). The whitelist gates by NAME only
     * (BackupSettingsKeys); the type comes from the live value, so the
     * archive round-trips the same typed keys the app writes.
     */
    override suspend fun exportPortableSettings(): Map<String, Any> {
        return dataStore.data.first().asMap()
            .filterKeys { BackupSettingsKeys.isPortable(it.name) }
            .mapKeys { it.key.name }
    }

    /**
     * Restores a portable-settings payload in ONE DataStore transaction.
     * Only whitelisted names are applied (a hand-edited archive cannot
     * inject device-bound keys); unlisted names in the payload are dropped.
     * The edit is followed by a full cache re-prime (the same shape as the
     * init prime): every save method keeps the in-memory cache in step with
     * the store because the flows seed from it (.onStart emits the cache),
     * and a bulk import touching many keys at once would otherwise leave
     * one-shot .first() consumers (benchmark config, catalog fetch,
     * retranscribe) reading the PRE-import values until process restart.
     */
    override suspend fun importPortableSettings(values: Map<String, Any>) {
        dataStore.edit { prefs ->
            values.entries
                .filter { (name, _) -> BackupSettingsKeys.isPortable(name) }
                .forEach { (name, typed) ->
                    when (val v = typed) {
                        is Boolean -> prefs[booleanPreferencesKey(name)] = v
                        is Int -> prefs[intPreferencesKey(name)] = v
                        // A hand-edited archive may carry a Long/Double for a
                        // string key (JSON has no int/long distinction); drop
                        // the malformed entry instead of failing the whole
                        // one-transaction import (review: the CCE killed
                        // every setting over one bad value).
                        is String -> prefs[stringPreferencesKey(name)] = v
                    }
                }
            // TASK-993 review: an archive from a pre-preset version carries
            // the prompt with NO preset key, and that absence is ARCHIVE
            // knowledge: the imported instruction was the active one on the
            // source device. It must win over a LOCAL deliberate non-custom
            // preset (the plain reconcile only fires when the local key is
            // unset, which a prior choice defeats), so the promotion rides
            // the same edit with the archive in hand.
            val archivedPrompt = (values["summary_prompt"] as? String).orEmpty()
            val archiveHasPresetKey = values.containsKey("summary_preset")
            if (!archiveHasPresetKey && archivedPrompt.isNotBlank()) {
                prefs[SUMMARY_PRESET] = SummaryPreset.CUSTOM.key
            }
        }
        cache.set(dataStore.data.first().toCached())
    }
}