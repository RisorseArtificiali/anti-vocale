package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.download.CatalogModelValidator
import com.antivocale.app.util.formatFileSize
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Catalog-driven model manager for a built-in sherpa-onnx model (the consolidated
 * replacement for the deleted per-model managers). One cached instance per
 * [entryId]; discovery, validation and cleanup all derive from the bundled
 * catalog entry, so no per-model metadata lives in code anymore.
 */
class SherpaModelManager private constructor(val entryId: String) {

    companion object {
        /**
         * GH #43 / TASK-603: the installed streaming catalog entry id, when one
         * resolves locally. ONE owner: the orchestrator's runtime two-pass gate
         * and the Settings toggle's availability both derive from this, so the
         * advertised state can never drift from the enforced one.
         */
        suspend fun installedStreamingEntryId(context: android.content.Context): String? =
            runCatching {
                com.antivocale.app.data.catalog.BundledCatalog.entries()
                    .firstOrNull { it.isStreaming }
                    ?.takeIf { entry ->
                        SherpaModelManager.of(entry.id).resolveActiveModelPath(context) != null
                    }
                    ?.id
            }.getOrNull()
        private val cache = ConcurrentHashMap<String, SherpaModelManager>()

        fun of(entryId: String): SherpaModelManager =
            cache.getOrPut(entryId) { SherpaModelManager(entryId) }
    }

    private fun entry(): CatalogEntry =
        BundledCatalog.byId(entryId)
            ?: throw IllegalStateException("bundled catalog is missing entry '$entryId'")

    /** File names of the default variant (native-config contract for sherpa-onnx). */
    val REQUIRED_FILES: List<String> get() = entry().defaultVariant.files.map { it.name }

    /** All known variant dir names (orphan-cleanup whitelist). */
    val validModelDirNames: Set<String> get() = entry().variants.map { it.dirName }.toSet()

    fun getModelStorageDir(context: Context): File =
        File(context.filesDir, entry().storageDir ?: entryId)

    /**
     * Variant NAME for a directory name: exact match first, then a substring
     * fallback (both directions) so path-derived lookups (e.g. the registry's
     * display-name derivation) stay tolerant of synthetic directory names.
     */
    fun detectVariant(dirName: String): String? {
        val variants = entry().variants
        return variants.firstOrNull { it.dirName == dirName }?.name
            ?: variants.firstOrNull { dirName.contains(it.dirName) || it.dirName.contains(dirName) }?.name
    }

    fun isValidModelDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val variant = entry().variants.firstOrNull { it.dirName == dir.name } ?: return false
        val fileNames = variant.files.map { it.name }
        if (!CatalogModelValidator.isValidModelDir(dir, fileNames)) return false
        // tokenizer/ subdirectory requirement (e.g. Qwen3-ASR).
        if (fileNames.any { it.startsWith("tokenizer/") } && !File(dir, "tokenizer").isDirectory) return false
        return true
    }

    fun isValidModelPath(path: String): Boolean = isValidModelDir(File(path))

    fun validateModelDirectory(modelDir: File): CatalogModelInfo? {
        val variant = entry().variants.firstOrNull { it.dirName == modelDir.name } ?: return null
        if (!isValidModelDir(modelDir)) return null
        val fileNames = variant.files.map { it.name }
        val totalSize = fileNames.sumOf { File(modelDir, it).length() }
        return CatalogModelInfo(
            name = modelDir.name,
            path = modelDir.absolutePath,
            sizeBytes = totalSize,
            variantName = variant.name,
            encoderPath = fileNames.firstOrNull { it.contains("encoder") }?.let { File(modelDir, it).absolutePath },
            decoderPath = fileNames.firstOrNull { it.contains("decoder") }?.let { File(modelDir, it).absolutePath },
            joinerPath = fileNames.firstOrNull { it.contains("joiner") || it.contains("joint") }?.let { File(modelDir, it).absolutePath },
            tokensPath = fileNames.firstOrNull { it.contains("tokens") }?.let { File(modelDir, it).absolutePath },
            convFrontendPath = fileNames.firstOrNull { it.contains("conv_frontend") }?.let { File(modelDir, it).absolutePath },
            tokenizerDirPath = if (fileNames.any { it.startsWith("tokenizer/") }) File(modelDir, "tokenizer").absolutePath else null,
        )
    }

    fun getModelInfo(modelPath: String): CatalogModelInfo? = validateModelDirectory(File(modelPath))

    fun discoverModels(context: Context): List<CatalogModelInfo> =
        getModelStorageDir(context).listFiles()?.asSequence()
            ?.filter { it.isDirectory }
            ?.mapNotNull { validateModelDirectory(it) }
            ?.toList() ?: emptyList()

    fun deleteModel(modelPath: String): Boolean = try {
        File(modelPath).deleteRecursively()
    } catch (e: Exception) {
        false
    }

    fun getTotalModelsSize(context: Context): Long = discoverModels(context).sumOf { it.sizeBytes }

    /**
     * Active model path: first variant dir (in catalog order) that validates,
     * else [fallbackPath] when it still validates.
     */
    fun resolveActiveModelPath(context: Context, fallbackPath: String? = null): String? {
        val storageDir = getModelStorageDir(context)
        return try {
            entry().variants.firstOrNull { validateModelDirectory(File(storageDir, it.dirName)) != null }
                ?.let { File(storageDir, it.dirName).absolutePath }
                ?: fallbackPath?.takeIf(::isValidModelPath)
        } catch (e: Exception) {
            fallbackPath?.takeIf(::isValidModelPath)
        }
    }
}

/**
 * Validation result for a catalog-driven model directory. Role paths are derived
 * from the variant's file names (encoder/decoder/joiner/joint/tokens/conv_frontend,
 * tokenizer/ subdir) — the generic replacement for the per-model result types.
 */
data class CatalogModelInfo(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val variantName: String? = null,
    val encoderPath: String? = null,
    val decoderPath: String? = null,
    val joinerPath: String? = null,
    val tokensPath: String? = null,
    val convFrontendPath: String? = null,
    val tokenizerDirPath: String? = null,
) {
    val sizeFormatted: String get() = formatFileSize(sizeBytes)
}