package com.antivocale.app

import com.antivocale.app.testing.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-845 (maintainer decision 2026-10-14): the backup extraction rules
 * are a privacy contract, and a manifest cleanup or a res-template edit can
 * silently drop them, putting the preferences file (the remote-offload
 * bearer key lives in there) back on the OS cloud transport. This test IS
 * the fence (the ManifestFgsMirrorTest precedent): the manifest attributes
 * and both rule files are pinned as source text.
 */
class BackupExtractionRulesTest {

    private val manifest = RepoFiles.readText("src/main/AndroidManifest.xml")
    private val extractionRules = RepoFiles.readText("src/main/res/xml/data_extraction_rules.xml")
    private val legacyRules = RepoFiles.readText("src/main/res/xml/backup_rules.xml")

    @Test
    fun `the manifest wires both rule files and keeps backup on`() {
        assertTrue(manifest.contains("android:allowBackup=\"true\""))
        assertTrue(
            "without the attribute the API 31+ cloud channel runs unrestricted",
            manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(
            "without the attribute the legacy (API 30-) channel runs unrestricted",
            manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
    }

    @Test
    fun `the cloud channel excludes every credential, heavy, and biometric path`() {
        val cloudBlock = extractionRules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        // Review 2026-10-15: the exclusion set is load-bearing in three
        // classes: credentials (the offload key's DataStore, the HuggingFace
        // token prefs), the quota (models/ alone would blow the ~25MB
        // per-app backup limit and abort the WHOLE backup, transcripts
        // included), and biometrics (speaker voiceprints + enrollment
        // audio). A missing line here falsifies the privacy policy's
        // backup paragraph.
        val required = listOf(
            "<exclude domain=\"file\" path=\"datastore/\" />",
            "<exclude domain=\"file\" path=\"models/\" />",
            "<exclude domain=\"file\" path=\"shared_audio/\" />",
            "<exclude domain=\"file\" path=\"speaker_identities/\" />",
            "<exclude domain=\"sharedpref\" path=\"huggingface_encrypted_prefs\" />",
        )
        val missing = required.filterNot { cloudBlock.contains(it) }
        assertTrue("cloud exclusion set incomplete: $missing", missing.isEmpty())
    }

    @Test
    fun `the device-transfer channel keeps everything`() {
        val transferBlock = extractionRules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertFalse(
            "a direct cable migration must lose nothing",
            transferBlock.contains("exclude"))
    }

    @Test
    fun `the legacy channel matches the cloud exclusions`() {
        for (line in extractionRules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>").lines()) {
            if (line.contains("<exclude")) {
                assertTrue(
                    "legacy rules must mirror the cloud exclusion: $line",
                    legacyRules.contains(line.trim()))
            }
        }
    }
}
