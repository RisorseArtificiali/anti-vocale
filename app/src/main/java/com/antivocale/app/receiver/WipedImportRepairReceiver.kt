package com.antivocale.app.receiver

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antivocale.app.MainActivity
import com.antivocale.app.R
import com.antivocale.app.data.ExternalImportRepairer
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.di.ApplicationScope
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.util.canPostNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * TASK-963: the Recover action on the wiped-imports notification. The
 * broadcast carries no payload on purpose: the repairer singleton kept the
 * candidates and captured selections from its startup capture, so a
 * notification posted by an earlier process instance still works after the
 * process that armed it is gone.
 *
 * No goAsync: a repair spans minutes of multi-gigabyte downloads, far past
 * any broadcast window, so holding one would only risk the system killing
 * the process as a stuck receiver. The work runs on the application scope
 * (the injected process-lifetime scope; hand-building one is a documented
 * project rule violation) and the receiver returns immediately.
 */
@AndroidEntryPoint
class WipedImportRepairReceiver : BroadcastReceiver() {

    @Inject lateinit var repairer: ExternalImportRepairer
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPAIR_WIPED_IMPORTS) return
        runCatching {
            // The actionable notice is consumed by the tap; the outcome
            // re-posts on the same id below.
            context.getSystemService(NotificationManager::class.java)
                .cancel(WipedImportRepairNotifications.NOTIFICATION_ID)
            applicationScope.launch(Dispatchers.IO) {
                val repaired = try {
                    repairer.repairAll()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Wiped-import repair failed (manual re-import stays available)", e)
                    emptyList()
                }
                runCatching {
                    val nm = context.getSystemService(NotificationManager::class.java)
                    nm.cancel(WipedImportRepairNotifications.NOTIFICATION_ID)
                    if (repaired.isNotEmpty()) {
                        WipedImportRepairNotifications.postRecovered(context, repaired.size)
                    } else {
                        WipedImportRepairNotifications.postFailed(context)
                    }
                }.onFailure { e ->
                    Log.w(TAG, "Failed to post the repair outcome notification", e)
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to launch the wiped-import repair", e)
        }
    }

    companion object {
        private const val TAG = "WipedImportRepairReceiver"
        const val ACTION_REPAIR_WIPED_IMPORTS = "com.antivocale.app.REPAIR_WIPED_IMPORTS"
    }
}

/**
 * TASK-963: the three states of the wiped-import recovery notice, on ONE
 * fixed id and the [AppNotificationChannel.MODEL_REPAIR] channel. Every
 * post creates the channel first and gates on [canPostNotification] (the
 * shared range-review gate); the posts are contained so a notification
 * failure never breaks startup or the repair.
 */
object WipedImportRepairNotifications {

    /** Fixed id below the result allocator's 3000 base; registered in
     *  ReservedNotificationIdContractTest and the factory's contract table. */
    const val NOTIFICATION_ID = 2504

    /** Names shown verbatim in the wiped notice; beyond this the body
     *  collapses to a count so a wide wipe stays one readable line. */
    private const val VISIBLE_NAME_LIMIT = 3

    private const val RC_LAUNCH_APP = 0
    private const val RC_RECOVER = 1

    /** State 1, posted by BridgeApplication at startup when capture() found
     *  dangling candidates: what the cleaner deleted, with the Recover
     *  action that alone decides to spend the network. */
    fun postWiped(context: Context, candidates: List<ExternalModelRecord>) {
        post(context) { wipedNotification(context, candidates) }
    }

    /** State 2, posted by the receiver when at least one record is
     *  loadable again. */
    fun postRecovered(context: Context, count: Int) {
        post(context) {
            baseBuilder(context)
                .setContentTitle(context.getString(R.string.wiped_imports_recovered_title))
                .setContentText(context.getString(R.string.wiped_imports_recovered_text, count))
                .build()
        }
    }

    /** State 3, posted by the receiver when nothing recovered: the honest
     *  failure with the manual fallback named. */
    fun postFailed(context: Context) {
        post(context) {
            baseBuilder(context)
                .setContentTitle(context.getString(R.string.wiped_imports_failed_title))
                .setContentText(context.getString(R.string.wiped_imports_failed_text))
                .build()
        }
    }

    /** The wiped notice as a buildable notification (separate from the
     *  post so the Robolectric pin can assert the Recover action's
     *  PendingIntent). */
    fun wipedNotification(context: Context, candidates: List<ExternalModelRecord>): Notification {
        val names = candidates.map { it.displayName }
        val namesLine = if (names.size > VISIBLE_NAME_LIMIT) {
            names.take(VISIBLE_NAME_LIMIT).joinToString(", ") + " " +
                context.getString(R.string.wiped_imports_names_overflow, names.size - VISIBLE_NAME_LIMIT)
        } else {
            names.joinToString(", ")
        }
        val text = context.getString(R.string.wiped_imports_text, candidates.size, namesLine)
        // Explicit component, not a package-scoped action alone: the
        // receiver is not exported, so the PendingIntent must name it.
        val recover = PendingIntent.getBroadcast(
            context, RC_RECOVER,
            Intent(context, WipedImportRepairReceiver::class.java).apply {
                action = WipedImportRepairReceiver.ACTION_REPAIR_WIPED_IMPORTS
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return baseBuilder(context)
            .setContentTitle(context.getString(R.string.wiped_imports_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(
                android.R.drawable.stat_sys_download,
                context.getString(R.string.wiped_imports_recover_action),
                recover
            )
            .build()
    }

    private fun baseBuilder(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, AppNotificationChannel.MODEL_REPAIR.id)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(launchPendingIntent(context))
            .setAutoCancel(true)

    private fun launchPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, RC_LAUNCH_APP,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun post(context: Context, build: () -> Notification) {
        val channel = AppNotificationChannel.MODEL_REPAIR
        channel.create(context)
        if (!canPostNotification(context, channel)) return
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, build())
        }.onFailure { e ->
            Log.w("WipedImportRepair", "Failed to post the wiped-import notification", e)
        }
    }
}
