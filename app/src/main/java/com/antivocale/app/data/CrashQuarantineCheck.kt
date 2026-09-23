package com.antivocale.app.data

import android.app.NotificationManager
import android.content.Context
import com.antivocale.app.R
import com.antivocale.app.service.ResultNotificationFactory
import kotlinx.coroutines.flow.first

/**
 * TASK-640: startup pass turning a leaked [PreferencesManager.pendingBackendLoad]
 * marker into a quarantine. The marker is armed around the external sherpa
 * loads ([com.antivocale.app.transcription.TranscriptionOrchestrator.configureBackend])
 * and cleared on any normal return; only a native death (uncatchable
 * in-process) leaves it set. This pass runs BEFORE [DanglingBackendCleaner] so
 * the record is already unresolvable when the cleaner reverts a dangling
 * active id to the default backend.
 *
 * Design decisions recorded: the trigger is the marker alone, NOT
 * [com.antivocale.app.util.NativeCrashDetector]'s exit reason, because the
 * marker is the only mechanism that covers every supported API (minSdk 26;
 * ApplicationExitInfo is 30+) and a model whose load gets the process killed
 * by lmkd on a low-RAM device is an accepted quarantine candidate (the same
 * exposure NativeCrashDetector already accepts). The record is QUARANTINED,
 * not deleted: deletion would destroy the downloaded files without consent;
 * the quarantined record stays listed in the Models tab (delete + re-import
 * is the re-enable path) while disappearing from backend selection.
 */
class CrashQuarantineCheck(
    private val preferencesManager: PreferencesManager,
    private val externalModelStore: ExternalModelStore,
) {
    /**
     * Quarantines the record the leaked marker names and returns it for the
     * notification; the marker is cleared only after the quarantine lands, so
     * a death between the two writes re-quarantines on the next launch
     * instead of losing the protection.
     */
    suspend fun apply(): ExternalModelRecord? {
        val pending = preferencesManager.pendingBackendLoad.first() ?: return null
        if (!pending.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX)) {
            preferencesManager.savePendingBackendLoad(null)
            return null
        }
        val id = pending.removePrefix(ExternalModelRecord.BACKEND_ID_PREFIX)
        val record = externalModelStore.records().firstOrNull { it.id == id }
        if (record == null || record.quarantined) {
            preferencesManager.savePendingBackendLoad(null)
            return null
        }
        externalModelStore.quarantine(id)
        preferencesManager.savePendingBackendLoad(null)
        return record
    }

    suspend fun check(context: Context) {
        val record = apply() ?: return
        ResultNotificationFactory(context).alertNotification(
            title = context.getString(R.string.external_model_quarantined_title),
            text = context.getString(R.string.external_model_quarantined_text, record.displayName),
        ).let { notification ->
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /** Fixed id below the result allocator's 3000 base; registered in ReservedNotificationIdContractTest. */
        const val NOTIFICATION_ID = 1005
    }
}
