package com.antivocale.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antivocale.app.R
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.receiver.TaskerTrampolineActivity
import com.antivocale.app.util.AppNotificationChannel

/**
 * TASK-500 code-review F2/F6: the ONE enqueue path for InferenceService
 * requests. Every producer site builds its intent as before and hands it
 * here; this object owns the start plus the Tasker-style fallback for the
 * API 31+ foreground-service-start restriction.
 *
 * The restriction surfaces as SecurityException or IllegalStateException
 * from startForegroundService when the app is backgrounded at the moment of
 * the start. Instead of dropping the request, a high-priority notification
 * is posted whose tap launches [TaskerTrampolineActivity]; the tap counts
 * as user-initiated and the trampoline forwards the FULL extras set to the
 * service, so any request shape rides the fallback unchanged.
 */
object InferenceEnqueue {

    sealed interface Outcome {
        /** The service started directly. */
        data object Started : Outcome

        /** The start was restricted; the fallback notification now carries the request. */
        data object FallbackNotificationPosted : Outcome

        /** The start failed for a different reason; nothing was posted. */
        data class Failed(val exception: Throwable) : Outcome
    }

    fun start(context: Context, serviceIntent: Intent): Outcome {
        return try {
            context.startForegroundService(serviceIntent)
            Outcome.Started
        } catch (e: SecurityException) {
            Log.w(TAG, "FGS restricted (${e.javaClass.simpleName}); posting fallback notification")
            postFallbackNotification(context, serviceIntent)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot start from background (${e.javaClass.simpleName}); posting fallback notification")
            postFallbackNotification(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "InferenceService enqueue failed", e)
            Outcome.Failed(e)
        }
    }

    /**
     * Mirrors [TaskerRequestReceiver]'s fallback: sequential id in the
     * reserved Tasker band (two concurrent posts never share a slot; the
     * notification's contentIntent is the sole carrier of the request).
     */
    private fun postFallbackNotification(context: Context, serviceIntent: Intent): Outcome =
        runCatching {
            postFallbackNotificationUnchecked(context, serviceIntent)
        }.getOrElse { e ->
            Log.e(TAG, "Failed to post fallback notification", e)
            Outcome.Failed(e)
        }

    private fun postFallbackNotificationUnchecked(context: Context, serviceIntent: Intent): Outcome {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        AppNotificationChannel.TASKER_FALLBACK.create(context)

        val trampolineIntent = Intent(context, TaskerTrampolineActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtras(serviceIntent)
        }
        // The fallback slot keys BOTH the notification id and the PendingIntent
        // requestCode: a taskId-hash requestCode let two concurrent restricted
        // starts with colliding hashes overwrite one PendingIntent while the
        // two notifications stayed distinct, silently losing the first request
        // (the band comment's exact failure class, closed here).
        val slot = TaskerRequestReceiver.fallbackNotificationId()
        val pendingIntent = PendingIntent.getActivity(
            context,
            slot,
            trampolineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(
            context, AppNotificationChannel.TASKER_FALLBACK.id
        )
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.tasker_fallback_notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            notificationManager.notify(slot, notification)
            Outcome.FallbackNotificationPosted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post fallback notification", e)
            Outcome.Failed(e)
        }
    }

    private const val TAG = "InferenceEnqueue"
}
