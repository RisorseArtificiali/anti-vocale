package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TASK-406: the chunk cap is also a memory budget. Encoder attention cost grows
 * with the square of chunk length, so the effective cap must shrink on devices
 * whose free RAM cannot hold the predicted peak. Calibration numbers in
 * [TranscriptionMemoryPolicy]'s KDoc.
 */
class TranscriptionMemoryPolicyTest {

    private val MiB = 1024L * 1024L

    @Test
    fun `fails open to the catalog cap when memory inputs are unknown`() {
        assertEquals(120, TranscriptionMemoryPolicy.effectiveChunkSeconds(0L, 864 * MiB, 120))
        assertEquals(120, TranscriptionMemoryPolicy.effectiveChunkSeconds(3L * 1024 * MiB, 0L, 120))
        assertEquals(30, TranscriptionMemoryPolicy.effectiveChunkSeconds(0L, 0L, 30))
    }

    @Test
    fun `roomy device keeps the catalog cap`() {
        // 6 GiB free, SmoothQuant 864 MiB: predicted peak at 120s fits with room to spare.
        assertEquals(120, TranscriptionMemoryPolicy.effectiveChunkSeconds(6L * 1024 * MiB, 864 * MiB, 120))
        // The shipped Parakeet cap (60) keeps the same shape at its own value.
        assertEquals(60, TranscriptionMemoryPolicy.effectiveChunkSeconds(6L * 1024 * MiB, 864 * MiB, 60))
        // TASK-408 canary caps at 10s, BELOW the 30s floor: roomy and starved
        // devices alike must clamp to the family cap (the pre-fix code threw
        // IllegalArgumentException from coerceIn(30, 10) on every request, and
        // returned a degenerate 30s on starved devices).
        assertEquals(10, TranscriptionMemoryPolicy.effectiveChunkSeconds(6L * 1024 * MiB, 200 * MiB, 10))
        assertEquals(10, TranscriptionMemoryPolicy.effectiveChunkSeconds(1L * 1024 * MiB, 200 * MiB, 10))
    }

    @Test
    fun `tight device tightens the cap below the catalog value`() {
        // 3300 MiB free, SmoothQuant 864 MiB: budget 3300-1229=2071, baseline 1764,
        // t = sqrt(307/0.030) ~ 101 -> floored to 100.
        assertEquals(100, TranscriptionMemoryPolicy.effectiveChunkSeconds(3300 * MiB, 864 * MiB, 120))
    }

    @Test
    fun `starved device falls back to the minimum chunk`() {
        // Budget below the load baseline: the smallest chunk is the only safe answer.
        assertEquals(
            TranscriptionMemoryPolicy.MIN_CHUNK_SECONDS,
            TranscriptionMemoryPolicy.effectiveChunkSeconds(2L * 1024 * MiB, 864 * MiB, 120))
    }

    @Test
    fun `result never exceeds the catalog cap`() {
        // Absurdly large free RAM must not raise the cap above the shipped default.
        assertEquals(120, TranscriptionMemoryPolicy.effectiveChunkSeconds(64L * 1024 * MiB, 864 * MiB, 120))
    }

    @Test
    fun `predicted peak matches the measured sweep`() {
        // The calibration invariant, kept honest: predicting the desktop measurements
        // (stock int8, model 640 MiB): 120s -> ~1528+432=~1960 MiB, 366s -> ~1528+4019=~5547 MiB
        // against measured 1946 / 5226 MiB. Exposed via peak prediction so the test
        // recomputes from the same constants the policy uses.
        val peak120 = TranscriptionMemoryPolicy.predictedPeakMiB(640, 120)
        val peak366 = TranscriptionMemoryPolicy.predictedPeakMiB(640, 366)
        // within ~10% of the measured VmHWM values
        assertEquals(1946.0, peak120, 195.0)
        assertEquals(5226.0, peak366, 523.0)
    }
}
