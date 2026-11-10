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
    /** TASK-973: the Remove action's deleter (the same semantics as the
     *  Models tab's delete; required, so skipping it fails at construction
     *  instead of at the first Remove tap). */
    private val deleter: ExternalModelDeleter,
    /** Re-imports one record from its pinned source; the production wiring
     *  ([reimportFor]) calls [ExternalModelImporter]
     *  `.importFromUrlWithMetadata` with the record's own parameters (the
     *  metadata carrier, so a streaming record's flag survives the
     *  catalog-entry arm; plain importFromUrl drops caller parameters
     *  there by its entry-driven contract). A one-call lambda rather than
     *  the wider ExternalModelImportOperations interface: this class
     *  forwards exactly one operation and wants it fake-able without the
     *  network. CONTRACT the repair depends on: the returned record is
     *  the SAME id, because the importer's same-hash dedupe repoints a
     *  dangling record at the fresh copy instead of registering a new one
     *  (the canonical comment is ExternalModelImporter's dedupe block).
     *  When upstream content drifts and the dedupe misses, the returned
     *  id DIFFERS: the repairer then deletes the stale record and
     *  re-points captured selections at the fresh id, so the drift is
     *  visible in the log instead of leaving a phantom. */
    private val reimport: suspend (ExternalModelRecord) -> ExternalModelRecord,
) {
    /** The repair outcome the notification consumes: how many dangling
     *  records the tap ATTEMPTED, and the record ids LOADABLE again when
     *  [repairAll] returns (the id that resolved, which is the fresh id on
     *  dedupe drift). Attempted 0 means nothing was dangling at tap time:
     *  a stale notification with nothing left to do, not a failure. */
    data class RepairResult(
        val attempted: Int,
        val repairedIds: List<String>,
        /** TASK-973: candidates that failed on NETWORK grounds (DNS, socket,
         *  timeout): retrying on a working network can still recover them. */
        val networkFailedIds: List<String> = emptyList(),
        /** TASK-973: candidates whose pinned source proved dead or unusable
         *  (HTTP 4xx on the manifest, content the importer rejects): retrying
         *  cannot help, and the ids persist into the dead-source set so the
         *  startup notice and future taps stop counting them. */
        val sourceFailedIds: List<String> = emptyList(),
    )

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
    suspend fun scanWipedImports(): List<ExternalModelRecord> {
        val dead = preferencesManager.wipedRepairDeadSources.first()
        if (dead.isEmpty()) return scanWipedImports(dead)
        // Prune ids that no longer name a dangling record: a hand re-import
        // keeps the SAME id (the dedupe repoints it), and a stale dead
        // entry would otherwise hide a future wipe of that record from
        // this scan forever (the 2026-11-10 review's re-wipe scenario).
        val dangling = dead.filterNot { id ->
            val record = store.records().firstOrNull { it.id == id }
            record == null || store.isLoadable(record)
        }.toSet()
        if (dangling != dead) {
            preferencesManager.saveWipedRepairDeadSources(dangling)
        }
        return scanWipedImports(dangling)
    }

    private suspend fun scanWipedImports(dead: Set<String>): List<ExternalModelRecord> {
        return store.records().filter { record ->
            record.source == ExternalModelSource.URL &&
                !record.sourceUrl.isNullOrBlank() &&
                !record.quarantined &&
                record.id !in dead &&
                !store.isLoadable(record)
        }.also { dangling ->
            if (dangling.isNotEmpty()) {
                android.util.Log.i(
                    TAG, "Wiped imports repairable: ${dangling.joinToString { it.id }}")
            }
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
    /** TASK-973: the failed notice's Remove action. Deletes every record
     *  in the persisted dead-source set through the shared deleter (record,
     *  files, selection resets) and clears the set. Re-derives from the
     *  preference, never from intent payloads: a stale notification still
     *  removes exactly what a later process marked dead. Idempotent: an
     *  empty set is a no-op. Returns the removed record ids. */
    suspend fun removeDeadSources(): List<String> {
        val dead = preferencesManager.wipedRepairDeadSources.first()
        if (dead.isEmpty()) return emptyList()
        val removed = mutableListOf<String>()
        val pruned = dead.toMutableSet()
        for (id in dead) {
            val record = store.records().firstOrNull { it.id == id } ?: continue
            if (store.isLoadable(record)) {
                // A stale Remove tap after a manual re-import (the dedupe
                // keeps the SAME id): the record holds fresh files, so the
                // delete is refused and the id leaves the dead set.
                pruned -= id
                continue
            }
            if (deleter.delete(record).existed) removed += id
        }
        // Cleared wholesale: records that vanished by other means leave the
        // set too (their ids no longer name anything).
        preferencesManager.saveWipedRepairDeadSources(emptySet())
        return removed
    }

    suspend fun repairAll(): RepairResult = repairMutex.withLock {
        sweepJob?.join()
        // ONE read of the dead set owns both the candidate filter and the
        // skip decision (a Remove tap between two reads would leave the
        // snapshot and the guard disagreeing).
        val dead = preferencesManager.wipedRepairDeadSources.first()
        val candidates = scanWipedImports(dead)
        // old id -> fresh id for repairs whose source drifted past the
        // dedupe (the stale record was deleted; the fresh copy registered
        // a new id). Run-local: it exists only between this repair and its
        // selection restore.
        val driftReplacements = mutableMapOf<String, String>()
        val repaired = mutableListOf<String>()
        val networkFailed = mutableListOf<String>()
        val sourceFailed = mutableListOf<String>()
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
                // scan picks it up again. TASK-973: the failure is TYPED so
                // the outcome notice can offer the right action: IOException
                // (DNS, socket, timeout) is the network class a retry can
                // fix; anything else is the importer rejecting the source's
                // content or its HTTP status, which no retry changes.
                val dead = isSourceDead(e)
                if (dead) sourceFailed += record.id else networkFailed += record.id
                android.util.Log.w(
                    TAG, "Repair of ${record.displayName} (${record.id}) failed" +
                        " (${if (dead) "dead-source" else "retryable"}); leaving it dangling", e)
            }
        }
        restoreCapturedSelections(driftReplacements)
        // TASK-973: persist the dead-source set for the next scan and tap.
        // The skip guard above means no dead id was attempted, so nothing
        // needs subtracting: the old set plus this repair's source deaths.
        val nextDead = dead + sourceFailed
        if (nextDead != dead) {
            preferencesManager.saveWipedRepairDeadSources(nextDead)
        }
        RepairResult(
            attempted = candidates.size,
            repairedIds = repaired,
            networkFailedIds = networkFailed,
            sourceFailedIds = sourceFailed)
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

    companion object {
        private const val TAG = "ExternalImportRepairer"

        /**
         * TASK-973: is this failure PROVEN dead, the only state that may
         * retire a record? The 2026-11-10 review inverted the question
         * this classifier answers: device-local failures this feature's
         * own audience hits (disk-full pre-flight, temp-file rename) and
         * the multi-GB download's own HTTP layer (DownloadException.
         * HttpError carries the code ResumeDownloadHelper saw) are
         * TRANSIENT like any network state - a retry on a working network
         * with space can succeed. DEAD is only a 4xx-class answer from
         * the SOURCE itself (HttpSourceException: 404/410 and friends,
         * 429 excluded); a content rejection is NOT proven dead (the
         * publisher can fix the manifest), so it stays retryable.
         */
        private fun isSourceDead(e: Throwable): Boolean =
            e is HttpSourceException && e.code != 429 && e.code < 500

        /**
         * The production re-import lambda, fed by AppModule: forward the
         * record's OWN parameters through [ExternalModelImportOperations]
         * `.importFromUrlWithMetadata`, never plain `.importFromUrl`.
         * The difference is load-bearing (the maintainer's Fritter
         * Streaming repair failed instantly): importFromUrl's
         * catalog-entry branch is entry-driven and DROPS the caller's
         * parameters, so a streaming record's flag never reached the
         * synthesis and a declaring manifest was rejected ("import it
         * from its catalog entry"), the exact path the record itself came
         * from. The metadata carrier forwards streaming, name, and
         * licence, which is what a repair re-imports: the record AS IT
         * WAS, including the flag that made it importable.
         */
        fun reimportFor(
            importer: ExternalModelImportOperations,
            progressRelay: ExternalImportProgressRelay? = null,
        ): suspend (ExternalModelRecord) -> ExternalModelRecord = { record ->
            importer.importFromUrlWithMetadata(
                url = requireNotNull(record.sourceUrl) {
                    "repair candidate without a sourceUrl: ${record.id}"
                },
                displayName = record.displayName,
                licence = record.licence,
                modelType = record.modelType,
                options = record.options,
                family = record.family,
                languages = record.languages,
                streaming = record.streaming,
                onProgress = { i, c, n, b, t -> progressRelay?.invoke(i, c, n, b, t) },
            )
        }
    }
}
