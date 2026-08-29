package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord

import android.content.Context

/**
 * Interface for transcription backends.
 *
 * Each backend handles audio transcription
 * and text generation
 * using different underlying technologies (LiteRT-LM, sherpa-onnx, etc).
 */
interface TranscriptionBackend {
    /**
     * Unique identifier for this backend.
     */
    val id: String

    /**
     * User-friendly display name.
     */
    val displayName: String

    /**
     * Whether this backend supports audio transcription.
     */
    val supportsAudio: Boolean

    /**
     * Whether this backend supports text generation
     */
    val supportsText: Boolean

    /**
     * Maximum audio chunk duration this backend can process efficiently.
     * Audio longer than this will be split into chunks.
     * null means no chunking limit (process entire audio as single chunk)
     */
    val maxChunkDurationSeconds: Int?
        get() = 30  // Default: 30 seconds (safe for most backends)

    /**
     * True when this backend's decoder degrades on chunks cut at arbitrary
     * positions and needs VAD-aligned (silence-boundary) segmentation instead of
     * fixed-length pipeline cuts, REGARDLESS of the user's VAD toggle. TASK-370
     * (Gemma audio encoder) and the canary external family (TASK-408, measured:
     * mid-speech cuts make half the chunks decode empty) set this.
     */
    val requiresVadAlignedChunking: Boolean
        get() = false

    /**
     * Initializes the backend with the given configuration.
     */
    suspend fun initialize(context: Context, config: BackendConfig): Result<Unit>

    /**
     * Transcribes audio data to text.
     *
     * @param samples PCM float samples normalized to [-1.0, 1.0], mono channel
     * @param sampleRate Sample rate of the audio data
     * @return Result containing [TranscriptionResult] with text, optional confidence, and detected language
     */
    suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String): Result<TranscriptionResult>

    /**
     * Streaming variant of [transcribeAudio] that emits partial hypotheses via [onPartial]
     * as the audio is decoded, enabling progressive/real-time display.
     *
     * The default implementation ignores [onPartial] and delegates to [transcribeAudio];
     * backends backed by a streaming recognizer (e.g. Nemotron's OnlineRecognizer) override
     * this to surface progressive text. The final returned result must be equivalent to
     * [transcribeAudio]'s output for the same input.
     */
    suspend fun transcribeAudioStreaming(
        samples: FloatArray,
        sampleRate: Int,
        prompt: String,
        onPartial: suspend (String) -> Unit
    ): Result<TranscriptionResult> = transcribeAudio(samples, sampleRate, prompt)

    /**
     * Generates text from a prompt.
     */
    suspend fun generateText(prompt: String): Result<String>

    /**
     * Returns whether the backend is ready for inference.
     */
    fun isReady(): Boolean

    /**
     * Returns whether this backend supports audio transcription.
     */
    fun isAudioSupported(): Boolean

    /**
     * Unloads the backend and releases resources.
     */
    fun unload()

    /**
     * Sets the keep-alive timeout for the backend.
     */
    fun setKeepAliveTimeout(minutes: Int)

    /**
     * Callback invoked after the backend unloads itself (idle timeout or
     * explicit unload), so the manager can clear its bookkeeping. Default
     * no-op for backends without self-managed lifecycle.
     */
    fun setOnAutoUnloadCallback(callback: (() -> Unit)?) {}

    /**
     * Returns the path to the model file.
     */
    fun getModelPath(): String?
}

/**
 * Sealed class for backend-specific configuration.
 */
sealed class BackendConfig {
    /**
     * Configuration for LiteRT-LM backend.
     */
    data class LiteRTConfig(val modelPath: String) : BackendConfig()

    /**
     * Configuration for sherpa-onnx backend.
     *
     * @param modelDir Directory containing encoder/decoder/joiner/tokens
     * @param modelType Model architecture type (default: nemo_transducer for Parakeet)
     */
    data class SherpaOnnxConfig(
        val modelDir: String,
        val modelType: String = "nemo_transducer",
        val numThreads: Int,
        val language: String = "",
        val provider: String = "cpu"
    ) : BackendConfig()

    data class GgufConfig(
        val modelPath: String,
        val contextSize: Int = 2048,
        val threadCount: Int = 4
    ) : BackendConfig()

    data class ExternalConfig(
        val record: ExternalModelRecord,
        val numThreads: Int,
        val provider: String,
    ) : BackendConfig()
}

/**
 * Typed exceptions backends throw inside Result.failure, so the orchestrator
 * and UI can distinguish failure causes and show specific, user-facing messages
 * instead of a generic "transcription failed".
 *
 * Backends should prefer these over raw exceptions where the cause is identifiable.
 * The [cause] chain is always preserved for logcat diagnostics.
 */
sealed class TranscriptionException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    /** The model file is missing, corrupt, truncated, or the wrong format for this backend. */
    class ModelLoadError(detail: String, cause: Throwable? = null) :
        TranscriptionException("Model load failed: $detail", cause)

    /** The model loaded but a native/decoding error occurred during transcription. */
    class NativeError(detail: String, cause: Throwable? = null) :
        TranscriptionException("Native inference error: $detail", cause)

    /** The backend was not initialized (no model loaded) when transcription was requested. */
    class NotInitialized :
        TranscriptionException("Backend not initialized (no model loaded)")

    /** Audio could be decoded but produced no transcription text. */
    class NoTranscriptionProduced :
        TranscriptionException("No transcription produced")

    /** The device had too little free memory to load the model (pre-flight block). */
    class InsufficientMemory(detail: String) :
        TranscriptionException("Insufficient memory: $detail")

    /** The persisted external model record is gone or its files vanished (TASK-342). */
    class ExternalModelUnavailable(backendId: String) :
        TranscriptionException("External model no longer available: $backendId")
}

/**
 * Result from audio transcription containing the text and optional metadata.
 */
data class TranscriptionResult(
    val text: String,
    val confidence: Float? = null,
    val detectedLanguage: String? = null,
    val isPartial: Boolean = false,
    val failedChunkCount: Int = 0
) {
    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun computeConfidence(text: String, sampleCount: Int, sampleRate: Int): Float? {
            val audioDurationSeconds = sampleCount.toFloat() / sampleRate
            if (audioDurationSeconds <= 0f) return null
            val wordCount = text.split(WHITESPACE).count { it.isNotEmpty() }
            if (wordCount == 0) return null
            val wps = wordCount / audioDurationSeconds
            return when {
                wps >= 1.5f -> 0.85f.coerceAtMost(0.7f + 0.15f * minOf(1f, (wps - 1.5f) / 3f))
                wps >= 0.5f -> 0.4f + 0.3f * ((wps - 0.5f) / 1f)
                else -> (wps / 0.5f) * 0.4f
            }
        }
    }
}
