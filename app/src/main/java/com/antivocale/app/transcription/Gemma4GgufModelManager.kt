package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.R
import com.antivocale.app.data.download.DownloadConfig
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.data.download.ResumeDownloadHelper
import com.antivocale.app.data.download.downloadWithRetry as sharedDownloadWithRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages GGUF model downloads for Gemma 4 E4B-it OBLITERATED.
 *
 * Models are hosted on HuggingFace (Apache 2.0 license) and downloaded as
 * single .gguf files. No extraction step is needed — the file is used
 * directly by [com.antivocale.app.llm.LlamaCppEngine] for inference.
 *
 * Available quantisation variants:
 * - **Q4_K_M**: 4.9 GB — recommended, best balance of quality and size
 * - **Q5_K_M**: 5.3 GB — higher quality at moderate size increase
 * - **Q8_0**:    7.4 GB — best quality, requires 8 GB+ RAM
 *
 * Storage layout:
 * ```
 * context.filesDir/
 *   models/
 *     gguf/
 *       gemma-4-E4B-it-OBLITERATED-Q4_K_M.gguf
 *       gemma-4-E4B-it-OBLITERATED-Q5_K_M.gguf
 *       gemma-4-E4B-it-OBLITERATED-Q8_0.gguf
 * ```
 *
 * Follows the same download patterns as [ModelDownloader]:
 * resume via HTTP Range, retry with exponential backoff, per-variant
 * cancel flags for concurrent downloads.
 */
object Gemma4GgufModelManager {

    private const val TAG = "Gemma4GgufModelMgr"
    private const val MAX_RETRIES = 3
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val TMP_FILE_EXT = ".tmp"
    private const val GGUF_SUBDIR = "gguf"

    private const val HF_REPO = "OBLITERATUS/gemma-4-E4B-it-OBLITERATED"
    private const val HF_BASE_URL = "https://huggingface.co/$HF_REPO/resolve/main"

    /**
     * Available GGUF quantisation variants for Gemma 4 E4B-it OBLITERATED.
     *
     * Implements [ModelVariant] so the generic UI ([ModelVariantCard]) can
     * render these alongside Whisper and other model types.
     */
    enum class GgufVariant(
        override val titleResId: Int,
        override val descriptionResId: Int,
        override val dirName: String,
        override val estimatedSizeMB: Long,
        val fileName: String
    ) : ModelVariant {
        Q4_K_M(
            titleResId = R.string.model_title_gemma4_gguf_q4km,
            descriptionResId = R.string.model_desc_gemma4_gguf_q4km,
            dirName = "gemma-4-gguf-q4km",
            estimatedSizeMB = 4900L,
            fileName = "gemma-4-E4B-it-OBLITERATED-Q4_K_M.gguf"
        ),
        Q5_K_M(
            titleResId = R.string.model_title_gemma4_gguf_q5km,
            descriptionResId = R.string.model_desc_gemma4_gguf_q5km,
            dirName = "gemma-4-gguf-q5km",
            estimatedSizeMB = 5300L,
            fileName = "gemma-4-E4B-it-OBLITERATED-Q5_K_M.gguf"
        ),
        Q8_0(
            titleResId = R.string.model_title_gemma4_gguf_q8,
            descriptionResId = R.string.model_desc_gemma4_gguf_q8,
            dirName = "gemma-4-gguf-q8",
            estimatedSizeMB = 7400L,
            fileName = "gemma-4-E4B-it-OBLITERATED-Q8_0.gguf"
        )
    }

    /** Per-variant cancel flags — supports concurrent downloads of different variants. */
    private val cancelFlags = ConcurrentHashMap<GgufVariant, Boolean>()

    // ── Storage helpers ─────────────────────────────────────────────────────

    /**
     * Returns the base directory for GGUF models:
     * `context.filesDir/models/gguf/`
     */
    fun getModelStorageDir(context: Context): File =
        File(context.filesDir, "models/$GGUF_SUBDIR")

