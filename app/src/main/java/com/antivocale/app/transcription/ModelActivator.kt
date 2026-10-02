package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.R
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.ModelFamily
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.catalog.BundledCatalog
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * TASK-552: the ONE owner of what "make this model the active backend" means
 * at the persistence level. ModelViewModel's four activation sites (catalog
 * Use, the LLM download path, external imports, the LAN-offload card) and
 * the headless app-icon shortcut trampoline all delegate here, so the
 * writes cannot fork between the UI and the no-UI paths.
 *
 * The activation core per family (documented per site, carried verbatim
 * from the ModelViewModel bodies this extraction closed):
 *  - catalog: resolve the on-disk directory (downloader, then the manager,
 *    then the STALE catalog path disk-checked; the fallback is stale by
 *    design, TASK-342), persist backend + path, clear any silent-decode
 *    demotion (TASK-675: the explicit pick is the give-it-another-chance
 *    path).
 *  - LLM: persist the file path and the LLM backend together (GH #23:
 *    persisting only the path left the previous backend active and Gemma
 *    never loaded).
 *  - external: persist the backend, clear the demotion, and flip VAD on for
 *    Canary at selection time (TASK-408: canary decodes empty on chunks cut
 *    mid-speech; visible in Settings, never a silent override).
 *  - remote offload: the backend write only (no model dir exists).
 *
 * UI state, snackbars, and the keep-alive timer stay with the callers: this
 * class owns persistence, not presentation.
 */
@Singleton
class ModelActivator @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val silentModelDemoter: SilentModelDemoter,
    private val externalModelStore: ExternalModelStore,
    private val backendRegistry: BackendRegistry,
) {

    /**
     * The catalog resolution ladder, shared by the Use action and the
     * shortcut trampoline: downloader first (fresh disk read), the manager's
     * active-dir resolution second, then the stale catalog path disk-checked
     * (its state is populated by an earlier scan; TASK-342 B2: the first two
     * reads must stay free of extra IO).
     *
     * @param staleCatalogPath the catalog scan's remembered path for the
     *   entry, or null; it is only trusted when its directory still exists.
     * @return the model directory path, or null when nothing is on disk.
     */
    fun resolveCatalogPath(
        context: Context,
        entryId: String,
        variantName: String?,
        staleCatalogPath: String?,
        preferredPath: String? = null,
    ): String? =
        // Range review: the user's SAVED variant path wins when it still
        // exists - the ladder otherwise resolves the FIRST variant (turbo
        // over the user's small), silently reverting their Models-tab pick.
        // Only the headless switch passes one; useModel's explicit
        // variantName picks take the same first slot via the downloader.
        preferredPath?.takeIf { File(it).exists() }
            ?: SherpaModelDownloader.of(entryId).getModelPath(context, variantName)
            ?: SherpaModelManager.of(entryId).resolveActiveModelPath(context)
            ?: staleCatalogPath?.takeIf { File(it).exists() }

    /**
     * Activates a catalog model. Returns the resolved path on success, or
     * null when no valid directory exists (the caller must be loud about
     * that, never silently keep the previous backend: TASK-342 defect 1).
     */
    suspend fun activateCatalog(
        context: Context,
        entryId: String,
        variantName: String?,
        staleCatalogPath: String?,
        preferredPath: String? = null,
    ): String? {
        val modelPath = resolveCatalogPath(context, entryId, variantName, staleCatalogPath, preferredPath)
        if (modelPath == null) return null
        preferencesManager.saveSherpaModelPath(entryId, modelPath)
        preferencesManager.saveTranscriptionBackend(entryId)
        silentModelDemoter.onManualSelection(entryId)
        return modelPath
    }

    /** Activates a downloaded LLM (.litertlm) file as the active backend. */
    suspend fun activateLlm(file: File) {
        preferencesManager.saveModelPath(file.absolutePath)
        preferencesManager.saveTranscriptionBackend(LlmTranscriptionBackend.BACKEND_ID)
    }

    /** Activates an imported external model (Canary flips VAD on, TASK-408). */
    suspend fun activateExternal(record: ExternalModelRecord) {
        preferencesManager.saveTranscriptionBackend(record.backendId)
        silentModelDemoter.onManualSelection(record.backendId)
        if (record.family == ModelFamily.CANARY && !preferencesManager.vadEnabled.first()) {
            preferencesManager.saveVadEnabled(true)
        }
    }

    /** Activates the LAN-offload backend (no model directory exists). */
    suspend fun activateRemote() {
        preferencesManager.saveTranscriptionBackend(RemoteOmnivoiceBackend.BACKEND_ID)
    }

    /**
     * The headless entry (the app-icon shortcut trampoline): dispatches on
     * the backend id's KIND and runs the matching family activation.
     *
     * @return the activated model's DISPLAY NAME (for the confirmation
     *   notification), or null when the id is unknown or its model is not
     *   on disk anymore (the caller reports the failure; the previous
     *   backend stays active, never a dangling preference).
     */
    suspend fun activate(backendId: String, context: Context): String? = when {
        backendId == RemoteOmnivoiceBackend.BACKEND_ID -> {
            activateRemote()
            context.getString(R.string.remote_omnivoice_name)
        }
        backendId.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX) -> {
            val record = externalModelStore.byId(backendId.removePrefix(ExternalModelRecord.BACKEND_ID_PREFIX))
                ?: return null
            activateExternal(record)
            record.displayName
        }
        backendId == LlmTranscriptionBackend.BACKEND_ID -> {
            val path = backendRegistry.byBackendId(backendId)
                ?.modelPathFlow(preferencesManager)?.first()
                ?: return null
            // The generic modelPath preference keeps stale paths after a
            // file delete (the catalog arm disk-checks through its ladder;
            // this arm must honor the same no-dangling-preference contract).
            if (!File(path).isFile) return null
            activateLlm(File(path))
            variantAwareDisplayName(context, backendRegistry.byBackendId(backendId), path)
                .ifBlank { backendId }
        }
        BundledCatalog.byId(backendId) != null -> {
            // Range review: the user's SAVED variant path wins when it still
            // resolves (the ladder otherwise picks the FIRST variant: turbo
            // over the user's small, silently reverting their Models-tab
            // pick). The saved path rides the stale-path slot, which the
            // ladder disk-checks before trusting.
            val savedPath = backendRegistry.byBackendId(backendId)
                ?.modelPathFlow(preferencesManager)?.first()
            val path = activateCatalog(context, backendId, variantName = null, staleCatalogPath = savedPath)
                ?: return null
            variantAwareDisplayName(context, backendRegistry.byBackendId(backendId), path)
                .ifBlank { backendId }
        }
        else -> null
    }
}
