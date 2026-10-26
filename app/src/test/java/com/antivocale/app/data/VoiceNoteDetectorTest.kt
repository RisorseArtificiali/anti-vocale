package com.antivocale.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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

    @Test
    fun `temporary and in-flight names never match`() {
        // The MOVED_TO arm fires for the rename INTO the final name; the
        // temp side of a temp+rename download (or a partial copy) must not
        // emit on its own. Same naming for both WhatsApp clients.
        assertFalse(VoiceNoteFilter.isWhatsAppVoiceNoteName("PTT-20261020-WA0001.opus.tmp"))
        assertFalse(VoiceNoteFilter.isWhatsAppVoiceNoteName(".PTT-20261020-WA0001.opus.part"))
        assertFalse(VoiceNoteFilter.isWhatsAppVoiceNoteName(".trashed-1730000000-PTT-20261020-WA0001.opus"))
        assertFalse(VoiceNoteFilter.isCounterFolderName("WhatsApp Business Voice Notes"))
    }

    @Test
    fun `each source's detector watches its own root`() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val prefs = io.mockk.mockk<PreferencesManager>(relaxed = true)
        io.mockk.every { prefs.voiceNoteWatermark } returns kotlinx.coroutines.flow.flowOf(null)
        val wa = VoiceNoteDetector(scope, prefs)
        val w4b = VoiceNoteDetector(scope, prefs, VoiceNoteSource.WHATSAPP_BUSINESS)
        assertTrue(wa.root.path.endsWith("/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"))
        assertTrue(w4b.root.path.endsWith(
            "/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes"))
    }

    // ---- TASK-897: canonical note names ----

    @Test
    fun `an undecorated name canonicalizes to itself byte-identically`() {
        // The id-contract precondition: every existing row's task id stays
        // unchanged, so the canonical form of a real WhatsApp name (counter
        // always 5-digit zero-padded) must be the name itself.
        assertEquals("PTT-20261025-WA00001.opus",
            VoiceNoteFilter.canonicalNoteName("PTT-20261025-WA00001.opus"))
        assertEquals("PTT-20261022-WA00019.opus",
            VoiceNoteFilter.canonicalNoteName("PTT-20261022-WA00019.opus"))
    }

    @Test
    fun `a decorated twin canonicalizes to its original's name`() {
        assertEquals("PTT-20261025-WA00001.opus",
            VoiceNoteFilter.canonicalNoteName("PTT-20261025-WA00001 (1).opus"))
        assertEquals("PTT-20261025-WA00012.opus",
            VoiceNoteFilter.canonicalNoteName("PTT-20261025-WA12-copy.opus"))
        // Case-insensitive parse, canonical uppercase output.
        assertEquals("PTT-20261025-WA00001.opus",
            VoiceNoteFilter.canonicalNoteName("ptt-20261025-wa00001.opus"))
    }

    @Test
    fun `non-parseable and wide-counter names pass through or keep their digits`() {
        // Non-PTT names are not ours to canonicalize.
        assertEquals("voice_memo.m4a", VoiceNoteFilter.canonicalNoteName("voice_memo.m4a"))
        assertEquals("PTT-hello.opus", VoiceNoteFilter.canonicalNoteName("PTT-hello.opus"))
        // A counter beyond the 5-digit convention keeps its full width (no
        // truncation, no re-padding to 5).
        assertEquals("PTT-20261025-WA123456.opus",
            VoiceNoteFilter.canonicalNoteName("PTT-20261025-WA123456.opus"))
    }
}
