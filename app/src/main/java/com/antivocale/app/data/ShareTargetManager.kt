package com.antivocale.app.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.antivocale.app.transcription.BackendDescriptor
import com.antivocale.app.transcription.BackendRegistry
import kotlinx.coroutines.flow.first

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
 * BridgeApplication.onCreate (launched on an application scope) before the first
 * emission lands.
 *
 * Every read here is a suspend DataStore/flow read: callers must already be on a
 * coroutine (BridgeApplication's application scope, viewModelScope, or a test's
 * runTest); the manager never blocks its calling thread.
 */
class ShareTargetManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val backendRegistry: BackendRegistry,
    private val externalModelStore: ExternalModelStore,
) {
    companion object {
        private const val TAG = "ShareTargetManager"
        // Single source (also used by ShareReceiverActivity): renaming must find
        // the manifest literal too, pinned by BackendRegistryTest.
        internal const val EXTERNAL_FAMILY_ALIAS = "com.antivocale.app.ShareExternal"
    }

    private suspend fun hasModel(backendId: String): Boolean {
        val descriptor = backendRegistry.byBackendId(backendId) ?: return false
        return descriptor.modelPathFlow(preferencesManager).first() != null
    }

    private fun setComponentEnabled(target: BackendDescriptor, enabled: Boolean) {
        // Sideload-only and external backends have no manifest activity-alias; skip them.
        if (target.shareAlias.isBlank()) return
        setClassNameEnabled(target.shareAlias, enabled)
    }

    private fun setClassNameEnabled(className: String, enabled: Boolean) {
        val state = if (enabled)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, className),
                state,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync $className", e)
        }
    }

    private suspend fun externalRecordsPresent(): Boolean =
        externalModelStore.validRecords().isNotEmpty()

    /** Family-level sync for the external-models share target: enabled iff advanced sharing AND a valid record. */
    private suspend fun syncExternalFamily(advancedEnabled: Boolean) {
        setClassNameEnabled(EXTERNAL_FAMILY_ALIAS, advancedEnabled && externalRecordsPresent())
    }

    suspend fun syncAll() {
        val advancedEnabled = preferencesManager.advancedSharingEnabled.first()

        backendRegistry.backends.forEach { target ->
            // Skip alias-less targets before the has-model check: externals would
            // otherwise buy a pointless DataStore read per sync.
            if (target.shareAlias.isBlank()) return@forEach
            setComponentEnabled(target, advancedEnabled && hasModel(target.backendId))
        }

        syncExternalFamily(advancedEnabled)
    }

    suspend fun onModelDeleted(backendId: String) {
        // An external record deletion can remove the LAST valid record: resync the family.
        // This runs BEFORE the descriptor lookup: an external id may not derive a descriptor
        // anymore (already deleted from the store; the provider snapshot lags), and the
        // early return below would otherwise skip the family resync entirely.
        if (backendId.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX)) {
            val advancedEnabled = preferencesManager.advancedSharingEnabled.first()
            syncExternalFamily(advancedEnabled)
        }
        val target = backendRegistry.backends.find { it.backendId == backendId } ?: return
        setComponentEnabled(target, false)
    }

    suspend fun onModelDownloaded() {
        syncAll()
    }

    suspend fun setAdvancedSharingEnabled(enabled: Boolean) {
        if (enabled) {
            syncAll()
        } else {
            backendRegistry.backends.forEach { setComponentEnabled(it, false) }
            setClassNameEnabled(EXTERNAL_FAMILY_ALIAS, false)
        }
    }
}
