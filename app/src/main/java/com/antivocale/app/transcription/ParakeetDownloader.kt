package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.data.download.DownloadConfig
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.data.download.ResumeDownloadHelper
import com.antivocale.app.data.download.TarBz2Extractor
import com.antivocale.app.data.download.downloadWithRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads and extracts the Parakeet TDT model for sherpa-onnx.
 *
 * Model source: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/
 * Model size: ~464MB (int8 quantized)
 * Languages: 25 European languages
 *
 * Features:
 * - Resume: picks up interrupted downloads via HTTP Range + partial .tar.bz2 file
 * - Retry: up to 3 attempts with exponential backoff
 * - On cancel: keeps .tar.bz2 for resume (deleted only after successful extraction)
 */
object ParakeetDownloader {

    private const val TAG = "ParakeetDownloader"

    // Official sherpa-onnx pre-converted model (no authentication required)
    private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2"
    private const val MODEL_NAME = "parakeet-tdt-0.6b-v3-int8"
    private const val ESTIMATED_SIZE_MB = 464L

    // Required files after extraction
    val REQUIRED_FILES = listOf(
        "encoder.int8.onnx",
        "decoder.int8.onnx",
        "joiner.int8.onnx",
        "tokens.txt"
    )

    private var isCancelled = false

    /**
     * Detects a partial download.
     *
     * @return [DownloadState.PartiallyDownloaded] if a .tar.bz2 temp file exists, null otherwise
     */
    fun detectPartialDownload(context: Context): DownloadState.PartiallyDownloaded? {
        val targetDir = ParakeetModelManager.getModelStorageDir(context)
        val tarFile = File(targetDir, "$MODEL_NAME.tar.bz2")

        if (tarFile.length() == 0L) return null

        val downloadedBytes = tarFile.length()
        val estimatedBytes = ESTIMATED_SIZE_MB * 1024 * 1024
        val totalBytes = ResumeDownloadHelper.readStoredTotalBytes(tarFile, estimatedBytes)

        // If the tar file is already complete (>= 90% of actual size),
        // don't report it as a partial download — extraction just needs to run.
        if (downloadedBytes >= totalBytes * 9 / 10) return null

        val progressPercent = ((downloadedBytes.toFloat() / totalBytes) * 100).toInt().coerceIn(0, 99)

        return DownloadState.PartiallyDownloaded(
            bytesDownloaded = downloadedBytes,
            totalBytes = totalBytes,
            progressPercent = progressPercent
        )
    }

    /**
     * Checks if a tar file exists that needs extraction (model not yet downloaded/validated).
     * Returns true only when the tar file is large enough to be considered complete
     * (>= 90% of actual size from HTTP Content-Length), matching the threshold in
     * [detectPartialDownload] and [downloadModel]'s skipDownload check.
     */
    fun needsExtraction(context: Context): Boolean {
        if (isModelDownloaded(context)) return false
        val tarFile = File(ParakeetModelManager.getModelStorageDir(context), "$MODEL_NAME.tar.bz2")
        val estimatedBytes = ESTIMATED_SIZE_MB * 1024 * 1024
        val totalBytes = ResumeDownloadHelper.readStoredTotalBytes(tarFile, estimatedBytes)
        return tarFile.length() >= totalBytes * 9 / 10
    }

    /**
     * Clears a partial download (.tar.bz2).
     */
    fun clearPartialDownload(context: Context): Boolean {
        val targetDir = ParakeetModelManager.getModelStorageDir(context)
        val tarFile = File(targetDir, "$MODEL_NAME.tar.bz2")
        ResumeDownloadHelper.clearTarDownload(tarFile)
        return true
    }

