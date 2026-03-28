package com.antivocale.app.data

import android.content.Context
import android.util.Log
import com.antivocale.app.R
import com.antivocale.app.data.download.DownloadConfig
import com.antivocale.app.data.download.DownloadException
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.data.download.downloadWithRetry as sharedDownloadWithRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
     * Download error types (Gemma-specific auth/license/storage errors).
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
     * Detects a partial download for a given variant.
     *
     * @return [DownloadState.PartiallyDownloaded] if a .tmp file exists, null otherwise
     */
    fun detectPartialDownload(context: Context, variant: ModelVariant = ModelVariant.GEMMA_3N_E2B): DownloadState.PartiallyDownloaded? {
        val tempFile = File(context.filesDir, "models/${variant.fileName}$TMP_FILE_EXT")
        if (tempFile.length() == 0L) return null

        val downloadedBytes = tempFile.length()
        val totalBytes = variant.estimatedSizeMB * 1024 * 1024
        val progressPercent = ((downloadedBytes.toFloat() / totalBytes) * 100).toInt().coerceIn(0, 99)

        return DownloadState.PartiallyDownloaded(
            bytesDownloaded = downloadedBytes,
            totalBytes = totalBytes,
            progressPercent = progressPercent
        )
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
        if (targetFile.length() > 0) {
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
     * Delegates to the shared [downloadWithRetry] helper.
     */
    private suspend fun downloadWithRetry(
        variant: ModelVariant,
        downloadUrl: String,
        accessToken: String?,
        targetFile: File,
        onProgress: (Float) -> Unit,
        onStateChange: (DownloadState) -> Unit
    ): Result<File> {
        val tempFile = File(targetFile.parentFile!!, "${variant.fileName}$TMP_FILE_EXT")

        val result = sharedDownloadWithRetry(
            config = DownloadConfig(
                url = downloadUrl,
                tempFile = tempFile,
                targetFile = targetFile,
                estimatedSizeBytes = variant.estimatedSizeMB * 1024 * 1024,
                authHeader = accessToken?.let { "Bearer $it" },
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
                isCancelled = { isCancelled }
            ),
            maxRetries = MAX_RETRIES,
            backoffBase = 3.0,
            tag = TAG,
            onProgress = onProgress,
            onStateChange = onStateChange
        )

        // Map shared DownloadException types to domain DownloadError types
        if (result.isSuccess) return result

        val error = result.exceptionOrNull()!!
        return Result.failure(when (error) {
            is DownloadException.Unauthorized -> DownloadError.AuthError(error.message ?: "Auth error")
            is DownloadException.Forbidden -> DownloadError.LicenseError(error.message ?: "License error")
            is DownloadException.Cancelled -> error
            else -> DownloadError.NetworkError(error.message ?: "Download failed", error)
        })
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
        return if (file.length() > 0) {
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
        return file.length() > 0
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
            if (file.length() > 0) {
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
