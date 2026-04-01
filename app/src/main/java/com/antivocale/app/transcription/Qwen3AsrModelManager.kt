package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.R
import java.io.File

object Qwen3AsrModelManager {

    private const val TAG = "Qwen3AsrModelManager"
    const val QWEN3_ASR_MODEL_DIR = "qwen3-asr"

    val CONV_FRONTEND_PATTERNS = listOf(
        "conv_frontend.onnx", "conv_frontend.int8.onnx"
    )

    val ENCODER_PATTERNS = listOf(
        "encoder.int8.onnx", "encoder.onnx"
    )

    val DECODER_PATTERNS = listOf(
        "decoder.int8.onnx", "decoder.onnx"
    )

    val TOKENIZER_PATTERNS = listOf("tokenizer")

    private const val TOKENIZER_DIR_NAME = "tokenizer"

    enum class Variant(
        val titleResId: Int,
        val descriptionResId: Int,
        val dirName: String,
        val estimatedSizeMB: Long
    ) {
        QWEN3_ASR_0_6B(
            titleResId = R.string.qwen3_asr_0_6b_title,
            descriptionResId = R.string.qwen3_asr_0_6b_description,
            dirName = "sherpa-onnx-qwen3-asr-0.6b-int8",
            estimatedSizeMB = 938
        )
    }

    fun getModelStorageDir(context: Context): File = File(context.filesDir, QWEN3_ASR_MODEL_DIR)

    fun discoverModels(context: Context): List<Qwen3AsrModel> {
        val models = mutableListOf<Qwen3AsrModel>()
        val storageDir = getModelStorageDir(context)
        if (!storageDir.exists()) return emptyList()
        storageDir.listFiles()?.filter { it.isDirectory }?.forEach { modelDir ->
            val model = validateModelDirectory(modelDir)
            if (model != null) models.add(model)
        }
        Log.i(TAG, "Discovered ${models.size} Qwen3 ASR model(s)")
        return models
    }

    fun isValidModelDir(modelDir: File): Boolean {
        if (!modelDir.isDirectory) return false
        val tokenizerDir = File(modelDir, TOKENIZER_DIR_NAME)
        if (!tokenizerDir.exists() || !tokenizerDir.isDirectory) return false
        if (CONV_FRONTEND_PATTERNS.map { File(modelDir, it) }.firstOrNull { it.exists() } == null) return false
        if (ENCODER_PATTERNS.map { File(modelDir, it) }.firstOrNull { it.exists() } == null) return false
        return DECODER_PATTERNS.map { File(modelDir, it) }.firstOrNull { it.exists() } != null
    }

    fun validateModelDirectory(modelDir: File): Qwen3AsrModel? {
        if (!isValidModelDir(modelDir)) return null

        val convFrontendFile = CONV_FRONTEND_PATTERNS.map { File(modelDir, it) }.first { it.exists() }
        val encoderFile = ENCODER_PATTERNS.map { File(modelDir, it) }.first { it.exists() }
        val decoderFile = DECODER_PATTERNS.map { File(modelDir, it) }.first { it.exists() }
        val tokenizerDir = File(modelDir, TOKENIZER_DIR_NAME)

        val totalSize = convFrontendFile.length() + encoderFile.length() + decoderFile.length() +
            tokenizerDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        val variant = detectVariant(modelDir.name)
        return Qwen3AsrModel(
            name = modelDir.name,
            path = modelDir.absolutePath,
            sizeBytes = totalSize,
            variant = variant,
            isValid = true
        )
    }

    fun detectVariant(dirName: String): Variant? {
        val name = dirName.lowercase()
        return when {
            name.contains("0.6b") -> Variant.QWEN3_ASR_0_6B
            else -> null
        }
    }

    fun isValidModelPath(path: String): Boolean = isValidModelDir(File(path))

    fun getModelInfo(context: Context, modelPath: String): Qwen3AsrModel? = validateModelDirectory(File(modelPath))

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

data class Qwen3AsrModel(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val variant: Qwen3AsrModelManager.Variant?,
    val isValid: Boolean
) {
    val sizeFormatted: String get() = com.antivocale.app.util.formatFileSize(sizeBytes)
}