    /**
     * Downloads and extracts the Parakeet model.
     *
     * @param context Application context
     * @param onProgress Callback for overall progress (0.0 to 1.0)
     * @param onStateChange Callback for state changes
     * @return Result containing the model directory or an error
     */
    suspend fun downloadModel(
        context: Context,
        onProgress: (Float) -> Unit = {},
        onStateChange: (DownloadState) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        isCancelled = false

        val targetDir = ParakeetModelManager.getModelStorageDir(context)
        val modelDir = File(targetDir, MODEL_NAME)

        // Check if already downloaded
        if (isModelDownloaded(context)) {
            Log.i(TAG, "Model already downloaded: ${modelDir.absolutePath}")
            onStateChange(DownloadState.Complete(modelDir))
            return@withContext Result.success(modelDir)
        }

        // Pre-download storage check
        val requiredBytes = ESTIMATED_SIZE_MB * 1024 * 1024 * 2 // 2x for tar + extracted
        val availableBytes = context.filesDir.usableSpace
        if (availableBytes < requiredBytes) {
            val errorMsg = "Insufficient storage. Need ~${ESTIMATED_SIZE_MB * 2}MB, have ${availableBytes / (1024*1024)}MB available."
            val error = Exception(errorMsg)
            Log.e(TAG, errorMsg)
            onStateChange(DownloadState.Error(errorMsg, error))
            return@withContext Result.failure(error)
        }

        // Create target directory
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val tarFile = File(targetDir, "$MODEL_NAME.tar.bz2")

        // If the tar file is already complete (>= 90% of actual size),
        // skip the download and go straight to extraction.
        // This matches the threshold in detectPartialDownload() so that a
        // partial download at < 90% will resume the download rather than
        // skipping straight to extraction.
        val estimatedSizeBytes = ESTIMATED_SIZE_MB * 1024 * 1024
        val totalBytes = ResumeDownloadHelper.readStoredTotalBytes(tarFile, estimatedSizeBytes)
        val skipDownload = tarFile.exists() && tarFile.length() >= totalBytes * 9 / 10

        if (!skipDownload) {
            Log.i(TAG, "Starting download: $MODEL_URL")
            onStateChange(DownloadState.Connecting(MODEL_URL))

            // Download with retry (shared helper)
            val config = DownloadConfig(
                url = MODEL_URL,
                tempFile = tarFile,
                targetFile = tarFile,
                estimatedSizeBytes = ESTIMATED_SIZE_MB * 1024 * 1024,
                connectTimeoutMs = 60_000,
                readTimeoutMs = 120_000,
                isCancelled = { isCancelled }
            )

            val downloadResult = downloadWithRetry(
                config = config,
                tag = TAG,
                onProgress = { progress -> onProgress(progress * 0.6f) },
                onStateChange = onStateChange
            )

            if (downloadResult.isFailure) {
                // On failure, keep tarFile for potential resume — don't delete
                return@withContext downloadResult
            }

            Log.i(TAG, "Download complete: ${tarFile.length()} bytes")
        }

        // Extract phase (40% of progress)
        onStateChange(DownloadState.Extracting(0, REQUIRED_FILES.size))

        val extractResult = TarBz2Extractor.extract(
            tarFile = tarFile,
            modelDir = modelDir,
            isRequiredFile = { fileName -> fileName in REQUIRED_FILES },
            isCancelled = { isCancelled },
            tag = TAG,
            totalFiles = REQUIRED_FILES.size
        ) { fileIndex, _, fileName, bytesExtracted, fileSize ->
            val fileProgress = if (fileSize > 0) bytesExtracted.toFloat() / fileSize else 0f
            val extractProgress = 0.6f + ((fileIndex - 1 + fileProgress) / REQUIRED_FILES.size) * 0.4f
            onProgress(extractProgress.coerceIn(0.6f, 1f))
            onStateChange(DownloadState.Extracting(fileIndex, REQUIRED_FILES.size, fileName, bytesExtracted, fileSize))
        }

        // Clean up tar file only after successful extraction
        ResumeDownloadHelper.clearTarDownload(tarFile)

        if (extractResult.isFailure) {
            onStateChange(DownloadState.Error(extractResult.exceptionOrNull()?.message ?: "Extraction failed"))
            return@withContext extractResult
        }

        // Verify extraction
        val missingFiles = REQUIRED_FILES.filter { !File(modelDir, it).exists() }
        if (missingFiles.isNotEmpty()) {
            val errorMsg = "Missing files after extraction: ${missingFiles.joinToString()}"
            onStateChange(DownloadState.Error(errorMsg))
            return@withContext Result.failure(Exception(errorMsg))
        }

        Log.i(TAG, "Model ready: ${modelDir.absolutePath}")
        onStateChange(DownloadState.Complete(modelDir))
        Result.success(modelDir)
    }

    /**
     * Cancels any ongoing download.
     */
    fun cancel() {
        isCancelled = true
        Log.i(TAG, "Download cancellation requested")
    }

    /**
     * Checks if the model is already downloaded.
     */
    fun isModelDownloaded(context: Context): Boolean {
        val modelDir = File(ParakeetModelManager.getModelStorageDir(context), MODEL_NAME)
        return ParakeetModelManager.validateModelDirectory(modelDir) != null
    }

    /**
     * Gets the model directory path if downloaded.
     */
    fun getModelPath(context: Context): String? {
        val modelDir = File(ParakeetModelManager.getModelStorageDir(context), MODEL_NAME)
        return if (ParakeetModelManager.validateModelDirectory(modelDir) != null) {
            modelDir.absolutePath
        } else {
            null
        }
    }

    /**
     * Gets the estimated download size in MB.
     */
    fun getEstimatedSizeMB(): Long = ESTIMATED_SIZE_MB

    /**
     * Deletes the downloaded model.
     */
    fun deleteModel(context: Context): Boolean {
        val targetDir = ParakeetModelManager.getModelStorageDir(context)
        val modelDir = File(targetDir, MODEL_NAME)
        if (!modelDir.exists()) return true

        // Also clean up partial tar.bz2 and .size sidecar
        val tarFile = File(targetDir, "$MODEL_NAME.tar.bz2")
        ResumeDownloadHelper.clearTarDownload(tarFile)

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
