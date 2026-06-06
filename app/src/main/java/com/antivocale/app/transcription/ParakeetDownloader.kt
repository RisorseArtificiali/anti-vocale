package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.data.download.SherpaOnnxModelConfig
import com.antivocale.app.data.download.SherpaOnnxModelDownloader
import java.io.File

/**
 * Downloads the Parakeet TDT model for sherpa-onnx from HuggingFace.
 *
 * Downloads individual ONNX files directly — no tar.bz2 extraction needed.
 * This is significantly faster than the previous archive-based approach.
 *
 * Delegates to [SherpaOnnxModelDownloader] with Parakeet-specific configuration.
 */
object ParakeetDownloader {

    private const val MODEL_DIR_NAME = "parakeet-tdt-0.6b-v3-smoothquant"
    private const val ESTIMATED_SIZE_MB = 862L

    private val config = SherpaOnnxModelConfig(
        tag = "ParakeetDownloader",
        modelDirNames = mapOf(Unit to MODEL_DIR_NAME),
        hfRepoNames = mapOf(Unit to "pantinor/parakeet-tdt-0.6b-v3-smoothquant"),
        hfFileNames = mapOf(
            Unit to ParakeetModelManager.REQUIRED_FILES
        ),
        estimatedSizeMB = { ESTIMATED_SIZE_MB },
        modelStorageDir = { context -> ParakeetModelManager.getModelStorageDir(context) },
        isValidModel = { dir -> ParakeetModelManager.validateModelDirectory(dir) != null }
    )

    private val delegate = SherpaOnnxModelDownloader(config)

    fun detectPartialDownload(context: Context): DownloadState.PartiallyDownloaded? =
        delegate.detectPartialDownload(context, Unit)

    fun clearPartialDownload(context: Context): Boolean =
        delegate.clearPartialDownload(context, Unit)

    suspend fun downloadModel(
        context: Context,
        onProgress: (Float) -> Unit = {},
        onStateChange: (DownloadState) -> Unit = {}
    ): Result<File> = delegate.downloadModel(context, Unit, onProgress, onStateChange)

    fun cancel() = delegate.cancel(Unit)

    fun isModelDownloaded(context: Context): Boolean =
        delegate.isModelDownloaded(context, Unit)

    fun getModelPath(context: Context): String? =
        delegate.getModelPath(context, Unit)

    fun getEstimatedSizeMB(): Long = delegate.getEstimatedSizeMB(Unit)

    fun deleteModel(context: Context): Boolean =
        delegate.deleteModel(context, Unit)
}
