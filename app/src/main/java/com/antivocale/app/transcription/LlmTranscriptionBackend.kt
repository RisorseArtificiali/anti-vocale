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

        /**
         * The encoder takes ~30s per pass; the orchestrator splits longer inputs at
         * this size and concatenates (same contract as Whisper/Qwen3 chunking).
         * Public so the model-card label (GH #49, TASK-370/#371) and tests derive
         * from the value the runtime uses.
         */
        const val AUDIO_CHUNK_SECONDS = 30
        private const val TAG = "LlmTranscriptionBackend"
    }

    override val id: String = BACKEND_ID
    override val maxChunkDurationSeconds: Int = AUDIO_CHUNK_SECONDS

    // TASK-370: the audio encoder is far more boundary-sensitive than the ASR
    // decoders (mid-word 30s cuts garble Gemma chunks); VAD-aligned segmentation
    // is forced regardless of the user toggle.
    override val requiresVadAlignedChunking: Boolean = true

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
