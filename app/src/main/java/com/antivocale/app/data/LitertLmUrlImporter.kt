package com.antivocale.app.data

import com.antivocale.app.data.HuggingFaceRepoListing.HfFile
import java.io.File

/** One downloadable .litertlm asset discovered in a HF repo. */
data class LitertLmFile(val fileName: String, val sizeBytes: Long)

/**
 * TASK-373: import any .litertlm model from a Hugging Face repo URL. Deliberately
 * NOT part of the external-sherpa platform: a litert-lm asset is a single file
 * consumed via the generic model_path preference and the "llm" backend, exactly
 * like the manual SAF import (onModelSelected) and the curated Gemma downloads.
 */
// No @Inject constructor (AppModule provides it, constructing the
// HuggingFaceRepoListing with the shared OkHttpClient; ExternalModelStore
// precedent for the same pattern).
class LitertLmUrlImporter(
    private val listing: HuggingFaceRepoListing,
) {
    companion object {
        /** Pure, JVM-testable: which .litertlm files does this repo offer? */
        fun planDownload(files: List<HfFile>, repoId: String): List<LitertLmFile> {
            // `size` lives on the sealed subclasses, not the HfFile parent.
            fun HfFile.sizeOf(): Long = when (this) {
                is HfFile.Lfs -> size
                is HfFile.Plain -> size
            }
            val candidates = files
                .filter { it.name.endsWith(".litertlm") }
                .map { LitertLmFile(it.name, it.sizeOf()) }
            require(candidates.isNotEmpty()) {
                "no .litertlm file in $repoId (this importer is for LiteRT-LM models)"
            }
            return candidates
        }

        /** Same tolerance as the external URL importer: full URL or owner/repo. */
        fun parseRepoIdOrThrow(url: String): String =
            HuggingFaceRepoListing.parseRepoId(url)
                ?: throw IllegalArgumentException(
                    "unsupported URL: $url (expected https://huggingface.co/<owner>/<repo>)")
    }

    /** Lists the repo's .litertlm files; throws IllegalArgumentException on bad URL/empty repo. */
    fun listModels(url: String): List<LitertLmFile> {
        val repoId = parseRepoIdOrThrow(url)
        return planDownload(listing.listFiles(repoId), repoId)
    }

    /**
     * THE seam (reviewer-pinned): every dependency beyond URL parsing is a function
     * parameter, so JVM tests inject fakes and the ViewModel injects the production
     * download lambda around [com.antivocale.app.data.download.ResumeDownloadHelper].
     * Invariants: 2x disk pre-flight (a download doubles usage), Bearer passthrough,
     * resolveUrl form, empty-file rejection.
     */
    fun importFromUrl(
        url: String,
        fileName: String,
        sizeBytes: Long,
        modelsDir: File,
        freeBytes: () -> Long,
        token: String?,
        download: (url: String, targetFile: File, sizeBytes: Long, authHeader: String?) -> Result<File>,
    ): Result<File> = runCatching {
        require(freeBytes() >= sizeBytes * 2) { "not enough free space (need ${sizeBytes * 2} bytes)" }
        modelsDir.mkdirs()
        val downloaded = download(
            listing.resolveUrl(parseRepoIdOrThrow(url), fileName),
            File(modelsDir, fileName), sizeBytes, com.antivocale.app.data.huggingFaceAuthHeader(token)).getOrThrow()
        require(downloaded.length() > 0) { "downloaded file is empty" }
        downloaded
    }
}
