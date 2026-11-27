package com.antivocale.app.audio

/**
 * Single source of truth for audio-duration ceilings and the long-audio
 * warning decision (spec: docs/superpowers/specs/2026-09-01-audio-duration-cap-design.md).
 *
 * Pure Kotlin, no Android imports: memory readings enter as parameters so the
 * whole policy is JVM-testable.
 */
object AudioDurationPolicy {

    /**
     * TASK-1037 (GH #190) phase 6: the session-capable ceiling, lifted
     * from the old 2h valve now that resumable sessions survive kills.
     * The heaviest history row (text plus segment timings) measured
     * ~31.4 KB for 8 minutes of audio on the 11-22 spike, ~3.9 KB per
     * minute: 4h projects ~0.95 MB, safely under the 2 MB
     * CursorWindow Room reads a row into; 6h (~1.4 MB) sits borderline
     * and 12h (~2.8 MB) would break the read. Still a practical valve,
     * not a memory constraint: the streaming decode is chunked, and
     * every run at or above the session engage threshold checkpoints
     * and resumes (the one exception is the engage's free-space floor
     * on a nearly-full disk, where the run proceeds uncheckpointed).
     */
    const val SESSION_MAX_SECONDS = 4 * 3600L

    /** Streaming path: the session-capable ceiling (see [SESSION_MAX_SECONDS]). */
    const val STREAMING_MAX_SECONDS = SESSION_MAX_SECONDS

    /** Whole-file path clamp floor; also the fail-open value. */
    const val VAD_MIN_SECONDS = 600L

    /** Whole-file path clamp ceiling (the same ceiling as streaming, reached only with a huge heap). */
    const val VAD_MAX_SECONDS = SESSION_MAX_SECONDS

    /** 16kHz mono FloatArray bytes per second of audio. */
    const val PCM_BYTES_PER_SECOND = 64 * 1024L

    /** Peak copies budgeted: merge peaks at 2x-of-final (final included) plus the VAD copy. */
    const val PCM_PEAK_COPIES = 3L

    enum class DecodePath { STREAMING, WHOLE_FILE_PCM }

    data class WarnDecision(
        val showDialog: Boolean,
        /** Estimated compute time, rounded UP to the minute. */
        val estimateMinutes: Long,
        /** Audio length, rounded UP to the minute; the dialog reads both from here. */
        val durationMinutes: Long,
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
        // The fail-open default (VAD_MIN_SECONDS of bytes / 3 copies = 200s,
        // coerced back up to the 600s floor) converges to the old direct
        // fail-open; the normal path is the same arithmetic.
        val budgetBytes = retentionBudgetBytes(availableRamBytes, maxHeapBytes)
        return (budgetBytes / (PCM_PEAK_COPIES * PCM_BYTES_PER_SECOND))
            .coerceIn(VAD_MIN_SECONDS, VAD_MAX_SECONDS)
    }

    /**
     * TASK-728: the RAW byte budget (min(ram/4, heap/2)) the whole-file
     * arm budgets over; consumers apply their own divisor. The runtime
     * retention guard divides by PCM_PEAK_COPIES like [ceilingSeconds]
     * (the 1.13.2 crash device died at ~77MB retained; the undivided
     * budget would trip too late to prevent it).
     * The runtime guard compares ACTUAL retained bytes against it, so no
     * metadata is needed and unreadable durations are covered. Fail-open
     * to the 10-minute equivalent when either reading is missing (the
     * policy's standing convention), still bounded.
     */
    fun retentionBudgetBytes(availableRamBytes: Long?, maxHeapBytes: Long?): Long {
        val ram = availableRamBytes ?: return VAD_MIN_SECONDS * PCM_BYTES_PER_SECOND
        val heap = maxHeapBytes ?: return (VAD_MIN_SECONDS * PCM_BYTES_PER_SECOND)
        if (ram <= 0L || heap <= 0L) return VAD_MIN_SECONDS * PCM_BYTES_PER_SECOND
        return minOf(ram / 4L, heap / 2L)
    }

    /** TASK-1060.4: how the speaker-labeling pass gets its audio. */
    enum class SpeakerPass {
        /** The run retains its decoded chunks and diarizes them whole. */
        WHOLE_FILE,
        /** The pass re-decodes the file and diarizes it window by window. */
        WINDOWED,
    }

