package com.antivocale.app.audio

/**
 * TASK-1037 phase 1: the pure arithmetic of resuming a stream decode at an
 * audio offset. Offsets are 16 kHz OUTPUT samples (the pipeline's canonical
 * timeline), so the chunk-size law (a resumed stint may tighten its chunk
 * cap differently) never bends the resume point.
 *
 * The baseline resume mechanism is decode-and-discard: the decoder always
 * starts at zero (MediaExtractor has no sample-accurate seek on this path),
 * and decoded output is dropped until the skip target is reached. Exact by
 * construction; the only cost is the discard time, which phase 0 measured
 * per format before deciding whether seek is added above it.
 *
 * Pure Kotlin, no Android imports: the whole policy is JVM-testable, the
 * AudioDurationPolicy pattern.
 */
object StreamResume {

    /**
     * How much of one decoded, already-resampled buffer survives the skip.
     * [alreadySkipped] is the running count of OUTPUT samples discarded so
     * far; [skipTarget] the total to discard. [keepFrom] is the index the
     * caller copies from; the caller adds keepFrom to its own alreadySkipped
     * (a straddling buffer's tail from keepFrom on is real audio).
     */
    data class KeepDecision(val keepFrom: Int, val skipComplete: Boolean)

    fun keepAfterSkip(alreadySkipped: Long, decodedCount: Int, skipTarget: Long): KeepDecision {
        require(skipTarget >= 0) { "skipTarget must be >= 0" }
        require(alreadySkipped >= 0) { "alreadySkipped must be >= 0" }
        val remainingToSkip = skipTarget - alreadySkipped
        if (remainingToSkip <= 0) return KeepDecision(keepFrom = 0, skipComplete = true)
        val keepFrom = minOf(remainingToSkip, decodedCount.toLong()).toInt()
        // Complete once THIS buffer crosses the target: a straddling buffer's
        // tail is real audio, so the skip finishes mid-buffer.
        return KeepDecision(
            keepFrom = keepFrom,
            skipComplete = alreadySkipped + keepFrom >= skipTarget)
    }

    /** The audio seconds a resume still owes: total minus what the offset
     * already consumed. Never negative; a bogus offset past the end owes
     * zero (the caller decides whether that is an error). */
    fun remainingSeconds(
        totalDurationSeconds: Double,
        skippedOutputSamples: Long,
        outputSampleRate: Int,
    ): Double {
        require(outputSampleRate > 0) { "outputSampleRate must be > 0" }
        val skippedSeconds = skippedOutputSamples.toDouble() / outputSampleRate
        return (totalDurationSeconds - skippedSeconds).coerceAtLeast(0.0)
    }

    /**
     * The chunk count a resumed stint's header should announce: derived from
     * the REMAINING audio under the same cap the full decode would use, so
     * the interim 'Chunk i/N' rendering stays honest on a resume. Mirrors
     * [expectedChunkCount]'s shape (null cap = one chunk; unknown duration =
     * unknown count).
     */
    fun expectedChunkCountFrom(
        totalDurationSeconds: Double,
        maxChunkDurationSeconds: Int?,
        skippedOutputSamples: Long,
        outputSampleRate: Int,
    ): Int {
        if (maxChunkDurationSeconds == null) return 1
        val remaining = remainingSeconds(totalDurationSeconds, skippedOutputSamples, outputSampleRate)
        if (remaining <= 0) return 0
        return expectedChunkCount(remaining, maxChunkDurationSeconds)
    }

    /**
     * THE chunk-count estimate (moved from AudioPreprocessor, its first
     * home, so the pure arithmetic lives in one place and the resumed
     * variant shares it). A floor keeps non-exact multiples from
     * under-counting by one: the interim suffix logic drops the total when
     * the stream passes it, so the estimate stays an estimate, never a
     * contract (TASK-444).
     */
    fun expectedChunkCount(totalDurationSeconds: Double, chunkDurationSeconds: Int): Int =
        kotlin.math.ceil(totalDurationSeconds / chunkDurationSeconds).toInt().coerceAtLeast(1)
}
