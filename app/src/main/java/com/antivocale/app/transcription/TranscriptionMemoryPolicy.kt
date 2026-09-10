package com.antivocale.app.transcription

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * RAM-derived upper bound on the audio chunk length handed to a backend (TASK-406).
 *
 * The chunk cap is a memory budget, not just the engine's structural limit: encoder
 * self-attention cost grows with the SQUARE of chunk length and the ONNX graph
 * materializes it, so peak RSS during one chunk's decode is approximately
 *
 *     peak(T) = modelSize + familyOverhead + K_MIB_PER_S2 * T^2
 *
 * Calibrated per family (TASK-475): TRANSDUCER from the desktop VmHWM sweep
 * (Parakeet TDT, 2026-08-29: OVERHEAD ~ 888 MiB, rounded to 900; verified on
 * device 2026-09-09 at ~912 MiB); WHISPER from the RMX3853 measurement
 * 2026-09-10 (whisper-small 358 MB peaked at 2974 MiB total, ~2320 MiB
 * overhead: cross-attention is far heavier than a transducer's). The original
 * one-size 900 MiB under-protected the whisper family by 2.5x and would
 * false-block tiny on exactly the sub-2GB phones it serves.
 *
 * FRAME (TASK-472 review): the calibration compared peak RSS against PRE-load
 * free RAM. Callers here read availability AFTER the model is resident, so the
 * model term must NOT re-enter the comparison (post-load avail + already-resident
 * model = pre-load avail): every predicate in this object therefore works on the
 * DECODE-side delta alone, the family overhead plus the attention term. The
 * original model-inclusive comparison double-counted the model and over-tightened
 * every cap on starved devices; with TASK-472 turning the starved case into a
 * user-visible refusal, the double count became a false-refusal band.
 *
 * The policy only TIGHTENS the catalog cap; it never raises it. Devices with ample
 * RAM keep the shipped default. A device whose free RAM cannot hold even the
 * minimum-chunk baseline is the caller's refusal to make ([canServeMinimumChunk],
 * TASK-472): proceeding at the floor there walked the process into an LMK/OEM
 * kill with no trace, so this object stays a pure cap and the go/no-go lives
 * upstream.
 */
object TranscriptionMemoryPolicy {

    /**
     * Interpreter + arena + audio buffers beyond the model files, per model
     * family (MiB). See the class KDoc for each family's calibration.
     */
    enum class Family {
        /** Roughly constant across transducer sizes (measured at 640 and 326 MB). */
        TRANSDUCER {
            override fun overheadMiB(modelSizeMiB: Double) = TRANSDUCER_OVERHEAD_MIB
        },
        /**
         * Scales with model size: whisper-small (358 MB) measured at ~2320 MiB
         * overhead on the RMX3853 (ratio ~6.5x), the cross-attention cost
         * scaling with the model dimension squared. Tiny (103 MB) lands at
         * ~670 MiB, matching the reporter's sub-2GB success. The floor
         * prevents a degenerate 0 MB model from having no overhead.
         */
        WHISPER {
            override fun overheadMiB(modelSizeMiB: Double) =
                maxOf(600.0, modelSizeMiB * WHISPER_OVERHEAD_RATIO)
        };

        abstract fun overheadMiB(modelSizeMiB: Double): Double
    }

    private const val TRANSDUCER_OVERHEAD_MIB = 900.0
    private const val WHISPER_OVERHEAD_RATIO = 6.5

    /** Default for families without a measured overhead: the transducer value. */
    internal val OVERHEAD_MIB = TRANSDUCER_OVERHEAD_MIB

    /** Quadratic attention growth per chunk-second squared (MiB/s^2). */
    internal const val K_MIB_PER_S2 = 0.030

    /**
     * RAM left for the system while one chunk decodes (1.2 GiB, MiB). A separate
     * budget from the orchestrator's load pre-flight headroom
     * (MEMORY_HEADROOM_BYTES), which only absorbs load-time noise.
     */
    internal const val HEADROOM_MIB = 1229.0

    internal const val MIN_CHUNK_SECONDS = 30
    private const val STEP_SECONDS = 10

