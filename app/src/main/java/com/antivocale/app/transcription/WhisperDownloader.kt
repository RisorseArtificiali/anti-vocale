package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.data.download.SherpaOnnxModelConfig
import com.antivocale.app.data.download.SherpaOnnxModelDownloader
import java.io.File

/**
 * Downloads Whisper models for sherpa-onnx from HuggingFace.
 *
 * All models are downloaded as individual files (no tar.bz2 extraction),
 * which is significantly faster on mobile devices.
 *
 * Delegates to [SherpaOnnxModelDownloader] with Whisper-specific configuration.
 */
object WhisperDownloader {

    private val ESTIMATED_SIZES = mapOf(
        WhisperModelManager.Variant.SMALL to 358L,
        WhisperModelManager.Variant.TURBO to 988L,
        WhisperModelManager.Variant.MEDIUM to 903L,
        WhisperModelManager.Variant.DISTIL_LARGE_V3 to 938L
    )

    private val config = SherpaOnnxModelConfig(
        tag = "WhisperDownloader",
        modelDirNames = mapOf(
            WhisperModelManager.Variant.SMALL to "sherpa-onnx-whisper-small",
            WhisperModelManager.Variant.TURBO to "sherpa-onnx-whisper-turbo",
            WhisperModelManager.Variant.MEDIUM to "sherpa-onnx-whisper-medium",
            WhisperModelManager.Variant.DISTIL_LARGE_V3 to "sherpa-onnx-whisper-distil-large-v3-it"
        ),
        hfFileNames = mapOf(
            WhisperModelManager.Variant.SMALL to listOf(
                "small-encoder.int8.onnx", "small-decoder.int8.onnx", "small-tokens.txt"
            ),
            WhisperModelManager.Variant.TURBO to listOf(
                "turbo-encoder.int8.onnx", "turbo-decoder.int8.onnx", "turbo-tokens.txt"
            ),
            WhisperModelManager.Variant.MEDIUM to listOf(
                "medium-encoder.int8.onnx", "medium-decoder.int8.onnx", "medium-tokens.txt"
            ),
            WhisperModelManager.Variant.DISTIL_LARGE_V3 to listOf(
                "distil-large-v3-it-encoder.int8.onnx", "distil-large-v3-it-decoder.int8.onnx", "distil-large-v3-it-tokens.txt"
            )
        ),
        estimatedSizeMB = { variant -> ESTIMATED_SIZES[variant] ?: 75L },
        modelStorageDir = { context -> WhisperModelManager.getModelStorageDir(context) },
        isValidModel = { dir -> WhisperModelManager.validateModelDirectory(dir) != null }
    )

    private val delegate = SherpaOnnxModelDownloader(config)

    fun getModelDirName(variant: WhisperModelManager.Variant): String =
        delegate.getModelDirName(variant)

    fun detectPartialDownload(context: Context, variant: WhisperModelManager.Variant): DownloadState.PartiallyDownloaded? =
        delegate.detectPartialDownload(context, variant)

    fun needsExtraction(context: Context, variant: WhisperModelManager.Variant): Boolean =
        delegate.needsExtraction(context, variant)

    fun clearPartialDownload(context: Context, variant: WhisperModelManager.Variant): Boolean =
        delegate.clearPartialDownload(context, variant)

    suspend fun downloadModel(
        context: Context,
        variant: WhisperModelManager.Variant,
        onProgress: (Float) -> Unit = {},
        onStateChange: (DownloadState) -> Unit = {}
    ): Result<File> = delegate.downloadModel(context, variant, onProgress, onStateChange)

    fun cancel(variant: WhisperModelManager.Variant) = delegate.cancel(variant)
    fun cancel() = delegate.cancel()

    fun isModelDownloaded(context: Context, variant: WhisperModelManager.Variant): Boolean =
        delegate.isModelDownloaded(context, variant)

    fun getModelPath(context: Context, variant: WhisperModelManager.Variant): String? =
        delegate.getModelPath(context, variant)

    fun getEstimatedSizeMB(variant: WhisperModelManager.Variant): Long =
        delegate.getEstimatedSizeMB(variant)

    fun deleteModel(context: Context, variant: WhisperModelManager.Variant): Boolean =
        delegate.deleteModel(context, variant)
}
