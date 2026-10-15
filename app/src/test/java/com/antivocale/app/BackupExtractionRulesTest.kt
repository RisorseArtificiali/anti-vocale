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
    fun `the cloud channel excludes the datastore directory`() {
        val cloudBlock = extractionRules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        assertTrue(
            "the preferences directory (the offload key) must stay off the cloud backup",
            cloudBlock.contains("<exclude domain=\"file\" path=\"datastore/\" />"))
    }

    @Test
    fun `the device-transfer channel keeps everything`() {
        val transferBlock = extractionRules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertFalse(
            "a direct cable migration must lose nothing",
            transferBlock.contains("exclude"))
    }

    @Test
    fun `the legacy channel matches the cloud exclusion`() {
        assertTrue(
            legacyRules.contains("<exclude domain=\"file\" path=\"datastore/\" />"))
    }
}
