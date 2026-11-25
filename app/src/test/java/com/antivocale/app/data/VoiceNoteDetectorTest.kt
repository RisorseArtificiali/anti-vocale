package com.antivocale.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * TASK-271 (the 2026-10-13 rework): the detector's pure decision layer for
 * the FileObserver route. The MediaStore-era tests (row queries, payload
 * classification by mime column, watermark logic) went with the premise
 * they pinned; the observer route's decisions are the NAME rule, the
 * counter-folder rule, and the event dedup.
 */
class VoiceNoteDetectorTest {

    /** TASK-906: the detector's SAF-lane context; these tests ride the raw
     *  lane and never query it, so a relaxed mock stands in. */
    private fun appContext(): android.content.Context = io.mockk.mockk(relaxed = true)


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
        // and post-write touches re-fire: one offer per note. The filter
        // is key-agnostic; the arrival math has its own pin below.
        val filter = VoiceNoteFilter.IncrementalFilter()
        assertTrue("first sighting is new", filter.observe(42L))
        assertFalse("the re-fire is a duplicate", filter.observe(42L))
        assertFalse("and again", filter.observe(42L))
        assertTrue("a different note is new", filter.observe(43L))
    }

    /**
     * TASK-1055 (GH #191): the watched source's live dedup. A re-copied
     * filename past the window is a NEW arrival (the reporter's exact
     * case: same name, new copy, must transcribe again); one arrival's
     * double events (CLOSE_WRITE then MOVED_TO, adjacent on the clock)
     * collapse EVEN when they straddle a bucket boundary, which is the
     * property the moving window exists for (the salted task id has no
     * such guarantee and does not need one: the filter guarantees it one
     * emission per arrival).
     */
    @Test
    fun `the watched arrival window collapses one arrival's doubles and passes re-copies`() {
        val arrivals = VoiceNoteFilter.ArrivalWindowFilter()
        val pathHash = "note.m4a".hashCode().toLong()
        assertTrue("the first sighting is an arrival", arrivals.observe(pathHash, 1_000L))
        assertFalse("a double event 999ms later is the same arrival", arrivals.observe(pathHash, 1_999L))
        assertFalse("even one straddling the old bucket boundary", arrivals.observe(pathHash, 2_001L))
        assertTrue("a re-copy past the window is a new arrival", arrivals.observe(pathHash, 60_000L))
        assertFalse("the re-copy's own double event collapses", arrivals.observe(pathHash, 60_999L))
        assertTrue("a different file is always independent",
            arrivals.observe("other.m4a".hashCode().toLong(), 1_050L))
    }

    /** TASK-1055: the task id's arrival salt is deterministic per bucket
     *  (the transcriber contract the 842 guard relies on): same bucket,
     *  same id; a new bucket, a new id. */
    @Test
    fun `the watched arrival salt is deterministic per bucket`() {
        assertEquals(VoiceNoteFilter.watchedArrivalSalt(4_000L),
            VoiceNoteFilter.watchedArrivalSalt(5_999L))
        assertNotEquals(VoiceNoteFilter.watchedArrivalSalt(4_000L),
            VoiceNoteFilter.watchedArrivalSalt(6_000L))
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
        val wa = VoiceNoteDetector(scope, appContext(), prefs)
        val w4b = VoiceNoteDetector(scope, appContext(), prefs, VoiceNoteSource.WHATSAPP_BUSINESS)
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