    /**
     * Returns the local .gguf file for a variant, or null if not downloaded.
     */
    fun getLocalPath(context: Context, variant: GgufVariant): String? {
        val file = File(getModelStorageDir(context), variant.fileName)
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    /**
     * Returns true when the .gguf file for [variant] exists and is non-empty.
     */
    fun isDownloaded(context: Context, variant: GgufVariant): Boolean =
        getLocalPath(context, variant) != null

    // ── Partial-download detection ──────────────────────────────────────────

    /**
     * Detects a partial download (leftover .tmp file) for [variant].
     *
     * @return [DownloadState.PartiallyDownloaded] if a .tmp file exists, null otherwise
     */
    fun detectPartialDownload(context: Context, variant: GgufVariant): DownloadState.PartiallyDownloaded? {
        val tempFile = File(getModelStorageDir(context), "${variant.fileName}$TMP_FILE_EXT")
        if (!tempFile.exists() || tempFile.length() == 0L) return null

        val downloadedBytes = tempFile.length()
        val estimatedBytes = variant.estimatedSizeMB * 1024 * 1024
        val totalBytes = ResumeDownloadHelper.readStoredTotalBytes(tempFile, estimatedBytes)
        val progressPercent = ((downloadedBytes.toFloat() / totalBytes) * 100).toInt().coerceIn(0, 99)

        return DownloadState.PartiallyDownloaded(
            bytesDownloaded = downloadedBytes,
            totalBytes = totalBytes,
            progressPercent = progressPercent
        )
    }

    /**
     * Deletes the partial .tmp file for [variant].
     */
    fun clearPartialDownload(context: Context, variant: GgufVariant): Boolean {
        val tempFile = File(getModelStorageDir(context), "${variant.fileName}$TMP_FILE_EXT")
        return if (tempFile.exists()) tempFile.delete() else true
    }

    // ── Download ────────────────────────────────────────────────────────────

    /**
     * Downloads a GGUF model from HuggingFace.
     *
     * The model is publicly accessible (Apache 2.0) — no auth token needed.
     * Uses the shared [sharedDownloadWithRetry] helper for resume + retry.
     *
     * @param context Application context
     * @param variant Quantisation variant to download
     * @param onProgress Callback for download progress (0.0 to 1.0)
     * @param onStateChange Callback for state transitions
     * @return Result containing the downloaded .gguf file or an error
     */
    suspend fun download(
        context: Context,
        variant: GgufVariant,
        onProgress: (Float) -> Unit = {},
        onStateChange: (DownloadState) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        cancelFlags[variant] = false
        try {
            // Pre-download storage check
            val requiredBytes = variant.estimatedSizeMB * 1024 * 1024
            val availableBytes = context.filesDir.usableSpace
            if (availableBytes < requiredBytes) {
                val errorMsg = "Insufficient storage. Need ${variant.estimatedSizeMB}MB, " +
                    "have ${availableBytes / (1024 * 1024)}MB available."
                val error = Exception(errorMsg)
                Log.e(TAG, errorMsg)
                onStateChange(DownloadState.Error(errorMsg, error))
                return@withContext Result.failure(error)
            }

            val targetDir = getModelStorageDir(context)
            if (!targetDir.exists()) targetDir.mkdirs()

            val targetFile = File(targetDir, variant.fileName)

            // Already downloaded
            if (targetFile.exists() && targetFile.length() > 0) {
                Log.i(TAG, "Model already exists: ${targetFile.path}")
                onStateChange(DownloadState.Complete(targetFile))
                return@withContext Result.success(targetFile)
            }

            val downloadUrl = "$HF_BASE_URL/${variant.fileName}"

            // Download with retry + resume
            onStateChange(DownloadState.Connecting(downloadUrl))
            val tempFile = File(targetDir, "${variant.fileName}$TMP_FILE_EXT")

            val result = sharedDownloadWithRetry(
                config = DownloadConfig(
                    url = downloadUrl,
                    tempFile = tempFile,
                    targetFile = targetFile,
                    estimatedSizeBytes = variant.estimatedSizeMB * 1024 * 1024,
                    authHeader = null,  // Publicly accessible — no auth needed
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    readTimeoutMs = READ_TIMEOUT_MS,
                    isCancelled = { cancelFlags[variant] == true }
                ),
                maxRetries = MAX_RETRIES,
                backoffBase = 3.0,
                tag = TAG,
                onProgress = onProgress,
                onStateChange = onStateChange
            )

            if (result.isSuccess) {
                Log.i(TAG, "GGUF model ready: ${targetFile.absolutePath}")
                onStateChange(DownloadState.Complete(targetFile))
            }

            result
        } finally {
            cancelFlags.remove(variant)
        }
    }

    // ── Cancel ──────────────────────────────────────────────────────────────

    /**
     * Cancels the ongoing download for [variant].
     */
    fun cancel(variant: GgufVariant) {
        cancelFlags[variant] = true
        Log.i(TAG, "Download cancellation requested for ${variant.name}")
    }

    /**
     * Cancels all ongoing downloads.
     */
    fun cancel() {
        cancelFlags.keys.forEach { cancelFlags[it] = true }
        Log.i(TAG, "Download cancellation requested for all variants")
    }

    // ── Delete ──────────────────────────────────────────────────────────────

    /**
     * Deletes the downloaded .gguf file and any leftover .tmp for [variant].
     *
     * @return true if anything was deleted
     */
    fun delete(context: Context, variant: GgufVariant): Boolean {
        val dir = getModelStorageDir(context)
        val file = File(dir, variant.fileName)
        val tempFile = File(dir, "${variant.fileName}$TMP_FILE_EXT")

        var deleted = false
        if (file.exists()) deleted = file.delete() || deleted
        if (tempFile.exists()) deleted = tempFile.delete() || deleted

        Log.i(TAG, "Model deleted: ${variant.fileName}, success=$deleted")
        return deleted
    }
}
