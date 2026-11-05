package com.antivocale.app.data

import kotlinx.coroutines.flow.first

/**
 * TASK-963: automatic repair for wiped external imports. Platform storage
 * cleaners (ColorOS Phone Manager, Samsung Storage Booster; see
 * claudedocs/research_coloros_storage_wipe_2026-11-05.md) delete model
 * directories from the app's private storage while the RECORD survives
 * dangling: the user's only remedy was a full manual re-import, every time
 * (the Thurner report). The built-in path already auto-heals through
 * ResumeDownloadHelper; this gives externals the same autonomy from the
 * record's own [ExternalModelRecord.sourceUrl] (per-file sha256s pinned by
 * the catalog entry; see the dedupe-repoint contract on the [reimport]
 * seam below).
 *
 * Two phases, because [DanglingBackendCleaner] resets a dangling active
 * backend id at startup BEFORE a repair could finish: [capture] runs
 * synchronously before the cleaner and remembers which dangling records
 * the user had selected (active backend, refinement pin); [repairAll]
 * runs on the application scope, joins the orphan-dir sweep first (its
 * "no import can be in flight" invariant would otherwise let the sweep
 * delete a repair download in progress), and restores the captured
 * selections once the record is loadable again.
 *
 * Metered networks never trigger a repair: model payloads run ~0.3-1.8GB,
 * so the automatic path waits for an unmetered connection and retries on
 * a later launch (the manual re-import in the Models tab stays available
 * on any network). Quarantined records are left alone: their load was
 * already flagged as unsafe, and re-downloading would not change why.
 */
class ExternalImportRepairer(
    private val store: ExternalModelStore,
    private val preferencesManager: PreferencesManager,
    private val isMetered: () -> Boolean,
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
    /** Backend ids the user had selected, captured before the
     * DanglingBackendCleaner could reset the preference (null = nothing
     * repairable was selected). */
    private var capturedActive: String? = null
    private var capturedPin: String? = null
    private var candidates: List<ExternalModelRecord> = emptyList()
    /** old id -> fresh id for repairs whose source drifted past the dedupe
     *  (the stale record was deleted; the fresh copy registered a new id). */
    private val driftReplacements = mutableMapOf<String, String>()

    /** Phase 1, synchronous at startup BEFORE DanglingBackendCleaner:
     * snapshot the preferences and the repairable records, so the cleaner's
     * reset cannot erase what the user had selected. */
    suspend fun capture() {
        val dangling = store.records().filter { record ->
            // isLoadable's single predicate, EXCEPT quarantine: a
            // quarantined record is deliberately parked as unsafe, and
            // re-downloading would not change why it was flagged.
            !store.isLoadable(record) &&
                !record.quarantined &&
                record.source == ExternalModelSource.URL &&
                !record.sourceUrl.isNullOrBlank()
        }
        if (dangling.isEmpty()) return
        candidates = dangling
        fun captured(backendId: String): String? {
            if (!backendId.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX)) return null
            val recordId = backendId.removePrefix(ExternalModelRecord.BACKEND_ID_PREFIX)
            return dangling.firstOrNull { it.id == recordId }?.let { backendId }
        }
        capturedActive = captured(preferencesManager.transcriptionBackend.first())
        capturedPin = captured(preferencesManager.refinementModelBackendId.first())
        if (capturedActive != null || capturedPin != null) {
            android.util.Log.i(
                TAG,
                "Wiped imports repairable: ${dangling.joinToString { it.id }}; " +
                    "captured selections active=${capturedActive ?: "none"} pin=${capturedPin ?: "none"}")
        }
    }

    /** Phase 2, on the application scope after the orphan sweep:
     * re-import each dangling record from its pinned source, then restore
     * the captured selections. Returns the record ids that are LOADABLE
     * again when this returns (the id that resolved, which is the fresh id
     * on dedupe drift). */
    suspend fun repairAll(): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val repaired = mutableListOf<String>()
        for (record in candidates) {
            // Per record, not per batch: a mid-repair handover to a metered
            // network must stop the remaining downloads, not just the next
            // launch's.
            if (isMetered()) break
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
                val resolvedId = if (store.byId(fresh.id) != null) fresh.id else record.id
                if (store.byId(resolvedId) != null) {
                    repaired += resolvedId
                    android.util.Log.i(
                        TAG, "Repaired wiped import ${record.displayName} ($resolvedId)")
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
                // Models tab remains the fallback, and the next launch
                // retries (capture picks it up again).
                android.util.Log.w(
                    TAG, "Repair of ${record.displayName} (${record.id}) failed; leaving it dangling", e)
            }
        }
        if (repaired.isNotEmpty()) restoreCapturedSelections()
        return repaired
    }

    /** Restores a captured backend id only when (a) a record for it is
     * LOADABLE again, re-pointed at the fresh id when the dedupe drifted,
     * and (b) the user has not picked something else meanwhile: a repair
     * download spans minutes and gigabytes, and a fresh explicit choice
     * made in that window wins over the pre-wipe selection. */
    private suspend fun restoreCapturedSelections() {
        capturedActive?.let { captured ->
            val stillDefault = preferencesManager.transcriptionBackend.first() ==
                PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND
            if (stillDefault) {
                val restored = restoreId(
                    captured.removePrefix(ExternalModelRecord.BACKEND_ID_PREFIX))
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
                    captured.removePrefix(ExternalModelRecord.BACKEND_ID_PREFIX))
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
    private suspend fun restoreId(recordId: String): String? {
        if (store.byId(recordId) != null) return "external:$recordId"
        val fresh = driftReplacements[recordId] ?: return null
        if (store.byId(fresh) != null) return "external:$fresh"
        return null
    }

    private companion object {
        const val TAG = "ExternalImportRepairer"
    }
}
