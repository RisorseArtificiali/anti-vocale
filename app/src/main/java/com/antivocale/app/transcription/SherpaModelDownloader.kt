package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.data.download.CatalogModelDownloader
import com.antivocale.app.data.download.DownloadState
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Catalog-driven downloader for a built-in sherpa-onnx model (the consolidated
 * replacement for the deleted per-model downloaders). One cached instance per
 * [entryId]; every operation targets a catalog variant by NAME (null = the
 * entry's default variant, used by single-variant models like Nemotron/GigaAM).
 * Delegate instances are cached per variant so cancel() reaches the same
 * [CatalogModelDownloader] that started the download.
 */
class SherpaModelDownloader private constructor(val entryId: String) {

    companion object {
        private val cache = ConcurrentHashMap<String, SherpaModelDownloader>()

        fun of(entryId: String): SherpaModelDownloader =
            cache.getOrPut(entryId) { SherpaModelDownloader(entryId) }

        private const val DEFAULT_VARIANT_KEY = "\u0000default"
    }

    private val delegates = ConcurrentHashMap<String, CatalogModelDownloader>()

    private fun delegate(variantName: String? = null): CatalogModelDownloader {
        // ConcurrentHashMap forbids null keys; the null variant name means "the
        // entry's default variant", so map it to a private sentinel key.
        val key = variantName ?: DEFAULT_VARIANT_KEY
        return delegates.getOrPut(key) {
            val entry = BundledCatalog.byId(entryId)
                ?: throw IllegalStateException("bundled catalog is missing entry '$entryId'")
            CatalogModelDownloader.of(entry, variantName)
        }
    }

    fun getModelDirName(variantName: String? = null): String = delegate(variantName).getModelDirName()

    fun detectPartialDownload(context: Context, variantName: String? = null): DownloadState.PartiallyDownloaded? =
        delegate(variantName).detectPartialDownload(context)

    fun needsExtraction(context: Context, variantName: String? = null): Boolean =
        delegate(variantName).needsExtraction(context)

    fun clearPartialDownload(context: Context, variantName: String? = null): Boolean =
        delegate(variantName).clearPartialDownload(context)

    suspend fun downloadModel(
        context: Context,
        variantName: String? = null,
        onProgress: (Float) -> Unit = {},
        onStateChange: (DownloadState) -> Unit = {},
        tokenManager: com.antivocale.app.data.HuggingFaceTokenManager? = null,
    ): Result<File> = delegate(variantName).downloadModel(context, onProgress, onStateChange, tokenManager)

    /** Cancels one variant's download, or every active download when [variantName] is null. */
    fun cancel(variantName: String? = null) {
        if (variantName != null) {
            delegate(variantName).cancel()
        } else {
            delegates.values.forEach { it.cancel() }
        }
    }

    fun isModelDownloaded(context: Context, variantName: String? = null): Boolean =
        delegate(variantName).isModelDownloaded(context)

    fun getModelPath(context: Context, variantName: String? = null): String? =
        delegate(variantName).getModelPath(context)

    fun getEstimatedSizeMB(variantName: String? = null): Long =
        delegate(variantName).getEstimatedSizeMB()

    fun deleteModel(context: Context, variantName: String? = null): Boolean =
        delegate(variantName).deleteModel(context)
}