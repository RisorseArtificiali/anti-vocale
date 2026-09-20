package com.antivocale.app.transcription

/**
 * TASK-512: how a transcription was produced, carried on
 * [TranscriptionResult] and persisted on the log row (the Tim Veles triage
 * could not tell complete/partial/truncated/looped results apart from the
 * report email, and the pipeline decisions lived only in rotating logcat).
 *
 * [decodePath] values: "pipeline" (chunk-streamed decode overlapped with
 * inference), "streamed_no_vad" (the TASK-450 memory fallback of the same),
 * "vad_chunked" (whole-file decode, VAD-merged segments), "windowed"
 * (whole-file decode split into fixed windows: one speech span past the cap,
 * or the VAD-threw fallback), "whole_file" (single decode, no chunking).
 * Text-LLM entries carry no context: there is no audio pipeline to describe.
 */
data class ProcessingContext(
    val decodePath: String,
    /** Chunks the decode produced (expected count on the pipeline path). */
    val totalChunks: Int? = null,
    /** Chunks skipped after retry (mirrors TranscriptionResult.failedChunkCount). */
    val failedChunks: Int? = null,
    /** Audio seconds actually covered by inference (decoded seconds). */
    val transcribedSeconds: Double? = null,
    /** The chunk ceiling in force, after any RAM-driven tightening. */
    val chunkCapSeconds: Int? = null,
    /** Available RAM read at request time. */
    val availableRamBytes: Long? = null,
    /** TASK-545: the user's VAD toggle at transcription time. The EFFECTIVE
     *  decision is decodePath itself (vad_chunked/windowed ran VAD;
     *  pipeline/streamed_no_vad did not), so the pair is complete. */
    val vadRequested: Boolean? = null,
    /** GH #43: which backend produced THIS context (the row's context is
     *  phase 2's; refinementPhase carries phase 1's). */
    val backendId: String? = null,
    /** GH #43: the fast first pass's own context, nested on the refined
     *  row's (phase 2) context; null on single-model runs. */
    val refinementPhase: ProcessingContext? = null,
    /** GH #43: stable token when the first pass was skipped or refinement
     *  failed with text delivered anyway (fast_load_failed, fast_blank,
     *  fast_loop_detected, refine_load_failed, refine_inference_failed,
     *  refine_loop_detected). */
    val refinementSkipReason: String? = null,
)
