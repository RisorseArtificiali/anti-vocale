package com.antivocale.app.transcription

import com.antivocale.app.R

enum class ArchitectureType {
    ENCODER_DECODER,
    TRANSDUCER,
    ENCODER_ONLY_CTC,
    LLM
}

data class ModelInfo(
    val architectureType: ArchitectureType,
    val maxAudioDuration: Int?,
    val recommendedThreads: IntRange,
    val quantizationLevel: String?,
    val isArm64Only: Boolean,
    val supportsProgressiveTranscription: Boolean,
    val vadRecommended: Boolean,
    val benchmarkWer: Float?,
    val relativeSpeed: String?,
    val bestFor: Int,
    val performanceNotes: Int,
)

object ModelInfoProvider {

    private val infoMap: Map<String, ModelInfo> by lazy {
        buildMap {
            put("sherpa-onnx-whisper-small", ModelInfo(
                architectureType = ArchitectureType.ENCODER_DECODER,
                maxAudioDuration = 30,
                recommendedThreads = 2..4,
                quantizationLevel = null,
                isArm64Only = false,
                supportsProgressiveTranscription = true,
                vadRecommended = true,
                benchmarkWer = null,
                relativeSpeed = null,
                bestFor = R.string.model_info_best_for_whisper_small,
                performanceNotes = R.string.model_info_notes_whisper_small
            ))

            put("sherpa-onnx-whisper-turbo", ModelInfo(
                architectureType = ArchitectureType.ENCODER_DECODER,
                maxAudioDuration = 30,
                recommendedThreads = 4..6,
                quantizationLevel = null,
                isArm64Only = false,
                supportsProgressiveTranscription = true,
                vadRecommended = true,
                benchmarkWer = 6.3f,
                relativeSpeed = "3x",
                bestFor = R.string.model_info_best_for_whisper_turbo,
                performanceNotes = R.string.model_info_notes_whisper_turbo
            ))

            put("sherpa-onnx-whisper-medium", ModelInfo(
                architectureType = ArchitectureType.ENCODER_DECODER,
                maxAudioDuration = 30,
                recommendedThreads = 4..6,
                quantizationLevel = null,
                isArm64Only = false,
                supportsProgressiveTranscription = true,
                vadRecommended = true,
                benchmarkWer = null,
                relativeSpeed = null,
                bestFor = R.string.model_info_best_for_whisper_medium,
                performanceNotes = R.string.model_info_notes_whisper_medium
            ))

            put("sherpa-onnx-whisper-distil-large-v3-it", ModelInfo(
                architectureType = ArchitectureType.ENCODER_DECODER,
                maxAudioDuration = 30,
                recommendedThreads = 4..6,
                quantizationLevel = "INT8",
                isArm64Only = false,
                supportsProgressiveTranscription = true,
                vadRecommended = true,
                benchmarkWer = 4.3f,
                relativeSpeed = "1x (Baseline)",
                bestFor = R.string.model_info_best_for_distil_it,
                performanceNotes = R.string.model_info_notes_distil_it
            ))

            put("sherpa-onnx-qwen3-asr-0.6b-int8", ModelInfo(
                architectureType = ArchitectureType.ENCODER_ONLY_CTC,
                maxAudioDuration = 30,
                recommendedThreads = 4..6,
                quantizationLevel = "INT8",
                isArm64Only = true,
                supportsProgressiveTranscription = false,
                vadRecommended = true,
                benchmarkWer = 12.2f,
                relativeSpeed = null,
                bestFor = R.string.model_info_best_for_qwen3,
                performanceNotes = R.string.model_info_notes_qwen3
            ))

            put("parakeet-tdt", ModelInfo(
                architectureType = ArchitectureType.TRANSDUCER,
                maxAudioDuration = null,
                recommendedThreads = 6..8,
                quantizationLevel = "INT8",
                isArm64Only = true,
                supportsProgressiveTranscription = false,
                vadRecommended = false,
                benchmarkWer = 5.4f,
                relativeSpeed = "17.6x",
                bestFor = R.string.model_info_best_for_parakeet,
                performanceNotes = R.string.model_info_notes_parakeet
            ))

            put("gemma-4-gguf-q4km", ModelInfo(
                architectureType = ArchitectureType.LLM,
                maxAudioDuration = null,
                recommendedThreads = 4..6,
                quantizationLevel = "Q4_K_M",
                isArm64Only = false,
                supportsProgressiveTranscription = false,
                vadRecommended = false,
                benchmarkWer = null,
                relativeSpeed = null,
                bestFor = R.string.model_info_best_for_gemma,
                performanceNotes = R.string.model_info_notes_gemma
            ))

            put("gemma-4-gguf-q5km", ModelInfo(
                architectureType = ArchitectureType.LLM,
                maxAudioDuration = null,
                recommendedThreads = 4..6,
                quantizationLevel = "Q5_K_M",
                isArm64Only = false,
                supportsProgressiveTranscription = false,
                vadRecommended = false,
                benchmarkWer = null,
                relativeSpeed = null,
                bestFor = R.string.model_info_best_for_gemma,
                performanceNotes = R.string.model_info_notes_gemma
            ))

            put("gemma-4-gguf-q8", ModelInfo(
                architectureType = ArchitectureType.LLM,
                maxAudioDuration = null,
                recommendedThreads = 6..8,
                quantizationLevel = "Q8_0",
                isArm64Only = false,
                supportsProgressiveTranscription = false,
                vadRecommended = false,
                benchmarkWer = null,
                relativeSpeed = null,
                bestFor = R.string.model_info_best_for_gemma,
                performanceNotes = R.string.model_info_notes_gemma
            ))

            put("gemma-4-e2b", ModelInfo(
                architectureType = ArchitectureType.LLM,
                maxAudioDuration = null,
                recommendedThreads = 4..6,
                quantizationLevel = null,
                isArm64Only = false,
                supportsProgressiveTranscription = false,
                vadRecommended = false,
                benchmarkWer = null,
                relativeSpeed = null,
                bestFor = R.string.model_info_best_for_gemma,
                performanceNotes = R.string.model_info_notes_gemma
            ))

            put("gemma-4-e4b", ModelInfo(
                architectureType = ArchitectureType.LLM,
                maxAudioDuration = null,
                recommendedThreads = 6..8,
                quantizationLevel = null,
                isArm64Only = false,
                supportsProgressiveTranscription = false,
                vadRecommended = false,
                benchmarkWer = null,
                relativeSpeed = null,
                bestFor = R.string.model_info_best_for_gemma,
                performanceNotes = R.string.model_info_notes_gemma
            ))

            put("gemma-3n-e2b", ModelInfo(
                architectureType = ArchitectureType.LLM,
                maxAudioDuration = null,
                recommendedThreads = 4..6,
                quantizationLevel = "INT4",
                isArm64Only = false,
                supportsProgressiveTranscription = false,
                vadRecommended = false,
                benchmarkWer = null,
                relativeSpeed = null,
                bestFor = R.string.model_info_best_for_gemma,
                performanceNotes = R.string.model_info_notes_gemma
            ))

            put("gemma-3n-e4b", ModelInfo(
                architectureType = ArchitectureType.LLM,
                maxAudioDuration = null,
                recommendedThreads = 6..8,
                quantizationLevel = "INT4",
                isArm64Only = false,
                supportsProgressiveTranscription = false,
                vadRecommended = false,
                benchmarkWer = null,
                relativeSpeed = null,
                bestFor = R.string.model_info_best_for_gemma,
                performanceNotes = R.string.model_info_notes_gemma
            ))
        }
    }

    fun getInfo(variant: ModelVariant): ModelInfo? = infoMap[variant.dirName]

    fun getInfoByDirName(dirName: String): ModelInfo? = infoMap[dirName]
}
