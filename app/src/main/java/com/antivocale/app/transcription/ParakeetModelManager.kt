package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.R
import com.antivocale.app.data.download.ResumeDownloadHelper
import java.io.File

/**
 * Manages Parakeet TDT model discovery and validation.
 *
 * Parakeet TDT models are stored in app-specific storage and contain:
 * - encoder.int8.onnx
 * - decoder.int8.onnx
 * - joiner.int8.onnx
 * - tokens.txt
 *
 * Two variants are supported:
 * - [Variant.SMOOTHQUANT] — recommended, SmoothQuant-quantized (pantinor namespace)
 * - [Variant.STOCK_INT8] — lighter fallback, stock int8 (csukuangfj namespace)
 *
 * Both variants ship identical file names and use the same sherpa transducer config.
 * Unlike Qwen3-ASR, the user does NOT pick an active variant: [resolveActiveModelPath]
 * auto-selects the best available one at transcription time (prefer SmoothQuant).
 */
object ParakeetModelManager {

    private const val TAG = "ParakeetModelManager"

    // Model directory name in app storage
    const val PARAKEET_MODEL_DIR = "parakeet-tdt"

    // Required model files for Parakeet TDT (transducer model) — identical for both variants.
    val REQUIRED_FILES = listOf(
        "encoder.int8.onnx",
        "decoder.int8.onnx",
        "joiner.int8.onnx",
        "tokens.txt"
    )

    enum class Variant(
        override val titleResId: Int,
        override val descriptionResId: Int,
        override val dirName: String,
        override val estimatedSizeMB: Long,
        override val supportedLanguageCodes: Set<String> = emptySet()
    ) : ModelVariant {
        SMOOTHQUANT(
            titleResId = R.string.parakeet_smoothquant_title,
            descriptionResId = R.string.parakeet_smoothquant_description,
            dirName = "parakeet-tdt-0.6b-v3-smoothquant",
            estimatedSizeMB = 862,
            supportedLanguageCodes = Language.PARAKEET
        ),
        STOCK_INT8(
            titleResId = R.string.parakeet_stock_title,
            descriptionResId = R.string.parakeet_stock_description,
            dirName = "parakeet-tdt-0.6b-v3-int8",
            estimatedSizeMB = 464,
            supportedLanguageCodes = Language.PARAKEET
        )
    }

    /**
     * The set of currently-known Parakeet variant directory names.
     *
     * Used by [cleanOrphanedModelDirs] to identify stranded old-variant directories
     * that are safe to reclaim. A directory whose name is in this set is never deleted.
     */
    val validModelDirNames: Set<String>
        get() = Variant.entries.map { it.dirName }.toSet()

    /**
     * Gets the directory where Parakeet models are stored.
     */
    fun getModelStorageDir(context: Context): File {
        return File(context.filesDir, PARAKEET_MODEL_DIR)
    }

    /**
     * Discovers available Parakeet models in app storage.
     *
     * @param context Application context
     * @return List of valid model directories (each containing all required files)
     */
    fun discoverModels(context: Context): List<ParakeetModel> {
        val models = mutableListOf<ParakeetModel>()
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

        Log.i(TAG, "Discovered ${models.size} Parakeet model(s)")
        return models
    }

    /**
     * Validates a model directory and returns a ParakeetModel if valid.
     */
    fun validateModelDirectory(modelDir: File): ParakeetModel? {
        if (!modelDir.isDirectory) return null

        // Check all required files exist (ONNX files verified via .size sidecar)
        val missingFiles = REQUIRED_FILES.filter { requiredFile ->
            val file = File(modelDir, requiredFile)
            if (file.name.endsWith(".onnx")) !ResumeDownloadHelper.isFileComplete(file)
            else !file.exists()
        }

        if (missingFiles.isNotEmpty()) {
            Log.d(TAG, "Model directory ${modelDir.name} missing files: ${missingFiles.joinToString()}")
            return null
        }

        // Calculate total model size
        val totalSize = REQUIRED_FILES.sumOf { requiredFile ->
            File(modelDir, requiredFile).length()
        }

        val variant = detectVariant(modelDir.name)
        return ParakeetModel(
            name = modelDir.name,
            path = modelDir.absolutePath,
            sizeBytes = totalSize,
            variant = variant
        )
    }

    /**
     * Infers the variant from a model directory name, or null if unrecognized.
     */
    fun detectVariant(dirName: String): Variant? {
        val name = dirName.lowercase()
        return when {
            name.contains("smoothquant") -> Variant.SMOOTHQUANT
            name.contains("int8") -> Variant.STOCK_INT8
            else -> null
        }
    }

    /**
     * Checks if a valid Parakeet model exists at the given path.
     */
    fun isValidModelPath(path: String): Boolean {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return false

        return REQUIRED_FILES.all { requiredFile ->
            val file = File(dir, requiredFile)
            if (file.name.endsWith(".onnx")) ResumeDownloadHelper.isFileComplete(file)
            else file.exists()
        }
    }

    /**
     * Gets model info for display purposes.
     */
    fun getModelInfo(context: Context, modelPath: String): ParakeetModel? {
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

    /**
     * Resolves the best-available Parakeet model directory path using auto-fallback.
     *
     * Preference order:
     * 1. [Variant.SMOOTHQUANT] if its directory validates.
     * 2. [Variant.STOCK_INT8] if its directory validates.
     * 3. The saved preference [fallbackPath] if it validates.
     *
     * This is the Parakeet-specific behavior: unlike Qwen3-ASR (user-selected variant),
     * Parakeet silently prefers SmoothQuant and falls back to Stock int8 at transcription
     * / backend-load time. Returns null if no usable model is found.
     */
    fun resolveActiveModelPath(context: Context, fallbackPath: String? = null): String? {
        val storageDir = getModelStorageDir(context)
        // Preference order = Variant declaration order (SMOOTHQUANT, then STOCK_INT8).
        // Reuses validateModelDirectory() as the single source of truth for "is this a complete model".
        for (variant in Variant.entries) {
            val dir = File(storageDir, variant.dirName)
            if (validateModelDirectory(dir) != null) return dir.absolutePath
        }
        if (fallbackPath != null && isValidModelPath(fallbackPath)) return fallbackPath
        return null
    }
}

/**
 * Represents a Parakeet TDT model.
 */
data class ParakeetModel(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val variant: ParakeetModelManager.Variant? = null
) {
    val sizeFormatted: String
        get() = com.antivocale.app.util.formatFileSize(sizeBytes)
}
