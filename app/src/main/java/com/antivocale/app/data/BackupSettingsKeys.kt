package com.antivocale.app.data

/**
 * TASK-843: the portable-settings whitelist for the backup archive (GH #158).
 * Safe by default: a DataStore key NOT listed here never leaves the device,
 * so every future preference key forces a conscious classification (the
 * ReservedNotificationIdContractTest pattern, applied to privacy instead of
 * notification ids).
 *
 * Classes deliberately EXCLUDED (evaluator classification, 2026-10-14):
 * - device-bound: output_folder_uri, voice_note_tree_uri and
 *   voice_note_business_tree_uri (persisted SAF grants are per-install and
 *   arrive dead), voice_note_watermark and
 *   voice_note_business_watermark (each names THIS install's note
 *   history for its client; restored elsewhere it would
 *   wrongly suppress that install's catch-up), scheduled_folders_json (carries
 *   a device-derived file-snapshot dedup memory), external_models_json
 *   (references this device's sandbox dirs; models travel via the bundle),
 *   all *_model_path keys, measured_model_memory and demoted_backends
 *   (device-derived), inference_provider (NNAPI is device-dependent,
 *   issue #26 crash class), partial_transcription_* and pending_backend_load
 *   (transient), external_migration_done and custom_transducer_* (migration
 *   markers), remote_omnivoice_* (endpoint + BEARER API KEY: the #1 leak
 *   risk).
 *
 * Name-gated only: the value TYPES ride the runtime classes (the codec tags
 * them b/i/s at export and reconstructs them at import), so a mistyped
 * classification cannot corrupt a restore. /simplify 2026-10-15 verified
 * every entry against the real key declarations.
 */
object BackupSettingsKeys {

    private val BOOLEAN = setOf(
        "advanced_share_formats",
        "advanced_sharing_enabled",
        "benchmark_results",
        "external_catalog_url",
        "auto_copy_enabled",
        "compact_result_actions",
        "early_preview_enabled",
        "external_automation_enabled",
        "group_logs_by_conversation",
        "interrupted_run_notifications",
        "language_chip_enabled",
        "memory_protection",
        "onboarding_completed",
        "refinement_model_backend_id",
        "remote_omnivoice_enabled",
        "open_transcribe_enabled",
        "progressive_transcription",
        "refinement_enabled",
        "settings_transcription_advanced_expanded",
        "show_retranscribe_button",
        "show_technical_details",
        "signature_enabled",
        "speaker_id_enabled",
        "speaker_labels_enabled",
        "summarize_enabled",
        "freeze_protection_enabled",
        "vad_advisory_dismissed",
        "vad_enabled",
        "voice_note_business_detection_enabled",
        "voice_note_detection_enabled",
        "voice_note_identity_enabled",
    )

    private val STRING = setOf(
        "default_prompt",
        "language_preference",
        "model_filter_language",
        "punctuation_mode",
        "keyword_biasing",
        "punctuation_prompt",
        "signature_position",
        "signature_text",
        "summary_prompt",
        "swipe_action_mode",
        "theme_mode",
        "theme_preference",
        "transcript_export_format",
        "transcription_backend",
        "transcription_language",
    )

    private val INT = setOf(
        "keep_alive_timeout_v2",
        "subtitle_choice_timeout",
        "text_scale",
        "thread_count",
    )

    val PORTABLE: Set<String> = BOOLEAN + STRING + INT

    fun isPortable(name: String): Boolean = PORTABLE.contains(name)
}
