package com.antivocale.app.llm

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for GGUF-based on-device LLM inference.
 *
 * Implementations wrap native inference engines (e.g., llama.cpp via JNI)
 * to run GGUF quantized models locally on Android.
 *
 * The engine is stateless between calls — each [generate] invocation gets
 * a fresh context unless the caller manages chat history externally.
 */
interface GgufInferenceEngine {

    /**
     * Model configuration for initialization.
     */
    data class ModelConfig(
        val modelPath: String,
        val contextSize: Int = 2048,
        val threadCount: Int = 4,
        val batchSize: Int = 512,
        val repeatPenalty: Float = 1.1f
    )

    /**
     * Parameters controlling text generation behavior.
     */
    data class GenerateParams(
        val prompt: String,
        val temperature: Float = 0.7f,
        val topP: Float = 0.9f,
        val topK: Int = 40,
        val maxTokens: Int = 1024,
        val repeatPenalty: Float = 1.1f,
        val stopTokens: List<String> = emptyList()
    )

    /**
     * Initializes the engine with a GGUF model file.
     * Must be called before [generate] or [generateStream].
     *
     * @return Result.success if the model loaded successfully
     */
    fun initialize(config: ModelConfig): Result<Unit>

    /**
     * Generates text from a prompt, returning the complete response.
     *
     * @param params Generation parameters
     * @return Result containing the generated text
     */
    suspend fun generate(params: GenerateParams): Result<String>

    /**
     * Generates text from a prompt, streaming tokens as they are produced.
     *
     * @param params Generation parameters
     * @return Flow of generated text chunks
     */
    fun generateStream(params: GenerateParams): Flow<String>

    /**
     * Whether the engine is initialized and ready for inference.
     */
    fun isReady(): Boolean

    /**
     * Gets the model path currently loaded, or null if not initialized.
     */
    fun getModelPath(): String?

    /**
     * Gets estimated memory usage in bytes, or null if not initialized.
     */
    fun getMemoryUsageBytes(): Long?

    /**
     * Unloads the model and releases all native resources.
     */
    fun unload()
}
