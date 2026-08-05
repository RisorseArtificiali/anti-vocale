package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.data.download.ResumeDownloadHelper
import java.io.File

/**
 * Manages GigaAM v3 model discovery and validation.
 *
 * GigaAM v3 (Sber, MIT) is an end-to-end Russian ASR transducer converted to the
 * sherpa-onnx format (nemo_transducer). Files:
 * - gigaam_v3_e2e_rnnt_encoder_int8.onnx
 * - gigaam_v3_e2e_rnnt_decoder.onnx
 * - gigaam_v3_e2e_rnnt_joint.onnx
 * - gigaam_v3_e2e_rnnt_tokens.txt
 *
 * Single-variant (like Nemotron). Mirrors [NemotronModelManager].
 */
object GigaAmModelManager {

    private const val TAG = "GigaAmModelManager"

    // Model directory name in app storage
    const val GIGAAM_MODEL_DIR = "gigaam-v3"

    // Required model files for GigaAM v3 (nemo_transducer, sherpa-onnx format).
    val REQUIRED_FILES = listOf(
        "gigaam_v3_e2e_rnnt_encoder_int8.onnx",
        "gigaam_v3_e2e_rnnt_decoder.onnx",
        "gigaam_v3_e2e_rnnt_joint.onnx",
        "gigaam_v3_e2e_rnnt_tokens.txt"
    )

    /** Approximate total size in MB (~310MB of ONNX + tokens). */
    const val ESTIMATED_SIZE_MB = 326L

    /**
     * The set of currently-known GigaAM variant directory names.
     *
     * GigaAM is single-variant, so this has exactly one entry. Used by
     * [cleanOrphanedModelDirs] to identify stranded old-format directories.
     */
    val validModelDirNames: Set<String>
        get() = setOf(GIGAAM_MODEL_DIR)

    /**
     * Gets the directory where GigaAM models are stored.
     */
    fun getModelStorageDir(context: Context): File {
        return File(context.filesDir, GIGAAM_MODEL_DIR)
    }

    /**
     * Discovers available GigaAM models in app storage.
     *
     * @param context Application context
     * @return List of valid model directories (each containing all required files)
     */
    fun discoverModels(context: Context): List<GigaAmModel> {
        val models = mutableListOf<GigaAmModel>()
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

        Log.i(TAG, "Discovered ${models.size} GigaAM model(s)")
        return models
    }

    /**
     * Validates a model directory and returns a GigaAmModel if valid.
     */
    fun validateModelDirectory(modelDir: File): GigaAmModel? {
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

        return GigaAmModel(
            name = modelDir.name,
            path = modelDir.absolutePath,
            sizeBytes = totalSize
        )
    }

    /**
     * Checks if a valid GigaAM model exists at the given path.
     */
    fun isValidModelPath(path: String): Boolean =
        isValidModelDir(File(path))

    /**
     * Gets model info for display purposes.
     */
    fun getModelInfo(modelPath: String): GigaAmModel? =
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
        fileName.endsWith(".onnx")

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
 * Represents a GigaAM v3 model.
 */
data class GigaAmModel(
    val name: String,
    val path: String,
    val sizeBytes: Long
) {
    val sizeFormatted: String
        get() = com.antivocale.app.util.formatFileSize(sizeBytes)
}
