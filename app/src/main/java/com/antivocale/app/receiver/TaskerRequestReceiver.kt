package com.antivocale.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.service.InferenceService
import java.io.File

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
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROCESS_REQUEST) {
            Log.d(TAG, "Ignoring intent with action: ${intent.action}")
            return
        }

        Log.i(TAG, "Received Tasker request")

        // Extract request parameters
        val requestType = intent.getStringExtra(EXTRA_REQUEST_TYPE) ?: "text"
        val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: ""
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: "unknown_${System.currentTimeMillis()}"

        Log.d(TAG, "Request: type=$requestType, taskId=$taskId, prompt=${prompt.take(50)}...")

        // Keep BroadcastReceiver alive while processing
        val pendingResult = goAsync()

        // Forward to InferenceService for processing
        val serviceIntent = Intent(context, InferenceService::class.java).apply {
            putExtra(EXTRA_REQUEST_TYPE, requestType)
            putExtra(EXTRA_PROMPT, prompt)
            putExtra(EXTRA_FILE_PATH, filePath)
            putExtra(EXTRA_TASK_ID, taskId)
        }

        context.startForegroundService(serviceIntent)

        // We'll let the service handle the reply
        // The pendingResult will be finished by the service
        // For now, finish it here as the service runs independently
        pendingResult.finish()
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
