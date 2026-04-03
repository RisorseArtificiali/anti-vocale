package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.manager.LlmManager

/**
 * Transcription backend that wraps the existing LlmManager.
 *
 * This provides backward compatibility with the existing LiteRT-LM
 * and MediaPipe backends while conforming to the TranscriptionBackend interface.
 */
class LlmTranscriptionBackend : TranscriptionBackend {

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

        return LlmManager.initialize(context, llmConfig.modelPath)
    }

    override suspend fun transcribeAudio(audioData: ByteArray, prompt: String): Result<String> {
        return LlmManager.generateFromAudio(prompt, audioData)
    }

    override suspend fun generateText(prompt: String): Result<String> {
        return LlmManager.generateText(prompt)
    }

    override fun isReady(): Boolean = LlmManager.isReady()

    override fun isAudioSupported(): Boolean = LlmManager.isAudioSupported()

    override fun unload() {
        LlmManager.unload()
    }

    override fun setKeepAliveTimeout(minutes: Int) {
        LlmManager.setKeepAliveTimeout(minutes)
    }

    override fun getModelPath(): String? = LlmManager.getModelPath()
}
