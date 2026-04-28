package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.llm.GgufInferenceEngine

class Gemma4GgufBackend(
    private val engine: GgufInferenceEngine
) : TranscriptionBackend {

    companion object {
        const val BACKEND_ID = "gemma4_gguf"
        private const val TAG = "Gemma4GgufBackend"
    }

    override val id: String = BACKEND_ID
    override val displayName: String = "Gemma 4 (GGUF)"
    override val supportsAudio: Boolean = false
    override val supportsText: Boolean = true
    override val maxChunkDurationSeconds: Int? = null

    override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> {
        val ggufConfig = config as? BackendConfig.GgufConfig
            ?: return Result.failure(
                IllegalArgumentException("Invalid config type for Gemma4GgufBackend")
            )

        Log.i(TAG, "Initializing GGUF engine with model: ${ggufConfig.modelPath}")
        return engine.initialize(
            GgufInferenceEngine.ModelConfig(
                modelPath = ggufConfig.modelPath,
                contextSize = ggufConfig.contextSize,
                threadCount = ggufConfig.threadCount
            )
        )
    }

    override suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String): Result<String> {
        return Result.failure(
            UnsupportedOperationException("Audio transcription not supported by GGUF backend")
        )
    }

    override suspend fun generateText(prompt: String): Result<String> {
        return engine.generate(
            GgufInferenceEngine.GenerateParams(prompt = prompt)
        )
    }

    override fun isReady(): Boolean = engine.isReady()

    override fun isAudioSupported(): Boolean = supportsAudio

    override fun unload() {
        Log.i(TAG, "Unloading GGUF engine")
        engine.unload()
    }

    override fun setKeepAliveTimeout(minutes: Int) {
        Log.d(TAG, "Keep-alive timeout set to $minutes minutes (not yet implemented for GGUF backend)")
    }

    override fun getModelPath(): String? = engine.getModelPath()
}
