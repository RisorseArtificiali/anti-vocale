package com.antivocale.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * GH #185: the audio-derived export file names. The requester's exact
 * shapes ("Hello.ogg" -> "Hello.ogg.txt", "Hello_<DATE>.ogg.txt") are
 * pinned here so the extension placement cannot drift.
 */
class ExportFileNamerTest {

    private val today = LocalDate.of(2026, 11, 6)

    @Test
    fun `audio name keeps its extension and gains txt`() {
        assertEquals(
            "Hello.ogg.txt",
            ExportFileNamer.txtFileName("/storage/Hello.ogg", ExportFileNamer.Scheme.AUDIO_NAME, today))
    }

    @Test
    fun `dated scheme inserts the ISO date inside the audio extension`() {
        assertEquals(
            "Hello_2026-11-06.ogg.txt",
            ExportFileNamer.txtFileName("/storage/Hello.ogg", ExportFileNamer.Scheme.AUDIO_NAME_DATE, today))
    }

    @Test
    fun `only the file name is used, never the directories`() {
        assertEquals(
            "meeting.m4a.txt",
            ExportFileNamer.txtFileName(
                "/data/user/0/com.antivocale.app/files/shared_audio/meeting.m4a",
                ExportFileNamer.Scheme.AUDIO_NAME, today))
    }

    @Test
    fun `extensionless audio names append the date before txt`() {
        assertEquals(
            "recording_2026-11-06.txt",
            ExportFileNamer.txtFileName("/tmp/recording", ExportFileNamer.Scheme.AUDIO_NAME_DATE, today))
        assertEquals(
            "recording.txt",
            ExportFileNamer.txtFileName("/tmp/recording", ExportFileNamer.Scheme.AUDIO_NAME, today))
    }

    @Test
    fun `a leading-dot name has no real extension so it is not split`() {
        assertEquals(
            ".ogg_2026-11-06.txt",
            ExportFileNamer.txtFileName("/tmp/.ogg", ExportFileNamer.Scheme.AUDIO_NAME_DATE, today))
    }

    @Test
    fun `unknown audio yields null so callers keep their current naming`() {
        assertNull(ExportFileNamer.txtFileName(null, ExportFileNamer.Scheme.AUDIO_NAME, today))
        assertNull(ExportFileNamer.txtFileName("", ExportFileNamer.Scheme.AUDIO_NAME_DATE, today))
        assertNull(ExportFileNamer.txtFileName("   ", ExportFileNamer.Scheme.AUDIO_NAME, today))
        assertNull(ExportFileNamer.txtFileName("/storage/.", ExportFileNamer.Scheme.AUDIO_NAME, today))
        assertNull(ExportFileNamer.txtFileName("/storage/..", ExportFileNamer.Scheme.AUDIO_NAME_DATE, today))
    }

    @Test
    fun `a directory-shaped path reduces to its last segment`() {
        assertEquals(
            "note.wav.txt",
            ExportFileNamer.txtFileName("WhatsApp/Media/note.wav", ExportFileNamer.Scheme.AUDIO_NAME, today))
    }

    @Test
    fun `multi-dot names split on the last extension only`() {
        assertEquals(
            "clip.part2_2026-11-06.opus.txt",
            ExportFileNamer.txtFileName("/a/clip.part2.opus", ExportFileNamer.Scheme.AUDIO_NAME_DATE, today))
    }
}
