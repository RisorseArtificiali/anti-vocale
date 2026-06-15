package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.data.download.SherpaOnnxModelConfig
import com.antivocale.app.data.download.SherpaOnnxModelDownloader
import java.io.File

/**
 * Downloads Omnilingual ASR v2 (CTC) models for sherpa-onnx from HuggingFace.
 *
 * The model is just an encoder ONNX + a tokens.txt — no subdirectories. Delegates to
 * [SherpaOnnxModelDownloader] with Omnilingual-ASR-specific configuration. Files are fetched
 * from the csukuangfj2 namespace (overriding the default "pantinor/" repo prefix).
 */
object OmnilingualAsrDownloader {

    private val config = SherpaOnnxModelConfig(
        tag = "OmnilingualAsrDownloader",
        modelDirNames = mapOf(
            OmnilingualAsrModelManager.Variant.OMNILINGUAL_ASR_300M to
                OmnilingualAsrModelManager.Variant.OMNILINGUAL_ASR_300M.dirName
        ),
        // Omnilingual ASR lives under the csukuangfj2 namespace, not the default pantinor/.
        hfRepoNames = mapOf(
            OmnilingualAsrModelManager.Variant.OMNILINGUAL_ASR_300M to
                "csukuangfj2/${OmnilingualAsrModelManager.Variant.OMNILINGUAL_ASR_300M.dirName}"
        ),
        hfFileNames = mapOf(
            OmnilingualAsrModelManager.Variant.OMNILINGUAL_ASR_300M to listOf(
                "encoder.int8.onnx",
                "tokens.txt"
            )
        ),
        estimatedSizeMB = { variant -> variant.estimatedSizeMB },
        modelStorageDir = { context -> OmnilingualAsrModelManager.getModelStorageDir(context) },
        isValidModel = { dir -> OmnilingualAsrModelManager.isValidModelDir(dir) },
        ensureParentDirs = false
    )

    private val delegate = SherpaOnnxModelDownloader(config)

    fun getModelDirName(variant: OmnilingualAsrModelManager.Variant): String =
        delegate.getModelDirName(variant)

    fun detectPartialDownload(context: Context, variant: OmnilingualAsrModelManager.Variant): DownloadState.PartiallyDownloaded? =
        delegate.detectPartialDownload(context, variant)

    fun needsExtraction(context: Context, variant: OmnilingualAsrModelManager.Variant): Boolean =
        delegate.needsExtraction(context, variant)

    fun clearPartialDownload(context: Context, variant: OmnilingualAsrModelManager.Variant): Boolean =
        delegate.clearPartialDownload(context, variant)

    suspend fun downloadModel(
        context: Context,
        variant: OmnilingualAsrModelManager.Variant,
        onProgress: (Float) -> Unit = {},
        onStateChange: (DownloadState) -> Unit = {}
    ): Result<File> = delegate.downloadModel(context, variant, onProgress, onStateChange)

    fun cancel(variant: OmnilingualAsrModelManager.Variant) = delegate.cancel(variant)
    fun cancel() = delegate.cancel()

    fun isModelDownloaded(context: Context, variant: OmnilingualAsrModelManager.Variant): Boolean =
        delegate.isModelDownloaded(context, variant)

    fun getModelPath(context: Context, variant: OmnilingualAsrModelManager.Variant): String? =
        delegate.getModelPath(context, variant)

    fun getEstimatedSizeMB(variant: OmnilingualAsrModelManager.Variant): Long =
        delegate.getEstimatedSizeMB(variant)

    fun deleteModel(context: Context, variant: OmnilingualAsrModelManager.Variant): Boolean =
        delegate.deleteModel(context, variant)
}
