package com.antivocale.app.transcription

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
}

/**
 * Result from audio transcription containing the text and optional metadata.
 */
data class TranscriptionResult(
    val text: String,
    val confidence: Float? = null,
    val detectedLanguage: String? = null
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
