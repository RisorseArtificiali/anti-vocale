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
     */
    suspend fun transcribeAudio(audioData: ByteArray, prompt: String): Result<String>

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
        val modelType: String = "nemo_transducer"
    ) : BackendConfig()
}
