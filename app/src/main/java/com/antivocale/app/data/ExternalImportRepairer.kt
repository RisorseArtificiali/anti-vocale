package com.antivocale.app.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TASK-963: user-approved repair for wiped external imports. Platform storage
 * cleaners (ColorOS Phone Manager, Samsung Storage Booster; see
 * claudedocs/research_coloros_storage_wipe_2026-11-05.md) delete model
 * directories from the app's private storage while the RECORD survives
 * dangling: the user's only remedy was a full manual re-import, every time
 * (the Thurner report). The built-in path already auto-heals through
 * ResumeDownloadHelper; externals re-import from the record's own
 * [ExternalModelRecord.sourceUrl] (per-file sha256s pinned by the catalog
 * entry; see the dedupe-repoint contract on the [reimport] seam below).
 *
 * The trigger is the USER, not the launch: payloads run ~0.3-1.8GB, so an
 * automatic background download on a metered plan was rejected (maintainer
 * decision). Startup scans for dangling records and posts a notification
 * naming what the cleaner deleted; only its Recover action runs [repairAll],
 * on the network the user can see when they tap. Quarantined records are
 * left alone: their load was already flagged as unsafe, and re-downloading
 * would not change why.
 *
 * Two phases, because [DanglingBackendCleaner] resets a dangling active
 * backend id at startup BEFORE a repair could finish. [captureSelections]
 * runs synchronously before the cleaner and persists the two external
 * preference ids it reads (the cheap, order-dependent half; the
 * dangling-record scan does not need the blocking window, TASK-899's
 * startup lesson). The snapshot lives in PREFERENCES, not memory: the
 * Recover tap can land in a later process than the one that captured,
 * because the notification survives process death and the cleaner's reset
 * erases the preference before that later launch reads it. The scan,
 * [scanWipedImports], runs off the main thread at startup to post the
 * notification, and [repairAll] RE-DERIVES its candidates at tap time: the
 * tap can land hours or process restarts later, and a stale notification
 * must not claim a failure (or re-download gigabytes) for a model the user
 * already restored by hand. Even then, the tap still applies the persisted
 * selection snapshot when its gates pass, so a hand-repaired model lands
 * back where the user had it. [repairAll] joins the orphan-dir sweep first
 * (its "no import can be in flight" invariant would otherwise let the sweep
 * delete a repair download in progress; the sweep job reaches this singleton
 * through [onSweepLaunched] because the launch site's local job is otherwise
 * out of reach) and is single-flight: a second Recover tap suspends until
 * the first repair finishes instead of downloading the same gigabytes
 * twice.
 *
 * Provided via [AppModule][com.antivocale.app.di.AppModule] (not `@Inject`)
 * because the reimport lambda parameter is invisible to Dagger, the same
 * precedent as [ExternalModelStore]'s dirExists default.
 */
