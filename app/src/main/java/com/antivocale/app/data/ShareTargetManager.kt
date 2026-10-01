package com.antivocale.app.data

import android.content.Context
import com.antivocale.app.di.ApplicationScope
import com.antivocale.app.transcription.BackendDescriptor
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.util.ComponentAliasSync
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the manifest share-target activity-aliases in sync with model availability.
 *
 * The alias <-> backend-id <-> model-path mapping lives in [BackendRegistry]: each
 * descriptor's [BackendDescriptor.shareAlias] is the activity-alias class name and its
 * model-path flow supplies the has-model check. Targets iterate in the registry's
 * canonical backend order; each component is set independently, so the order is not
 * observable.
 *
 * The external-models family alias (ShareExternal) is synced as a FAMILY, not per
 * descriptor: external records carry blank aliases by design, and the single manifest
 * component is enabled iff advanced sharing is on AND at least one valid record exists.
 * The store (not the records provider) backs that check: the provider's StateFlow starts
 * empty and fills asynchronously, and this manager's syncs can run at
 * BridgeApplication.onCreate before the first emission lands.
 *
 * TASK-738: the manager OWNS its execution. The public entry points are
 * fire-and-forget: they launch on the injected [ApplicationScope] (never a
 * viewModelScope, so a ViewModel cleared mid-sync cannot cancel the PackageManager
 * IPC) and return Unit immediately, so callers no longer need a scope or a suspend
 * context. The two-form contract:
 *  - public fire-and-forget forms for isolated triggers (an import finished, an
 *    external entry deleted) where nothing is ordered against the sync;
 *  - the [internal] suspend forms for the sites pinned to an ADJACENT ordering
 *    (the cold-start heal -> sync -> shortcut-refresh chain, the corrupt-dir heal
 *    retiring the alias before the shortcut refresh, the download and toggle
 *    blocks that refresh shortcuts right after): those still await the sync
 *    inside their own coroutine, by design, not by omission.
 * Concurrent entry points are SERIALIZED on [syncMutex] (a full sync interleaved
 * with another at the setComponentEnabled granularity is last-writer-wins on a
 * stale snapshot); ordering WITHIN one caller chain comes from awaiting the
 * suspend form. Every sync re-derives the full state, so no caller depends on
 * cross-entry ordering, only on its own.
 */
class ShareTargetManager constructor(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val backendRegistry: BackendRegistry,
    private val externalModelStore: ExternalModelStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        private const val TAG = "ShareTargetManager"
        // Single source (also used by ShareReceiverActivity): renaming must find
        // the manifest literal too, pinned by BackendRegistryTest.
        internal const val EXTERNAL_FAMILY_ALIAS = "com.antivocale.app.ShareExternal"
    }

    private val syncMutex = Mutex()

    // No mutex here: the wrappers below launch the LOCKED Now-forms, so the
    // serialization lives in exactly one place (a withLock here too would
    // double-lock the non-reentrant Mutex and hang the sync).
    private fun launchSync(block: suspend () -> Unit) {
        applicationScope.launch(ioDispatcher) { block() }
    }

    private suspend fun hasModel(backendId: String): Boolean {
        val descriptor = backendRegistry.byBackendId(backendId) ?: return false
        return descriptor.modelPathFlow(preferencesManager).first() != null
    }

    private fun setComponentEnabled(target: BackendDescriptor, enabled: Boolean) {
        // Sideload-only and external backends have no manifest activity-alias; skip them.
        if (target.shareAlias.isBlank()) return
        ComponentAliasSync.setEnabled(context, target.shareAlias, enabled, TAG)
    }

    private suspend fun externalRecordsPresent(): Boolean =
        externalModelStore.validRecords().isNotEmpty()

    /** Family-level sync for the external-models share target: enabled iff advanced sharing AND a valid record. */
    private suspend fun syncExternalFamily(advancedEnabled: Boolean) {
        ComponentAliasSync.setEnabled(context, EXTERNAL_FAMILY_ALIAS, advancedEnabled && externalRecordsPresent(), TAG)
    }

    /** The full resync, lock-free: [syncAllNow] wraps it, and so does the enable
     *  branch of [setAdvancedSharingEnabledNow] (the Mutex is not reentrant). */
    private suspend fun syncAllBody() {
        val advancedEnabled = preferencesManager.advancedSharingEnabled.first()

        backendRegistry.backends.forEach { target ->
            // Skip alias-less targets before the has-model check: externals would
            // otherwise buy a pointless DataStore read per sync.
            if (target.shareAlias.isBlank()) return@forEach
            setComponentEnabled(target, advancedEnabled && hasModel(target.backendId))
        }

        syncExternalFamily(advancedEnabled)
    }

    /** Fire-and-forget full sync. */
    fun syncAll() {
        launchSync { syncAllNow() }
    }

    internal suspend fun syncAllNow() = syncMutex.withLock { syncAllBody() }

    /** Fire-and-forget deletion resync. */
    fun onModelDeleted(backendId: String) {
        launchSync { onModelDeletedNow(backendId) }
    }

    internal suspend fun onModelDeletedNow(backendId: String) = syncMutex.withLock {
        // An external record deletion can remove the LAST valid record: resync the family.
        // This runs BEFORE the descriptor lookup: an external id may not derive a descriptor
        // anymore (already deleted from the store; the provider snapshot lags), and the
        // early return below would otherwise skip the family resync entirely.
        if (backendId.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX)) {
            val advancedEnabled = preferencesManager.advancedSharingEnabled.first()
            syncExternalFamily(advancedEnabled)
        }
        val target = backendRegistry.backends.find { it.backendId == backendId } ?: return@withLock
        setComponentEnabled(target, false)
    }

    /** Fire-and-forget post-download/import resync. */
    fun onModelDownloaded() {
        launchSync { syncAllNow() }
    }

    /** Fire-and-forget toggle resync. */
    fun setAdvancedSharingEnabled(enabled: Boolean) {
        launchSync { setAdvancedSharingEnabledNow(enabled) }
    }

    internal suspend fun setAdvancedSharingEnabledNow(enabled: Boolean) = syncMutex.withLock {
        if (enabled) {
            syncAllBody()
        } else {
            backendRegistry.backends.forEach { setComponentEnabled(it, false) }
            ComponentAliasSync.setEnabled(context, EXTERNAL_FAMILY_ALIAS, false, TAG)
        }
    }
}
