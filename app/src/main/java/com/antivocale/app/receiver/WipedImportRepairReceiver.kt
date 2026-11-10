package com.antivocale.app.receiver

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antivocale.app.R
import com.antivocale.app.data.ExternalImportRepairer
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.di.ApplicationScope
import com.antivocale.app.service.ResultNotificationFactory
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.util.canPostNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * TASK-963/971: two arms of the wiped-imports notification family. The
 * REPAIR arm is now a router: current notices address
 * WipedImportRepairService directly (foreground service, per-file
 * progress, process survives the download), and this arm exists so a
 * Recover PendingIntent armed by an EARLIER process (they address this
 * component by name) still lands in the service. The REMOVE arm
 * (TASK-973) stays here: deletion is seconds, not a download, and needs
 * the Hilt-injected repairer this @AndroidEntryPoint receiver provides.
 *
 * No goAsync on the Remove arm: the delete runs on the
 * application scope (the injected process-lifetime scope) and the
 * receiver returns immediately.
 */
@AndroidEntryPoint
class WipedImportRepairReceiver : BroadcastReceiver() {

    @Inject lateinit var repairer: ExternalImportRepairer
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        // TASK-973: the Remove action has its own arm (it never downloads:
        // it deletes the records the last repair proved dead).
        if (intent.action == ACTION_REMOVE_DEAD_WIPED_IMPORTS) {
            runCatching {
                applicationScope.launch(Dispatchers.IO) {
                    val removed = runCatching { repairer.removeDeadSources() }
                        .onFailure { e -> Log.e(TAG, "Dead-source removal failed", e) }
                        .getOrDefault(emptyList())
                    runCatching {
                        WipedImportRepairNotifications.postRemoved(context, removed.size)
                    }
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to launch the dead-source removal", e)
            }
            return
        }
        if (intent.action != ACTION_REPAIR_WIPED_IMPORTS) return
        // TASK-971: the repair runs INSIDE WipedImportRepairService
        // (foreground: the process survives the multi-gigabyte download and
        // the notice carries per-file progress). This arm stays as the
        // router for staler PendingIntents posted by earlier processes; a
        // tap on a current notice addresses the service directly.
        runCatching {
            context.startForegroundService(
                android.content.Intent(
                    context, com.antivocale.app.service.WipedImportRepairService::class.java))
        }.onFailure { e ->
            Log.e(TAG, "Failed to start the wiped-import repair service", e)
        }
    }

    companion object {
        private const val TAG = "WipedImportRepairReceiver"
        const val ACTION_REPAIR_WIPED_IMPORTS = "com.antivocale.app.REPAIR_WIPED_IMPORTS"
        /** TASK-973: the failed notice's Remove action (dead sources only). */
        const val ACTION_REMOVE_DEAD_WIPED_IMPORTS =
            "com.antivocale.app.REMOVE_DEAD_WIPED_IMPORTS"
    }
}

/**
 * TASK-963: the states of the wiped-import recovery notice, on ONE fixed id
 * and the [AppNotificationChannel.MODEL_REPAIR] channel. The one-shot
 * outcome states compose through [ResultNotificationFactory.alertNotification]
 * (the family's documented home for plain alerts); only the actionable wiped
 * state, which returns a built Notification carrying a Recover action, keeps
 * its own builder here. Every post creates the channel first and gates on
 * [canPostNotification] (the shared range-review gate); the posts are
 * contained so a notification failure never breaks startup or the repair.
 */
object WipedImportRepairNotifications {

    /** Fixed id below the result allocator's 3000 base; registered in
     *  ReservedNotificationIdContractTest and the factory's contract table. */
    const val NOTIFICATION_ID = 2504

    /** Names shown verbatim in the wiped notice; beyond this the body
     *  collapses to a count so a wide wipe stays one readable line. */
    private const val VISIBLE_NAME_LIMIT = 3

    private const val RC_RECOVER = 1
    private const val RC_REMOVE_DEAD = 3
    private const val RC_CANCEL = 4

    /** State 1, posted at startup when the scan found dangling candidates:
     * what the cleaner deleted, with the Recover action that alone decides
     * to spend the network. */
    fun postWiped(context: Context, candidates: List<ExternalModelRecord>) {
        post(context) { wipedNotification(context, candidates) }
    }

    /** States 2 and 3, the repair outcome on the same id: recovered when
     *  anything is loadable again, the honest failure (manual fallback
     *  named) when something was attempted and nothing recovered, and
     *  NOTHING when nothing was dangling at tap time (a stale notification
     *  with nothing left to do is not a failure). The receiver's exception
     *  arm passes attempted 1 with 0 repaired, landing on the failure. */
    fun postOutcome(
        context: Context,
        attempted: Int,
        repairedCount: Int,
        networkFailedCount: Int = 0,
        sourceFailedCount: Int = 0,
    ) {
        if (sourceFailedCount > 0) {
            // The actionable state wins over the recovered tone when both
            // occurred: a silent dead-marking under a "recovery finished"
            // notice leaves the broken entry with no remedy (the Remove
            // action lives only here). The recovered models are visible on
            // the Models tab.
            post(context) { failedNotification(context, networkFailedCount, sourceFailedCount) }
        } else if (repairedCount > 0) {
            post(context) { recoveredAlert(context, repairedCount) }
        } else if (attempted > 0 || networkFailedCount > 0) {
            post(context) { failedNotification(context, networkFailedCount, sourceFailedCount) }
        }
    }

