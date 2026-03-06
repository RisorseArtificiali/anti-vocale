package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages Parakeet TDT model discovery and validation.
 *
 * Parakeet TDT models are stored in app-specific storage and contain:
 * - encoder.int8.onnx
 * - decoder.int8.onnx
 * - joiner.int8.onnx
 * - tokens.txt
 */
object ParakeetModelManager {

    private const val TAG = "ParakeetModelManager"

    // Model directory name in app storage
    const val PARAKEET_MODEL_DIR = "parakeet-tdt"

    // Required model files for Parakeet TDT (transducer model)
    val REQUIRED_FILES = listOf(
        "encoder.int8.onnx",
        "decoder.int8.onnx",
        "joiner.int8.onnx",
        "tokens.txt"
    )

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

        // Check all required files exist
        val missingFiles = REQUIRED_FILES.filter { requiredFile ->
            !File(modelDir, requiredFile).exists()
        }

        if (missingFiles.isNotEmpty()) {
            Log.d(TAG, "Model directory ${modelDir.name} missing files: ${missingFiles.joinToString()}")
            return null
        }

        // Calculate total model size
        val totalSize = REQUIRED_FILES.sumOf { requiredFile ->
            File(modelDir, requiredFile).length()
        }

        return ParakeetModel(
            name = modelDir.name,
            path = modelDir.absolutePath,
            sizeBytes = totalSize,
            isValid = true
        )
    }

    /**
     * Checks if a valid Parakeet model exists at the given path.
     */
    fun isValidModelPath(path: String): Boolean {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return false

        return REQUIRED_FILES.all { requiredFile ->
            File(dir, requiredFile).exists()
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
}

/**
 * Represents a Parakeet TDT model.
 */
data class ParakeetModel(
    val name: String,
    val path: String,
    val sizeBytes: Long,
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
