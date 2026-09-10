package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
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
        // TASK-472 frame: avail is POST-load, so the baseline is decode-side
        // only (900). Budget 3300-1229=2071, t = sqrt(1171/0.030) ~ 197 ->
        // floored 190, coerced to the 120 catalog cap: this device keeps the
        // cap (the pre-472 model-inclusive baseline wrongly tightened it).
        assertEquals(120, TranscriptionMemoryPolicy.effectiveChunkSeconds(3300 * MiB, 864 * MiB, 120))
        // One step tighter does clamp: budget 3000-1229=1771, t = sqrt(871/0.030)
        // ~ 170 -> floored 170, below the 180 catalog cap.
        assertEquals(170, TranscriptionMemoryPolicy.effectiveChunkSeconds(3000 * MiB, 864 * MiB, 180))
    }

    @Test
    fun `starved device falls back to the minimum chunk`() {
        // Budget 2048-1229=819 below the decode baseline 900: the smallest
        // chunk is the only safe answer.
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

    @Test
    fun `canServeMinimumChunk fails open on unknown inputs`() {
        assertNull(TranscriptionMemoryPolicy.canServeMinimumChunk(0L, 640 * MiB))
        assertNull(TranscriptionMemoryPolicy.canServeMinimumChunk(4L * 1024 * MiB, 0L))
    }

    @Test
    fun `canServeMinimumChunk refuses the starved class and serves the ample one`() {
        // The 4GB-reporter class, POST-load: the pre-flight let the 862MB
        // model load with ~1.4GB free, leaving ~540MB; the decode budget is
        // negative against the 927MiB minimum baseline: refuse.
        val reporterPostLoadAvail = 540L * MiB
        val parakeetSmoothquant = 862L * MiB
        assertEquals(false, TranscriptionMemoryPolicy.canServeMinimumChunk(reporterPostLoadAvail, parakeetSmoothquant))
        // A healthy phone: several GB free holds the baseline comfortably.
        assertEquals(true, TranscriptionMemoryPolicy.canServeMinimumChunk(6L * 1024 * MiB, parakeetSmoothquant))
    }

    @Test
    fun `whisper family has its own higher decode baseline`() = run {
        // Whisper-small measured at ~2320 MiB overhead (RMX3853, 2026-09-10);
        // the transducer constant (900) would under-protect by 2.5x.
        // The onsets are model-size dependent for whisper; compare at
        // whisper-small (358 MB, the calibrated measurement).
        val smallModel = 358L * MiB
        val onsetWhisper = TranscriptionMemoryPolicy.minimumDecodeBaselineBytes(
            TranscriptionMemoryPolicy.Family.WHISPER, smallModel)
        val onsetTransducer = TranscriptionMemoryPolicy.minimumDecodeBaselineBytes(
            TranscriptionMemoryPolicy.Family.TRANSDUCER)
        assertTrue(
            "whisper-small onset ($onsetWhisper) must exceed transducer ($onsetTransducer)",
            onsetWhisper > onsetTransducer,
        )
        // A sub-2GB phone (the 4GB-reporter class, 1900 MiB free): tiny is
        // SERVED under the whisper family (decode fits), the same model is
        // also served (the model is resident, only the overhead matters).
        val reporterFree = 2000L * MiB
        val tinyModel = 103L * MiB
        assertEquals(
            true,
            TranscriptionMemoryPolicy.canServeMinimumChunk(
                reporterFree, tinyModel, TranscriptionMemoryPolicy.Family.WHISPER))
    }

    @Test
    fun `canServeMinimumChunk boundary is pinned at the minimum decode baseline`() {
        // Onset: budget == baseline + headroom is a REFUSAL (strict >).
        // baseline = 900 + 0.030*30^2 = 927; onset avail = 927 + 1229 = 2156.
        val onset = 2156L * MiB
        val model = 862L * MiB
        assertEquals(false, TranscriptionMemoryPolicy.canServeMinimumChunk(onset, model))
        assertEquals(true, TranscriptionMemoryPolicy.canServeMinimumChunk(onset + MiB, model))
        // The refusal's user-facing number is the same bar.
        assertEquals(onset, TranscriptionMemoryPolicy.minimumDecodeBaselineBytes())
    }
}
