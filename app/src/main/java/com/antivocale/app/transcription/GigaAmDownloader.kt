package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.data.download.SherpaOnnxModelConfig
import com.antivocale.app.data.download.SherpaOnnxModelDownloader
import java.io.File

/**
 * Downloads the GigaAM v3 model for sherpa-onnx from the govorun-lite GitHub release.
 *
 * The ONNX files are NOT hosted on HuggingFace; they are published as GitHub release
 * assets at `http://github.com/amidexe/govorun-lite/releases/download/model-gigaam-v3`.
 * A custom [SherpaOnnxModelConfig.urlBuilder] points the shared downloader at those URLs.
 *
 * Single-variant (like [NemotronDownloader]). Delegates to [SherpaOnnxModelDownloader].
 */
object GigaAmDownloader {

    /**
     * The directory name used for the GigaAM model. Read-only exposure so
     * [GigaAmModelManager.validModelDirNames] can feed [cleanOrphanedModelDirs].
     */
    val modelDirName: String get() = GigaAmModelManager.GIGAAM_MODEL_DIR

    /** Releases download root for the sherpa-onnx-prepackaged GigaAM v3 files. */
    private const val RELEASE_BASE_URL =
        "https://github.com/amidexe/govorun-lite/releases/download/model-gigaam-v3"

    private val config = SherpaOnnxModelConfig(
        tag = "GigaAmDownloader",
        modelDirNames = mapOf(Unit to modelDirName),
        hfFileNames = mapOf(
            Unit to GigaAmModelManager.REQUIRED_FILES
        ),
        estimatedSizeMB = { GigaAmModelManager.ESTIMATED_SIZE_MB },
        modelStorageDir = { context -> GigaAmModelManager.getModelStorageDir(context) },
        isValidModel = { dir -> GigaAmModelManager.validateModelDirectory(dir) != null },
        // GigaAM ships as GitHub release assets, not a HuggingFace repo (which the
        // default downloader would otherwise hit). Build the direct asset URL.
        urlBuilder = { _, fileName -> "$RELEASE_BASE_URL/$fileName" }
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