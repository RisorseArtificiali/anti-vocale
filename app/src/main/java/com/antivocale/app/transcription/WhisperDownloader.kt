package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.data.download.DownloadConfig
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.data.download.ResumeDownloadHelper
import com.antivocale.app.data.download.downloadWithRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads Whisper models for sherpa-onnx from HuggingFace.
 *
 * All models are downloaded as individual files (no tar.bz2 extraction),
 * which is significantly faster on mobile devices.
 *
 * Model source: https://huggingface.co/pantinor/
 * Model sizes (int8 quantized):
 * - Small: ~610MB
 * - Turbo: ~538MB (large-v3-turbo distillation)
 * - Medium: ~1.8GB
 * - Distil Large V3 (Italian): ~939MB
 *
 * Languages: Multilingual (99+ languages including excellent Italian support)
 *
 * Features:
 * - Resume: picks up interrupted downloads via HTTP Range + partial files
 * - Retry: up to 3 attempts with exponential backoff
 * - On cancel: keeps partial files for resume
 */
object WhisperDownloader {

    private const val TAG = "WhisperDownloader"

    private val MODEL_NAMES = mapOf(
        WhisperModelManager.Variant.SMALL to "sherpa-onnx-whisper-small",
        WhisperModelManager.Variant.TURBO to "sherpa-onnx-whisper-turbo",
        WhisperModelManager.Variant.MEDIUM to "sherpa-onnx-whisper-medium",
        WhisperModelManager.Variant.DISTIL_LARGE_V3 to "sherpa-onnx-whisper-distil-large-v3-it"
    )

    /** Returns the model directory name for a variant (e.g., "sherpa-onnx-whisper-turbo"). */
    fun getModelDirName(variant: WhisperModelManager.Variant): String = MODEL_NAMES[variant] ?: ""

    private val ESTIMATED_SIZES = mapOf(
        WhisperModelManager.Variant.SMALL to 358L,
        WhisperModelManager.Variant.TURBO to 988L,
        WhisperModelManager.Variant.MEDIUM to 903L,
        WhisperModelManager.Variant.DISTIL_LARGE_V3 to 938L
    )

    // HuggingFace individual file downloads — URLs derived from repo name + file name
    private const val HF_BASE_URL = "https://huggingface.co"

    private fun hfFileUrl(repoName: String, fileName: String): String =
        "$HF_BASE_URL/pantinor/$repoName/resolve/main/$fileName"

    /** File names per variant (URLs derived at download time via [hfFileUrl]). */
    private val HF_FILE_NAMES = mapOf(
        WhisperModelManager.Variant.SMALL to listOf(
            "small-encoder.int8.onnx", "small-decoder.int8.onnx", "small-tokens.txt"
        ),
        WhisperModelManager.Variant.TURBO to listOf(
            "turbo-encoder.int8.onnx", "turbo-decoder.int8.onnx", "turbo-tokens.txt"
        ),
        WhisperModelManager.Variant.MEDIUM to listOf(
            "medium-encoder.int8.onnx", "medium-decoder.int8.onnx", "medium-tokens.txt"
        ),
        WhisperModelManager.Variant.DISTIL_LARGE_V3 to listOf(
            "distil-large-v3-it-encoder.int8.onnx", "distil-large-v3-it-decoder.int8.onnx", "distil-large-v3-it-tokens.txt"
        )
    )

    private var isCancelled = false

    /**
     * Detects a partial download for a given variant.
     *
     * @return [DownloadState.PartiallyDownloaded] if partial files exist, null otherwise
     */
    fun detectPartialDownload(context: Context, variant: WhisperModelManager.Variant): DownloadState.PartiallyDownloaded? {
        val modelDirName = MODEL_NAMES[variant] ?: return null
        val modelDir = File(WhisperModelManager.getModelStorageDir(context), modelDirName)
        if (!modelDir.exists() || !modelDir.isDirectory) return null
        if (WhisperModelManager.validateModelDirectory(modelDir) != null) return null

        val files = HF_FILE_NAMES[variant] ?: return null
        var totalDownloaded = 0L
        var totalExpected = 0L
        var hasPartialFiles = false

        for (fileName in files) {
            val file = File(modelDir, fileName)
            val fileSidecar = ResumeDownloadHelper.sizeSidecar(file)
            val storedSize = fileSidecar.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()

            if (file.exists() && file.length() > 0) {
                hasPartialFiles = true
                totalDownloaded += file.length()
                if (storedSize != null) totalExpected += storedSize
            }
        }

        if (!hasPartialFiles) return null

        val estimatedBytes = (ESTIMATED_SIZES[variant] ?: 984L) * 1024 * 1024
        val totalBytes = if (totalExpected > 0) totalExpected else estimatedBytes
        val progressPercent = ((totalDownloaded.toFloat() / totalBytes) * 100).toInt().coerceIn(0, 99)

        return DownloadState.PartiallyDownloaded(
            bytesDownloaded = totalDownloaded,
            totalBytes = totalBytes,
            progressPercent = progressPercent
        )
    }

    /**
     * No longer used — kept for API compatibility with ModelViewModel.
     * All models are now downloaded as individual files from HuggingFace.
     */
    fun needsExtraction(context: Context, variant: WhisperModelManager.Variant): Boolean = false

    /**
     * Clears a partial download for a variant.
     */
    fun clearPartialDownload(context: Context, variant: WhisperModelManager.Variant): Boolean {
        val modelDirName = MODEL_NAMES[variant] ?: return false
        val modelDir = File(WhisperModelManager.getModelStorageDir(context), modelDirName)
        if (!modelDir.exists()) return true
        modelDir.deleteRecursively()
        return true
    }

