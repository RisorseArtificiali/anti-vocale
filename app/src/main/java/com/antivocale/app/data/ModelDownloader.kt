package com.antivocale.app.data

import android.content.Context
import android.util.Log
import com.antivocale.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages Gemma 3n LiteRT-LM models for on-device inference.
 *
 * Model sources:
 * 1. **HuggingFace**: Download models — auth is optional (tries public first, falls back to token)
 *
 * Available models:
 * - **Gemma 3n E2B**: 3.3GB, recommended for most devices (multimodal: text + audio)
 * - **Gemma 3n E4B**: 4.2GB, for devices with more RAM (multimodal: text + audio)
 *
 * Features:
 * - Pre-flight check: tries downloading without auth first (like Google AI Edge Gallery)
 * - Resume: picks up interrupted downloads via HTTP Range + partial .tmp file
 * - Retry: up to 3 attempts with exponential backoff for transient errors
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val MAX_RETRIES = 3
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val BUFFER_SIZE = 8192
    private const val TMP_FILE_EXT = ".tmp"

    /**
     * Available model variants.
     */
    enum class ModelVariant(
        val displayName: String,
        val huggingFaceRepo: String,
        val fileName: String,
        val descriptionResId: Int?,
        val estimatedSizeMB: Long,
        val supportsAudio: Boolean
    ) {
        GEMMA_3N_E2B(
            displayName = "Gemma 3n E2B",
            huggingFaceRepo = "google/gemma-3n-E2B-it-litert-lm",
            fileName = "gemma-3n-E2B-it-int4.litertlm",
            descriptionResId = R.string.model_desc_gemma_3n_e2b,
            estimatedSizeMB = 3300L,
            supportsAudio = true
        ),
        GEMMA_3N_E4B(
            displayName = "Gemma 3n E4B",
            huggingFaceRepo = "google/gemma-3n-E4B-it-litert-lm",
            fileName = "gemma-3n-E4B-it-int4.litertlm",
            descriptionResId = R.string.model_desc_gemma_3n_e4b,
            estimatedSizeMB = 4235L,
            supportsAudio = true
        )
    }

    /**
     * Download state for progress tracking.
     */
    sealed class DownloadState {
        data object Idle : DownloadState()
        data class CheckingAccess(val url: String) : DownloadState()
        data class Connecting(val url: String) : DownloadState()
        data class Downloading(
            val bytesDownloaded: Long,
            val totalBytes: Long,
            val progressPercent: Float
        ) : DownloadState()
        data class Retrying(val attempt: Int, val maxRetries: Int, val reason: String) : DownloadState()
        data class Complete(val file: File) : DownloadState()
        data class Error(val message: String, val throwable: Throwable? = null) : DownloadState()
        data class Cancelled(val reason: String) : DownloadState()
    }

    /**
     * Download error types.
     */
    sealed class DownloadError(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class AuthRequired(message: String) : DownloadError(message)
        class AuthError(message: String) : DownloadError(message)
        class LicenseError(message: String) : DownloadError(message)
        class NetworkError(message: String, cause: Throwable? = null) : DownloadError(message, cause)
        class StorageError(message: String, val requiredBytes: Long, val availableBytes: Long) : DownloadError(message)
    }

    @Volatile private var isCancelled = false

    /**
     * Resolves the HuggingFace download URL for a model variant.
     */
    private fun getDownloadUrl(variant: ModelVariant): String {
        return "https://huggingface.co/${variant.huggingFaceRepo}/resolve/main/${variant.fileName}"
    }

    /**
     * Checks whether the model URL is publicly accessible (no auth needed).
     *
     * @return true if HTTP 200, false if auth is needed or network error
     */
    private fun checkPublicAccess(variant: ModelVariant): Boolean {
        return try {
            val url = URL(getDownloadUrl(variant))
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = 15_000
            // Force identity encoding so we get a reliable Content-Length
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connect()
            val code = connection.responseCode
            connection.disconnect()
            code == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.w(TAG, "Public access check failed: ${e.message}")
            false
        }
    }

    /**
     * Downloads a model from HuggingFace.
     *
     * Auth flow: tries without token first (public access check), falls back to token if needed.
     *
     * @param context Application context
     * @param variant Model variant to download
     * @param tokenManager HuggingFace token manager (optional — only used if model is gated)
     * @param onProgress Callback for download progress (0.0 to 1.0)
     * @param onStateChange Callback for state changes
     * @return Result containing the downloaded file or an error
     */
    suspend fun downloadModel(
        context: Context,
        variant: ModelVariant = ModelVariant.GEMMA_3N_E2B,
        tokenManager: HuggingFaceTokenManager? = null,
        onProgress: (Float) -> Unit = {},
        onStateChange: (DownloadState) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        isCancelled = false

        // Pre-download storage check
        val requiredBytes = variant.estimatedSizeMB * 1024 * 1024
        val availableBytes = context.filesDir.usableSpace
        if (availableBytes < requiredBytes) {
            val errorMsg = "Insufficient storage. Need ${variant.estimatedSizeMB}MB, have ${availableBytes / (1024*1024)}MB available."
            val error = DownloadError.StorageError(errorMsg, requiredBytes, availableBytes)
            Log.e(TAG, errorMsg)
            onStateChange(DownloadState.Error(errorMsg, error))
            return@withContext Result.failure(error)
        }

        val targetDir = File(context.filesDir, "models")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val targetFile = File(targetDir, variant.fileName)

        // Check if already downloaded
        if (targetFile.exists() && targetFile.length() > 0) {
            Log.i(TAG, "Model already exists: ${targetFile.path}")
            onStateChange(DownloadState.Complete(targetFile))
            return@withContext Result.success(targetFile)
        }

        val downloadUrl = getDownloadUrl(variant)

        // Step 1: Check if model is publicly accessible
        onStateChange(DownloadState.CheckingAccess(downloadUrl))
        val isPublic = checkPublicAccess(variant)
        val accessToken = if (isPublic) {
            Log.i(TAG, "Model is publicly accessible, downloading without auth")
            null
        } else {
            Log.i(TAG, "Model requires authentication, looking for stored token")
            tokenManager?.getEffectiveToken()
        }

        if (!isPublic && accessToken == null) {
            val errorMsg = "This model requires a HuggingFace account. Please add your token in Settings."
            val error = DownloadError.AuthRequired(errorMsg)
            Log.i(TAG, errorMsg)
            onStateChange(DownloadState.Error(errorMsg, error))
            return@withContext Result.failure(error)
        }

        // Step 2: Download with retry
        onStateChange(DownloadState.Connecting(downloadUrl))
        downloadWithRetry(
            variant = variant,
            downloadUrl = downloadUrl,
            accessToken = accessToken,
            targetFile = targetFile,
            onProgress = onProgress,
            onStateChange = onStateChange
        )
    }

    /**
     * Downloads a model with retry support and resume capability.
     */
    private suspend fun downloadWithRetry(
        variant: ModelVariant,
        downloadUrl: String,
        accessToken: String?,
        targetFile: File,
        onProgress: (Float) -> Unit,
        onStateChange: (DownloadState) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val tempFile = File(targetFile.parentFile!!, "${variant.fileName}$TMP_FILE_EXT")
        var lastError: Throwable? = null

        for (attempt in 1..MAX_RETRIES) {
            if (isCancelled) {
                onStateChange(DownloadState.Cancelled("User cancelled"))
                return@withContext Result.failure(Exception("Download cancelled"))
            }

            try {
                val result = downloadSingleAttempt(
                    variant = variant,
                    downloadUrl = downloadUrl,
                    accessToken = accessToken,
                    targetFile = targetFile,
                    tempFile = tempFile,
                    onProgress = onProgress,
                    onStateChange = onStateChange
                )

                if (result.isSuccess) return@withContext result

                val error = result.exceptionOrNull()!!
                lastError = error

                // Don't retry auth/license errors
                if (error is DownloadError.AuthError || error is DownloadError.LicenseError || error is DownloadError.AuthRequired) {
                    return@withContext result
                }

                // Don't retry on last attempt
                if (attempt < MAX_RETRIES) {
                    val delayMs = (1000L * Math.pow(3.0, (attempt - 1).toDouble())).toLong()
                    Log.i(TAG, "Download failed (attempt $attempt/$MAX_RETRIES), retrying in ${delayMs}ms: ${error.message}")
                    onStateChange(DownloadState.Retrying(attempt, MAX_RETRIES, error.message ?: "Unknown error"))
                    kotlinx.coroutines.delay(delayMs)
                }

            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_RETRIES) {
                    val delayMs = (1000L * Math.pow(3.0, (attempt - 1).toDouble())).toLong()
                    Log.i(TAG, "Download crashed (attempt $attempt/$MAX_RETRIES), retrying in ${delayMs}ms: ${e.message}")
                    onStateChange(DownloadState.Retrying(attempt, MAX_RETRIES, e.message ?: "Unknown error"))
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }

        val errorMsg = "Download failed after $MAX_RETRIES attempts: ${lastError?.message}"
        val error = DownloadError.NetworkError(errorMsg, lastError)
        onStateChange(DownloadState.Error(errorMsg, error))
        Result.failure(error)
    }

    /**
     * Single download attempt with resume support.
     */
    private fun downloadSingleAttempt(
        variant: ModelVariant,
        downloadUrl: String,
        accessToken: String?,
        targetFile: File,
        tempFile: File,
        onProgress: (Float) -> Unit,
        onStateChange: (DownloadState) -> Unit
    ): Result<File> {
        val url = URL(downloadUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        // Force identity encoding so resume works reliably
        connection.setRequestProperty("Accept-Encoding", "identity")

        // Set auth header if we have a token
        if (accessToken != null) {
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
        }

        // Resume: check for partial download
        var downloadedBytes = 0L
        val partialSize = tempFile.length()
        if (partialSize > 0) {
            Log.i(TAG, "Resuming download from ${partialSize} bytes")
            connection.setRequestProperty("Range", "bytes=$partialSize-")
        }

        connection.connect()

        when (connection.responseCode) {
            HttpURLConnection.HTTP_OK -> {
                // Full download (no resume supported or fresh start)
                if (partialSize > 0) {
                    Log.i(TAG, "Server doesn't support range, starting fresh")
                    tempFile.delete()
                }
                downloadedBytes = 0L
            }
            HttpURLConnection.HTTP_PARTIAL -> {
                // Resume supported
                val contentRange = connection.getHeaderField("Content-Range")
                if (contentRange != null) {
                    // Parse "bytes 12345-67890/67891"
                    val rangeParts = contentRange.substringAfter("bytes ").split("/")
                    val byteRange = rangeParts[0].split("-")
                    downloadedBytes = byteRange[0].toLong()
                    Log.i(TAG, "Resuming from byte $downloadedBytes")
                } else {
                    downloadedBytes = partialSize
                    Log.i(TAG, "No Content-Range header, assuming resume from $downloadedBytes")
                }
            }
            HttpURLConnection.HTTP_UNAUTHORIZED -> {
                val error = DownloadError.AuthError("Invalid or expired HuggingFace token")
                onStateChange(DownloadState.Error(error.message ?: "Auth error", error))
                return Result.failure(error)
            }
            HttpURLConnection.HTTP_FORBIDDEN -> {
                val error = DownloadError.LicenseError("License not accepted. Visit huggingface.co/${variant.huggingFaceRepo}")
                onStateChange(DownloadState.Error(error.message ?: "License error", error))
                return Result.failure(error)
            }
            else -> {
                val errorMsg = "Download failed: HTTP ${connection.responseCode}"
                val error = DownloadError.NetworkError(errorMsg)
                onStateChange(DownloadState.Error(errorMsg, error))
                return Result.failure(error)
            }
        }

        val inputStream = connection.inputStream
        val outputStream = FileOutputStream(tempFile, true) // append mode for resume
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        val totalBytes = variant.estimatedSizeMB * 1024 * 1024

        try {
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (isCancelled) {
                    onStateChange(DownloadState.Cancelled("User cancelled"))
                    return Result.failure(Exception("Download cancelled"))
                }

                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                val progress = (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                onProgress(progress)
                onStateChange(DownloadState.Downloading(
                    bytesDownloaded = downloadedBytes,
                    totalBytes = totalBytes,
                    progressPercent = progress * 100
                ))
            }
        } finally {
            outputStream.close()
            inputStream.close()
            connection.disconnect()
        }

        // Rename temp file to final name
        if (!tempFile.renameTo(targetFile)) {
            tempFile.delete()
            val error = "Failed to rename temp file"
            onStateChange(DownloadState.Error(error))
            return Result.failure(Exception(error))
        }

        Log.i(TAG, "Download complete: ${targetFile.path} (${targetFile.length()} bytes)")
        onStateChange(DownloadState.Complete(targetFile))
        return Result.success(targetFile)
    }

    /**
     * Cancels any ongoing download.
     */
    fun cancel() {
        isCancelled = true
        Log.i(TAG, "Download cancellation requested")
    }

    /**
     * Gets the local path for a model variant.
     * Returns null if the model hasn't been downloaded yet.
     */
    fun getLocalModelPath(context: Context, variant: ModelVariant = ModelVariant.GEMMA_3N_E2B): String? {
        val file = File(context.filesDir, "models/${variant.fileName}")
        return if (file.exists() && file.length() > 0) {
            file.absolutePath
        } else {
            null
        }
    }

    /**
     * Checks if a model is already downloaded.
     */
    fun isModelDownloaded(context: Context, variant: ModelVariant = ModelVariant.GEMMA_3N_E2B): Boolean {
        val file = File(context.filesDir, "models/${variant.fileName}")
        return file.exists() && file.length() > 0
    }

    /**
     * Checks if a model has a partial download that can be resumed.
     */
    fun hasPartialDownload(context: Context, variant: ModelVariant = ModelVariant.GEMMA_3N_E2B): Boolean {
        val tempFile = File(context.filesDir, "models/${variant.fileName}.tmp")
        return tempFile.exists() && tempFile.length() > 0
    }

    /**
     * Gets the partial download progress (0.0 to 1.0).
     * Returns null if no partial download exists.
     */
    fun getPartialDownloadProgress(context: Context, variant: ModelVariant = ModelVariant.GEMMA_3N_E2B): Float? {
        val tempFile = File(context.filesDir, "models/${variant.fileName}.tmp")
        if (!tempFile.exists()) return null

        val downloadedBytes = tempFile.length()
        return downloadedBytes.toFloat() / (variant.estimatedSizeMB * 1024 * 1024)
    }

    /**
     * Deletes a downloaded model to free up storage.
     */
    fun deleteModel(context: Context, variant: ModelVariant = ModelVariant.GEMMA_3N_E2B): Boolean {
        val file = File(context.filesDir, "models/${variant.fileName}")
        val tempFile = File(context.filesDir, "models/${variant.fileName}.tmp")

        var deleted = false
        if (file.exists()) {
            deleted = file.delete() || deleted
        }
        if (tempFile.exists()) {
            deleted = tempFile.delete() || deleted
        }

        Log.i(TAG, "Model deleted: ${variant.fileName}, success=$deleted")
        return deleted
    }

    /**
     * Gets the total size of downloaded models in bytes.
     */
    fun getTotalDownloadedSize(context: Context): Long {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) return 0L

        return modelsDir.listFiles()
            ?.filter { it.extension == "litertlm" }
            ?.sumOf { it.length() }
            ?: 0L
    }

    /**
     * Lists all downloaded models.
     */
    fun listDownloadedModels(context: Context): List<Pair<ModelVariant, File>> {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) return emptyList()

        return ModelVariant.entries.mapNotNull { variant ->
            val file = File(modelsDir, variant.fileName)
            if (file.exists() && file.length() > 0) {
                variant to file
            } else {
                null
            }
        }
    }

    /**
     * Deletes partial download temp files.
     */
    fun clearPartialDownload(context: Context, variant: ModelVariant = ModelVariant.GEMMA_3N_E2B): Boolean {
        val tempFile = File(context.filesDir, "models/${variant.fileName}.tmp")
        return if (tempFile.exists()) tempFile.delete() else true
    }
}
