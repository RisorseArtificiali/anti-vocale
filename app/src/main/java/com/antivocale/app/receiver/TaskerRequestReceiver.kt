package com.antivocale.app.receiver

import android.app.NotificationManager
import com.antivocale.app.util.AppNotificationChannel
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antivocale.app.R
import com.antivocale.app.service.InferenceService

/**
 * BroadcastReceiver for handling Tasker requests.
 *
 * Supported Intent Actions:
 * - com.antivocale.app.PROCESS_REQUEST
 *
 * Required Extras:
 * - request_type: "text" or "audio"
 * - task_id: Unique identifier for the request
 *
 * Optional Extras:
 * - prompt: Text prompt (required for text requests, optional prefix for audio)
 * - file_path: Path to audio file (required for audio requests)
 *
 * Response Intent:
 * - Action: net.dinglisch.android.tasker.ACTION_TASKER_INTENT
 * - Extras: task_id, status, result_text (on success), error_message (on error)
 *
 * Android 12+ restricts starting foreground services from the background. This receiver
 * tries the direct approach first (works when battery optimization is disabled for the app).
 * If that fails, it posts a notification the user can tap — the tap is user-initiated,
 * satisfying the FGS restriction and allowing the service to start.
 */
class TaskerRequestReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "TaskerRequestReceiver"

        // Intent actions
        const val ACTION_PROCESS_REQUEST = "com.antivocale.app.PROCESS_REQUEST"
        const val ACTION_TASKER_REPLY = "net.dinglisch.android.tasker.ACTION_TASKER_INTENT"

        // Intent extras
        const val EXTRA_REQUEST_TYPE = "request_type"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_TASK_ID = "task_id"

        // Reply extras
        const val EXTRA_STATUS = "status"
        const val EXTRA_RESULT_TEXT = "result_text"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        // Status values
        const val STATUS_SUCCESS = "success"
        const val STATUS_ERROR = "error"

        // Fallback notification
        private val FALLBACK_CHANNEL_ID = AppNotificationChannel.TASKER_FALLBACK.id
        private const val FALLBACK_NOTIFICATION_ID = 2001
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROCESS_REQUEST) {
            Log.d(TAG, "Ignoring intent with action: ${intent.action}")
            return
        }

        Log.i(TAG, "Received Tasker request")

        val requestType = intent.getStringExtra(EXTRA_REQUEST_TYPE) ?: "text"
        val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: ""
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: "unknown_${System.currentTimeMillis()}"

        Log.d(TAG, "Request: type=$requestType, taskId=$taskId, prompt=${prompt.take(50)}...")

        val serviceIntent = Intent(context, InferenceService::class.java).apply {
            putExtra(EXTRA_REQUEST_TYPE, requestType)
            putExtra(EXTRA_PROMPT, prompt)
            putExtra(EXTRA_FILE_PATH, filePath)
            putExtra(EXTRA_TASK_ID, taskId)
        }

        // Try starting the foreground service directly. This works when:
        // 1. The app is in the foreground, OR
        // 2. Battery optimization is disabled for the app (explicit FGS exemption)
        try {
            context.startForegroundService(serviceIntent)
            Log.i(TAG, "Started InferenceService directly for taskId: $taskId")
        } catch (e: SecurityException) {
            // Android 12+ background FGS restriction — fall back to user-initiated notification
            Log.w(TAG, "FGS restricted (${e.javaClass.simpleName}). Posting fallback notification.")
            postFallbackNotification(context, requestType, prompt, filePath, taskId)
        } catch (e: IllegalStateException) {
            // App not in foreground — same fallback
            Log.w(TAG, "Cannot start from background (${e.javaClass.simpleName}). Posting fallback notification.")
            postFallbackNotification(context, requestType, prompt, filePath, taskId)
        }
    }

    /**
     * Posts a high-priority notification that, when tapped, launches [TaskerTrampolineActivity]
     * which starts [InferenceService]. The notification tap counts as user-initiated,
     * satisfying Android 12+ foreground service restrictions.
     */
    private fun postFallbackNotification(
        context: Context,
        requestType: String,
        prompt: String,
        filePath: String?,
        taskId: String
    ) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        // Create channel (idempotent — safe to call multiple times)
        AppNotificationChannel.TASKER_FALLBACK.create(context)

        // Build trampoline intent with all service extras forwarded
        val trampolineIntent = Intent(context, TaskerTrampolineActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_REQUEST_TYPE, requestType)
            putExtra(EXTRA_PROMPT, prompt)
            putExtra(EXTRA_FILE_PATH, filePath)
            putExtra(EXTRA_TASK_ID, taskId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            taskId.hashCode(),
            trampolineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, FALLBACK_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.tasker_fallback_notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(FALLBACK_NOTIFICATION_ID, notification)
        Log.i(TAG, "Posted fallback notification for taskId: $taskId")
    }

    /**
     * Sends a reply intent back to Tasker.
     */
    fun sendTaskerReply(
        context: Context,
        taskId: String,
        status: String,
        resultText: String? = null,
        errorMessage: String? = null
    ) {
        val replyIntent = Intent(ACTION_TASKER_REPLY).apply {
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_STATUS, status)

            if (status == STATUS_SUCCESS && resultText != null) {
                putExtra(EXTRA_RESULT_TEXT, resultText)
            } else if (status == STATUS_ERROR && errorMessage != null) {
                putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
            }
        }

        Log.d(TAG, "Sending reply: taskId=$taskId, status=$status")
        context.sendBroadcast(replyIntent)
    }
}
