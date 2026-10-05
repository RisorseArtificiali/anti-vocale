package com.antivocale.app.work

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.antivocale.app.data.FileSnapshot
import com.antivocale.app.data.FolderScanPlanner
import com.antivocale.app.data.ScheduledFolderStore
import com.antivocale.app.service.InferenceEnqueue
import com.antivocale.app.service.InferenceService
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.util.SharedAudioHandler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

/**
 * TASK-741 (GH #125) slice 3: the scheduled folder scan. One run lists one
 * watched folder through SAF, feeds the listing to [FolderScanPlanner],
 * copies every stable unenqueued file into app storage (the service
 * consumes LOCAL paths, exactly like a share), and enqueues it through
 * [InferenceEnqueue] with [InferenceService.SOURCE_FOLDER_WATCH]. The
 * demoted snapshot (Failed enqueues back to enqueuedAt = null, per the
 * planner's worker-duty contract) is persisted before returning.
 *
 * The worker duties the planner's KDoc names as contract:
 *  - an empty or failed listing is scan-abandoned: the persisted-grant
 *    check below skips folders whose tree permission is gone, and a grant
 *    that is held but lists nothing is treated as a genuinely-empty
 *    folder (the enqueued-facts-kept policy bounds the damage of a
 *    provider hiccup to the awaiting entries' stability wait);
 *  - overlapping scans of the SAME folder must not run: [scanMutexes]
 *    serializes per folder across the periodic run and the manual
 *    scan-now (a second arrival returns success; the in-flight scan
 *    covers it).
 *
 * Wired for DI via [HiltWorker] like [SubtitleChoiceTimeoutWorker].
 */
