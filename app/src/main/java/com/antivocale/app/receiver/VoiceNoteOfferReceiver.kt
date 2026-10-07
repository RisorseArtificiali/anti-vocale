package com.antivocale.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.antivocale.app.service.InferenceEnqueue
import com.antivocale.app.service.InferenceService
import com.antivocale.app.service.VoiceNoteOfferNotifier
import java.io.File

/**
 * TASK-271: the Transcribe action of the voice-note offer notification.
 * Tapping it must NOT open an activity: this receiver rebuilds the same
 * service-intent shape the share path builds (request type audio, the
 * sender and source package threaded through) and hands it to
 * [InferenceEnqueue.start], the ONE enqueue path (the notification tap is
 * user-initiated, which satisfies the API 31+ background-start
 * restriction, and a still-restricted start rides the enqueue path's
 * trampoline fallback). If the file no longer exists at tap time the offer
 * is cancelled silently.
 */
class VoiceNoteOfferReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TRANSCRIBE -> handleTranscribe(context, intent)
            else -> Log.d(TAG, "Unknown action: ${intent.action}")
        }
    }

    private fun handleTranscribe(context: Context, intent: Intent) {
        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
        val path = intent.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH)
        // The file may be gone by tap time (chat cleanup, a media re-index
        // that moved it): cancel the offer silently, exactly like the
        // missing-path case. No error surface: the offer is best-effort.
        if (path == null || !File(path).exists()) {
            Log.i(TAG, "offer file gone at tap time: $path")
            notificationManager?.cancel(VoiceNoteOfferNotifier.OFFER_NOTIFICATION_ID)
            return
        }
        val serviceIntent = buildServiceIntent(context, intent)
        when (val outcome = InferenceEnqueue.start(context, serviceIntent)) {
            InferenceEnqueue.Outcome.Started,
            InferenceEnqueue.Outcome.FallbackNotificationPosted -> {
                Log.i(TAG, "Voice-note transcription enqueued: $path")
                notificationManager?.cancel(VoiceNoteOfferNotifier.OFFER_NOTIFICATION_ID)
            }
            is InferenceEnqueue.Outcome.Failed -> {
                // Keep the offer: the file is still there and a later tap
                // can retry. Failed means even the fallback notification
                // could not post; cancelling here would lose the request
                // with no signal at all.
                Log.e(TAG, "Could not enqueue voice-note transcription", outcome.exception)
            }
        }
    }

    companion object {
        const val TAG = "VoiceNoteOfferReceiver"
        const val ACTION_TRANSCRIBE = "com.antivocale.app.VOICE_NOTE_TRANSCRIBE"

        /**
         * The share-shaped request for one offer: request type audio, the
         * file path, a fresh task id, the sender and source package when
         * detection matched them, and the voice-note source so History rows
         * and the no-reply rule can tell the origin apart.
         */
        internal fun buildServiceIntent(context: Context, offerIntent: Intent): Intent =
            Intent(context, InferenceService::class.java).apply {
                putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, TaskerRequestReceiver.REQUEST_TYPE_AUDIO)
                putExtra(
                    TaskerRequestReceiver.EXTRA_TASK_ID,
                    // Same granularity as the share path's share_<millis>;
                    // two human taps cannot land in the same millisecond.
                    "voicenote_${System.currentTimeMillis()}")
                putExtra(
                    TaskerRequestReceiver.EXTRA_FILE_PATH,
                    offerIntent.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH))
                offerIntent.getStringExtra(InferenceService.EXTRA_SOURCE_PACKAGE)?.let {
                    putExtra(InferenceService.EXTRA_SOURCE_PACKAGE, it)
                }
                offerIntent.getStringExtra(InferenceService.EXTRA_SENDER_NAME)?.let {
                    putExtra(InferenceService.EXTRA_SENDER_NAME, it)
                }
                putExtra(InferenceService.EXTRA_SOURCE, InferenceService.SOURCE_VOICE_NOTE)
            }
    }
}
