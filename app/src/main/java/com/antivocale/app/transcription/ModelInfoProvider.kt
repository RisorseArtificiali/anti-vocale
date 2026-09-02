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
    /**
     * Hard per-pass audio cap (GH #49 display fact). Catalog-backed models carry
     * it in `models_catalog.json` (`flags.maxAudioDurationSeconds`); only the
     * non-catalog Gemma variants set it here. null = no known limit.
     */
    val maxAudioDuration: Int? = null,
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
        // Shared across the storage-dir and the catalog variant dir-names.
        val parakeetInfo = ModelInfo(
            architectureType = ArchitectureType.TRANSDUCER,
            recommendedThreads = 6..8,
            quantizationLevel = "INT8",
            isArm64Only = true,
            supportsProgressiveTranscription = false,
            vadRecommended = false,
            benchmarkWer = 5.4f,
            relativeSpeed = "17.6x",
            bestFor = R.string.model_info_best_for_parakeet,
            performanceNotes = R.string.model_info_notes_parakeet
        )
        val nemotronInfo = ModelInfo(
            architectureType = ArchitectureType.TRANSDUCER,
            recommendedThreads = 4..6,
            quantizationLevel = "INT8",
            isArm64Only = true,
            supportsProgressiveTranscription = true,
            vadRecommended = false,
            benchmarkWer = null,
            relativeSpeed = null,
            bestFor = R.string.model_info_best_for_nemotron,
            performanceNotes = R.string.model_info_notes_nemotron
        )

        buildMap {
            put("sherpa-onnx-whisper-small", ModelInfo(
                architectureType = ArchitectureType.ENCODER_DECODER,
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

            // Both variant dir-names resolve to the same per-model info, so the
            // section-level overlay and the per-variant cards share it. (The catalog
            // storage dir "parakeet-tdt" is NOT a key: since TASK-364 no production
            // lookup keys by storage dir.)
            put("parakeet-tdt-0.6b-v3-smoothquant", parakeetInfo)
            put("parakeet-tdt-0.6b-v3-int8", parakeetInfo)

            // Nemotron single-variant: key is the catalog variant dir-name.
            put("nemotron-3.5-asr-streaming-0.6b-1120ms-int8", nemotronInfo)

            put("gigaam-v3", ModelInfo(
                architectureType = ArchitectureType.TRANSDUCER,
                recommendedThreads = 4..6,
                quantizationLevel = "INT8",
                isArm64Only = true,
                supportsProgressiveTranscription = false,
                vadRecommended = false,
                benchmarkWer = null,
                relativeSpeed = null,
                bestFor = R.string.model_info_best_for_gigaam,
                performanceNotes = R.string.model_info_notes_gigaam
            ))

            // Shared base for every Gemma variant: all are LLMs with the same hard
            // 30s audio cap (GH #49); per-variant entries override threads/quant.
            val gemmaInfoBase = ModelInfo(
                architectureType = ArchitectureType.LLM,
                maxAudioDuration = 30,
                recommendedThreads = 4..6,
                quantizationLevel = null,
                isArm64Only = false,
                supportsProgressiveTranscription = false,
                vadRecommended = false,
                benchmarkWer = null,
                relativeSpeed = null,
                bestFor = R.string.model_info_best_for_gemma,
                performanceNotes = R.string.model_info_notes_gemma
            )

            put("gemma-4-gguf-q4km", gemmaInfoBase.copy(
                quantizationLevel = "Q4_K_M"))

            put("gemma-4-gguf-q5km", gemmaInfoBase.copy(
                quantizationLevel = "Q5_K_M"))

            put("gemma-4-gguf-q8", gemmaInfoBase.copy(
                quantizationLevel = "Q8_0"))

            put("gemma-4-e2b", gemmaInfoBase.copy())

            put("gemma-4-e4b", gemmaInfoBase.copy(
                recommendedThreads = 6..8))

            put("gemma-3n-e2b", gemmaInfoBase.copy(
                quantizationLevel = "INT4"))

            put("gemma-3n-e4b", gemmaInfoBase.copy(
                recommendedThreads = 6..8, quantizationLevel = "INT4"))
        }
    }

    fun getInfo(variant: ModelVariant): ModelInfo? = infoMap[variant.dirName]

    /**
     * String-keyed seam for the catalog-parity tests: they pin that every
     * catalog variant dir-name resolves here. Production resolves through
     * [getInfo] (the ModelVariant enum); no production caller keys by raw
     * string since TASK-364 moved the audio-limit join onto catalog flags.
     */
    @androidx.annotation.VisibleForTesting
    fun getInfoByDirName(dirName: String): ModelInfo? = infoMap[dirName]
}
