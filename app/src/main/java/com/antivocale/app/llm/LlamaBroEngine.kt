package com.antivocale.app.llm

import android.util.Log
import com.suhel.llamabro.sdk.config.InferenceConfig
import com.suhel.llamabro.sdk.config.LoadableModel
import com.suhel.llamabro.sdk.config.ModelLoadConfig
import com.suhel.llamabro.sdk.config.ModelProfiles
import com.suhel.llamabro.sdk.config.SessionConfig
import com.suhel.llamabro.sdk.engine.LlamaEngine
import com.suhel.llamabro.sdk.engine.LlamaSession
import com.suhel.llamabro.sdk.engine.TokenGenerationResultCode
import com.suhel.llamabro.sdk.model.LlamaError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * GGUF inference engine backed by llama-bro SDK (llama.cpp via JNI).
 *
 * llama-bro ships pre-built native libraries for arm64-v8a and provides
 * a Kotlin Flow API for model loading, text generation, and streaming.
 */
class LlamaBroEngine @Inject constructor() : GgufInferenceEngine {

    companion object {
        private const val TAG = "LlamaBroEngine"
    }

    @Volatile private var config: GgufInferenceEngine.ModelConfig? = null
    @Volatile private var engine: LlamaEngine? = null
    @Volatile private var session: LlamaSession? = null
    @Volatile private var initialized = false

    override suspend fun initialize(config: GgufInferenceEngine.ModelConfig): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (initialized) {
                Log.w(TAG, "Engine already initialized")
                return@withContext Result.success(Unit)
            }

            val modelFile = File(config.modelPath)
            if (!modelFile.exists()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Model file not found: ${config.modelPath}")
                )
            }

            if (!modelFile.name.endsWith(".gguf", ignoreCase = true)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Model must be a .gguf file, got: ${modelFile.name}")
                )
            }

            try {
                Log.i(TAG, "Loading GGUF model: ${config.modelPath} (${modelFile.length() / (1024 * 1024)}MB)")
                Log.i(TAG, "Config: ctx=${config.contextSize}, threads=${config.threadCount}")

                val loadableModel = LoadableModel(
                    loadConfig = ModelLoadConfig(
                        path = config.modelPath,
                        threads = config.threadCount,
                    ),
                    profile = ModelProfiles.GEMMA,
                )

                val loadedEngine = LlamaEngine.create(loadableModel) { progress ->
                    Log.d(TAG, "Model loading: ${(progress * 100).toInt()}%")
                    true
                }

                val loadedSession = loadedEngine.createSession(
                    SessionConfig(
                        contextSize = config.contextSize,
                        inferenceConfig = InferenceConfig(
                            temperature = 0.7f,
                            topP = 0.9f,
                            topK = 40,
                            repeatPenalty = config.repeatPenalty,
                        ),
                    )
                )

                this@LlamaBroEngine.engine = loadedEngine
                this@LlamaBroEngine.session = loadedSession
                this@LlamaBroEngine.config = config
                this@LlamaBroEngine.initialized = true

                Log.i(TAG, "GGUF model loaded successfully via llama-bro")
                Result.success(Unit)
            } catch (e: LlamaError.ModelNotFound) {
                Log.e(TAG, "Model not found: ${config.modelPath}", e)
                Result.failure(IllegalStateException("Model file not found: ${config.modelPath}"))
            } catch (e: LlamaError.ModelLoadFailed) {
                Log.e(TAG, "Model load failed", e)
                Result.failure(IllegalStateException("Failed to load GGUF model: ${e.message}"))
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "llama-bro native library not available", e)
                Result.failure(IllegalStateException(
                    "llama-bro native library not available. The SDK should bundle libllama_bro.so."
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Model loading failed", e)
                Result.failure(e)
            }
        }

    override suspend fun generate(params: GgufInferenceEngine.GenerateParams): Result<String> =
        withContext(Dispatchers.Default) {
            val currentSession = session
            if (!initialized || currentSession == null) {
                return@withContext Result.failure(IllegalStateException("Engine not initialized"))
            }

            try {
                Log.d(TAG, "Generating text: prompt='${params.prompt.take(60)}...' maxTokens=${params.maxTokens}")

                currentSession.updateSampler(
                    InferenceConfig(
                        temperature = params.temperature,
                        topP = params.topP,
                        topK = params.topK,
                        repeatPenalty = params.repeatPenalty,
                    )
                )
                currentSession.setPrefixedPrompt(params.prompt)

                val result = StringBuilder()
                currentSession.generateFlow().collect { tokenResult ->
                    if (tokenResult.resultCode == TokenGenerationResultCode.OK && tokenResult.token != null) {
                        result.append(tokenResult.token)
                    }
                }

                Log.d(TAG, "Generation complete: ${result.length} chars")
                Result.success(result.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Text generation failed", e)
                Result.failure(e)
            }
        }

    override fun generateStream(params: GgufInferenceEngine.GenerateParams): Flow<String> = flow {
        val currentSession = session
        if (!initialized || currentSession == null) {
            throw IllegalStateException("Engine not initialized")
        }

        currentSession.updateSampler(
            InferenceConfig(
                temperature = params.temperature,
                topP = params.topP,
                topK = params.topK,
                repeatPenalty = params.repeatPenalty,
            )
        )
        currentSession.setPrefixedPrompt(params.prompt)

        currentSession.generateFlow().collect { tokenResult ->
            if (tokenResult.resultCode == TokenGenerationResultCode.OK) {
                val token = tokenResult.token
                if (token != null) {
                    emit(token)
                }
            }
        }
    }.flowOn(Dispatchers.Default)

    override fun isReady(): Boolean = initialized && engine != null && session != null

    override fun getModelPath(): String? = config?.modelPath

    override fun getMemoryUsageBytes(): Long? = null

    override fun unload() {
        try {
            session?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing session", e)
        }
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing engine", e)
        }
        session = null
        engine = null
        config = null
        initialized = false
        Log.i(TAG, "Engine unloaded")
    }
}
