package com.antivocale.app.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import com.antivocale.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages Gemma 3n LiteRT-LM models for on-device inference.
 *
 * Model sources:
 * 1. **Google AI Edge Gallery**: Reuse models already downloaded by the Gallery app
 * 2. **HuggingFace**: Download new models (requires HuggingFace account + license)
 *
 * Available models:
 * - **Gemma 3n E2B**: 3.3GB, recommended for most devices (multimodal: text + audio)
 * - **Gemma 3n E4B**: 4.2GB, for devices with more RAM (multimodal: text + audio)
 * - **Gemma3-1B**: 557MB, lightweight text-only model
 *
 * Models support multimodal inference (text + audio) when loaded with LiteRT-LM.
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"

    /**
     * Google AI Edge Gallery model storage path.
     * Models are stored in: /storage/emulated/0/Android/data/com.google.ai.edge.gallery/files/
     */
    private const val GALLERY_PACKAGE = "com.google.ai.edge.gallery"
    private const val GALLERY_MODELS_PATH = "/storage/emulated/0/Android/data/$GALLERY_PACKAGE/files"

    /**
     * Available model variants.
     */
    enum class ModelVariant(
        val displayName: String,
        val huggingFaceRepo: String,
        val fileName: String,
        val descriptionResId: Int?,
        val estimatedSizeMB: Long,
        val supportsAudio: Boolean,
        val galleryModelName: String? = null  // Name in Google AI Edge Gallery
    ) {
        GEMMA_3N_E2B(
            displayName = "Gemma 3n E2B (Recommended)",
            huggingFaceRepo = "google/gemma-3n-E2B-it-litert-lm",
            fileName = "gemma-3n-E2B-it-int4.litertlm",
            descriptionResId = R.string.model_desc_gemma_3n_e2b,
            estimatedSizeMB = 3300L,
            supportsAudio = true,
            galleryModelName = "Gemma_3n_E2B_it"
        ),
        GEMMA_3N_E4B(
            displayName = "Gemma 3n E4B",
            huggingFaceRepo = "google/gemma-3n-E4B-it-litert-lm",
            fileName = "gemma-3n-E4B-it-int4.litertlm",
            descriptionResId = R.string.model_desc_gemma_3n_e4b,
            estimatedSizeMB = 4235L,
            supportsAudio = true,
            galleryModelName = "Gemma_3n_E4B_it"
        )
    }

    /**
     * Download state for progress tracking.
     */
    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Connecting(val url: String) : DownloadState()
        data class Downloading(
            val bytesDownloaded: Long,
            val totalBytes: Long,
            val progressPercent: Float
        ) : DownloadState()
        data class Complete(val file: File) : DownloadState()
        data class Error(val message: String, val throwable: Throwable? = null) : DownloadState()
        data class Cancelled(val reason: String) : DownloadState()
    }

    /**
     * Download error types for authenticated HuggingFace downloads.
     */
    sealed class DownloadError(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class AuthError(message: String) : DownloadError(message)
        class LicenseError(message: String) : DownloadError(message)
        class NetworkError(message: String, cause: Throwable? = null) : DownloadError(message, cause)
        class StorageError(message: String, val requiredBytes: Long, val availableBytes: Long) : DownloadError(message)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var isCancelled = false

    /**
     * Downloads a model from HuggingFace with authentication.
     *
     * @param context Application context
     * @param variant Model variant to download
     * @param tokenManager HuggingFace token manager for authenticated downloads
     * @param onProgress Callback for download progress (0.0 to 1.0)
     * @param onStateChange Callback for state changes
     * @return Result containing the downloaded file or an error
     */
    suspend fun downloadModel(
        context: Context,
        variant: ModelVariant = ModelVariant.GEMMA_3N_E2B,
        tokenManager: HuggingFaceTokenManager,
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

        // Get HuggingFace token for authenticated download
        val token = tokenManager.getEffectiveToken()
            ?: run {
                val errorMsg = "No HuggingFace token found. Please add your token in settings."
                val error = DownloadError.AuthError(errorMsg)
                Log.e(TAG, errorMsg)
                onStateChange(DownloadState.Error(errorMsg, error))
                return@withContext Result.failure(error)
            }

        val url = "https://huggingface.co/${variant.huggingFaceRepo}/resolve/main/${variant.fileName}"
        Log.i(TAG, "Starting authenticated download: $url")
        onStateChange(DownloadState.Connecting(url))

        return@withContext try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorMsg = when (response.code) {
                    401 -> "Invalid or expired HuggingFace token"
                    403 -> "License not accepted. Visit huggingface.co/${variant.huggingFaceRepo} to accept the license."
                    else -> "Download failed: HTTP ${response.code}"
                }
                val error = when (response.code) {
                    401 -> DownloadError.AuthError(errorMsg)
                    403 -> DownloadError.LicenseError(errorMsg)
                    else -> DownloadError.NetworkError(errorMsg)
                }
                Log.e(TAG, errorMsg)
                onStateChange(DownloadState.Error(errorMsg, error))
                return@withContext Result.failure(error)
            }

            val body = response.body ?: run {
                val error = "Empty response body"
                onStateChange(DownloadState.Error(error))
                return@withContext Result.failure(Exception(error))
            }

            val contentLength = body.contentLength()
            Log.i(TAG, "Content length: $contentLength bytes")

            // Download to temp file first
            val tempFile = File(targetDir, "${variant.fileName}.tmp")
            var totalBytesDownloaded = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled) {
                            tempFile.delete()
                            onStateChange(DownloadState.Cancelled("User cancelled"))
                            return@withContext Result.failure(Exception("Download cancelled"))
                        }

                        output.write(buffer, 0, bytesRead)
                        totalBytesDownloaded += bytesRead

                        // Report progress
                        val progress = if (contentLength > 0) {
                            totalBytesDownloaded.toFloat() / contentLength
                        } else {
                            // Estimate progress based on expected size
                            totalBytesDownloaded.toFloat() / (variant.estimatedSizeMB * 1024 * 1024)
                        }

                        onProgress(progress.coerceIn(0f, 1f))
                        onStateChange(DownloadState.Downloading(
                            bytesDownloaded = totalBytesDownloaded,
                            totalBytes = contentLength,
                            progressPercent = progress * 100
                        ))
                    }
                }
            }

            // Rename temp file to final name
            if (!tempFile.renameTo(targetFile)) {
                tempFile.delete()
                val error = "Failed to rename temp file"
                onStateChange(DownloadState.Error(error))
                return@withContext Result.failure(Exception(error))
            }

            Log.i(TAG, "Download complete: ${targetFile.path} (${targetFile.length()} bytes)")
            onStateChange(DownloadState.Complete(targetFile))
            Result.success(targetFile)

        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            onStateChange(DownloadState.Error(e.message ?: "Unknown error", e))
            Result.failure(e)
        }
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
     * Gets the download progress of a partially downloaded model.
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

    // ==================== Google AI Edge Gallery Integration ====================

    /**
     * Finds a model in Google AI Edge Gallery's storage.
     * Returns the model file if found, null otherwise.
     *
     * Gallery stores models at:
     * /storage/emulated/0/Android/data/com.google.ai.edge.gallery/files/{ModelName}/{hash}/{model}.litertlm
     *
     * The hash folder name changes between downloads/versions, so we search recursively.
     */
    fun findGalleryModel(variant: ModelVariant): File? {
        // Check for storage permission first (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Log.w(TAG, "findGalleryModel: ❌ No MANAGE_EXTERNAL_STORAGE permission - cannot access Gallery models")
            Log.w(TAG, "  Grant 'All files access' permission to use Gallery models")
            return null
        }

        val galleryName = variant.galleryModelName ?: return null
        val galleryDir = File(GALLERY_MODELS_PATH, galleryName)

        Log.d(TAG, "Searching for Gallery model: ${variant.displayName}")
        Log.d(TAG, "  Expected path: ${galleryDir.path}")
        Log.d(TAG, "  Permission granted: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else "N/A (pre-Android 11)"}")

        if (!galleryDir.exists()) {
            Log.w(TAG, "  Gallery model directory NOT FOUND: ${galleryDir.path}")
            // Try to list parent directory to debug
            val parentDir = File(GALLERY_MODELS_PATH)
            if (parentDir.exists()) {
                Log.d(TAG, "  Parent directory exists, contents: ${parentDir.listFiles()?.map { it.name }}")
            } else {
                Log.w(TAG, "  Parent directory also not found: ${parentDir.path}")
            }
            return null
        }

        Log.d(TAG, "  Gallery directory exists, searching for .litertlm files...")

        // Find the model file in subdirectories (hash-named folders)
        // The hash changes between versions, so we search all subdirectories
        var foundFiles = 0
        galleryDir.walkTopDown()
            .onEnter { dir ->
                Log.v(TAG, "    Scanning: ${dir.name}")
                true
            }
            .filter { 
                val matches = it.isFile && it.extension == "litertlm" && it.length() > 100_000_000
                if (it.isFile && it.extension == "litertlm") {
                    Log.d(TAG, "    Found .litertlm file: ${it.name}, size: ${it.length()}, matches: $matches")
                }
                matches
            }
            .onEach { foundFiles++ }
            .firstOrNull()
            ?.let { file ->
                Log.i(TAG, "  ✅ Found Gallery model: ${file.path} (${file.length()} bytes)")
                return file
            }

        Log.w(TAG, "  ❌ No matching .litertlm files found (scanned $foundFiles files)")
        return null
    }

    /**
     * Checks if a model is available from Google AI Edge Gallery.
     */
    fun isGalleryModelAvailable(variant: ModelVariant): Boolean {
        return findGalleryModel(variant) != null
    }

    /**
     * Gets the path to a Gallery model if available.
     */
    fun getGalleryModelPath(variant: ModelVariant): String? {
        return findGalleryModel(variant)?.absolutePath
    }

    /**
     * Lists all available models from Google AI Edge Gallery.
     */
    fun listGalleryModels(): List<Pair<ModelVariant, File>> {
        return ModelVariant.entries.mapNotNull { variant ->
            findGalleryModel(variant)?.let { file -> variant to file }
        }
    }

    /**
     * Copies a model from Google AI Edge Gallery to the app's local storage.
     * This allows the app to use Gallery models without needing storage permissions.
     *
     * @param context Application context
     * @param variant Model variant to copy
     * @param onProgress Callback for copy progress (0.0 to 1.0)
     * @return Result containing the copied file or an error
     */
    suspend fun copyFromGallery(
        context: Context,
        variant: ModelVariant,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val sourceFile = findGalleryModel(variant)
            ?: return@withContext Result.failure(
                FileNotFoundException("Gallery model not found for ${variant.displayName}")
            )

        val targetDir = File(context.filesDir, "models")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val targetFile = File(targetDir, variant.fileName)

        // Check if already copied
        if (targetFile.exists() && targetFile.length() > 0) {
            Log.i(TAG, "Model already copied: ${targetFile.path}")
            return@withContext Result.success(targetFile)
        }

        Log.i(TAG, "Copying model from Gallery: ${sourceFile.path}")

        return@withContext try {
            val totalBytes = sourceFile.length()
            var copiedBytes = 0L

            FileInputStream(sourceFile).use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        copiedBytes += bytesRead

                        val progress = copiedBytes.toFloat() / totalBytes
                        onProgress(progress.coerceIn(0f, 1f))
                    }
                }
            }

            Log.i(TAG, "Copy complete: ${targetFile.path} (${targetFile.length()} bytes)")
            Result.success(targetFile)

        } catch (e: Exception) {
            Log.e(TAG, "Copy failed", e)
            // Clean up partial copy
            if (targetFile.exists()) {
                targetFile.delete()
            }
            Result.failure(e)
        }
    }

    /**
     * Gets the best available model path, prioritizing:
     * 1. Already downloaded/copied model in app storage
     * 2. Google AI Edge Gallery model (if accessible)
     * 3. Returns null if no model available
     */
    fun getBestAvailableModelPath(context: Context, variant: ModelVariant = ModelVariant.GEMMA_3N_E2B): String? {
        // Check app's local storage first
        getLocalModelPath(context, variant)?.let { return it }

        // Check Google AI Edge Gallery
        getGalleryModelPath(variant)?.let { return it }

        return null
    }

    /**
     * Checks if any model is available (either local or from Gallery).
     */
    fun isAnyModelAvailable(context: Context, variant: ModelVariant = ModelVariant.GEMMA_3N_E2B): Boolean {
        return getBestAvailableModelPath(context, variant) != null
    }
}

private class FileNotFoundException(message: String) : Exception(message)
