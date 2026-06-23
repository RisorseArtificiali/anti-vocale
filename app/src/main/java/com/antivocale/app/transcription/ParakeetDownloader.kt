package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.data.download.SherpaOnnxModelConfig
import com.antivocale.app.data.download.SherpaOnnxModelDownloader
import java.io.File

/**
 * Downloads Parakeet TDT models for sherpa-onnx from HuggingFace.
 *
 * Downloads individual ONNX files directly — no tar.bz2 extraction needed.
 * Two variants:
 * - [ParakeetModelManager.Variant.SMOOTHQUANT] — `pantinor/` namespace (default)
 * - [ParakeetModelManager.Variant.STOCK_INT8] — `csukuangfj/` namespace
 *
 * Both ship the same four files ([ParakeetModelManager.REQUIRED_FILES]).
 * Delegates to [SherpaOnnxModelDownloader] with Parakeet-specific configuration.
 */
object ParakeetDownloader {

    private val config = SherpaOnnxModelConfig(
        tag = "ParakeetDownloader",
        modelDirNames = mapOf(
            ParakeetModelManager.Variant.SMOOTHQUANT to
                ParakeetModelManager.Variant.SMOOTHQUANT.dirName,
            ParakeetModelManager.Variant.STOCK_INT8 to
                ParakeetModelManager.Variant.STOCK_INT8.dirName
        ),
        // Repo names are LITERAL — do NOT interpolate dirName. The on-device dirName is only the
        // local storage folder; the HF repo names differ (SmoothQuant repo suffix == its dirName,
        // but the csukuangfj stock repo has a "sherpa-onnx-nemo-" prefix that is NOT the dirName).
        hfRepoNames = mapOf(
            ParakeetModelManager.Variant.SMOOTHQUANT to
                "pantinor/parakeet-tdt-0.6b-v3-smoothquant",
            ParakeetModelManager.Variant.STOCK_INT8 to
                "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
        ),
        hfFileNames = mapOf(
            ParakeetModelManager.Variant.SMOOTHQUANT to ParakeetModelManager.REQUIRED_FILES,
            ParakeetModelManager.Variant.STOCK_INT8 to ParakeetModelManager.REQUIRED_FILES
        ),
        estimatedSizeMB = { variant -> variant.estimatedSizeMB },
        modelStorageDir = { context -> ParakeetModelManager.getModelStorageDir(context) },
        isValidModel = { dir -> ParakeetModelManager.validateModelDirectory(dir) != null },
        ensureParentDirs = false
    )

    private val delegate = SherpaOnnxModelDownloader(config)

    fun getModelDirName(variant: ParakeetModelManager.Variant): String =
        delegate.getModelDirName(variant)

    fun detectPartialDownload(context: Context, variant: ParakeetModelManager.Variant): DownloadState.PartiallyDownloaded? =
        delegate.detectPartialDownload(context, variant)

    fun needsExtraction(context: Context, variant: ParakeetModelManager.Variant): Boolean =
        delegate.needsExtraction(context, variant)

    fun clearPartialDownload(context: Context, variant: ParakeetModelManager.Variant): Boolean =
        delegate.clearPartialDownload(context, variant)

    suspend fun downloadModel(
        context: Context,
        variant: ParakeetModelManager.Variant,
        onProgress: (Float) -> Unit = {},
        onStateChange: (DownloadState) -> Unit = {}
    ): Result<File> = delegate.downloadModel(context, variant, onProgress, onStateChange)

    fun cancel(variant: ParakeetModelManager.Variant) = delegate.cancel(variant)
    fun cancel() = delegate.cancel()

    fun isModelDownloaded(context: Context, variant: ParakeetModelManager.Variant): Boolean =
        delegate.isModelDownloaded(context, variant)

    fun getModelPath(context: Context, variant: ParakeetModelManager.Variant): String? =
        delegate.getModelPath(context, variant)

    fun getEstimatedSizeMB(variant: ParakeetModelManager.Variant): Long =
        delegate.getEstimatedSizeMB(variant)

    fun deleteModel(context: Context, variant: ParakeetModelManager.Variant): Boolean =
        delegate.deleteModel(context, variant)
}
