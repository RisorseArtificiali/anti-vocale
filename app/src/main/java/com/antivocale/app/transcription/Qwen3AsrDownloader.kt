package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.data.download.SherpaOnnxModelConfig
import com.antivocale.app.data.download.SherpaOnnxModelDownloader
import java.io.File

/**
 * Downloads Qwen3-ASR models for sherpa-onnx from HuggingFace.
 *
 * Models ship as individual files including a tokenizer/ subdirectory.
 * Delegates to [SherpaOnnxModelDownloader] with Qwen3-ASR-specific configuration.
 */
object Qwen3AsrDownloader {

    private val config = SherpaOnnxModelConfig(
        tag = "Qwen3AsrDownloader",
        modelDirNames = mapOf(
            Qwen3AsrModelManager.Variant.QWEN3_ASR_0_6B to "sherpa-onnx-qwen3-asr-0.6b-int8"
        ),
        hfFileNames = mapOf(
            Qwen3AsrModelManager.Variant.QWEN3_ASR_0_6B to listOf(
                "conv_frontend.onnx",
                "encoder.int8.onnx",
                "decoder.int8.onnx",
                "tokenizer/merges.txt",
                "tokenizer/tokenizer_config.json",
                "tokenizer/vocab.json"
            )
        ),
        estimatedSizeMB = { variant -> variant.estimatedSizeMB },
        modelStorageDir = { context -> Qwen3AsrModelManager.getModelStorageDir(context) },
        isValidModel = { dir -> Qwen3AsrModelManager.isValidModelDir(dir) },
        ensureParentDirs = true
    )

    private val delegate = SherpaOnnxModelDownloader(config)

    fun getModelDirName(variant: Qwen3AsrModelManager.Variant): String =
        delegate.getModelDirName(variant)

    fun detectPartialDownload(context: Context, variant: Qwen3AsrModelManager.Variant): DownloadState.PartiallyDownloaded? =
        delegate.detectPartialDownload(context, variant)

    fun needsExtraction(context: Context, variant: Qwen3AsrModelManager.Variant): Boolean =
        delegate.needsExtraction(context, variant)

    fun clearPartialDownload(context: Context, variant: Qwen3AsrModelManager.Variant): Boolean =
        delegate.clearPartialDownload(context, variant)

    suspend fun downloadModel(
        context: Context,
        variant: Qwen3AsrModelManager.Variant,
        onProgress: (Float) -> Unit = {},
        onStateChange: (DownloadState) -> Unit = {}
    ): Result<File> = delegate.downloadModel(context, variant, onProgress, onStateChange)

    fun cancel(variant: Qwen3AsrModelManager.Variant) = delegate.cancel(variant)
    fun cancel() = delegate.cancel()

    fun isModelDownloaded(context: Context, variant: Qwen3AsrModelManager.Variant): Boolean =
        delegate.isModelDownloaded(context, variant)

    fun getModelPath(context: Context, variant: Qwen3AsrModelManager.Variant): String? =
        delegate.getModelPath(context, variant)

    fun getEstimatedSizeMB(variant: Qwen3AsrModelManager.Variant): Long =
        delegate.getEstimatedSizeMB(variant)

    fun deleteModel(context: Context, variant: Qwen3AsrModelManager.Variant): Boolean =
        delegate.deleteModel(context, variant)
}
