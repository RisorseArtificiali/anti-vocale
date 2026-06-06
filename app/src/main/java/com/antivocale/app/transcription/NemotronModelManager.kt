package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.data.download.ResumeDownloadHelper
import java.io.File

/**
 * Manages Nemotron streaming ASR model discovery and validation.
 *
 * Nemotron models are stored in app-specific storage and contain:
 * - encoder.onnx
 * - encoder.onnx.data
 * - decoder_joint.onnx
 * - tokenizer.model
 */
object NemotronModelManager {

    private const val TAG = "NemotronModelManager"

    // Model directory name in app storage
    const val NEMOTRON_MODEL_DIR = "nemotron"

    // Required model files for Nemotron streaming model (sherpa-onnx format)
    val REQUIRED_FILES = listOf(
        "encoder.onnx",
        "encoder.onnx.data",
        "decoder.onnx",
        "joiner.onnx",
        "tokens.txt"
    )

    /**
     * Gets the directory where Nemotron models are stored.
     */
    fun getModelStorageDir(context: Context): File {
        return File(context.filesDir, NEMOTRON_MODEL_DIR)
    }

    /**
     * Discovers available Nemotron models in app storage.
     *
     * @param context Application context
     * @return List of valid model directories (each containing all required files)
     */
    fun discoverModels(context: Context): List<NemotronModel> {
        val models = mutableListOf<NemotronModel>()
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

        Log.i(TAG, "Discovered ${models.size} Nemotron model(s)")
        return models
    }

    /**
     * Validates a model directory and returns a NemotronModel if valid.
     */
    fun validateModelDirectory(modelDir: File): NemotronModel? {
        if (!modelDir.isDirectory) return null

        // Check all required files exist (ONNX files verified via .size sidecar)
        val missingFiles = REQUIRED_FILES.filter { requiredFile ->
            val file = File(modelDir, requiredFile)
            if (file.name.endsWith(".onnx") || file.name.endsWith(".onnx.data"))
                !ResumeDownloadHelper.isFileComplete(file)
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

        return NemotronModel(
            name = modelDir.name,
            path = modelDir.absolutePath,
            sizeBytes = totalSize,
            isValid = true
        )
    }

    /**
     * Checks if a valid Nemotron model exists at the given path.
     */
    fun isValidModelPath(path: String): Boolean {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return false

        return REQUIRED_FILES.all { requiredFile ->
            val file = File(dir, requiredFile)
            if (file.name.endsWith(".onnx") || file.name.endsWith(".onnx.data"))
                ResumeDownloadHelper.isFileComplete(file)
            else file.exists()
        }
    }

    /**
     * Gets model info for display purposes.
     */
    fun getModelInfo(context: Context, modelPath: String): NemotronModel? {
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
 * Represents a Nemotron streaming ASR model.
 */
data class NemotronModel(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val isValid: Boolean
) {
    val sizeFormatted: String
        get() = com.antivocale.app.util.formatFileSize(sizeBytes)
}
