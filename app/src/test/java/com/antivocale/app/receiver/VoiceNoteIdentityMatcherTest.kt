package com.antivocale.app.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-736: the share-to-identity selection rules (candidates arrive newest
 * first, TTL-pruned by the cache; see the matcher's KDoc for the evidence).
 */
class VoiceNoteIdentityMatcherTest {

    private fun note(sender: String, durationSeconds: Long?, ts: Long) =
        VoiceNoteIdentityExtractor.VoiceNote(
            packageName = "com.whatsapp", sender = sender,
            durationSeconds = durationSeconds, postedAtMs = ts)

    @Test
    fun `a duration within tolerance wins even over a newer note`() {
        val candidates = listOf( // newest first
            note("Marta", 52L, ts = 2_000),
            note("Chiara", 4L, ts = 1_000),
        )
        // The shared audio is 4.2s: Chiara's note agrees, Marta's does not.
        assertEquals("Chiara", VoiceNoteIdentityMatcher.select(candidates, audioDurationSeconds = 4)?.sender)
    }

    @Test
    fun `no agreeing duration and none durationless means no label`() {
        val candidates = listOf(note("Marta", 52L, ts = 2_000))
        assertNull(VoiceNoteIdentityMatcher.select(candidates, audioDurationSeconds = 4))
    }

    @Test
    fun `without a probed duration there is no label`() {
        // Review: recency-only wrote the newest CACHED name on whatever was
        // shared; a wrong name beats silence in no direction.
        val candidates = listOf(
            note("Marta", 52L, ts = 2_000),
            note("Chiara", 4L, ts = 1_000),
        )
        assertNull(VoiceNoteIdentityMatcher.select(candidates, audioDurationSeconds = null))
    }

    @Test
    fun `an empty cache never labels`() {
        assertNull(VoiceNoteIdentityMatcher.select(emptyList(), audioDurationSeconds = 4))
    }

    @Test
    fun `the tolerance covers the marker's whole-second rounding`() {
        val candidates = listOf(note("Chiara", 4L, ts = 1_000))
        assertEquals("Chiara", VoiceNoteIdentityMatcher.select(candidates, audioDurationSeconds = 5)?.sender)
        assertNull(VoiceNoteIdentityMatcher.select(candidates, audioDurationSeconds = 6))
    }
}