    /**
     * Downloads a Whisper model as individual files from HuggingFace.
     *
     * @param context Application context
     * @param variant The model variant to download
     * @param onProgress Callback for overall progress (0.0 to 1.0)
     * @param onStateChange Callback for state changes
     * @return Result containing the model directory or an error
     */
    suspend fun downloadModel(
        context: Context,
        variant: WhisperModelManager.Variant,
        onProgress: (Float) -> Unit = {},
        onStateChange: (DownloadState) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        isCancelled = false

        val targetDir = WhisperModelManager.getModelStorageDir(context)
        val modelDirName = MODEL_NAMES[variant] ?: return@withContext Result.failure(
            IllegalArgumentException("Unknown variant: $variant")
        )
        val modelDir = File(targetDir, modelDirName)

        // Check if already downloaded
        if (isModelDownloaded(context, variant)) {
            Log.i(TAG, "Model already downloaded: ${modelDir.absolutePath}")
            onStateChange(DownloadState.Complete(modelDir))
            return@withContext Result.success(modelDir)
        }

        val files = HF_FILE_NAMES[variant]
            ?: return@withContext Result.failure(IllegalArgumentException("No URLs for variant: $variant"))

        // Pre-download storage check (1.1x — no intermediate archive)
        val estimatedSizeMB = ESTIMATED_SIZES[variant] ?: 984L
        val requiredBytes = (estimatedSizeMB * 1024 * 1024 * 1.1).toLong()
        val availableBytes = context.filesDir.usableSpace
        if (availableBytes < requiredBytes) {
            val errorMsg = "Insufficient storage. Need ~${estimatedSizeMB}MB, have ${availableBytes / (1024*1024)}MB available."
            val error = Exception(errorMsg)
            Log.e(TAG, errorMsg)
            onStateChange(DownloadState.Error(errorMsg, error))
            return@withContext Result.failure(error)
        }

        if (!modelDir.exists()) modelDir.mkdirs()

        val totalFiles = files.size
        var completedFiles = 0

        for (fileName in files) {
            val fileUrl = hfFileUrl(modelDirName, fileName)
            if (isCancelled) {
                onStateChange(DownloadState.Cancelled("User cancelled"))
                return@withContext Result.failure(Exception("Download cancelled"))
            }

            val targetFile = File(modelDir, fileName)

            // Skip if file is complete (verified against .size sidecar)
            val sidecar = ResumeDownloadHelper.sizeSidecar(targetFile)
            val storedTotal = sidecar.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
            if (targetFile.exists() && storedTotal != null && targetFile.length() >= storedTotal) {
                Log.i(TAG, "File already complete, skipping: $fileName")
                completedFiles++
                onProgress(completedFiles.toFloat() / totalFiles)
                onStateChange(DownloadState.Extracting(completedFiles, totalFiles, fileName))
                continue
            }

            Log.i(TAG, "Downloading: $fileName")
            onStateChange(DownloadState.Connecting(fileUrl))

            val config = DownloadConfig(
                url = fileUrl,
                tempFile = targetFile,
                targetFile = targetFile,
                estimatedSizeBytes = 0L,
                connectTimeoutMs = 60_000,
                readTimeoutMs = 120_000,
                isCancelled = { isCancelled }
            )

            val downloadResult = downloadWithRetry(
                config = config,
                tag = TAG,
                onProgress = { fileProg ->
                    onProgress((completedFiles + fileProg) / totalFiles)
                },
                onStateChange = onStateChange
            )

            if (downloadResult.isFailure) {
                return@withContext downloadResult
            }

            completedFiles++
            onStateChange(DownloadState.Extracting(completedFiles, totalFiles, fileName))
        }

        // Verify all required files exist
        if (WhisperModelManager.validateModelDirectory(modelDir) == null) {
            val errorMsg = "Missing files after download in ${modelDir.absolutePath}"
            onStateChange(DownloadState.Error(errorMsg))
            return@withContext Result.failure(Exception(errorMsg))
        }

        Log.i(TAG, "Model ready: ${modelDir.absolutePath}")
        onStateChange(DownloadState.Complete(modelDir))
        Result.success(modelDir)
    }

    fun cancel() {
        isCancelled = true
        Log.i(TAG, "Download cancellation requested")
    }

    /**
     * Checks if a Whisper model is already downloaded.
     */
    fun isModelDownloaded(context: Context, variant: WhisperModelManager.Variant): Boolean {
        val modelDirName = MODEL_NAMES[variant] ?: return false
        val modelDir = File(WhisperModelManager.getModelStorageDir(context), modelDirName)
        return WhisperModelManager.validateModelDirectory(modelDir) != null
    }

    /**
     * Gets the model directory path if downloaded.
     */
    fun getModelPath(context: Context, variant: WhisperModelManager.Variant): String? {
        val modelDirName = MODEL_NAMES[variant] ?: return null
        val modelDir = File(WhisperModelManager.getModelStorageDir(context), modelDirName)
        return if (WhisperModelManager.validateModelDirectory(modelDir) != null) {
            modelDir.absolutePath
        } else {
            null
        }
    }

    /**
     * Gets the estimated download size in MB for a variant.
     */
    fun getEstimatedSizeMB(variant: WhisperModelManager.Variant): Long {
        return ESTIMATED_SIZES[variant] ?: 75L
    }

    /**
     * Deletes a downloaded Whisper model.
     */
    fun deleteModel(context: Context, variant: WhisperModelManager.Variant): Boolean {
        val modelDirName = MODEL_NAMES[variant] ?: return false
        val modelDir = File(WhisperModelManager.getModelStorageDir(context), modelDirName)
        if (!modelDir.exists()) return true

        return try {
            modelDir.deleteRecursively()
            Log.i(TAG, "Model deleted: ${modelDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model", e)
            false
        }
    }
}
