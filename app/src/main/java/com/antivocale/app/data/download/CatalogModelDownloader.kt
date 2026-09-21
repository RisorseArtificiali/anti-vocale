package com.antivocale.app.data.download

import android.content.Context
import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.catalog.CatalogVariant
import java.io.File

/**
 * Catalog-driven downloader for a built-in sherpa-onnx model.
 *
 * Derives the ENTIRE download behavior — directory names, file lists, sizes, HF
 * repo, SHA-256 pins, parent-dir creation, storage dir — from ONE
 * [CatalogEntry] + [CatalogVariant]. Per-model downloaders (Parakeet, Whisper,
 * Qwen3-ASR, Nemotron, GigaAM) are thin adapters that resolve their entry from
 * [com.antivocale.app.data.catalog.BundledCatalog] and delegate here, so no
 * download metadata lives in code anymore.
 *
 * Wraps the shared [SherpaOnnxModelDownloader] algorithm (resumable parallel
 * downloads, sidecar-based resume, optional SHA-256 verification).
 */
class CatalogModelDownloader private constructor(
    private val entry: CatalogEntry,
    private val variant: CatalogVariant,
) {

    private val delegate = SherpaOnnxModelDownloader<String>(buildConfig())

    fun getModelDirName(): String = variant.dirName

    fun detectPartialDownload(context: Context): DownloadState.PartiallyDownloaded? =
        delegate.detectPartialDownload(context, VARIANT_KEY)

    fun needsExtraction(context: Context): Boolean =
        delegate.needsExtraction(context, VARIANT_KEY)

    fun clearPartialDownload(context: Context): Boolean =
        delegate.clearPartialDownload(context, VARIANT_KEY)

    suspend fun downloadModel(
        context: Context,
        onProgress: (Float) -> Unit = {},
        onStateChange: (DownloadState) -> Unit = {}
    ): Result<File> = delegate.downloadModel(context, VARIANT_KEY, onProgress, onStateChange)

    fun cancel() = delegate.cancel(VARIANT_KEY)

    fun isModelDownloaded(context: Context): Boolean =
        delegate.isModelDownloaded(context, VARIANT_KEY)

    fun getModelPath(context: Context): String? =
        delegate.getModelPath(context, VARIANT_KEY)

    fun getEstimatedSizeMB(): Long = delegate.getEstimatedSizeMB(VARIANT_KEY)

    fun deleteModel(context: Context): Boolean =
        delegate.deleteModel(context, VARIANT_KEY)

    private fun buildConfig(): SherpaOnnxModelConfig<String> {
        val repo = variant.source.repo
            ?: throw IllegalArgumentException(
                "entry ${entry.id} variant ${variant.name}: built-in download requires a HuggingFace repo")
        val fileNames = variant.files.map { it.name }
        return SherpaOnnxModelConfig(
            tag = "CatalogModelDownloader:${entry.id}/${variant.name}",
            modelDirNames = mapOf(VARIANT_KEY to variant.dirName),
            hfFileNames = mapOf(VARIANT_KEY to fileNames),
            estimatedSizeMB = { variant.estimatedSizeMB },
            modelStorageDir = { context -> File(context.filesDir, entry.storageDir ?: entry.id) },
            isValidModel = { dir -> CatalogModelValidator.isValidModelDir(dir, fileNames) },
            ensureParentDirs = entry.flags.ensureParentDirs,
            expectedSha256 = buildExpectedSha256(),
            hfRepoNames = mapOf(VARIANT_KEY to repo)
        )
    }

    /** SHA-256 pins (e.g. GigaAM) become post-download verification, others stay empty. */
    private fun buildExpectedSha256(): Map<String, Map<String, String>> {
        val pinned = variant.files.filter { it.sha256 != null }
        return if (pinned.isEmpty()) emptyMap()
        else mapOf(VARIANT_KEY to pinned.associate { it.name to it.sha256!! })
    }

    companion object {
        private const val VARIANT_KEY = "selected"

        /**
         * Builds a downloader for [entry], targeting [variantName] or the entry's
         * default variant when [variantName] is null.
         */
        fun of(entry: CatalogEntry, variantName: String? = null): CatalogModelDownloader {
            val variant: CatalogVariant = when {
                variantName != null -> entry.variant(variantName)
                    ?: throw IllegalArgumentException("entry ${entry.id}: unknown variant '$variantName'")
                else -> entry.defaultVariant
            }
            return CatalogModelDownloader(entry, variant)
        }
    }
}

/**
 * Completeness validation shared by the catalog downloader (and, later, the
 * catalog-driven model managers): every required file must exist, and ONNX files
 * must additionally be sidecar-complete ([ResumeDownloadHelper]) so a partial
 * download is never mistaken for a usable model.
 */
object CatalogModelValidator {

    fun isValidModelDir(dir: File, fileNames: List<String>): Boolean {
        if (!dir.isDirectory) return false
        return fileNames.all { name ->
            val file = File(dir, name)
            // .ort joins .onnx (GH #89 moonshine v2 ships the ORT extension;
            // the completeness check is extension-agnostic, the magic check is not).
            if (file.name.endsWith(".onnx", ignoreCase = true) || file.name.endsWith(".ort", ignoreCase = true)) {
                ResumeDownloadHelper.isFileComplete(file)
            } else {
                file.exists()
            }
        }
    }
}
