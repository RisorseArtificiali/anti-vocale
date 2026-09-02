package com.antivocale.app.audio

/**
 * Single source of truth for audio-duration ceilings and the long-audio
 * warning decision (spec: docs/superpowers/specs/2026-09-01-audio-duration-cap-design.md).
 *
 * Pure Kotlin, no Android imports: memory readings enter as parameters so the
 * whole policy is JVM-testable.
 */
object AudioDurationPolicy {

    /** Streaming path: practical valve, not a memory constraint. */
    const val STREAMING_MAX_SECONDS = 7200L

    /** Whole-file path clamp floor; also the fail-open value. */
    const val VAD_MIN_SECONDS = 600L

    /** Whole-file path clamp ceiling (same 2h as streaming, reached only with a huge heap). */
    const val VAD_MAX_SECONDS = 7200L

    /** 16kHz mono FloatArray bytes per second of audio. */
    const val PCM_BYTES_PER_SECOND = 64 * 1024L

    /** Peak copies budgeted: merge peaks at 2x-of-final (final included) plus the VAD copy. */
    const val PCM_PEAK_COPIES = 3L

    enum class DecodePath { STREAMING, WHOLE_FILE_PCM }

    data class WarnDecision(
        val showDialog: Boolean,
        /** Estimated compute time, rounded UP to the minute. */
        val estimateMinutes: Long,
        /** True when the estimate came from the cold-start fallback (fewer than 2 calibration samples). */
        val isRough: Boolean,
    )

    /**
     * WHOLE_FILE_PCM budgets the binding constraint. The decoded FloatArray lives
     * in the dalvik heap (no largeHeap in the manifest), so the heap, not system
     * RAM, caps large arrays: budget = min(availRam/4, maxHeap/2) over 3 PCM
     * copies, clamped to [VAD_MIN_SECONDS, VAD_MAX_SECONDS]. Either reading null
     * or <= 0 fails open to the floor, matching the pre-1.12 flat cap.
     */
    fun ceilingSeconds(path: DecodePath, availableRamBytes: Long?, maxHeapBytes: Long?): Long {
        if (path == DecodePath.STREAMING) return STREAMING_MAX_SECONDS
        val ram = availableRamBytes ?: return VAD_MIN_SECONDS
        val heap = maxHeapBytes ?: return VAD_MIN_SECONDS
        if (ram <= 0L || heap <= 0L) return VAD_MIN_SECONDS
        val budgetBytes = minOf(ram / 4L, heap / 2L)
        return (budgetBytes / (PCM_PEAK_COPIES * PCM_BYTES_PER_SECOND))
            .coerceIn(VAD_MIN_SECONDS, VAD_MAX_SECONDS)
    }

    /** Advisory dialog above 30 minutes. */
    fun warnThresholdSeconds(): Long = 1800L

    /**
     * Estimate tiering: the on-device calibration (2+ samples) wins even when
     * slower than the family fallback, because optimism is the failure mode.
     */
    fun resolveEstimateMsPerSec(calibratedMsPerSec: Float?, sampleCount: Int, fallbackRtf: Float): Float =
        if (sampleCount >= 2 && calibratedMsPerSec != null && calibratedMsPerSec > 0f) calibratedMsPerSec
        else 1000f / fallbackRtf

    /**
     * No dialog when duration exceeds the ceiling: the pre-read refusal already
     * carries the actionable message, and a dialog there would promise a
     * transcription that is then refused.
     */
    fun warnDecision(
        durationSeconds: Long,
        ceilingSeconds: Long,
        estimateMsPerSec: Float,
        dialogCapable: Boolean,
        calibrated: Boolean = true,
    ): WarnDecision {
        val show = dialogCapable &&
            durationSeconds in (warnThresholdSeconds() + 1) until ceilingSeconds
        if (!show) return WarnDecision(false, 0L, !calibrated)
        val minutes = kotlin.math.ceil(durationSeconds * estimateMsPerSec / 1000f / 60f)
        return WarnDecision(true, minutes.toLong(), !calibrated)
    }
}
