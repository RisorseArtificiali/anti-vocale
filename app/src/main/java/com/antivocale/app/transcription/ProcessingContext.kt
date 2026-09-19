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
)
