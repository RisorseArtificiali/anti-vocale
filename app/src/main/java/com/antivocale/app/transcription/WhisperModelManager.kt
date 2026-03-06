package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.R
import java.io.File

/**
 * Manages Whisper model discovery and validation.
 *
 * Whisper models are stored in app-specific storage and contain:
 * - encoder-decoder.int8.onnx (or similar naming)
 * - tokens.txt
 *
 * Supported variants:
 * - Small: ~610MB, good accuracy, recommended starting point
 * - Turbo: ~538MB, near large-v3 quality, best value
 * - Medium: ~1.8GB, very accurate, best for Italian
 */
object WhisperModelManager {

    private const val TAG = "WhisperModelManager"

    // Model directory name in app storage
    const val WHISPER_MODEL_DIR = "whisper"

    // Required model files for Whisper
    val REQUIRED_FILES = listOf(
        "tokens.txt"
    )

    // Possible encoder-decoder file names (exported for WhisperDownloader)
    // Note: Whisper models have SEPARATE encoder and decoder files
    val ENCODER_PATTERNS = listOf(
        "encoder.int8.onnx",
        "small-encoder.int8.onnx",
        "turbo-encoder.int8.onnx",
        "medium-encoder.int8.onnx",
        "base-encoder.int8.onnx",
        "tiny-encoder.int8.onnx",
        "encoder.onnx",
        "small-encoder.onnx",
        "turbo-encoder.onnx",
        "medium-encoder.onnx"
    )

    val DECODER_PATTERNS = listOf(
        "decoder.int8.onnx",
        "small-decoder.int8.onnx",
        "turbo-decoder.int8.onnx",
        "medium-decoder.int8.onnx",
        "base-decoder.int8.onnx",
        "tiny-decoder.int8.onnx",
        "decoder.onnx",
        "small-decoder.onnx",
        "turbo-decoder.onnx",
        "medium-decoder.onnx"
    )

    // Legacy patterns for backwards compatibility (combined encoder-decoder)
    val ENCODER_DECODER_PATTERNS = listOf(
        "encoder-decoder.int8.onnx",
        "tiny-encoder-decoder.int8.onnx",
        "base-encoder-decoder.int8.onnx",
        "small-encoder-decoder.int8.onnx",
        "encoder-decoder.onnx"
    )

    // Tokens file patterns
    val TOKENS_PATTERNS = listOf(
        "tokens.txt",
        "small-tokens.txt",
        "turbo-tokens.txt",
        "medium-tokens.txt",
        "base-tokens.txt",
        "tiny-tokens.txt"
    )

    /**
     * Available Whisper model variants.
     */
    enum class Variant(
        val titleResId: Int,
        val descriptionResId: Int,
        val dirName: String,
        val estimatedSizeMB: Long
    ) {
        SMALL(
            titleResId = R.string.whisper_small_title,
            descriptionResId = R.string.whisper_small_description,
            dirName = "sherpa-onnx-whisper-small",
            estimatedSizeMB = 610
        ),
        TURBO(
            titleResId = R.string.whisper_turbo_title,
            descriptionResId = R.string.whisper_turbo_description,
            dirName = "sherpa-onnx-whisper-turbo",
            estimatedSizeMB = 538
        ),
        MEDIUM(
            titleResId = R.string.whisper_medium_title,
            descriptionResId = R.string.whisper_medium_description,
            dirName = "sherpa-onnx-whisper-medium",
            estimatedSizeMB = 1842
        )
    }

    /**
     * Gets the directory where Whisper models are stored.
     */
    fun getModelStorageDir(context: Context): File {
        return File(context.filesDir, WHISPER_MODEL_DIR)
    }

    /**
     * Discovers available Whisper models in app storage.
     *
     * @param context Application context
     * @return List of valid model directories (each containing all required files)
     */
    fun discoverModels(context: Context): List<WhisperModel> {
        val models = mutableListOf<WhisperModel>()
        val storageDir = getModelStorageDir(context)

        if (!storageDir.exists()) {
            Log.d(TAG, "Model storage directory does not exist: ${storageDir.absolutePath}")
            return emptyList()
        }

        // Look for directories containing all required files
        storageDir.listFiles()?.filter { it.isDirectory }?.forEach { modelDir ->
            val model = validateModelDirectory(modelDir)
            if (model != null) {
                models.add(model)
            }
        }

        Log.i(TAG, "Discovered ${models.size} Whisper model(s)")
        return models
    }

