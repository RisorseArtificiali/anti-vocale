package com.antivocale.app.service

/**
 * Callback interface for transcription lifecycle events.
 * Implemented by the Android service layer to handle UI/Android-specific concerns
 * (notifications, broadcasts, clipboard). The orchestrator calls these methods
 * during transcription; the service translates them into Android actions.
 */
interface TranscriptionListener {

    /** Processing phase changed (e.g., "Processing audio...", "Generating text...") */
    fun onStatusUpdate(message: String)

    /** Indeterminate progress during model loading or preprocessing */
    fun onIndeterminateProgress(message: String)

    /** Determinate progress update during chunk processing */
    fun onProgress(
        contentText: String,
        progressPercent: Int,
        etaText: String,
        durationSeconds: Int,
        startTimeMillis: Long,
        queuedCount: Int
    )

    /**
     * Interim transcription result from progressive/VAD/pipeline mode.
     *
     * Chunk metadata (TASK-242) lets the listener offer prev/next navigation through
     * completed chunks on the in-progress notification. Defaults preserve the
     * "no chunk context" case (e.g. a single streaming partial).
     *
     * - [chunkIndex]: 0-based index of the chunk that just completed (-1 if not chunk-based).
     * - [chunkText]: that chunk's own text (null if not chunk-based).
     * - [totalChunks]: total chunk count for this job (0 if not chunk-based / unknown).
     */
    fun onInterimResult(
        contentText: String,
        bigText: String,
        subText: String,
        chunkIndex: Int = -1,
        chunkText: String? = null,
        totalChunks: Int = 0
    )

    /** Transcription completed successfully */
    fun onSuccess(
        taskId: String,
        resultText: String,
        isShareRequest: Boolean,
        sourcePackage: String?,
        durationMs: Long,
        confidence: Float? = null,
        detectedLanguage: String? = null,
        isPartial: Boolean = false,
        failedChunkCount: Int = 0
    )

    /** Transcription or backend loading failed */
    fun onError(
        taskId: String,
        errorCode: String,
        errorMessage: String,
        isShareRequest: Boolean,
        isNoModelError: Boolean,
        durationMs: Long
    )
}
