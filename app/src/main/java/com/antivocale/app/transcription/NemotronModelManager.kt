package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.data.download.ResumeDownloadHelper
import java.io.File

/**
 * Manages Nemotron streaming ASR model discovery and validation.
 *
 * Nemotron models are stored in app-specific storage (int8 format) and contain:
 * - encoder.int8.onnx
 * - decoder.int8.onnx
 * - joiner.int8.onnx
 * - tokens.txt
 */
object NemotronModelManager {

    private const val TAG = "NemotronModelManager"

    // Model directory name in app storage
    const val NEMOTRON_MODEL_DIR = "nemotron"

    // Required model files for Nemotron streaming model (int8 sherpa-onnx format)
    val REQUIRED_FILES = listOf(
        "encoder.int8.onnx",
        "decoder.int8.onnx",
        "joiner.int8.onnx",
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

        // Single pass: validate completeness and accumulate size
        var totalSize = 0L
        for (requiredFile in REQUIRED_FILES) {
            val file = File(modelDir, requiredFile)
            val complete = if (requiresSidecarCheck(file.name))
                ResumeDownloadHelper.isFileComplete(file)
            else file.exists()
            if (!complete) {
                Log.d(TAG, "Model directory ${modelDir.name} missing/incomplete: $requiredFile")
                return null
            }
            totalSize += file.length()
        }

        return NemotronModel(
            name = modelDir.name,
            path = modelDir.absolutePath,
            sizeBytes = totalSize
        )
    }

    /**
     * Checks if a valid Nemotron model exists at the given path.
     */
    fun isValidModelPath(path: String): Boolean =
        isValidModelDir(File(path))

    /**
     * Gets model info for display purposes.
     */
    fun getModelInfo(modelPath: String): NemotronModel? =
        validateModelDirectory(File(modelPath))

    /**
     * Checks whether a directory contains all required model files.
     */
    private fun isValidModelDir(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        return REQUIRED_FILES.all { requiredFile ->
            val file = File(dir, requiredFile)
            if (requiresSidecarCheck(file.name))
                ResumeDownloadHelper.isFileComplete(file)
            else file.exists()
        }
    }

    /** ONNX files are downloaded via resumable HTTP and verified via .size sidecar. */
    private fun requiresSidecarCheck(fileName: String): Boolean =
        fileName.endsWith(".onnx") || fileName.endsWith(".onnx.data")

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
    val sizeBytes: Long
) {
    val sizeFormatted: String
        get() = com.antivocale.app.util.formatFileSize(sizeBytes)
}