    /**
     * Whether free RAM can hold even the minimum-chunk decode baseline. Null when
     * either input is unknown: the same fail-open stance as
     * [effectiveChunkSeconds] and the orchestrator's load pre-flight. TASK-472.
     *
     * [modelSizeBytes] no longer enters the arithmetic (see the FRAME note above)
     * but still gates fail-open: an unknown model size means an unknown picture.
     */
    fun canServeMinimumChunk(
        availableBytes: Long,
        modelSizeBytes: Long,
        family: Family = Family.TRANSDUCER,
    ): Boolean? {
        if (availableBytes <= 0 || modelSizeBytes <= 0) return null
        return decodeFits(availableBytes, minimumDecodeBaselineMiB(family, modelSizeBytes))
    }

    /**
     * Effective chunk cap for this request: the catalog cap tightened by free RAM.
     * Fails open to [catalogCapSeconds] when either memory input is unknown (0),
     * mirroring the load pre-flight's fail-open stance.
     */
    fun effectiveChunkSeconds(
        availableBytes: Long,
        modelSizeBytes: Long,
        catalogCapSeconds: Int,
        family: Family = Family.TRANSDUCER,
    ): Int {
        if (availableBytes <= 0 || modelSizeBytes <= 0) return catalogCapSeconds
        // The floor can never exceed the catalog cap: families below the 30s
        // floor exist (canary caps at 10s, TASK-408) and coerceIn(min, max)
        // throws when min > max; a starved device must clamp to the family's
        // own cap, not to 30s of degenerate decode.
        val floor = minOf(MIN_CHUNK_SECONDS, catalogCapSeconds)
        val overhead = family.overheadMiB(modelSizeBytes / (1024.0 * 1024.0))
        if (!decodeFits(availableBytes, overhead)) return floor
        val budgetMiB = decodeBudgetMiB(availableBytes)
        val seconds = floor(sqrt((budgetMiB - overhead) / K_MIB_PER_S2) / STEP_SECONDS) * STEP_SECONDS
        return seconds.toInt().coerceIn(floor, catalogCapSeconds)
    }

    /** The decode-side budget: post-load free RAM minus the system headroom (MiB). */
    private fun decodeBudgetMiB(availableBytes: Long): Double =
        availableBytes / (1024.0 * 1024.0) - HEADROOM_MIB

    /**
     * Single source of every budget comparison, so the refusal predicate and the
     * cap predicate cannot drift apart. Null is impossible here (inputs are
     * pre-gated by the callers' fail-open), the Boolean is the verdict.
     */
    private fun decodeFits(availableBytes: Long, decodeBaselineMiB: Double): Boolean =
        decodeBudgetMiB(availableBytes) > decodeBaselineMiB

    /**
     * Decode cost of the smallest chunk the cap machinery can hand a backend:
     * overhead plus the attention term at the 30s floor. Families capped below
     * 30s (canary at 10s) are held to this slightly stricter bar.
     */
    private fun minimumDecodeBaselineMiB(family: Family, modelSizeBytes: Long): Double =
        family.overheadMiB(modelSizeBytes / (1024.0 * 1024.0)) +
            K_MIB_PER_S2 * MIN_CHUNK_SECONDS * MIN_CHUNK_SECONDS

    /** [minimumDecodeBaselineMiB] plus the system headroom, as bytes: the number the refusal shows. */
    fun minimumDecodeBaselineBytes(
        family: Family = Family.TRANSDUCER,
        modelSizeBytes: Long = 0L,
    ): Long =
        ((minimumDecodeBaselineMiB(family, modelSizeBytes) + HEADROOM_MIB) * 1024 * 1024).toLong()

    /** Peak-RSS prediction for the calibration test; same constants as the cap. */
    internal fun predictedPeakMiB(
        modelSizeMiB: Long,
        chunkSeconds: Int,
        family: Family = Family.TRANSDUCER,
    ): Double =
        modelSizeMiB + family.overheadMiB(modelSizeMiB.toDouble()) + K_MIB_PER_S2 * chunkSeconds * chunkSeconds
}
