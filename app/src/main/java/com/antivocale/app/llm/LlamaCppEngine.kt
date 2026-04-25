package com.antivocale.app.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * GGUF inference engine backed by llama.cpp via JNI.
 *
 * This engine loads GGUF quantized models (Q4_K_M, Q5_K_M, Q8_0, etc.)
 * and runs text generation locally on device using CPU inference.
 *
 * Native library requirement: libllama.so must be available for ARM64.
 * Build from https://github.com/ggml-org/llama.cpp for Android:
 *   cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
 *         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28
 *
 * Thread count defaults to 4 — optimal for most flagship ARM big cores.
 * The repeat penalty of 1.1 mitigates the ~20% degenerate output rate
 * reported for the Gemma 4 E4B-it OBLITERATED model.
 */
class LlamaCppEngine : GgufInferenceEngine {

    companion object {
        private const val TAG = "LlamaCppEngine"
        private const val NATIVE_LIB = "llama"
    }

    @Volatile private var config: GgufInferenceEngine.ModelConfig? = null
    @Volatile private var nativeHandle: Long = 0L
    @Volatile private var initialized = false

    init {
        try {
            System.loadLibrary(NATIVE_LIB)
            Log.i(TAG, "Native llama.cpp library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native llama.cpp library not found - GGUF inference unavailable. " +
                "Build llama.cpp for Android ARM64 and include libllama.so in jniLibs.")
        }
    }

    // Native methods matching llama.cpp C API
    private external fun nativeLoadModel(modelPath: String, contextSize: Int, threads: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, temperature: Float,
                                        topP: Float, topK: Int, maxTokens: Int,
                                        repeatPenalty: Float): String
    private external fun nativeGenerateStart(handle: Long, prompt: String, temperature: Float,
                                             topP: Float, topK: Int, maxTokens: Int,
                                             repeatPenalty: Float): Long
    private external fun nativeGenerateNext(tokenPtr: Long): String?
    private external fun nativeGenerateEnd(tokenPtr: Long)
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeGetMemoryUsage(handle: Long): Long

    override fun initialize(config: GgufInferenceEngine.ModelConfig): Result<Unit> {
        if (initialized) {
            Log.w(TAG, "Engine already initialized")
            return Result.success(Unit)
        }

        val modelFile = File(config.modelPath)
        if (!modelFile.exists()) {
            return Result.failure(IllegalArgumentException("Model file not found: ${config.modelPath}"))
        }

        if (!modelFile.name.endsWith(".gguf", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Model must be a .gguf file, got: ${modelFile.name}"))
        }

        return try {
            Log.i(TAG, "Loading GGUF model: ${config.modelPath} (${modelFile.length() / (1024 * 1024)}MB)")
            Log.i(TAG, "Config: ctx=${config.contextSize}, threads=${config.threadCount}")

            nativeHandle = nativeLoadModel(
                config.modelPath,
                config.contextSize,
                config.threadCount
            )

            if (nativeHandle == 0L) {
                return Result.failure(IllegalStateException("Failed to load model — nativeLoadModel returned null handle"))
            }

            this.config = config
            initialized = true

            Log.i(TAG, "GGUF model loaded successfully")
            Result.success(Unit)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library not available", e)
            Result.failure(IllegalStateException(
                "llama.cpp native library not available. Build libllama.so for Android ARM64."
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Model loading failed", e)
            Result.failure(e)
        }
    }

    override suspend fun generate(params: GgufInferenceEngine.GenerateParams): Result<String> =
        withContext(Dispatchers.Default) {
            val handle = nativeHandle
            if (!initialized || handle == 0L) {
                return@withContext Result.failure(IllegalStateException("Engine not initialized"))
            }

            return@withContext try {
                Log.d(TAG, "Generating text: prompt='${params.prompt.take(60)}...' maxTokens=${params.maxTokens}")

                val result = nativeGenerate(
                    handle,
                    params.prompt,
                    params.temperature,
                    params.topP,
                    params.topK,
                    params.maxTokens,
                    params.repeatPenalty
                )

                Log.d(TAG, "Generation complete: ${result.length} chars")
                Result.success(result)
            } catch (e: Exception) {
                Log.e(TAG, "Text generation failed", e)
                Result.failure(e)
            }
        }

    override fun generateStream(params: GgufInferenceEngine.GenerateParams): Flow<String> = flow {
        val handle = nativeHandle
        if (!initialized || handle == 0L) {
            throw IllegalStateException("Engine not initialized")
        }

        var tokenPtr: Long = 0L
        try {
            tokenPtr = nativeGenerateStart(
                handle,
                params.prompt,
                params.temperature,
                params.topP,
                params.topK,
                params.maxTokens,
                params.repeatPenalty
            )

            while (true) {
                val token = nativeGenerateNext(tokenPtr) ?: break
                emit(token)
            }
        } finally {
            if (tokenPtr != 0L) {
                nativeGenerateEnd(tokenPtr)
            }
        }
    }.flowOn(Dispatchers.Default)

    override fun isReady(): Boolean = initialized && nativeHandle != 0L

    override fun getModelPath(): String? = config?.modelPath

    override fun getMemoryUsageBytes(): Long? {
        if (!initialized || nativeHandle == 0L) return null
        return try {
            nativeGetMemoryUsage(nativeHandle)
        } catch (e: Exception) {
            null
        }
    }

    override fun unload() {
        if (nativeHandle != 0L) {
            try {
                nativeFreeModel(nativeHandle)
            } catch (e: Exception) {
                Log.e(TAG, "Error freeing native model", e)
            }
            nativeHandle = 0L
        }
        config = null
        initialized = false
        Log.i(TAG, "Engine unloaded")
    }
}