    /**
     * Validates a model directory and returns a WhisperModel if valid.
     */
    fun validateModelDirectory(modelDir: File): WhisperModel? {
        if (!modelDir.isDirectory) return null

        // Check tokens.txt exists
        val tokensFile = TOKENS_PATTERNS
            .map { File(modelDir, it) }
            .firstOrNull { it.exists() }

        if (tokensFile == null) {
            Log.d(TAG, "Model directory ${modelDir.name} missing tokens.txt")
            return null
        }

        // Check for encoder file (separate encoder-decoder model)
        val encoderFile = ENCODER_PATTERNS
            .map { File(modelDir, it) }
            .firstOrNull { it.exists() }

        // Check for decoder file (separate encoder-decoder model)
        val decoderFile = DECODER_PATTERNS
            .map { File(modelDir, it) }
            .firstOrNull { it.exists() }

        // Check for combined encoder-decoder file (legacy format)
        val encoderDecoderFile = ENCODER_DECODER_PATTERNS
            .map { File(modelDir, it) }
            .firstOrNull { it.exists() }

        // Must have either: (separate encoder AND decoder) OR (combined encoder-decoder)
        val hasSeparateEncoderDecoder = encoderFile != null && decoderFile != null
        val hasCombinedEncoderDecoder = encoderDecoderFile != null

        if (!hasSeparateEncoderDecoder && !hasCombinedEncoderDecoder) {
            Log.d(TAG, "Model directory ${modelDir.name} missing encoder/decoder files")
            return null
        }

        // Calculate total model size
        val modelFiles = mutableListOf<File>()
        modelFiles.add(tokensFile)
        if (hasSeparateEncoderDecoder) {
            modelFiles.add(encoderFile!!)
            modelFiles.add(decoderFile!!)
        } else {
            modelFiles.add(encoderDecoderFile!!)
        }
        val totalSize = modelFiles.sumOf { it.length() }

        // Detect variant from directory name or file name
        val primaryFileName = when {
            hasSeparateEncoderDecoder -> encoderFile!!.name
            else -> encoderDecoderFile!!.name
        }
        val variant = detectVariant(modelDir.name, primaryFileName)

        return WhisperModel(
            name = modelDir.name,
            path = modelDir.absolutePath,
            sizeBytes = totalSize,
            variant = variant,
            isValid = true
        )
    }

    /**
     * Detects the Whisper variant from directory or file names.
     */
    private fun detectVariant(dirName: String, fileName: String): Variant? {
        val name = (dirName + fileName).lowercase()
        return when {
            name.contains("turbo") -> Variant.TURBO
            name.contains("medium") -> Variant.MEDIUM
            name.contains("small") -> Variant.SMALL
            else -> null  // Unknown variant
        }
    }

    /**
     * Checks if a valid Whisper model exists at the given path.
     */
    fun isValidModelPath(path: String): Boolean {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return false

        // Check tokens.txt
        val hasTokens = TOKENS_PATTERNS.any { File(dir, it).exists() }
        if (!hasTokens) return false

        // Check for separate encoder AND decoder files
        val hasEncoder = ENCODER_PATTERNS.any { File(dir, it).exists() }
        val hasDecoder = DECODER_PATTERNS.any { File(dir, it).exists() }
        val hasSeparateFiles = hasEncoder && hasDecoder

        // Or check for combined encoder-decoder file (legacy)
        val hasCombined = ENCODER_DECODER_PATTERNS.any { File(dir, it).exists() }

        return hasSeparateFiles || hasCombined
    }

    /**
     * Gets model info for display purposes.
     */
    fun getModelInfo(context: Context, modelPath: String): WhisperModel? {
        val dir = File(modelPath)
        return validateModelDirectory(dir)
    }

    /**
     * Deletes a model directory and all its contents.
     */
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

    /**
     * Gets the total size of all models in storage.
     */
    fun getTotalModelsSize(context: Context): Long {
        return discoverModels(context).sumOf { it.sizeBytes }
    }
}

/**
 * Represents a Whisper model.
 */
data class WhisperModel(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val variant: WhisperModelManager.Variant?,
    val isValid: Boolean
) {
    val sizeFormatted: String
        get() = formatSize(sizeBytes)

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
