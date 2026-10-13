package com.antivocale.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-271 (the 2026-10-13 rework): the detector's pure decision layer for
 * the FileObserver route. The MediaStore-era tests (row queries, payload
 * classification by mime column, watermark logic) went with the premise
 * they pinned; the observer route's decisions are the NAME rule, the
 * counter-folder rule, and the event dedup.
 */
class VoiceNoteDetectorTest {

    @Test
    fun `whatsapp voice note names match the PTT pattern`() {
        assertTrue(VoiceNoteFilter.isWhatsAppVoiceNoteName(
            "PTT-20261013-WA0009.opus"))
        assertTrue(VoiceNoteFilter.isWhatsAppVoiceNoteName(
            "ptt-20190101-wa0000.OPUS"))
    }

    @Test
    fun `foreign names never match`() {
        // A podcast episode named after the folder is not a note; a photo
        // is not a note; a bare folder event is not a note.
        assertFalse(VoiceNoteFilter.isWhatsAppVoiceNoteName(
            "WhatsApp Voice Notes - demo.opus"))
        assertFalse(VoiceNoteFilter.isWhatsAppVoiceNoteName(
            "IMG-20261013-WA0001.jpg"))
        assertFalse(VoiceNoteFilter.isWhatsAppVoiceNoteName(
            "202642"))
        assertFalse(VoiceNoteFilter.isWhatsAppVoiceNoteName(
            "PTT-notes.txt"))
    }

    @Test
    fun `counter folder names are exactly the numeric ones`() {
        assertTrue(VoiceNoteFilter.isCounterFolderName("202642"))
        assertTrue(VoiceNoteFilter.isCounterFolderName("0"))
        assertFalse(VoiceNoteFilter.isCounterFolderName("202642.opus"))
        assertFalse(VoiceNoteFilter.isCounterFolderName("WhatsApp Voice Notes"))
    }

    @Test
    fun `the incremental filter dedups repeated events for one note`() {
        // WhatsApp's write fires CREATE and CLOSE_WRITE for the same file,
        // and post-write touches re-fire: one offer per note.
        val filter = VoiceNoteFilter.IncrementalFilter()
        val entry = VoiceNoteEntry(id = 42L, path = "/x/PTT-1.opus", detectedAtMs = 1L)
        assertTrue("first sighting is new", filter.observe(entry))
        assertFalse("the re-fire is a duplicate", filter.observe(entry))
        assertFalse("and again", filter.observe(entry))
        val other = entry.copy(id = 43L)
        assertTrue("a different note is new", filter.observe(other))
    }
}
