package com.antivocale.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-1037 phase 1: the pure arithmetic of the decode-and-discard resume.
 * The device proof (decoding from offset N == a full decode sliced at N,
 * per format) complements these; here the invariants are the numbers.
 */
class StreamResumeTest {

    @Test
    fun `zero skip keeps every buffer whole`() {
        val d = StreamResume.keepAfterSkip(alreadySkipped = 0, decodedCount = 512, skipTarget = 0)
        assertEquals(0, d.keepFrom)
        assertTrue(d.skipComplete)
    }

    @Test
    fun `a buffer fully inside the skip is dropped whole`() {
        val d = StreamResume.keepAfterSkip(alreadySkipped = 0, decodedCount = 1000, skipTarget = 5000)
        assertEquals(1000, d.keepFrom)
        assertFalse(d.skipComplete)
    }

    @Test
    fun `the buffer that exactly completes the skip and the one that straddles it`() {
        // 4000 of 5000 consumed; a 1000-wide buffer completes the skip exactly.
        val exact = StreamResume.keepAfterSkip(alreadySkipped = 4000, decodedCount = 1000, skipTarget = 5000)
        assertEquals(1000, exact.keepFrom)
        assertTrue(exact.skipComplete)
        // 4500 of 5000 consumed; the buffer straddles the target: only its
        // first 500 samples are skip, the tail is real audio.
        val straddle = StreamResume.keepAfterSkip(alreadySkipped = 4500, decodedCount = 1000, skipTarget = 5000)
        assertEquals(500, straddle.keepFrom)
        assertTrue(straddle.skipComplete)
    }

    @Test
    fun `a skip already satisfied keeps from zero`() {
        val d = StreamResume.keepAfterSkip(alreadySkipped = 5000, decodedCount = 1000, skipTarget = 5000)
        assertEquals(0, d.keepFrom)
        assertTrue(d.skipComplete)
    }

    @Test
    fun `an empty decoded buffer contributes nothing and cannot complete a skip`() {
        // Realistic resampler-warmup output: the loop's full-drop path must
        // not treat a zero-width buffer as progress.
        val d = StreamResume.keepAfterSkip(alreadySkipped = 4000, decodedCount = 0, skipTarget = 5000)
        assertEquals(0, d.keepFrom)
        assertFalse(d.skipComplete)
    }

    @Test
    fun `remaining seconds floor at zero past the end and exact at the offset`() {
        assertEquals(0.0, StreamResume.remainingSeconds(300.0, 16000L * 300, 16000), 1e-9)
        assertEquals(0.0, StreamResume.remainingSeconds(300.0, 16000L * 400, 16000), 1e-9)
        assertEquals(100.0, StreamResume.remainingSeconds(300.0, 16000L * 200, 16000), 1e-9)
    }

    @Test
    fun `a resumed header counts only the remaining audio`() {
        // 3h file, 30s cap, resumed at 1h: 2h remaining = 240 chunks.
        assertEquals(240, StreamResume.expectedChunkCountFrom(
            10800.0, 30, 16000L * 3600, 16000))
        // The non-exact-multiple floor survives the resume (TASK-444 shape):
        // 61s remaining under a 30s cap is 3, never 2.
        assertEquals(3, StreamResume.expectedChunkCountFrom(
            3601.0, 30, 16000L * 3540, 16000))
        // Null cap announces one chunk, the full-decode convention.
        assertEquals(1, StreamResume.expectedChunkCountFrom(
            10800.0, null, 16000L * 3600, 16000))
        // An offset at or past the end owes nothing.
        assertEquals(0, StreamResume.expectedChunkCountFrom(
            300.0, 30, 16000L * 300, 16000))
    }

    @Test
    fun `the shared chunk-count estimate keeps its original contract`() {
        assertEquals(1, StreamResume.expectedChunkCount(30.0, 30))
        assertEquals(2, StreamResume.expectedChunkCount(31.0, 30))
        assertEquals(1, StreamResume.expectedChunkCount(0.5, 30))
    }
}
