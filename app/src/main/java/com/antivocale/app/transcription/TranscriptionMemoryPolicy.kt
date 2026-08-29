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
 *     peak(T) = modelSize + OVERHEAD_MIB + K_MIB_PER_S2 * T^2
 *
 * Calibrated 2026-08-29 from the desktop VmHWM sweep (Parakeet stock int8, 4 threads,
 * one process per duration): measured peak 120s = 1946 MiB, 366s = 5226 MiB with a
 * 640 MiB model, giving OVERHEAD ~ 888 MiB and K ~ 0.028 MiB/s^2; both rounded
 * conservative here. The sweep context: a 6:06 single-pass file (Parakeet's former
 * 380s catalog cap meant one whole-file pass) killed an 8GB phone system-wide
 * (GH #44), while 30s voice messages cost nothing above the loaded-model baseline.
 *
 * The policy only TIGHTENS the catalog cap; it never raises it. Devices with ample
 * RAM keep the shipped default, and a device whose free RAM cannot even hold the
 * load baseline falls back to the minimum chunk rather than refusing to transcribe.
 */
object TranscriptionMemoryPolicy {

    /** Interpreter + arena + audio buffers beyond the model files themselves (MiB). */
    internal const val OVERHEAD_MIB = 900.0

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
     * Effective chunk cap for this request: the catalog cap tightened by free RAM.
     * Fails open to [catalogCapSeconds] when either memory input is unknown (0),
     * mirroring the load pre-flight's fail-open stance.
     */
    fun effectiveChunkSeconds(availableBytes: Long, modelSizeBytes: Long, catalogCapSeconds: Int): Int {
        if (availableBytes <= 0 || modelSizeBytes <= 0) return catalogCapSeconds
        val availableMiB = availableBytes / (1024.0 * 1024.0)
        val modelMiB = modelSizeBytes / (1024.0 * 1024.0)
        val budgetMiB = availableMiB - HEADROOM_MIB
        val baselineMiB = modelMiB + OVERHEAD_MIB
        if (budgetMiB <= baselineMiB) return MIN_CHUNK_SECONDS
        val seconds = floor(sqrt((budgetMiB - baselineMiB) / K_MIB_PER_S2) / STEP_SECONDS) * STEP_SECONDS
        return seconds.toInt().coerceIn(MIN_CHUNK_SECONDS, catalogCapSeconds)
    }

    /** Peak-RSS prediction for the calibration test; same constants as the cap. */
    internal fun predictedPeakMiB(modelSizeMiB: Long, chunkSeconds: Int): Double =
        modelSizeMiB + OVERHEAD_MIB + K_MIB_PER_S2 * chunkSeconds * chunkSeconds
}
