package com.antivocale.app.transcription

/**
 * TASK-1037 (GH #190) phase 5: the remaining-time estimate for a paused
 * or in-flight session stint, as one pure derivation over what the
 * session table already holds. The maintainer's locked UX asks for "at
 * least a coarse" estimate; the coarse contract is why the rate is the
 * CUMULATIVE average (accumulatedComputeMs over the whole covered
 * prefix, stints included) rather than the last stint's instantaneous
 * speed: a stint that starts with the model warm measures faster than
 * the file's truth, and optimism is the failure mode
 * (AudioDurationPolicy.resolveEstimateMsPerSec's tiering rule).
 *
 * Sample units are the session store's 16 kHz output samples on both
 * axes, so the rate is ms-per-sample and no sample-rate conversion ever
 * appears here.
 */
object SessionProgress {

    /**
     * The remaining compute time in whole minutes, rounded UP (a paused
     * user resuming "in 2 minutes" that takes 3 is the honest direction;
     * the warn dialog's estimate rounds the same way). Null whenever the
     * derivation could not be honest: no recorded total, no covered
     * prefix or compute time to derive a rate from, or nothing
     * remaining. (The takeIf zero-guard below is invariant-defensive:
     * with remaining >= 1 and a strictly positive rate, ceil is always
     * >= 1; the review round proved the guard unreachable.)
     */
    fun remainingMinutes(
        prefixEndSamples: Long,
        totalSamples: Long?,
        accumulatedComputeMs: Long,
    ): Long? {
        if (totalSamples == null || totalSamples <= 0L) return null
        if (prefixEndSamples <= 0L || accumulatedComputeMs <= 0L) return null
        val remainingSamples = totalSamples - prefixEndSamples
        if (remainingSamples <= 0L) return null
        val msPerSample = accumulatedComputeMs.toDouble() / prefixEndSamples
        val remainingMs = remainingSamples * msPerSample
        val minutes = kotlin.math.ceil(remainingMs / 60_000.0).toLong()
        return minutes.takeIf { it > 0L }
    }
}
