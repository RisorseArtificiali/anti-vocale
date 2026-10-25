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

        /**
         * TASK-872: VibeVoice streaming models are detected by the loaded
         * model's file name (the litert-community artifacts all carry
         * "vibevoice" in the name, case-insensitive). The multi-turn
         * contract differs from Gemma's whole-chunk single-shot path.
         */
        internal fun isVibeVoiceModel(modelPath: String?): Boolean =
            modelPath != null && modelPath.contains("vibevoice", ignoreCase = true)

        /**
         * The VAD gate rule for any llm model path, stated once for the
         * loaded-engine getter and the selected-path pre-load query
         * (TranscriptionBackendManager.gateInputsFor, TASK-873).
         */
        internal fun requiresVadAlignedChunkingFor(modelPath: String?): Boolean =
            !isVibeVoiceModel(modelPath)
    }

    override val id: String = BACKEND_ID
    override val maxChunkDurationSeconds: Int = AUDIO_CHUNK_SECONDS

    // TASK-370: the audio encoder is far more boundary-sensitive than the ASR
    // decoders (mid-word 30s cuts garble Gemma chunks); VAD-aligned segmentation
    // is forced regardless of the user toggle. TASK-872: VibeVoice models use
    // FIXED 3.47s windows, so VAD alignment would fight the model's own frame
    // boundaries; the multi-turn loop handles segmentation internally.
    // Known exposure (gate round): with VAD off the streaming arm still cuts
    // CHUNKS at arbitrary positions (the RAM-tightened cap, at most 30s);
    // the multi-turn loop segments within each chunk, so the seam cost is
    // bounded to one padded partial window per chunk boundary (and speaker
    // numbering restarts per chunk, TASK-873).
    // Like ExternalSherpaBackend.configuredFamily, this reads the LOADED
    // backend's state: pre-load queries key on the SELECTED path instead
    // (TranscriptionBackendManager.gateInputsFor, TASK-873), so this getter
    // serves them only when no selection is known; the orchestrator consumes
    // it on the initialized backend.
    override val requiresVadAlignedChunking: Boolean
        get() = requiresVadAlignedChunkingFor(llmManager.getModelPath())

    // TASK-872 gate-round: the model's own "Speaker N:" lines ARE the
    // diarization; the GH #83 post-pass would double-label every cue.
    override val emitsNativeSpeakerLabels: Boolean
        get() = isVibeVoiceModel(llmManager.getModelPath())

    override val displayName: String = "Gemma (LiteRT-LM)"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = true

    override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> {
        val llmConfig = config as? BackendConfig.LiteRTConfig
            ?: return Result.failure(IllegalArgumentException("Invalid config type for LlmTranscriptionBackend"))

        val result = llmManager.initialize(context, llmConfig.modelPath)
        if (result.isSuccess && isVibeVoiceModel(llmConfig.modelPath)) {
            Log.i(TAG, "VibeVoice model detected at ${llmConfig.modelPath}; transcription routed to the multi-turn 3.47s-window path")
        }
        return result
    }

    override suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String): Result<TranscriptionResult> {
        if (isVibeVoiceModel(llmManager.getModelPath())) {
            // TASK-872: the VibeVoice multi-turn path; each 3.47s window is one
            // conversation turn, and the model emits "Speaker N: text" per turn.
            val windows = VibeVoiceWindowSlicer.sliceToWavWindows(samples, sampleRate)
            return llmManager.generateFromAudioWindows(prompt, windows).map { text ->
                TranscriptionResult(text = text)
            }
        }
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

    /** TASK-644: a native generation is in flight (the withWork bracket). */
    override fun isBusy(): Boolean = llmManager.isBusy()
}
