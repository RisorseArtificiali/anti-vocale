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
 * Downloads and extracts Whisper models for sherpa-onnx.
 *
 * Model source: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/
 * Model sizes:
 * - Small: ~610MB (int8 quantized)
 * - Turbo: ~538MB (large-v3-turbo distillation)
 * - Medium: ~1.8GB (int8 quantized)
 *
 * Languages: Multilingual (99+ languages including excellent Italian support)
 *
 * Features:
 * - Resume: picks up interrupted downloads via HTTP Range + partial .tar.bz2 file
 * - Retry: up to 3 attempts with exponential backoff
 * - On cancel: keeps .tar.bz2 for resume (deleted only after successful extraction)
 */
object WhisperDownloader {

    private const val TAG = "WhisperDownloader"

    // Official sherpa-onnx pre-converted models (no authentication required)
    private val MODEL_URLS = mapOf(
        WhisperModelManager.Variant.SMALL to
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2",
        WhisperModelManager.Variant.TURBO to
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-turbo.tar.bz2",
        WhisperModelManager.Variant.MEDIUM to
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-medium.tar.bz2"
    )

    private val MODEL_NAMES = mapOf(
        WhisperModelManager.Variant.SMALL to "sherpa-onnx-whisper-small",
        WhisperModelManager.Variant.TURBO to "sherpa-onnx-whisper-turbo",
        WhisperModelManager.Variant.MEDIUM to "sherpa-onnx-whisper-medium"
    )

    /** Returns the model directory name for a variant (e.g., "sherpa-onnx-whisper-turbo"). */
    fun getModelDirName(variant: WhisperModelManager.Variant): String = MODEL_NAMES[variant] ?: ""

    private val ESTIMATED_SIZES = mapOf(
        WhisperModelManager.Variant.SMALL to 610L,
        WhisperModelManager.Variant.TURBO to 538L,
        WhisperModelManager.Variant.MEDIUM to 1842L
    )

    private var isCancelled = false

