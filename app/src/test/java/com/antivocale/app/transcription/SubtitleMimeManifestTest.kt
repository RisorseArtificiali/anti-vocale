package com.antivocale.app.transcription

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-677 simplify F2: the manifest's SEND intent-filter MIME set for
 * subtitle files is pinned to the code set (SHARE_SUBTITLE_MIME_TO_EXTENSION).
 * The manifest cannot reference constants, so this test IS the sync: a MIME
 * added on one side and not the other fails here, not in a user's share sheet.
 */
class SubtitleMimeManifestTest {

    @Test
    fun `manifest subtitle SEND filters equal the code MIME set`() {
        val manifest = sequenceOf(File("app/src/main/AndroidManifest.xml"), File("src/main/AndroidManifest.xml"))
            .first { it.exists() }
            .readText()
        val manifestMimes = Regex("android:mimeType=\"([^\"]+)\"")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .filter { it in SubtitleExtractor.SHARE_SUBTITLE_MIME_TO_EXTENSION || it.startsWith("text/srt") || it.startsWith("application/x-s") || it.contains("vtt") }
            .toSortedSet()
        val codeMimes = SubtitleExtractor.SHARE_SUBTITLE_MIME_TO_EXTENSION.keys.toSortedSet()
        assertEquals(codeMimes, manifestMimes)
    }

    @Test
    fun `every code MIME maps to a subtitle extension`() {
        SubtitleExtractor.SHARE_SUBTITLE_MIME_TO_EXTENSION.values.forEach {
            assertTrue(it == "srt" || it == "vtt")
        }
    }
}
