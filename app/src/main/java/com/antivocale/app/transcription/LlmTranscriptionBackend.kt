package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.manager.LlmManager
import com.antivocale.app.util.WavUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmTranscriptionBackend @Inject constructor(
    private val llmManager: LlmManager
) : TranscriptionBackend {

    companion object {
        const val BACKEND_ID = "llm"
        private const val TAG = "LlmTranscriptionBackend"
    }

    override val id: String = BACKEND_ID
    override val displayName: String = "Gemma (LiteRT-LM)"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = true

    override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> {
        val llmConfig = config as? BackendConfig.LiteRTConfig
            ?: return Result.failure(IllegalArgumentException("Invalid config type for LlmTranscriptionBackend"))

        return llmManager.initialize(context, llmConfig.modelPath)
    }

    override suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String): Result<TranscriptionResult> {
        val wavBytes = WavUtils.floatSamplesToWav(samples, sampleRate)
        return llmManager.generateFromAudio(prompt, wavBytes).map { text ->
            TranscriptionResult(text = text)
        }
    }

    override suspend fun generateText(prompt: String): Result<String> {
        return llmManager.generateText(prompt)
    }

    override fun isReady(): Boolean = llmManager.isReady()

    override fun isAudioSupported(): Boolean = llmManager.isAudioSupported()

    override fun unload() {
        llmManager.unload()
    }

    override fun setKeepAliveTimeout(minutes: Int) {
        llmManager.setKeepAliveTimeout(minutes)
    }

    override fun getModelPath(): String? = llmManager.getModelPath()
}
