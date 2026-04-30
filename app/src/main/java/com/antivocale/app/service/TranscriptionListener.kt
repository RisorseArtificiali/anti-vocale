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

    /** Interim transcription result from progressive/VAD mode */
    fun onInterimResult(contentText: String, bigText: String, subText: String)

    /** Transcription completed successfully */
    fun onSuccess(
        taskId: String,
        resultText: String,
        isShareRequest: Boolean,
        sourcePackage: String?,
        durationMs: Long,
        confidence: Float? = null,
        detectedLanguage: String? = null
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
