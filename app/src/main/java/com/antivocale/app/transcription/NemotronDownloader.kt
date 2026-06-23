package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.data.download.SherpaOnnxModelConfig
import com.antivocale.app.data.download.SherpaOnnxModelDownloader
import java.io.File

/**
 * Downloads the Nemotron streaming ASR model for sherpa-onnx from HuggingFace.
 *
 * Downloads individual ONNX files directly — no tar.bz2 extraction needed.
 * This is significantly faster than the previous archive-based approach.
 *
 * Delegates to [SherpaOnnxModelDownloader] with Nemotron-specific configuration.
 */
object NemotronDownloader {

    /**
     * The directory name used for the current Nemotron variant. Read-only exposure so
     * [NemotronModelManager.validModelDirNames] can feed [cleanOrphanedModelDirs].
     * Changing this is a format pivot; the previous value becomes an orphaned dir.
     */
    val modelDirName: String get() = MODEL_DIR_NAME

    private const val MODEL_DIR_NAME = "nemotron-3.5-asr-streaming-0.6b-1120ms-int8"
    private const val ESTIMATED_SIZE_MB = 640L  // ~640 MB total (int8, 1120ms chunk)

    private val config = SherpaOnnxModelConfig(
        tag = "NemotronDownloader",
        modelDirNames = mapOf(Unit to MODEL_DIR_NAME),
        // csukuangfj2's sherpa-native int8 export (1120ms chunk). The previous fp32 pantinor model
        // had a malformed decoder (dangling `prednet_lengths` output — ORT refused to load it).
        // This int8 export is well-formed (validated: onnxruntime loads decoder/joiner cleanly)
        // and ~4x smaller. Produced by csukuangfj (sherpa maintainer).
        hfRepoNames = mapOf(Unit to "csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-1120ms-int8-2026-06-11"),
        hfFileNames = mapOf(
            Unit to NemotronModelManager.REQUIRED_FILES
        ),
        estimatedSizeMB = { ESTIMATED_SIZE_MB },
        modelStorageDir = { context -> NemotronModelManager.getModelStorageDir(context) },
        isValidModel = { dir -> NemotronModelManager.validateModelDirectory(dir) != null }
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