    /**
     * Detects a partial download for a given variant.
     *
     * @return [DownloadState.PartiallyDownloaded] if a .tar.bz2 temp file exists, null otherwise
     */
    fun detectPartialDownload(context: Context, variant: WhisperModelManager.Variant): DownloadState.PartiallyDownloaded? {
        val modelDirName = MODEL_NAMES[variant] ?: return null
        val targetDir = WhisperModelManager.getModelStorageDir(context)
        val tarFile = File(targetDir, "$modelDirName.tar.bz2")

        if (tarFile.length() == 0L) return null

        val downloadedBytes = tarFile.length()
        val estimatedBytes = (ESTIMATED_SIZES[variant] ?: 75L) * 1024 * 1024
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
    fun needsExtraction(context: Context, variant: WhisperModelManager.Variant): Boolean {
        if (isModelDownloaded(context, variant)) return false
        val modelDirName = MODEL_NAMES[variant] ?: return false
        val tarFile = File(WhisperModelManager.getModelStorageDir(context), "$modelDirName.tar.bz2")
        val estimatedBytes = (ESTIMATED_SIZES[variant] ?: 75L) * 1024 * 1024
        val totalBytes = ResumeDownloadHelper.readStoredTotalBytes(tarFile, estimatedBytes)
        return tarFile.length() >= totalBytes * 9 / 10
    }

    /**
     * Clears a partial download (.tar.bz2) for a variant.
     */
    fun clearPartialDownload(context: Context, variant: WhisperModelManager.Variant): Boolean {
        val modelDirName = MODEL_NAMES[variant] ?: return false
        val targetDir = WhisperModelManager.getModelStorageDir(context)
        val tarFile = File(targetDir, "$modelDirName.tar.bz2")
        ResumeDownloadHelper.clearTarDownload(tarFile)
        return true
    }

    /**
     * Downloads and extracts a Whisper model.
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

        // Pre-download storage check
        val estimatedSizeMB = ESTIMATED_SIZES[variant] ?: 75L
        val requiredBytes = estimatedSizeMB * 1024 * 1024 * 2 // 2x for tar + extracted
        val availableBytes = context.filesDir.usableSpace
        if (availableBytes < requiredBytes) {
            val errorMsg = "Insufficient storage. Need ~${estimatedSizeMB * 2}MB, have ${availableBytes / (1024*1024)}MB available."
            val error = Exception(errorMsg)
            Log.e(TAG, errorMsg)
            onStateChange(DownloadState.Error(errorMsg, error))
            return@withContext Result.failure(error)
        }

        // Create target directory
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val url = MODEL_URLS[variant] ?: return@withContext Result.failure(
            IllegalArgumentException("No URL for variant: $variant")
        )
        val tarFile = File(targetDir, "$modelDirName.tar.bz2")

        // If the tar file is already complete (>= 90% of actual size),
        // skip the download and go straight to extraction.
        // This matches the threshold in detectPartialDownload() so that a
        // partial download at < 90% will resume the download rather than
        // skipping straight to extraction.
        val estimatedSizeBytes = estimatedSizeMB * 1024 * 1024
        val totalBytes = ResumeDownloadHelper.readStoredTotalBytes(tarFile, estimatedSizeBytes)
        val skipDownload = tarFile.exists() && tarFile.length() >= totalBytes * 9 / 10

        if (skipDownload) {
            Log.i(TAG, "Tar file already exists (${tarFile.length()} bytes), skipping download")
        } else {
            Log.i(TAG, "Starting download: $url")
            onStateChange(DownloadState.Connecting(url))
        }

        // Download with retry (shared helper)
        if (!skipDownload) {
            val config = DownloadConfig(
                url = url,
                tempFile = tarFile,
                targetFile = tarFile,
                estimatedSizeBytes = estimatedSizeMB * 1024 * 1024,
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

        val isRequiredFile: (String) -> Boolean = { fileName ->
            WhisperModelManager.TOKENS_PATTERNS.contains(fileName) ||
            WhisperModelManager.ENCODER_PATTERNS.contains(fileName) ||
            WhisperModelManager.DECODER_PATTERNS.contains(fileName) ||
            WhisperModelManager.ENCODER_DECODER_PATTERNS.contains(fileName)
        }

        // Extract phase — no pre-pass to count files (that would decompress the
        // entire archive twice). Use indeterminate progress instead.
        onStateChange(DownloadState.Extracting(0, 0))

        val extractResult = TarBz2Extractor.extract(
            tarFile = tarFile,
            modelDir = modelDir,
            isRequiredFile = isRequiredFile,
            isCancelled = { isCancelled },
            tag = TAG,
            totalFiles = 0
        ) { fileIndex, _, fileName, bytesExtracted, fileSize ->
            val fileProgress = if (fileSize > 0) bytesExtracted.toFloat() / fileSize else 0f
            val extractProgress = 0.6f + fileProgress * 0.4f
            onProgress(extractProgress.coerceIn(0.6f, 1f))
            onStateChange(DownloadState.Extracting(fileIndex, 0, fileName, bytesExtracted, fileSize))
        }

        // Clean up tar file only after successful extraction
        ResumeDownloadHelper.clearTarDownload(tarFile)

        if (extractResult.isFailure) {
            onStateChange(DownloadState.Error(extractResult.exceptionOrNull()?.message ?: "Extraction failed"))
            return@withContext extractResult
        }

        // Verify extraction
        val hasTokens = File(modelDir, "tokens.txt").exists() ||
            WhisperModelManager.TOKENS_PATTERNS.any { File(modelDir, it).exists() }
        val hasEncoder = WhisperModelManager.ENCODER_PATTERNS.any { File(modelDir, it).exists() }
        val hasDecoder = WhisperModelManager.DECODER_PATTERNS.any { File(modelDir, it).exists() }

        if (!hasTokens || !hasEncoder || !hasDecoder) {
            val errorMsg = "Missing files after extraction in ${modelDir.absolutePath}"
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

        // Also clean up partial tar.bz2 and .size sidecar
        val tarFile = File(WhisperModelManager.getModelStorageDir(context), "$modelDirName.tar.bz2")
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