@HiltWorker
class ScheduledFolderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val store: ScheduledFolderStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val treeUri = inputData.getString(KEY_TREE_URI)
            ?: return Result.failure()
        val scanNow = inputData.getBoolean(KEY_SCAN_NOW, false)
        return scanFolder(treeUri, scanNow)
    }

    /** scanNow is slice 4's seam: the manual trigger distinguishes its runs
     *  (logging/UX); the serialization treats both identically. */
    private suspend fun scanFolder(treeUriString: String, scanNow: Boolean): Result {
        // Per-folder serialization: the planner's read-plan-write window is
        // not protected by the store's mutation mutex, so a periodic run and
        // a manual scan-now of the same folder must not interleave.
        val mutex = scanMutexes.computeIfAbsent(treeUriString) { Mutex() }
        if (!mutex.tryLock()) {
            Log.i(TAG, "Scan of $treeUriString already in flight; skipping this run")
            return Result.success()
        }
        try {
            val folder = store.byUri(treeUriString) ?: run {
                // Removed between schedule and run: drop the schedule with it.
                ScheduledFolderScheduler.cancel(applicationContext, treeUriString)
                return Result.success()
            }
            // Uri.parse is lenient (never throws for non-null); a garbage
            // string simply matches no persisted grant below.
            val treeUri = Uri.parse(treeUriString)
            if (!grantHeld(treeUri)) {
                Log.w(TAG, "Persisted grant lost for $treeUriString; scan abandoned (record kept)")
                return Result.success()
            }
            // ONE projection query replaces DocumentFile's 4N+1 binder
            // round trips, and (the load-bearing part) makes a BROKEN
            // listing distinguishable from an EMPTY folder: a null cursor
            // or a throwing query is scan-abandoned with the snapshot kept
            // verbatim, while an empty cursor with the grant held is a
            // genuinely-empty folder and plans normally (review F1).
            val childrenWithUris = try {
                listChildren(treeUri) ?: run {
                    Log.w(TAG, "Listing failed for $treeUriString; scan abandoned, snapshot kept")
                    return Result.success()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Listing threw for $treeUriString; scan abandoned, snapshot kept", e)
                return Result.success()
            }
            // The record already carries the snapshot; a second store read
            // would re-decode the same JSON for one field.
            val previous = folder.snapshot
            val plan = FolderScanPlanner.plan(
                childrenWithUris.map { it.first }, previous, now = System.currentTimeMillis(),
            )
            if (plan.toEnqueue.isEmpty()) {
                // Quiet scan: skip the no-op write when nothing moved.
                if (plan.nextSnapshot != previous) {
                    store.saveSnapshot(treeUriString, plan.nextSnapshot)
                }
                return Result.success()
            }

            // The snapshot is persisted per successful enqueue, not once at
            // the end: a worker cancellation (the 10-minute stop limit, an
            // OEM freezer kill) mid-batch must not re-open the double-
            // transcription race the per-folder mutex closed (review F2).
            val working = plan.nextSnapshot.toMutableList()
            var enqueued = 0
            var failed = 0
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                for (child in plan.toEnqueue) {
                    val uri = childrenWithUris.firstOrNull { it.first.name == child.name }?.second
                    val stamped: com.antivocale.app.data.FileSnapshot
                    if (uri == null) {
                        // Defensive: a planner name the listing lacks is a
                        // retry, never a silent consume of the dedup fact.
                        Log.w(TAG, "No uri for planned ${child.name}; demoting")
                        stamped = FileSnapshot(child.name, child.size, null)
                        failed++
                    } else {
                        val copy = SharedAudioHandler.copyToAppStorage(applicationContext, uri)
                        val outcome = (copy as? SharedAudioHandler.CopyResult.Success)
                            ?.let { InferenceEnqueue.start(applicationContext, buildServiceIntent(it.path, child.name, treeUriString)) }
                        if (copy is SharedAudioHandler.CopyResult.Success &&
                            outcome !is InferenceEnqueue.Outcome.Failed
                        ) {
                            stamped = FileSnapshot(child.name, child.size, System.currentTimeMillis())
                            enqueued++
                        } else {
                            // The demote-not-drop contract: the entry keeps
                            // its size baseline and retries next scan.
                            Log.w(TAG, "Copy/enqueue failed for ${child.name}; demoting to retry next scan")
                            stamped = FileSnapshot(child.name, child.size, null)
                            failed++
                        }
                    }
                    val idx = working.indexOfFirst { it.name == child.name }
                    if (idx >= 0) working[idx] = stamped
                    store.saveSnapshot(treeUriString, working)
                }
            }
            // In the background-only regime weeks can pass without an app
            // open, and the share-dir cleanup runs only at process start:
            // the scan tail owns its copies' hygiene.
            runCatching { SharedAudioHandler.cleanupOldFiles(applicationContext) }
            Log.i(
                TAG,
                "Scanned $treeUriString${if (scanNow) " (manual)" else ""}: " +
                    "$enqueued enqueued, $failed failed and demoted, " +
                    "${plan.awaitingStability.size} awaiting stability" +
                    if (plan.morePending) ", batch cap deferred the tail" else "",
            )
            return Result.success()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * The single-query listing: (child, uri) pairs, or NULL when the
     * provider answered nothing (scan-abandoned). A directory MIME or a
     * missing name filters the row; the document id builds the child uri
     * for the copy.
     */
    private fun listChildren(treeUri: Uri): List<Pair<FolderScanPlanner.Child, Uri>>? {
        val root = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, root)
        val cursor = applicationContext.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            ),
            null, null, null,
        ) ?: return null
        cursor.use { c ->
            val nameIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val mtimeIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val idIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val out = ArrayList<Pair<FolderScanPlanner.Child, Uri>>(c.count)
            while (c.moveToNext()) {
                val name = c.getString(nameIx) ?: continue
                val mime = c.getString(mimeIx) ?: continue
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val size = if (c.isNull(sizeIx)) 0L else c.getLong(sizeIx)
                val mtime = if (c.isNull(mtimeIx)) 0L else c.getLong(mtimeIx)
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(idIx))
                out += FolderScanPlanner.Child(name, size, mtime) to uri
            }
            return out
        }
    }

    private fun grantHeld(treeUri: Uri): Boolean =
        applicationContext.contentResolver.persistedUriPermissions
            .any { it.uri == treeUri && it.isReadPermission }

    /**
     * The share-shaped request (local path + request type), with the folder
     * watch as the source so History rows and the skip logic can tell the
     * origin apart; no source package (no calling app).
     */
    private fun buildServiceIntent(localPath: String, fileName: String, treeUri: String): Intent =
        Intent(applicationContext, InferenceService::class.java).apply {
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, TaskerRequestReceiver.REQUEST_TYPE_AUDIO)
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, localPath)
            putExtra(
                TaskerRequestReceiver.EXTRA_TASK_ID,
                // The tree uri rides the id: two folders holding a
                // same-named file scanned in the same millisecond must not
                // collide (the service drops duplicate task ids silently).
                "fwatch_${treeUri.hashCode().toUInt()}_${System.currentTimeMillis()}_${fileName.hashCode().toUInt()}",
            )
            putExtra(InferenceService.EXTRA_SOURCE, InferenceService.SOURCE_FOLDER_WATCH)
        }

    companion object {
        private const val TAG = "ScheduledFolderWorker"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_SCAN_NOW = "scan_now"

        /** The per-folder serialization (see the class KDoc). */
        private val scanMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    }
}

