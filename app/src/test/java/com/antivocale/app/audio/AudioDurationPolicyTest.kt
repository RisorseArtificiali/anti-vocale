package com.antivocale.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDurationPolicyTest {

    // 3 * 64KiB/s peak budget; the admission-formula denominator
    private val denom = 3L * 64 * 1024L

    @Test
    fun `streaming ceiling is the 2h valve regardless of memory`() {
        assertEquals(7200L, AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.STREAMING, 1L shl 30, 256L * 1024 * 1024))
        assertEquals(7200L, AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.STREAMING, null, null))
    }

    @Test
    fun `whole-file ceiling is heap-bound on typical devices`() {
        // 512MB heap, RAM plentiful: budget = min(ram/4, heap/2) = 256MiB
        val ram = 8L * 1024 * 1024 * 1024
        val heap512 = 512L * 1024 * 1024
        val expected = (heap512 / 2) / denom   // 1365s = ~22.8 min
        assertEquals(expected, AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, ram, heap512))
        // 256MB heap -> 682s = ~11.4 min
        val heap256 = 256L * 1024 * 1024
        assertEquals((heap256 / 2) / denom, AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, ram, heap256))
    }

    @Test
    fun `whole-file ceiling is RAM-bound on a low-RAM device with a big heap`() {
        val ram = 512L * 1024 * 1024        // ram/4 = 128MiB
        val heap = 512L * 1024 * 1024       // heap/2 = 256MiB; RAM binds
        assertEquals((ram / 4) / denom, AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, ram, heap))
    }

    @Test
    fun `clamp bounds hold on both sides`() {
        val tiny = AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, 128L shl 20, 128L shl 20)
        assertEquals(AudioDurationPolicy.VAD_MIN_SECONDS, tiny)
        val huge = AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, 32L shl 30, 4L shl 30)
        assertEquals(AudioDurationPolicy.VAD_MAX_SECONDS, huge)
    }

    @Test
    fun `fail-open on unreadable memory is the 600s floor`() {
        assertEquals(600L, AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, null, 512L shl 20))
        assertEquals(600L, AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, 8L shl 30, null))
        assertEquals(600L, AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, 0L, 0L))
    }

    @Test
    fun `estimate tiering prefers the device calibration`() {
        // 2+ samples: measured value wins even when slower than the fallback
        val measured = 250f   // ms per second of audio (RTF 0.25)
        assertEquals(measured, AudioDurationPolicy.resolveEstimateMsPerSec(measured, 2, 1000f / 15f))
        assertEquals(measured, AudioDurationPolicy.resolveEstimateMsPerSec(measured, 7, 1000f / 15f))
        // <2 samples: family fallback (rtf 15 => 1000/15 ms per second)
        assertEquals(1000f / 15f, AudioDurationPolicy.resolveEstimateMsPerSec(measured, 1, 15f))
        assertEquals(1000f / 15f, AudioDurationPolicy.resolveEstimateMsPerSec(null, 0, 15f))
    }

    @Test
    fun `warn decision truth table`() {
        val ceiling = 7200L
        val est = 1000f / 15f   // Parakeet cold: 45 min audio -> ~3 min estimate
        // below threshold: no dialog
        assertFalse(AudioDurationPolicy.warnDecision(1700L, ceiling, est, true).showDialog)
        // above threshold + dialog-capable: dialog, estimate rounded UP to the minute
        val d = AudioDurationPolicy.warnDecision(2700L, ceiling, est, true)
        assertTrue(d.showDialog); assertEquals(3L, d.estimateMinutes)
        // headless: never a dialog
        assertFalse(AudioDurationPolicy.warnDecision(2700L, ceiling, est, false).showDialog)
        // above ceiling: NO dialog (the pre-read refusal carries the message)
        assertFalse(AudioDurationPolicy.warnDecision(8000L, ceiling, est, true).showDialog)
    }

    @Test
    fun `decodePathFor mirrors the usePipeline rule`() {
        assertEquals(AudioDurationPolicy.DecodePath.STREAMING,
            AudioDurationPolicy.decodePathFor(vadEnabled = false, maxChunkDurationSeconds = 30))
        assertEquals(AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM,
            AudioDurationPolicy.decodePathFor(vadEnabled = true, maxChunkDurationSeconds = 30))
        assertEquals(AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM,
            AudioDurationPolicy.decodePathFor(vadEnabled = false, maxChunkDurationSeconds = null))
    }

    @Test
    fun `estimate rounds up to the minute`() {
        // 31 min audio at RTF 15 => 124s = 2.07 min -> 3
        assertEquals(3L, AudioDurationPolicy.warnDecision(
            1860L, 7200L, 1000f / 15f, true).estimateMinutes)
    }
}
