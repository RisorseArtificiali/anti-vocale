package com.antivocale.app.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first

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
 * runs synchronously before the cleaner and snapshots only the two
 * preference ids (the cheap, order-dependent half; the dangling-record scan
 * does not need the blocking window, TASK-899's startup lesson). The scan
 * itself, [scanWipedImports], runs off the main thread at startup to post
 * the notification, and [repairAll] RE-DERIVES its candidates at tap time:
 * the tap can land hours or process restarts later, and a stale notification
 * must not claim a failure (or re-download gigabytes) for a model the user
 * already restored by hand. [repairAll] joins the orphan-dir sweep first
 * (its "no import can be in flight" invariant would otherwise let the sweep
 * delete a repair download in progress; the sweep job reaches this singleton
 * through [onSweepLaunched] because the launch site's local job is otherwise
 * out of reach), then restores the captured selections once the record is
 * loadable again.
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

    /** Backend ids the user had selected, captured before the
     * DanglingBackendCleaner could reset the preference. Captured as any
     * external: id, unfiltered by dangling-ness: loadability is validated
     * again at restore time, and the restore's still-default gate already
     * keeps ids whose record was never wiped out (their preference was
     * never reset). Null = no external backend was selected. */
    private var capturedActive: String? = null
    private var capturedPin: String? = null

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
     * snapshot the two selection preferences, so the cleaner's reset cannot
     * erase what the user had selected. Two warm DataStore reads are the
     * whole blocking cost; the record scan is [scanWipedImports], off the
     * main thread. */
    suspend fun captureSelections() {
        fun externalOrNull(backendId: String): String? =
            backendId.takeIf { it.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX) }
        capturedActive = externalOrNull(preferencesManager.transcriptionBackend.first())
        capturedPin = externalOrNull(preferencesManager.refinementModelBackendId.first())
    }

    /** The dangling-but-repairable records right now. Startup calls this to
     *  decide whether to post the wiped-imports notification; [repairAll]
     *  re-derives the same set at tap time. In-memory predicates come
     *  before the isLoadable directory stat (isLoadable's single predicate,
     *  EXCEPT quarantine: a quarantined record is deliberately parked as
     *  unsafe, and re-downloading would not change why it was flagged). */
    suspend fun scanWipedImports(): List<ExternalModelRecord> {
        val dangling = store.records().filter { record ->
            record.source == ExternalModelSource.URL &&
                !record.sourceUrl.isNullOrBlank() &&
                !record.quarantined &&
                !store.isLoadable(record)
        }
        if (dangling.isNotEmpty()) {
            android.util.Log.i(
                TAG,
                "Wiped imports repairable: ${dangling.joinToString { it.id }}; " +
                    "captured selections active=${capturedActive ?: "none"} pin=${capturedPin ?: "none"}")
        }
        return dangling
    }

    /** Phase 2, launched by the notification's Recover action on the
     * application scope: join the orphan-dir sweep, re-derive the
     * candidates, re-import each from its pinned source, then restore the
     * captured selections. A candidate the user already re-imported from
     * the Models tab is no longer dangling and drops out of the scan
     * before any download. */
    suspend fun repairAll(): RepairResult {
        sweepJob?.join()
        val candidates = scanWipedImports()
        if (candidates.isEmpty()) return RepairResult(attempted = 0, repairedIds = emptyList())
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
        if (repaired.isNotEmpty()) restoreCapturedSelections(driftReplacements)
        return RepairResult(attempted = candidates.size, repairedIds = repaired)
    }

    /** Restores a captured backend id only when (a) a record for it is
     * LOADABLE again, re-pointed at the fresh id when the dedupe drifted,
     * and (b) the user has not picked something else meanwhile: a repair
     * download spans minutes and gigabytes, and a fresh explicit choice
     * made in that window wins over the pre-wipe selection. */
    private suspend fun restoreCapturedSelections(driftReplacements: Map<String, String>) {
        capturedActive?.let { captured ->
            val stillDefault = preferencesManager.transcriptionBackend.first() ==
                PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND
            if (stillDefault) {
                val restored = restoreId(
                    captured.removePrefix(ExternalModelRecord.BACKEND_ID_PREFIX), driftReplacements)
                if (restored != null) {
                    preferencesManager.saveTranscriptionBackend(restored)
                    android.util.Log.i(TAG, "Restored active backend $restored after repair")
                }
            }
        }
        capturedPin?.let { captured ->
            val stillBlank = preferencesManager.refinementModelBackendId.first().isBlank()
            if (stillBlank) {
                val restored = restoreId(
                    captured.removePrefix(ExternalModelRecord.BACKEND_ID_PREFIX), driftReplacements)
                if (restored != null) {
                    preferencesManager.saveRefinementModelBackendId(restored)
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