class ExternalImportRepairer(
    private val store: ExternalModelStore,
    private val preferencesManager: PreferencesManager,
    /** Re-imports one record from its pinned source; the production wiring
     *  calls [ExternalModelImporter.importFromUrl] with the record's own
     *  parameters. A one-call lambda rather than the wider
     *  ExternalModelImportOperations interface: this class forwards exactly
     *  one operation and wants it fake-able without the network. CONTRACT
     *  the repair depends on: the returned record is the SAME id, because
     *  the importer's same-hash dedupe repoints a dangling record at the
     *  fresh copy instead of registering a new one (the canonical comment
     *  is ExternalModelImporter's dedupe block). When upstream content
     *  drifts and the dedupe misses, the returned id DIFFERS: the repairer
     *  then deletes the stale record and re-points captured selections at
     *  the fresh id, so the drift is visible in the log instead of
     *  leaving a phantom. */
    private val reimport: suspend (ExternalModelRecord) -> ExternalModelRecord,
) {
    /** The repair outcome the notification consumes: how many dangling
     *  records the tap ATTEMPTED, and the record ids LOADABLE again when
     *  [repairAll] returns (the id that resolved, which is the fresh id on
     *  dedupe drift). Attempted 0 means nothing was dangling at tap time:
     *  a stale notification with nothing left to do, not a failure. */
    data class RepairResult(val attempted: Int, val repairedIds: List<String>)

    /** Single-flight guard for [repairAll]: a double tap or a tap racing a
     *  manual Models-tab re-import must not run two multi-gigabyte
     *  downloads of the same payload. The second caller suspends until the
     *  first finishes, then re-derives an empty candidate set. */
    private val repairMutex = Mutex()

    /** The startup orphan-dir sweep, registered by its single writer
     *  (BridgeApplication, right after launching it) so [repairAll] can
     *  join it; @Volatile because the write and the read land on different
     *  coroutines. Null until registered (tests and any launch path
     *  without a sweep simply skip the join). */
    @Volatile private var sweepJob: Job? = null

    /** Registers the startup orphan-dir sweep job [repairAll] must join. */
    fun onSweepLaunched(job: Job) {
        sweepJob = job
    }

    /** Phase 1, synchronous at startup BEFORE DanglingBackendCleaner:
     * persist the external selections into the repair-pending preference
     * keys, so the cleaner's reset cannot erase what the user had selected
     * and a later process can still restore it. A default read stays
     * untouched on purpose: it either precedes the cleaner's own reset
     * (the next scan decides whether the pending snapshot is still needed)
     * or is a deliberate deselection with the models intact, which the
     * idle-scan clear below handles. Two warm DataStore reads are the
     * whole blocking cost; the record scan is [scanWipedImports], off the
     * main thread. */
    suspend fun captureSelections() {
        preferencesManager.transcriptionBackend.first()
            .takeIf { it.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX) }
            ?.let { preferencesManager.saveWipedRepairCapturedActive(it) }
        preferencesManager.refinementModelBackendId.first()
            .takeIf { it.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX) }
            ?.let { preferencesManager.saveWipedRepairCapturedPin(it) }
    }

    /** The dangling-but-repairable records right now. Startup calls this to
     *  decide whether to post the wiped-imports notification; [repairAll]
     *  re-derives the same set at tap time. In-memory predicates come
     *  before the isLoadable directory stat (isLoadable's single predicate,
     *  EXCEPT quarantine: a quarantined record is deliberately parked as
     *  unsafe, and re-downloading would not change why it was flagged). */
    suspend fun scanWipedImports(): List<ExternalModelRecord> =
        store.records().filter { record ->
            record.source == ExternalModelSource.URL &&
                !record.sourceUrl.isNullOrBlank() &&
                !record.quarantined &&
                !store.isLoadable(record)
        }.also { dangling ->
            if (dangling.isNotEmpty()) {
                android.util.Log.i(
                    TAG, "Wiped imports repairable: ${dangling.joinToString { it.id }}")
            }
        }

    /** Clears the pending selection snapshot when a startup scan found
     *  nothing dangling: either nothing was ever wiped this cycle or the
     *  user already fixed it, and a pending snapshot for an intact model
     *  would re-apply a selection the user has since replaced. Called from
     *  the startup scan path ONLY (a tap-time scan finding nothing must
     *  NOT clear the snapshot: that is exactly the state the restore is
     *  for). */
    suspend fun clearCapturedSelectionsIfIdle() {
        preferencesManager.saveWipedRepairCapturedActive("")
        preferencesManager.saveWipedRepairCapturedPin("")
    }

    /** Phase 2, launched by the notification's Recover action on the
     * application scope: join the orphan-dir sweep, re-derive the
     * candidates, re-import each from its pinned source, then apply the
     * persisted selection snapshot. The restore runs even when the scan
     * found nothing to download: a hand-repaired model with the
     * preference still on the cleaner's default is exactly the state the
     * snapshot exists to fix. A candidate the user already re-imported
     * from the Models tab is no longer dangling and drops out of the scan
     * before any download. */
    suspend fun repairAll(): RepairResult = repairMutex.withLock {
        sweepJob?.join()
        val candidates = scanWipedImports()
        // old id -> fresh id for repairs whose source drifted past the
        // dedupe (the stale record was deleted; the fresh copy registered
        // a new id). Run-local: it exists only between this repair and its
        // selection restore.
        val driftReplacements = mutableMapOf<String, String>()
        val repaired = mutableListOf<String>()
        for (record in candidates) {
            try {
                val fresh = reimport(record)
                if (fresh.id != record.id) {
                    // Upstream content drifted past the dedupe: the fresh
                    // copy registered under a new id while the old record
                    // still points at nothing. Drop the stale record; the
                    // selections are re-pointed at the fresh id below.
                    android.util.Log.w(
                        TAG,
                        "Repair of ${record.displayName} drifted: ${record.id} -> ${fresh.id} " +
                            "(source content changed; deleted the stale record)")
                    store.delete(record.id)
                    driftReplacements[record.id] = fresh.id
                }
                val resolved = store.byId(fresh.id) ?: store.byId(record.id)
                if (resolved != null) {
                    repaired += resolved.id
                    android.util.Log.i(
                        TAG, "Repaired wiped import ${record.displayName} (${resolved.id})")
                } else {
                    android.util.Log.w(
                        TAG,
                        "Repair of ${record.displayName} (${record.id}) returned without a " +
                            "loadable record; leaving it dangling")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The record stays dangling; the manual re-import in the
                // Models tab remains the fallback, and the next launch's
                // scan picks it up again.
                android.util.Log.w(
                    TAG, "Repair of ${record.displayName} (${record.id}) failed; leaving it dangling", e)
            }
        }
        restoreCapturedSelections(driftReplacements)
        RepairResult(attempted = candidates.size, repairedIds = repaired)
    }

    /** Applies the persisted selection snapshot only when (a) a record for
     * it is LOADABLE again, re-pointed at the fresh id when the dedupe
     * drifted, and (b) the user has not picked something else meanwhile: a
     * repair download spans minutes and gigabytes, and a fresh explicit
     * choice made in that window wins over the pre-wipe selection. A
     * consumed snapshot is cleared; a refused one stays, because a failed
     * repair leaves the cycle open for the next launch's notification. */
    private suspend fun restoreCapturedSelections(driftReplacements: Map<String, String>) {
        preferencesManager.wipedRepairCapturedActive.first()
            .takeIf { it.isNotBlank() }
            ?.let { captured ->
                val stillDefault = preferencesManager.transcriptionBackend.first() ==
                    PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND
                if (stillDefault) {
                    val restored = restoreId(
                        captured.removePrefix(ExternalModelRecord.BACKEND_ID_PREFIX),
                        driftReplacements)
                    if (restored != null) {
                        preferencesManager.saveTranscriptionBackend(restored)
                        preferencesManager.saveWipedRepairCapturedActive("")
                        android.util.Log.i(TAG, "Restored active backend $restored after repair")
                    }
                }
            }
        preferencesManager.wipedRepairCapturedPin.first()
            .takeIf { it.isNotBlank() }
            ?.let { captured ->
                val stillBlank = preferencesManager.refinementModelBackendId.first().isBlank()
                if (stillBlank) {
                    val restored = restoreId(
                        captured.removePrefix(ExternalModelRecord.BACKEND_ID_PREFIX),
                        driftReplacements)
                    if (restored != null) {
                        preferencesManager.saveRefinementModelBackendId(restored)
                        preferencesManager.saveWipedRepairCapturedPin("")
                    }
                }
            }
    }

    /** The backend id to restore for a captured record id: the same id
     *  when its record is loadable again, or the fresh replacement's id
     *  when the dedupe drift deleted the stale record during repair.
     *  Null when nothing loadable matches. */
    private suspend fun restoreId(
        recordId: String,
        driftReplacements: Map<String, String>,
    ): String? {
        if (store.byId(recordId) != null) {
            return ExternalModelRecord.BACKEND_ID_PREFIX + recordId
        }
        val fresh = driftReplacements[recordId] ?: return null
        if (store.byId(fresh) != null) {
            return ExternalModelRecord.BACKEND_ID_PREFIX + fresh
        }
        return null
    }

    private companion object {
        const val TAG = "ExternalImportRepairer"
    }
}