    private fun recoveredAlert(context: Context, count: Int): Notification =
        ResultNotificationFactory(context).alertNotification(
            title = context.getString(R.string.wiped_imports_recovered_title),
            text = context.getString(R.string.wiped_imports_recovered_text, count),
            priority = NotificationCompat.PRIORITY_DEFAULT,
            channel = AppNotificationChannel.MODEL_REPAIR,
            contentIntent = modelsTabIntent(context),
        )

    /** TASK-973: the typed failure. Network failures keep the retryable
     *  copy and RE-ARM the Recover action (the maintainer's own friction:
     *  the old failed notice carried no action). Source failures carry the
     *  unavailable-source copy and the Remove action; a mixed outcome
     *  carries both actions and the source copy (the counts ride in the
     *  text; only source count is named, the network retry is the action). */
    private fun failedNotification(
        context: Context,
        networkFailedCount: Int,
        sourceFailedCount: Int,
    ): Notification {
        val builder = baseBuilder(context)
            // The terminal outcome may auto-cancel (the actionable wiped
            // notice must not); everything else rides baseBuilder.
            .setAutoCancel(true)
        if (sourceFailedCount > 0) {
            builder
                .setContentTitle(context.getString(R.string.wiped_imports_source_failed_title))
                .setContentText(
                    context.getString(R.string.wiped_imports_source_failed_text, sourceFailedCount))
                .addAction(
                    android.R.drawable.ic_menu_delete,
                    context.getString(R.string.wiped_imports_remove_action),
                    removePendingIntent(context))
        } else {
            builder
                .setContentTitle(context.getString(R.string.wiped_imports_failed_title))
                .setContentText(context.getString(R.string.wiped_imports_failed_text))
        }
        if (networkFailedCount > 0 || sourceFailedCount == 0) {
            builder.addAction(
                android.R.drawable.stat_sys_download,
                context.getString(R.string.wiped_imports_recover_action),
                recoverPendingIntent(context))
        }
        return builder.build()
    }

    /** TASK-973: the Remove outcome, on the same id. A no-op removal
     *  (stale second tap) posts nothing: a "Removed 0" confirmation would
     *  claim work that did not happen. */
    fun postRemoved(context: Context, removedCount: Int) {
        if (removedCount == 0) return
        post(context) {
            ResultNotificationFactory(context).alertNotification(
                title = context.getString(R.string.wiped_imports_source_failed_title),
                text = context.getString(R.string.wiped_imports_removed_text, removedCount),
                priority = NotificationCompat.PRIORITY_DEFAULT,
                channel = AppNotificationChannel.MODEL_REPAIR,
                contentIntent = modelsTabIntent(context),
            )
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
        val recover = recoverPendingIntent(context)
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

    /** The wiped notice's base: unlike the outcome alerts this state is
     *  actionable and BigText, so it keeps its own builder (the factory's
     *  alert family builds plain one-shots). NOT auto-cancel (TASK-963
     *  follow-up): a body tap must not swallow the Recover action; the
     *  outcome posts replace this notice on the same id, [cancelActionable]
     *  dismisses it, and a swipe is the user's explicit choice. */
    private fun baseBuilder(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, AppNotificationChannel.MODEL_REPAIR.id)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(modelsTabIntent(context))
            .setAutoCancel(false)

    /** The factory's Models-tab intent, hoisted so the receiver does not
     *  repeat the construction expression at every arm. */
    /** TASK-971: the foreground-service state: the same fixed id as every
     *  other state, ongoing (the service's lifecycle owns it), with the
     *  service-targeted Cancel action. [detail] carries the per-file line
     *  (filenames and counts are locale-free); null = the opening copy. */
    fun progressNotification(context: Context, detail: String? = null): Notification {
        val cancel = android.app.PendingIntent.getService(
            context, RC_CANCEL,
            android.content.Intent(
                context, com.antivocale.app.service.WipedImportRepairService::class.java).apply {
                action = com.antivocale.app.service.WipedImportRepairService.ACTION_CANCEL
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, AppNotificationChannel.MODEL_REPAIR.id)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.wiped_imports_progress_title))
            .setContentText(detail ?: context.getString(R.string.wiped_imports_progress_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(ResultNotificationFactory(context).modelsTabPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_cancel),
                cancel)
            .build()
    }

    /** In-place progress update through the family's posting container. */
    fun postProgress(context: Context, detail: String) = post(context) {
        progressNotification(context, detail)
    }

    private fun modelsTabIntent(context: Context): PendingIntent =
        ResultNotificationFactory(context).modelsTabPendingIntent()

    private fun recoverPendingIntent(context: Context): PendingIntent =
        // TASK-971: the foreground service owns the run; the system starts
        // it in the foreground directly from the action tap (the documented
        // pattern; the receiver's broadcast arm only serves stale notices).
        PendingIntent.getForegroundService(
            context, RC_RECOVER,
            Intent(context, com.antivocale.app.service.WipedImportRepairService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun removePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, RC_REMOVE_DEAD,
            Intent(context, WipedImportRepairReceiver::class.java).apply {
                action = WipedImportRepairReceiver.ACTION_REMOVE_DEAD_WIPED_IMPORTS
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun post(context: Context, build: () -> Notification) = runCatching {
        val channel = AppNotificationChannel.MODEL_REPAIR
        channel.create(context)
        if (canPostNotification(context, channel)) {
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, build())
        }
    }.onFailure { e ->
        Log.w("WipedImportRepair", "Failed to post the wiped-import notification", e)
    }
}
