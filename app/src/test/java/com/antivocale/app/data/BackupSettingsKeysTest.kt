package com.antivocale.app.data

import com.antivocale.app.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-843: the safe-by-default fence for the backup whitelist. Every
 * DataStore key declared in PreferencesManagerImpl must be CLASSIFIED:
 * either portable (rides the backup archive) or in the documented excluded
 * set (device-bound, secret, or transient). A newly declared key missing
 * from both lists fails here, so an unclassified key can never silently
 * ride (or silently vanish from) a backup.
 */
class BackupSettingsKeysTest {

    /** Keys deliberately excluded from the backup, with the reason class. */
    private val excluded = mapOf(
        "output_folder_uri" to "device-bound (per-install SAF grant)",
        "voice_note_tree_uri" to "device-bound (per-install SAF grant)",
        "voice_note_business_tree_uri" to "device-bound (per-install SAF grant)",
        // TASK-778: names a THIS device's WhatsApp note history; restored on
        // another install it would wrongly suppress that install's catch-up.
        "voice_note_watermark" to "device-bound (per-install WhatsApp note history)",
        "voice_note_business_watermark" to "device-bound (per-install Business note history)",
        "scheduled_folders_json" to "device-bound (paths + snapshot dedup memory)",
        "external_models_json" to "device-bound (sandbox dirs; models travel via the bundle)",
        "model_path" to "device-bound (sandbox dir)",
        "gigaam_model_path" to "device-bound (sandbox dir)",
        "nemotron_model_path" to "device-bound (sandbox dir)",
        "parakeet_model_path" to "device-bound (sandbox dir)",
        "qwen3_asr_model_path" to "device-bound (sandbox dir)",
        "whisper_model_path" to "device-bound (sandbox dir)",
        "custom_transducer_model_path" to "device-bound (sandbox dir)",
        "custom_transducer_model_type" to "device-bound (sandbox dir)",
        "measured_model_memory" to "device-derived",
        "demoted_backends" to "device-derived",
        "inference_provider" to "device-derived (NNAPI crash class)",
        "external_migration_done" to "migration marker",
        "partial_transcription_text" to "transient",
        "partial_transcription_timestamp" to "transient",
        "pending_backend_load" to "transient",
        "keep_alive_timeout" to "legacy (v2 superseded it)",
        "\$SHERPA_MODEL_PATH_PREFIX\$entryId" to "template pattern (per-entry derived key)",
        "remote_omnivoice_endpoint" to "secret-adjacent config",
        "remote_omnivoice_api_key" to "SECRET (bearer key)",
        "remote_omnivoice_model" to "secret-adjacent config",
    )

    @Test
    fun `every declared DataStore key is classified portable or excluded`() {
        val source = RepoFiles.readText(
            "src/main/java/com/antivocale/app/data/PreferencesManagerImpl.kt")
        val declared = Regex("""PreferencesKey\("([^"]+)"\)""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()
        val classified = BackupSettingsKeys.PORTABLE + excluded.keys
        val unclassified = declared - classified
        assertTrue(
            "unclassified DataStore keys (add to the portable whitelist or to " +
                "the excluded set with a reason): $unclassified",
            unclassified.isEmpty())
    }

    @Test
    fun `the portable whitelist contains no excluded key`() {
        val overlap = BackupSettingsKeys.PORTABLE intersect excluded.keys
        assertTrue(
            "a key cannot be both portable and excluded: $overlap",
            overlap.isEmpty())
    }
}
