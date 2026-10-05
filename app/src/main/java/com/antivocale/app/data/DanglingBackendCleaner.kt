package com.antivocale.app.data

import kotlinx.coroutines.flow.first

/**
 * Startup hygiene pass over the persisted transcription backend (TASK-342
 * defect 2). The external-model delete path resets the preference when it
 * deletes the active record, but the preference can still end up dangling
 * (a record removed through another path, files deleted underneath a record,
 * or a crash between store delete and preference reset). A backend id nothing
 * can load makes every transcription request fail, so at startup an external
 * id that no longer resolves to a valid record is reset to the default
 * backend. The retired GGUF backend id gets the same treatment (TASK-639):
 * its loader is gone, and without this reset a stale id would fall through
 * the registry-null catch-all into the LLM loader, silently transcribing with
 * the wrong backend and cold-reloading it on every request. Called from
 * [com.antivocale.app.BridgeApplication.onCreate], before the share-target
 * sync, next to [CustomTransducerMigrator].
 */
class DanglingBackendCleaner(
    private val preferencesManager: PreferencesManager,
    private val externalModelStore: ExternalModelStore,
) {
    suspend fun cleanIfNeeded() {
        val backend = preferencesManager.transcriptionBackend.first()
        if (backend == RETIRED_GGUF_BACKEND_ID || isDanglingExternal(backend)) {
            preferencesManager.saveTranscriptionBackend(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND)
        }
        // TASK-761 (altitude F4): the refinement pin dangles through the
        // same paths the active backend can (quarantine, files deleted
        // under the record, a crash between store delete and the
        // delete-time scrub): a dead pin silently degrades EVERY two-pass
        // run, so it gets the same startup net. Blank = inherit active.
        // EXTERNAL pins only: a built-in pin dangles through deletion
        // (scrubbed at delete time in ModelViewModel) rather than record
        // rot, and loadability of a built-in is not resolvable here.
        val pin = preferencesManager.refinementModelBackendId.first()
        if (isDanglingExternal(pin)) {
            preferencesManager.saveRefinementModelBackendId("")
        }
    }

    /** byId resolves valid records only (dir must exist), matching what the
     *  orchestrator's loadExternalBackend can actually load. */
    private suspend fun isDanglingExternal(backendId: String): Boolean =
        backendId.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX) &&
            externalModelStore.byId(backendId.removePrefix(ExternalModelRecord.BACKEND_ID_PREFIX)) == null

    private companion object {
        /** Backend id of the GGUF backend removed in TASK-639. */
        const val RETIRED_GGUF_BACKEND_ID = "gemma4_gguf"
    }
}
