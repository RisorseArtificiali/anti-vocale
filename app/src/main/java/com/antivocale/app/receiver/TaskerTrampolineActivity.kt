package com.antivocale.app.receiver

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.antivocale.app.service.InferenceEnqueue
import com.antivocale.app.service.InferenceService

/**
 * Transparent trampoline Activity launched by the fallback notification posted by
 * [TaskerRequestReceiver] when a direct foreground service start is blocked.
 *
 * The notification tap counts as a user-initiated action, which satisfies Android 12+
 * foreground service restrictions and allows [InferenceService] to start legally.
 *
 * This Activity finishes immediately after forwarding the intent — the user never sees it.
 */
class TaskerTrampolineActivity : Activity() {

    companion object {
        const val TAG = "TaskerTrampoline"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Trampoline launched from notification tap — forwarding to InferenceService")

        // Forward all extras from the notification PendingIntent to InferenceService
        val serviceIntent = Intent(this, InferenceService::class.java).apply {
            intent?.extras?.let { putExtras(it) }
        }

        // TASK-1032 H: a failed start rides InferenceEnqueue.start (the ONE
        // enqueue path) instead of being dropped: even a user tap can meet
        // an FGS restriction, and the enqueue path owns the fallback that
        // preserves the request.
        when (val outcome = InferenceEnqueue.start(this, serviceIntent)) {
            is InferenceEnqueue.Outcome.Started -> Unit
            is InferenceEnqueue.Outcome.FallbackNotificationPosted ->
                Log.w(TAG, "Trampoline start restricted; fallback notification carries the request")
            is InferenceEnqueue.Outcome.Failed ->
                Log.e(TAG, "Failed to start InferenceService from trampoline: $outcome")
        }
        finish()
    }
}
