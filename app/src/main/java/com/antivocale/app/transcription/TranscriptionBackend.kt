package com.antivocale.app.transcription

import android.content.Context

/**
 * Interface for transcription backends.
 *
 * Each backend handles audio transcription and/or text generation
 * using different underlying technologies (LiteRT-LM, sherpa-onnx, etc.)
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
     * Whether this backend supports text generation.
     */
    val supportsText: Boolean

    /**
     * Initializes the backend with the given configuration.
     *
     * @param context Application context
     * @param config Backend-specific configuration
     * @return Result success or failure
     */
    suspend fun initialize(context: Context, config: BackendConfig): Result<Unit>

    /**
     * Transcribes audio data to text.
     *
     * @param audioData WAV ByteArray (16kHz mono, 16-bit PCM)
     * @param prompt Optional prompt to guide transcription
     * @return Result containing the transcription text
     */
    suspend fun transcribeAudio(audioData: ByteArray, prompt: String): Result<String>

    /**
     * Generates text from a prompt.
     *
     * @param prompt The input prompt
     * @return Result containing the generated text
     */
    suspend fun generateText(prompt: String): Result<String>

    /**
     * Checks if the backend is ready for inference.
     */
    fun isReady(): Boolean

    /**
     * Checks if audio processing is available.
     */
    fun isAudioSupported(): Boolean

    /**
     * Unloads the model from memory.
     */
    fun unload()

    /**
     * Sets the keep-alive timeout in minutes.
     * After this period of inactivity, the model may be automatically unloaded.
     */
    fun setKeepAliveTimeout(minutes: Int)

    /**
     * Gets the current model path, if any.
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
     */
    data class SherpaOnnxConfig(
        val modelDir: String,  // Directory containing encoder/decoder/joiner/tokens
        val modelType: String = "nemo_transducer"
    ) : BackendConfig()
}
