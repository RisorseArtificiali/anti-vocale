package com.antivocale.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-843: the backup archive codec (settings payload only; the history
 * payload joins in TASK-844). Pure JVM round-trip: no DataStore, no
 * Android framework. The whitelist is NOT the codec's concern: the manager
 * exports only portable keys, and the codec preserves whatever it is given
 * (forward-compat with newer archives), dropping only entries whose type
 * tag this build cannot reconstruct.
 */
class BackupArchiveCodecTest {

    @Test
    fun `settings round-trip through the archive`() {
        val values = mapOf(
            "vad_enabled" to true,
            "thread_count" to 4,
            "summary_prompt" to "riassumi",
        )
        val archive = BackupArchiveCodec.build(
            values, appVersion = "1.15.0-SNAPSHOT", exportedAtMs = 1_000L)
        assertEquals(values, BackupArchiveCodec.parse(archive))
    }

    @Test
    fun `a malformed archive is rejected, not guessed`() {
        assertNull(BackupArchiveCodec.parse("this is not a zip".toByteArray()))
    }

    @Test
    fun `an unknown type tag is dropped, not guessed`() {
        // forward-compat: an archive from a NEWER app may carry a type this
        // build cannot reconstruct; the entry is skipped silently.
        val valuesJson =
            """{"vad_enabled": {"t": "b", "v": true}, "future_key": {"t": "weird", "v": 1}}"""
        val bytes = java.io.ByteArrayOutputStream().let { out ->
            val zip = java.util.zip.ZipOutputStream(out)
            zip.putNextEntry(java.util.zip.ZipEntry(BackupArchiveCodec.SETTINGS_ENTRY))
            zip.write(valuesJson.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry(BackupArchiveCodec.MANIFEST_ENTRY))
            zip.write("""{"formatVersion": 1}""".toByteArray())
            zip.closeEntry()
            zip.close()
            out.toByteArray()
        }
        val parsed = BackupArchiveCodec.parse(bytes)!!
        assertEquals(true, parsed["vad_enabled"])
        assertTrue("unknown tag dropped", "future_key" !in parsed)
    }

    @Test
    fun `an archive without a settings entry reads as empty`() {
        val bytes = java.io.ByteArrayOutputStream().let { out ->
            val zip = java.util.zip.ZipOutputStream(out)
            zip.putNextEntry(java.util.zip.ZipEntry(BackupArchiveCodec.MANIFEST_ENTRY))
            zip.write("""{"formatVersion": 1}""".toByteArray())
            zip.closeEntry()
            zip.close()
            out.toByteArray()
        }
        assertEquals(emptyMap<String, Any>(), BackupArchiveCodec.parse(bytes))
    }
}