    /**
     * TASK-1060.4: the TASK-597 gate as a decision. Retaining a streaming
     * run's chunks for the whole-file pass costs the whole-file PCM budget,
     * so a source longer than [wholeFileCeilingSeconds] takes the windowed
     * pass instead. An unreadable duration (null or non-positive) keeps the
     * whole-file pass: the TASK-728 runtime guard still measures what is
     * actually retained and switches the run to the windowed pass at the
     * budget.
     */
    fun speakerPass(sourceDurationSeconds: Double?, wholeFileCeilingSeconds: Long): SpeakerPass =
        if (sourceDurationSeconds != null && sourceDurationSeconds > wholeFileCeilingSeconds) SpeakerPass.WINDOWED
        else SpeakerPass.WHOLE_FILE

    /** Advisory dialog threshold, above 30 minutes. */
    const val WARN_THRESHOLD_SECONDS = 1800L

    /**
     * TASK-1037 (GH #190): the resumable-session engage threshold. Only a
     * pipelined run at least this long creates a transcription session
     * (shorter files re-run faster than a resume would save); the plan's
     * recommendation, equal to the warn threshold, so the runs that get
     * the "this will take a while" dialog are exactly the runs that can
     * now be paused and resumed. Raising the streaming valve (phase 6)
     * does not move this: sessions are about surviving kills, and a
     * 40-minute run is already worth checkpointing.
     */
    const val SESSION_MIN_SECONDS = WARN_THRESHOLD_SECONDS

    /** The usePipeline rule, single source: streaming needs VAD off AND a
     *  chunking backend; everything else decodes whole-file PCM. */
    fun decodePathFor(vadEnabled: Boolean, maxChunkDurationSeconds: Int?): DecodePath =
        if (!vadEnabled && maxChunkDurationSeconds != null) DecodePath.STREAMING
        else DecodePath.WHOLE_FILE_PCM

    /**
     * TASK-450: with VAD on (whole-file PCM path) the memory-derived ceiling
     * can refuse a file the streaming path would accept. That refusal is a
     * configuration trap, not a hardware limit, so when the backend can
     * stream, fall back to the streaming path for that request instead of
     * failing. Duration-only on purpose: backend capability (a chunk cap and
     * no forced VAD-aligned chunking) is the caller's fact and lives in the
     * orchestrator's guards around this call. True only when the fallback can
     * SUCCEED: a duration above the streaming valve would just re-refuse with
     * a different number. A non-positive duration (unreadable metadata) stays
     * on the whole-file path, whose post-decode backstop still catches those
     * files.
     */
    fun shouldFallBackToStreaming(
        durationSeconds: Double,
        wholeFileCeilingSeconds: Long,
    ): Boolean =
        durationSeconds > wholeFileCeilingSeconds &&
            durationSeconds <= STREAMING_MAX_SECONDS

    /**
     * [decodePathFor] in a null-shaped form so the orchestrator consumes the
     * decision instead of restating the null-ness rule for a smart cast: the
     * chunk cap when this request streams, null when it decodes whole-file.
     */
    fun streamingChunkSeconds(vadEnabled: Boolean, maxChunkDurationSeconds: Int?): Int? =
        if (decodePathFor(vadEnabled, maxChunkDurationSeconds) == DecodePath.STREAMING) maxChunkDurationSeconds
        else null

    /**
     * Estimate tiering: the on-device calibration (calibration-sufficient
     * samples, per TranscriptionCalibrator.CalibrationProfile.hasEstimate) wins
     * even when slower than the family fallback, because optimism is the
     * failure mode. A non-positive fallback RTF falls back to 1x real time
     * (rtfEstimate is a free constructor parameter; a bad value must not yield
     * Infinity).
     */
    fun resolveEstimateMsPerSec(calibratedMsPerSec: Float?, calibrated: Boolean, fallbackRtf: Float): Float =
        if (calibrated && calibratedMsPerSec != null && calibratedMsPerSec > 0f) calibratedMsPerSec
        else 1000f / fallbackRtf.coerceAtLeast(0.001f).coerceAtMost(1000f)

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
        // Inclusive of the ceiling itself: validateDuration refuses only ABOVE
        // it, so a file exactly at the ceiling is transcribed and deserves the
        // advisory most (it is the longest accepted case).
        val show = dialogCapable &&
            durationSeconds in (WARN_THRESHOLD_SECONDS + 1)..ceilingSeconds
        if (!show) return WarnDecision(false, 0L, durationSeconds.ceilMinutes(), !calibrated)
        val minutes = kotlin.math.ceil(durationSeconds * estimateMsPerSec / 1000f / 60f)
        return WarnDecision(true, minutes.toLong(), durationSeconds.ceilMinutes(), !calibrated)
    }

    private fun Long.ceilMinutes(): Long = kotlin.math.ceil(this / 60.0).toLong()
}
