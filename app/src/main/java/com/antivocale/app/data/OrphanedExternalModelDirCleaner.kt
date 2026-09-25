package com.antivocale.app.data

import android.util.Log
import com.antivocale.app.transcription.cleanOrphanedModelDirs
import com.antivocale.app.util.formatFileSize
import java.io.File

private const val TAG = "OrphanedExtModelDirs"

/**
 * Startup sweep reclaiming external-model directories whose record is gone
 * (TASK-657, GH #117). The reclaim loop itself is the BUILT-IN sweep
 * ([cleanOrphanedModelDirs]): identical selection (dirs whose name no live
 * set owns), deletion semantics, and per-dir logging, so the two sweep
 * families cannot drift; this class owns only what differs, the live set's
 * source and the in-flight guard.
 *
 * Residue sources (corrected in review): the reachable one is the Models-tab
 * delete, which removes the store record first and the dir second
 * (ModelViewModel: a process death in that window, or a failed
 * deleteRecursively, strands the dir). The registry's clearModelPath lambda
 * deletes records with no file removal, but it is UNREACHABLE for external
 * records today (external loads never route through it); the KDoc flags it
 * as a hazard to re-audit if that wiring ever changes.
 *
 * Keyed on [ExternalModelStore.records] alone, never on the community
 * catalog: a URL import without a catalog entry is a valid install and must
 * never be swept. ALL records, not only loadable ones: a quarantined record
 * (TASK-640) still owns its dir, and the Models tab keeps it listed and
 * deletable.
 *
 * In-flight safety is STRUCTURAL, not just timing (review): the importer has
 * no staging state (final-name dir, synchronous failure cleanup), so today
 * nothing can be mid-import at process start; but a future non-UI import
 * entry point must not depend on that. Directories modified within
 * [FRESH_DIR_GRACE_MS] join the protected set regardless of records: a
 * reclaim merely delays to the next process start, while deleting a live
 * import would destroy a multi-GB download.
 *
 * Called from [com.antivocale.app.BridgeApplication.onCreate] beside
 * [DanglingBackendCleaner], on the application scope off the main thread: a
 * multi-GB deleteRecursively must not block cold start, and a sweep failure
 * must never crash startup.
 */
class OrphanedExternalModelDirCleaner(
    private val store: ExternalModelStore,
    private val filesRoot: () -> File,
) {
    /** Review F2: generous against slow multi-GB imports; reclaim retry is free. */
    private val freshDirGraceMs: Long = FRESH_DIR_GRACE_MS

    /** @return Total bytes reclaimed. */
    suspend fun cleanIfNeeded(): Long {
        val root = filesRoot()
        // Name-based ownership (the record's dir name is the key; the absolute
        // prefix does not survive a backup restore to another user id, and
        // cleanOrphanedModelDirs matches names).
        val protected = store.records().mapTo(mutableSetOf()) { File(it.dir).name }
        val cutoff = System.currentTimeMillis() - freshDirGraceMs
        root.listFiles { file -> file.isDirectory }?.forEach { dir ->
            if (dir.lastModified() > cutoff) protected += dir.name
        }
        val reclaimed = cleanOrphanedModelDirs(root, protected)
        if (reclaimed > 0) {
            Log.i(TAG, "External sweep reclaimed ${formatFileSize(reclaimed)} under $root")
        }
        return reclaimed
    }

    companion object {
        /** Default grace window: two hours. */
        const val FRESH_DIR_GRACE_MS: Long = 2 * 60 * 60 * 1000L
    }
}