/**
 * The scheduling half of slice 3: keeps one unique periodic name per folder
 * (the store's period drives the interval), cancels schedules for removed
 * folders, and posts the manual scan-now one-shot under the same per-folder
 * name family.
 */
object ScheduledFolderScheduler {

    private const val TAG = "scheduled_folder_watch"
    private const val URI_TAG_PREFIX = "fw:"

    private fun uniqueName(treeUri: String) = "scheduled_folder_scan:$treeUri"

    /**
     * The two-directional reconcile: enqueues (or updates) one unique
     * periodic per watched folder AND cancels schedules whose folder record
     * is gone (a zombie wake per period otherwise survives every restart,
     * review F6; enumeration rides the TAG, the tree uri comes from the
     * work's own input data).
     */
    fun sync(context: Context, folders: List<com.antivocale.app.data.WatchedFolder>) {
        val wm = WorkManager.getInstance(context)
        val wanted = folders.associateBy { it.treeUri }
        for (folder in wanted.values) {
            enqueuePeriodic(wm, folder)
        }
        val infos: List<androidx.work.WorkInfo> = kotlinx.coroutines.runBlocking {
            wm.getWorkInfosByTag(TAG).get()
        }
        for (info in infos) {
            if (info.state.isFinished) continue
            val treeUri = info.tags.firstOrNull { it.startsWith(URI_TAG_PREFIX) }?.removePrefix(URI_TAG_PREFIX)
            if (treeUri != null && treeUri !in wanted) {
                Log.w(TAG, "Cancelling zombie folder-watch schedule (folder record gone): ${info.id}")
                wm.cancelWorkById(info.id)
            }
        }
    }

    /** Cancels one folder's schedule when its record goes away. */
    fun cancel(context: Context, treeUri: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(treeUri))
    }

    /**
     * The targeted reconcile for one mutation (TASK-741 slice 4): the caller
     * holds the changed folder, so the full sync (every folder re-enqueued
     * plus the zombie sweep) is strictly more work than the one enqueue.
     * The sweep stays owned by remove (a direct cancel) and by the app-start
     * self-heal.
     */
    fun enqueueFolder(context: Context, folder: com.antivocale.app.data.WatchedFolder) {
        enqueuePeriodic(WorkManager.getInstance(context), folder)
    }

    /**
     * The one enqueue arm [sync] and [enqueueFolder] share (review F3): the
     * unique name, the UPDATE policy, both tags, and the input data are one
     * contract with two callers - a future change landing in only one site
     * would silently break the other (the zombie sweep enumerates by TAG,
     * the worker resolves its folder from KEY_TREE_URI).
     */
    private fun enqueuePeriodic(wm: WorkManager, folder: com.antivocale.app.data.WatchedFolder) {
        wm.enqueueUniquePeriodicWork(
            uniqueName(folder.treeUri),
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ScheduledFolderWorker>(folder.periodHours.toLong(), TimeUnit.HOURS)
                // The family tag enumerates every folder-watch work for
                // the zombie sweep; the per-uri tag identifies WHICH
                // folder (WorkInfo 2.10 exposes tags but not input data).
                .addTag(TAG)
                .addTag("$URI_TAG_PREFIX${folder.treeUri}")
                .setInputData(
                    Data.Builder().putString(ScheduledFolderWorker.KEY_TREE_URI, folder.treeUri).build(),
                )
                .build(),
        )
    }

    /**
     * The manual trigger (slice 4's Scan now button): an expedited one-shot.
     * Overlap with a running periodic scan of the same folder is prevented
     * by the worker's per-folder mutex, not by the name (a one-shot cannot
     * share a periodic's unique slot without replacing the schedule).
     */
    fun scanNow(context: Context, treeUri: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "${uniqueName(treeUri)}_now",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ScheduledFolderWorker>()
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    Data.Builder()
                        .putString(ScheduledFolderWorker.KEY_TREE_URI, treeUri)
                        .putBoolean(ScheduledFolderWorker.KEY_SCAN_NOW, true)
                        .build(),
                )
                .build(),
        )
    }
}
