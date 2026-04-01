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

object Qwen3AsrDownloader {

    private const val TAG = "Qwen3AsrDownloader"

    private val MODEL_NAMES = mapOf(
        Qwen3AsrModelManager.Variant.QWEN3_ASR_0_6B to "sherpa-onnx-qwen3-asr-0.6b-int8"
    )

    fun getModelDirName(variant: Qwen3AsrModelManager.Variant): String = MODEL_NAMES[variant] ?: ""


    private const val HF_BASE_URL = "https://huggingface.co"

    private fun hfFileUrl(repoName: String, fileName: String): String =
        "$HF_BASE_URL/pantinor/$repoName/resolve/main/$fileName"

    private val HF_FILE_NAMES = mapOf(
        Qwen3AsrModelManager.Variant.QWEN3_ASR_0_6B to listOf(
            "conv_frontend.onnx",
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "tokenizer/merges.txt",
            "tokenizer/tokenizer_config.json",
            "tokenizer/vocab.json"
        )
    )

    private var isCancelled = false

    fun detectPartialDownload(context: Context, variant: Qwen3AsrModelManager.Variant): DownloadState.PartiallyDownloaded? {
        val modelDirName = MODEL_NAMES[variant] ?: return null
        val modelDir = File(Qwen3AsrModelManager.getModelStorageDir(context), modelDirName)
        if (!modelDir.exists() || !modelDir.isDirectory) return null
        if (Qwen3AsrModelManager.isValidModelDir(modelDir)) return null
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
        val estimatedSizeMB = variant.estimatedSizeMB
        val estimatedBytes = (estimatedSizeMB * 1024 * 1024 * 1.1).toLong()
        val totalBytes = if (totalExpected > 0) totalExpected else estimatedBytes
        val progressPercent = ((totalDownloaded.toFloat() / totalBytes) * 100).toInt().coerceIn(0, 99)
        return DownloadState.PartiallyDownloaded(bytesDownloaded = totalDownloaded, totalBytes = totalBytes, progressPercent = progressPercent)
    }

    /** Qwen3-ASR models ship as individual files, no tar.bz2 extraction needed. */
    fun needsExtraction(context: Context, variant: Qwen3AsrModelManager.Variant): Boolean = false

    fun clearPartialDownload(context: Context, variant: Qwen3AsrModelManager.Variant): Boolean {
        val modelDirName = MODEL_NAMES[variant] ?: return false
        val modelDir = File(Qwen3AsrModelManager.getModelStorageDir(context), modelDirName)
        if (!modelDir.exists()) return true
        modelDir.deleteRecursively()
        return true
    }

    suspend fun downloadModel(
        context: Context,
        variant: Qwen3AsrModelManager.Variant,
        onProgress: (Float) -> Unit = {},
        onStateChange: (DownloadState) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        isCancelled = false
        val targetDir = Qwen3AsrModelManager.getModelStorageDir(context)
        val modelDirName = MODEL_NAMES[variant] ?: return@withContext Result.failure(IllegalArgumentException("Unknown variant: $variant"))
        val modelDir = File(targetDir, modelDirName)
        if (isModelDownloaded(context, variant)) {
            Log.i(TAG, "Model already downloaded: ${modelDir.absolutePath}")
            onStateChange(DownloadState.Complete(modelDir))
            return@withContext Result.success(modelDir)
        }
        val files = HF_FILE_NAMES[variant]
            ?: return@withContext Result.failure(IllegalArgumentException("No URLs for variant: $variant"))
        val estimatedSizeMB = variant.estimatedSizeMB
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
            if (isCancelled) { onStateChange(DownloadState.Cancelled("User cancelled")); return@withContext Result.failure(Exception("Download cancelled")) }
            val targetFile = File(modelDir, fileName)
            targetFile.parentFile?.mkdirs()
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
            val config = DownloadConfig(url = fileUrl, tempFile = targetFile, targetFile = targetFile, estimatedSizeBytes = 0L, connectTimeoutMs = 60_000, readTimeoutMs = 120_000, isCancelled = { isCancelled })
            val downloadResult = downloadWithRetry(config = config, tag = TAG, onProgress = { fileProg -> onProgress((completedFiles + fileProg) / totalFiles) }, onStateChange = onStateChange)
            if (downloadResult.isFailure) return@withContext downloadResult
            completedFiles++
            onStateChange(DownloadState.Extracting(completedFiles, totalFiles, fileName))
        }
        if (Qwen3AsrModelManager.validateModelDirectory(modelDir) == null) {
            val errorMsg = "Missing files after download in ${modelDir.absolutePath}"
            onStateChange(DownloadState.Error(errorMsg))
            return@withContext Result.failure(Exception(errorMsg))
        }
        Log.i(TAG, "Model ready: ${modelDir.absolutePath}")
        onStateChange(DownloadState.Complete(modelDir))
        Result.success(modelDir)
    }

    fun cancel() { isCancelled = true; Log.i(TAG, "Download cancellation requested") }

    fun isModelDownloaded(context: Context, variant: Qwen3AsrModelManager.Variant): Boolean {
        val modelDirName = MODEL_NAMES[variant] ?: return false
        val modelDir = File(Qwen3AsrModelManager.getModelStorageDir(context), modelDirName)
        return Qwen3AsrModelManager.isValidModelDir(modelDir)
    }

    fun getModelPath(context: Context, variant: Qwen3AsrModelManager.Variant): String? {
        val modelDirName = MODEL_NAMES[variant] ?: return null
        val modelDir = File(Qwen3AsrModelManager.getModelStorageDir(context), modelDirName)
        return if (Qwen3AsrModelManager.isValidModelDir(modelDir)) modelDir.absolutePath else null
    }

    fun getEstimatedSizeMB(variant: Qwen3AsrModelManager.Variant): Long = variant.estimatedSizeMB

    fun deleteModel(context: Context, variant: Qwen3AsrModelManager.Variant): Boolean {
        val modelDirName = MODEL_NAMES[variant] ?: return false
        val modelDir = File(Qwen3AsrModelManager.getModelStorageDir(context), modelDirName)
        if (!modelDir.exists()) return true
        return try { modelDir.deleteRecursively(); Log.i(TAG, "Model deleted: ${modelDir.absolutePath}"); true }
        catch (e: Exception) { Log.e(TAG, "Failed to delete model", e); false }
    }
}
