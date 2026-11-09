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
 * TASK-963: the Recover action on the wiped-imports notification. The
 * broadcast carries no payload on purpose: the repairer re-derives its
 * candidates from the store at tap time, so a notification posted by an
 * earlier process instance still works after the process that armed it is
 * gone, and a stale tap on an already-restored model is a no-op rather than
 * a failure.
 *
 * Deliberately NOT routed through NotificationActionReceiver like the other
 * notification actions: the repair needs Hilt injection (the repairer
 * singleton and the application scope), and this dedicated @AndroidEntryPoint
 * receiver is the one-component way to get both.
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
            // Consume the actionable notice (tapping an ACTION button does
            // not trigger autoCancel; only the notification body does) and
            // show the in-progress state in its place: a multi-gigabyte
            // download with no visible surface is indistinguishable from a
            // dead tap. The outcome below replaces it on the same id; if
            // the process dies mid-download, the next cold start's scan
            // re-posts the wiped notice.
            WipedImportRepairNotifications.postInProgress(context)
            applicationScope.launch(Dispatchers.IO) {
                val result = try {
                    repairer.repairAll()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Wiped-import repair failed (manual re-import stays available)", e)
                    null
                }
                runCatching {
                    WipedImportRepairNotifications.postOutcome(
                        context, attempted = result?.attempted ?: 1,
                        repairedCount = result?.repairedIds?.size ?: 0)
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

    /** Dismisses the actionable wiped notice (the Recover tap's first
     *  effect; the outcome below re-posts on the same id). */
    fun cancelActionable(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(NOTIFICATION_ID)
    }

    /** The in-progress state between the Recover tap and the outcome: the
     *  same id, so the outcome replaces it in place. */
    fun postInProgress(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        post(context) {
            ResultNotificationFactory(context).alertNotification(
                title = context.getString(R.string.wiped_imports_progress_title),
                text = context.getString(R.string.wiped_imports_progress_text),
                priority = NotificationCompat.PRIORITY_DEFAULT,
                channel = AppNotificationChannel.MODEL_REPAIR,
                onlyAlertOnce = true,
                contentIntent = modelsTabIntent(context),
            )
        }
    }

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
    fun postOutcome(context: Context, attempted: Int, repairedCount: Int) {
        if (repairedCount > 0) {
            post(context) { recoveredAlert(context, repairedCount) }
        } else if (attempted > 0) {
            postFailed(context)
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

    /** The failed arm stays public: the receiver posts it directly when the
     *  repair coroutine itself died before producing a result. */
    fun postFailed(context: Context) {
        post(context) {
            ResultNotificationFactory(context).alertNotification(
                title = context.getString(R.string.wiped_imports_failed_title),
                text = context.getString(R.string.wiped_imports_failed_text),
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
    private fun modelsTabIntent(context: Context): PendingIntent =
        ResultNotificationFactory(context).modelsTabPendingIntent()

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
