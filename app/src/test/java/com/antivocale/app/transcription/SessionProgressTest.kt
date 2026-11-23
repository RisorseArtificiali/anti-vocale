package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-1037 phase 5: the derivation and the rounding of the
 * remaining-time estimate. All fixtures use 16 kHz samples (the session
 * store's units) and real-shaped rates (Parakeet TDT measured about
 * 210 ms of compute per second of audio on the RMX3853; the pause-proof
 * numbers: 5 stints of compute carry no weight here, only the
 * cumulative ratio does).
 */
class SessionProgressTest {

    /** 10 minutes of audio at exactly 0.2x real-time compute (5x faster
     *  than the audio: the Parakeet-class rate). */
    private fun tenMinutesAtFifthCompute() =
        (10L * 60 * TranscriptionOrchestrator.SESSION_SAMPLE_RATE) to (10L * 60 * 200)

    @Test
    fun `halfway through at constant speed leaves half the minutes`() {
        val (totalSamples, totalComputeMs) = tenMinutesAtFifthCompute()
        // Half decoded: 5 min of audio took 1 min of compute; 5 min of
        // audio remain at the same rate => 1 minute left.
        assertEquals(1L, SessionProgress.remainingMinutes(
            prefixEndSamples = totalSamples / 2,
            totalSamples = totalSamples,
            accumulatedComputeMs = totalComputeMs / 2))
    }

    @Test
    fun `rounding is upward, never zero`() {
        // A rate of exactly 1 ms per sample (accumulatedComputeMs equals
        // the prefix numerically): 61_000 samples remain = 61 s of
        // compute, which must report 2 minutes, not 1; 60_000 exactly
        // stays 1.
        assertEquals(2L, SessionProgress.remainingMinutes(
            prefixEndSamples = 100_000L,
            totalSamples = 161_000L,
            accumulatedComputeMs = 100_000L))
        assertEquals(1L, SessionProgress.remainingMinutes(
            prefixEndSamples = 100_000L,
            totalSamples = 160_000L,
            accumulatedComputeMs = 100_000L))
        // A sliver remaining: the fixture's derived rate is about
        // 100 ms per second of audio, so 1 second left is ~100 ms of
        // compute: the honest answer is "1 minute", never 0.
        val (totalSamples, _) = tenMinutesAtFifthCompute()
        assertEquals(1L, SessionProgress.remainingMinutes(
            prefixEndSamples = totalSamples - TranscriptionOrchestrator.SESSION_SAMPLE_RATE,
            totalSamples = totalSamples,
            accumulatedComputeMs = 60_000L))
    }

    @Test
    fun `every dishonest input answers null`() {
        assertNull("no total recorded", SessionProgress.remainingMinutes(1000L, null, 1000L))
        assertNull("degenerate total", SessionProgress.remainingMinutes(1000L, 0L, 1000L))
        assertNull("nothing decoded yet", SessionProgress.remainingMinutes(0L, 1000L, 1000L))
        assertNull("no compute time recorded", SessionProgress.remainingMinutes(1000L, 2000L, 0L))
        assertNull("already complete", SessionProgress.remainingMinutes(2000L, 2000L, 1000L))
        assertNull("prefix beyond the total", SessionProgress.remainingMinutes(3000L, 2000L, 1000L))
    }
}
