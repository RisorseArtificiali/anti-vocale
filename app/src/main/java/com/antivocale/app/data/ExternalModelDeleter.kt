package com.antivocale.app.data

import java.io.File
import kotlinx.coroutines.flow.first

/**
 * The ONE delete semantics for an imported external model, shared by the
 * Models tab's delete and the debug SPI's op=record_delete (TASK-974): the
 * record goes first (family sync sees the new world), then the files, then
 * a selection that pointed at it comes back to a sane state. Deleting the
 * ACTIVE model repoints to the default backend (the orchestrator's registry
 * lookup would otherwise fall through to the LLM loader), and a refinement
 * pin on the deleted model would degrade every two-pass run (TASK-761: the
 * pin is honored, the load fails, phase 1 silently skips). Deleting a
 * selected model is therefore a RESET, never a refusal, and
 * [DanglingBackendCleaner] remains the startup net for any path that misses.
 *
 * Share-alias resync stays at the call sites: the Models tab syncs
 * immediately, and the app's startup sync converges every cold start, so
 * this class needs no Android framework dependencies.
 */
class ExternalModelDeleter(
    private val store: ExternalModelStore,
    private val preferencesManager: PreferencesManager,
) {
    /** What the delete did: callers with their own follow-ups consume it
     *  (the Models tab clears its UI state on [resetActiveBackend]; the SPI
     *  serializes it). */
    data class Outcome(
        val existed: Boolean,
        val resetActiveBackend: Boolean,
        val clearedRefinementPin: Boolean,
    )

    suspend fun delete(record: ExternalModelRecord): Outcome {
        val removed = store.delete(record.id)
        File(record.dir).deleteRecursively()
        val resetActiveBackend =
            preferencesManager.transcriptionBackend.first() == record.backendId
        if (resetActiveBackend) {
            preferencesManager.saveTranscriptionBackend(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND)
        }
        val clearedRefinementPin =
            preferencesManager.refinementModelBackendId.first() == record.backendId
        if (clearedRefinementPin) {
            preferencesManager.saveRefinementModelBackendId("")
        }
        return Outcome(
            existed = removed != null,
            resetActiveBackend = resetActiveBackend,
            clearedRefinementPin = clearedRefinementPin,
        )
    }
}
