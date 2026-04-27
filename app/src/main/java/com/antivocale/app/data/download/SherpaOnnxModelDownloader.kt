package com.antivocale.app.data.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Generic downloader for sherpa-onnx models from HuggingFace.
 *
 * All sherpa-onnx backends (Whisper, Qwen3-ASR, etc.) share the same download
 * algorithm — only the model metadata differs. This class captures the shared
 * logic; each backend provides a [SherpaOnnxModelConfig] that specifies its
 * variants, file lists, sizes, and validation.
 *
 * Usage: Create a thin facade object that holds a [SherpaOnnxModelConfig] and
 * delegates every public method to an instance of this class.
 */
class SherpaOnnxModelDownloader<V>(
    private val config: SherpaOnnxModelConfig<V>
) {

    private val cancelFlags = ConcurrentHashMap<V, Boolean>()

    fun getModelDirName(variant: V): String = config.modelDirNames[variant] ?: ""

    fun detectPartialDownload(context: Context, variant: V): DownloadState.PartiallyDownloaded? {
        val modelDirName = config.modelDirNames[variant] ?: return null
        val modelDir = File(config.modelStorageDir(context), modelDirName)
        if (!modelDir.exists() || !modelDir.isDirectory) return null
        if (config.isValidModel(modelDir)) return null

        val files = config.hfFileNames[variant] ?: return null
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

        val estimatedSizeMB = config.estimatedSizeMB(variant)
        val estimatedBytes = (estimatedSizeMB * 1024 * 1024 * 1.1).toLong()
        val totalBytes = if (totalExpected > 0) totalExpected else estimatedBytes
        val progressPercent = ((totalDownloaded.toFloat() / totalBytes) * 100).toInt().coerceIn(0, 99)

        return DownloadState.PartiallyDownloaded(
            bytesDownloaded = totalDownloaded,
            totalBytes = totalBytes,
            progressPercent = progressPercent
        )
    }

    /** No sherpa-onnx models use tar.bz2 extraction — all are individual files. */
    fun needsExtraction(context: Context, variant: V): Boolean = false

    fun clearPartialDownload(context: Context, variant: V): Boolean {
        val modelDirName = config.modelDirNames[variant] ?: return false
        val modelDir = File(config.modelStorageDir(context), modelDirName)
        if (!modelDir.exists()) return true
        modelDir.deleteRecursively()
        return true
    }

    suspend fun downloadModel(
        context: Context,
        variant: V,
        onProgress: (Float) -> Unit = {},
        onStateChange: (DownloadState) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        cancelFlags[variant] = false
        try {

        val targetDir = config.modelStorageDir(context)
        val modelDirName = config.modelDirNames[variant] ?: return@withContext Result.failure(
            IllegalArgumentException("Unknown variant: $variant")
        )
        val modelDir = File(targetDir, modelDirName)

        if (isModelDownloaded(context, variant)) {
            Log.i(config.tag, "Model already downloaded: ${modelDir.absolutePath}")
            onStateChange(DownloadState.Complete(modelDir))
            return@withContext Result.success(modelDir)
        }

        val files = config.hfFileNames[variant]
            ?: return@withContext Result.failure(IllegalArgumentException("No URLs for variant: $variant"))

        val estimatedSizeMB = config.estimatedSizeMB(variant)
        val requiredBytes = (estimatedSizeMB * 1024 * 1024 * 1.1).toLong()
        val availableBytes = context.filesDir.usableSpace
        if (availableBytes < requiredBytes) {
            val errorMsg = "Insufficient storage. Need ~${estimatedSizeMB}MB, have ${availableBytes / (1024 * 1024)}MB available."
            val error = Exception(errorMsg)
            Log.e(config.tag, errorMsg)
            onStateChange(DownloadState.Error(errorMsg, error))
            return@withContext Result.failure(error)
        }

        if (!modelDir.exists()) modelDir.mkdirs()

        val totalFiles = files.size
        val completedFiles = AtomicInteger(0)

        // Phase 1: Identify already-complete files
        val filesToDownload = mutableListOf<String>()
        for (fileName in files) {
            val targetFile = File(modelDir, fileName)
            val sidecar = ResumeDownloadHelper.sizeSidecar(targetFile)
            val storedTotal = sidecar.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
            if (targetFile.exists() && storedTotal != null && targetFile.length() >= storedTotal) {
                Log.i(config.tag, "File already complete, skipping: $fileName")
                val done = completedFiles.incrementAndGet()
                onProgress(done.toFloat() / totalFiles)
            } else {
                filesToDownload.add(fileName)
            }
        }

        if (completedFiles.get() > 0) {
            onStateChange(DownloadState.CopyingFiles(completedFiles.get(), totalFiles, ""))
        }

        // Phase 2: Download remaining files in parallel
        if (filesToDownload.isNotEmpty()) {
            if (cancelFlags[variant] == true) {
                onStateChange(DownloadState.Cancelled("User cancelled"))
                return@withContext Result.failure(Exception("Download cancelled"))
            }

            try {
                coroutineScope {
                    filesToDownload.map { fileName ->
                        async(Dispatchers.IO) {
                            if (cancelFlags[variant] == true) return@async

                            val targetFile = File(modelDir, fileName)
                            if (config.ensureParentDirs) {
                                targetFile.parentFile?.mkdirs()
                            }

                            Log.i(config.tag, "Downloading: $fileName")

                            val downloadConfig = DownloadConfig(
                                url = hfFileUrl(modelDirName, fileName),
                                tempFile = targetFile,
                                targetFile = targetFile,
                                estimatedSizeBytes = 0L,
                                connectTimeoutMs = 60_000,
                                readTimeoutMs = 120_000,
                                isCancelled = { cancelFlags[variant] == true }
                            )

                            val downloadResult = downloadWithRetry(
                                config = downloadConfig,
                                tag = config.tag,
                                onProgress = { },
                                onStateChange = { }
                            )

                            if (downloadResult.isFailure) {
                                throw downloadResult.exceptionOrNull()
                                    ?: Exception("Download failed: $fileName")
                            }

                            val done = completedFiles.incrementAndGet()
                            onProgress(done.toFloat() / totalFiles)
                            onStateChange(DownloadState.CopyingFiles(done, totalFiles, fileName))
                        }
                    }.awaitAll()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (cancelFlags[variant] == true) {
                    onStateChange(DownloadState.Cancelled("User cancelled"))
                    return@withContext Result.failure(Exception("Download cancelled"))
                }
                throw e
            } catch (e: Exception) {
                onStateChange(DownloadState.Error(e.message ?: "Download failed", e))
                return@withContext Result.failure(e)
            }
        }

        if (!config.isValidModel(modelDir)) {
            val errorMsg = "Missing files after download in ${modelDir.absolutePath}"
            onStateChange(DownloadState.Error(errorMsg))
            return@withContext Result.failure(Exception(errorMsg))
        }

        Log.i(config.tag, "Model ready: ${modelDir.absolutePath}")
        onStateChange(DownloadState.Complete(modelDir))
        Result.success(modelDir)
        } finally {
            cancelFlags.remove(variant)
        }
    }

    fun cancel(variant: V) {
        cancelFlags[variant] = true
        Log.i(config.tag, "Download cancellation requested for variant: $variant")
    }

    fun cancel() {
        cancelFlags.keys.forEach { cancelFlags[it] = true }
        Log.i(config.tag, "Download cancellation requested for all variants")
    }

    fun isModelDownloaded(context: Context, variant: V): Boolean {
        val modelDirName = config.modelDirNames[variant] ?: return false
        val modelDir = File(config.modelStorageDir(context), modelDirName)
        return config.isValidModel(modelDir)
    }

    fun getModelPath(context: Context, variant: V): String? {
        val modelDirName = config.modelDirNames[variant] ?: return null
        val modelDir = File(config.modelStorageDir(context), modelDirName)
        return if (config.isValidModel(modelDir)) modelDir.absolutePath else null
    }

    fun getEstimatedSizeMB(variant: V): Long = config.estimatedSizeMB(variant)

    fun deleteModel(context: Context, variant: V): Boolean {
        val modelDirName = config.modelDirNames[variant] ?: return false
        val modelDir = File(config.modelStorageDir(context), modelDirName)
        if (!modelDir.exists()) return true

        return try {
            modelDir.deleteRecursively()
            Log.i(config.tag, "Model deleted: ${modelDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(config.tag, "Failed to delete model", e)
            false
        }
    }

    companion object {
        private const val HF_BASE_URL = "https://huggingface.co"

        private fun hfFileUrl(repoName: String, fileName: String): String =
            "$HF_BASE_URL/pantinor/$repoName/resolve/main/$fileName"
    }
}

/**
 * Configuration that captures all model-specific behavior for a sherpa-onnx backend.
 *
 * @param V The variant enum type (e.g. WhisperModelManager.Variant)
 * @param tag Logcat tag for this backend
 * @param modelDirNames Map from variant to HuggingFace repo / directory name
 * @param hfFileNames Map from variant to list of files to download
 * @param estimatedSizeMB Returns estimated download size in MB for a variant
 * @param modelStorageDir Returns the base storage directory for this backend
 * @param isValidModel Returns true if the model directory contains all required files
 * @param ensureParentDirs If true, creates parent directories for each file (e.g. tokenizer/)
 */
data class SherpaOnnxModelConfig<V>(
    val tag: String,
    val modelDirNames: Map<V, String>,
    val hfFileNames: Map<V, List<String>>,
    val estimatedSizeMB: (V) -> Long,
    val modelStorageDir: (Context) -> File,
    val isValidModel: (File) -> Boolean,
    val ensureParentDirs: Boolean = false
)
