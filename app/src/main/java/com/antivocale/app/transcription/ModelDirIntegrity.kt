package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.CatalogVariant
import com.antivocale.app.data.download.DownloadedModelIntegrity
import com.antivocale.app.data.download.HashVerifier
import com.antivocale.app.data.download.ResumeDownloadHelper
import java.io.File

/**
 * TASK-479 / GH #88: content-level model-dir validation at LOAD time.
 *
 * The existing checks catch incompleteness (presence, `.size` sidecar) but not
 * corruption: a file whose length matches the sidecar but whose bytes are
 * garbage (interrupted write, bit rot, a bad resume) reaches the native
 * OfflineRecognizer, which aborts the whole process with "Protobuf parsing
 * failed" - and every retry re-aborts on the same file. This object adds the
 * content layer on top:
 *
 * - structural checks for every file (ONNX header byte, size floors), shared
 *   with the download-time pass ([DownloadedModelIntegrity]);
 * - SHA-256 verification for files the catalog pins (exact, catches same-length
 *   corruption; costs ~1-2 s per GB on device during a load that already takes
 *   seconds).
 *
 * [verify] is pure I/O (no Android types) so the decision is unit-testable;
 * the caller owns the failure UX and the heal.
 */
object ModelDirIntegrity {

    /** One file that failed verification, with the human-readable reason. */
    data class Failure(val file: File, val reason: String)

    /**
     * Verifies [dir] against [variant]. Returns the failures (empty = healthy).
     * Files with extensions other than .onnx/.txt are not content-checked
     * here; their existence is the completeness layer's job
     * ([CatalogModelValidator]).
     */
    fun verify(dir: File, variant: CatalogVariant): List<Failure> {
        val structural = DownloadedModelIntegrity.validate(dir).map {
            Failure(it.file, it.reason)
        }
        val pinned = variant.files
            .filter { it.sha256 != null }
            .mapNotNull { catalogFile ->
                val onDisk = File(dir, catalogFile.name)
                if (!onDisk.isFile) return@mapNotNull null // presence is the completeness layer's job
                val actual = runCatching { HashVerifier.sha256(onDisk) }.getOrNull()
                    ?: return@mapNotNull Failure(onDisk, "unreadable")
                if (!actual.equals(catalogFile.sha256, ignoreCase = true)) {
                    Failure(onDisk, "SHA-256 mismatch (pinned ${catalogFile.sha256})")
                } else null
            }
        return structural + pinned
    }

    /**
     * The heal half: removes every failed file AND its `.size` sidecar, so the
     * downloader treats it as missing (the Models tab offers a clean
     * re-download) instead of the loader re-aborting on the same bytes.
     * Directory-level findings (an empty or missing dir) are skipped: they
     * belong to the completeness layer, and deleting directories here would
     * overreach.
     */
    fun removeFailed(failures: List<Failure>) {
        failures
            .filter { it.file.isFile }
            .forEach { failure -> ResumeDownloadHelper.clearTarDownload(failure.file) }
    }
}
