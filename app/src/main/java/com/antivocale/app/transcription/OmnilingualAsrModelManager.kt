package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.R
import com.antivocale.app.data.download.ResumeDownloadHelper
import java.io.File

/**
 * Model manager for Meta's Omnilingual ASR v2 (exported to sherpa-onnx by csukuangfj2).
 *
 * CTC architecture: the model is just an encoder + a tokens.txt vocabulary (no decoder/joiner,
 * no conv_frontend, no SentencePiece dir). Output is character-level — typically without
 * punctuation or casing — so quality is lower than transducer models (Parakeet) but coverage
 * spans 1600+ languages. Intended as an additional backend for exotic languages, not a
 * replacement for Parakeet/Whisper on common languages.
 */
object OmnilingualAsrModelManager {

    private const val TAG = "OmnilingualAsrModelManager"
    const val OMNILINGUAL_ASR_MODEL_DIR = "omnilingual-asr"

    val ENCODER_PATTERNS = listOf(
        "encoder.int8.onnx", "encoder.onnx"
    )

    val TOKENS_PATTERNS = listOf(
        "tokens.txt"
    )

    private const val TOKENS_FILE_NAME = "tokens.txt"

    enum class Variant(
        override val titleResId: Int,
        override val descriptionResId: Int,
        override val dirName: String,
        override val estimatedSizeMB: Long,
        /** 1600+ languages cannot be enumerated; leave empty so no flag grid is shown. */
        override val supportedLanguageCodes: Set<String> = emptySet()
    ) : ModelVariant {
        OMNILINGUAL_ASR_300M(
            titleResId = R.string.omnilingual_asr_300m_title,
            descriptionResId = R.string.omnilingual_asr_300m_description,
            dirName = "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05",
            estimatedSizeMB = 235
        )
    }

    fun getModelStorageDir(context: Context): File = File(context.filesDir, OMNILINGUAL_ASR_MODEL_DIR)

    fun discoverModels(context: Context): List<OmnilingualAsrModel> {
        val models = mutableListOf<OmnilingualAsrModel>()
        val storageDir = getModelStorageDir(context)
        if (!storageDir.exists()) return emptyList()
        storageDir.listFiles()?.filter { it.isDirectory }?.forEach { modelDir ->
            val model = validateModelDirectory(modelDir)
            if (model != null) models.add(model)
        }
        Log.i(TAG, "Discovered ${models.size} Omnilingual ASR model(s)")
        return models
    }

    fun isValidModelDir(modelDir: File): Boolean {
        if (!modelDir.isDirectory) return false
        if (ENCODER_PATTERNS.map { File(modelDir, it) }.firstOrNull { ResumeDownloadHelper.isFileComplete(it) } == null) return false
        return TOKENS_PATTERNS.map { File(modelDir, it) }.firstOrNull { ResumeDownloadHelper.isFileComplete(it) } != null
    }

    fun validateModelDirectory(modelDir: File): OmnilingualAsrModel? {
        if (!isValidModelDir(modelDir)) return null

        val encoderFile = ENCODER_PATTERNS.map { File(modelDir, it) }.first { it.exists() }
        val tokensFile = File(modelDir, TOKENS_FILE_NAME)

        val totalSize = encoderFile.length() + tokensFile.length()

        val variant = detectVariant(modelDir.name)
        return OmnilingualAsrModel(
            name = modelDir.name,
            path = modelDir.absolutePath,
            sizeBytes = totalSize,
            variant = variant,
            encoderPath = encoderFile.absolutePath,
            tokensPath = tokensFile.absolutePath
        )
    }

    fun detectVariant(dirName: String): Variant? {
        val name = dirName.lowercase()
        return when {
            name.contains("300m") || name.contains("300-m") -> Variant.OMNILINGUAL_ASR_300M
            else -> null
        }
    }

    fun isValidModelPath(path: String): Boolean = isValidModelDir(File(path))

    fun getModelInfo(context: Context, modelPath: String): OmnilingualAsrModel? = validateModelDirectory(File(modelPath))

    fun deleteModel(modelPath: String): Boolean {
        val dir = File(modelPath)
        if (!dir.exists()) return true
        return try {
            dir.deleteRecursively()
            Log.i(TAG, "Deleted model: $modelPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model: $modelPath", e)
            false
        }
    }

    fun getTotalModelsSize(context: Context): Long = discoverModels(context).sumOf { it.sizeBytes }
}

data class OmnilingualAsrModel(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val variant: OmnilingualAsrModelManager.Variant?,
    /** Resolved file paths — always non-null when created via validateModelDirectory(). */
    val encoderPath: String = "",
    val tokensPath: String = ""
) {
    val sizeFormatted: String get() = com.antivocale.app.util.formatFileSize(sizeBytes)
}
