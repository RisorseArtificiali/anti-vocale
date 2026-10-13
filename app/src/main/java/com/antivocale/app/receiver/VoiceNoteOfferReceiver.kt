package com.antivocale.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.antivocale.app.data.VoiceNoteTreeGrant
import com.antivocale.app.service.InferenceEnqueue
import com.antivocale.app.service.InferenceService
import com.antivocale.app.service.VoiceNoteOfferNotifier
import com.antivocale.app.util.CrashReporter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * TASK-271: the Transcribe action of the voice-note offer notification.
 * Tapping it must NOT open an activity: this receiver rebuilds the same
 * service-intent shape the share path builds (request type audio, the
 * sender and source package threaded through) and hands it to
 * [InferenceEnqueue.start], the ONE enqueue path (the notification tap is
 * user-initiated, which satisfies the API 31+ background-start
 * restriction, and a still-restricted start rides the enqueue path's
 * trampoline fallback). If the file cannot be resolved at tap time the
 * offer is cancelled silently.
 *
 * The resolution (a DataStore read, two SAF provider queries, a file
 * copy) runs on Dispatchers.IO under goAsync, never runBlocking on the
 * main thread: the NotificationActionReceiver pattern.
 */
@AndroidEntryPoint
class VoiceNoteOfferReceiver : BroadcastReceiver() {

    @Inject lateinit var treeGrant: VoiceNoteTreeGrant

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TRANSCRIBE -> handleTranscribe(context, intent)
            else -> Log.d(TAG, "Unknown action: ${intent.action}")
        }
    }

    private fun handleTranscribe(context: Context, intent: Intent) {
        val whatsappPath = intent.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH)
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO + SupervisorJob() + CrashReporter.handler).launch {
            try {
                // The observed path lives in WhatsApp's FUSE-hidden tree and
                // is UNREADABLE directly (the 2026-10-13 spike): the tap
                // resolves it through the SAF tree grant into a readable
                // sandbox copy before enqueueing. A resolution failure
                // cancels the offer silently, the missing-file contract.
                val path = whatsappPath?.let { treeGrant.resolve(it) }
                if (path == null) {
                    Log.i(TAG, "offer file unresolvable at tap time: $whatsappPath")
                    appContext.getSystemService(android.app.NotificationManager::class.java)
                        ?.cancel(VoiceNoteOfferNotifier.OFFER_NOTIFICATION_ID)
                    return@launch
                }
                val serviceIntent = buildServiceIntent(appContext, intent, path)
                when (val outcome = InferenceEnqueue.start(appContext, serviceIntent)) {
                    InferenceEnqueue.Outcome.Started,
                    InferenceEnqueue.Outcome.FallbackNotificationPosted -> {
                        Log.i(TAG, "Voice-note transcription enqueued: $path")
                        appContext.getSystemService(android.app.NotificationManager::class.java)
                            ?.cancel(VoiceNoteOfferNotifier.OFFER_NOTIFICATION_ID)
                    }
                    is InferenceEnqueue.Outcome.Failed -> {
                        // Keep the offer: the file is still there and a later
                        // tap can retry. Failed means even the fallback
                        // notification could not post; cancelling here would
                        // lose the request with no signal at all.
                        Log.e(TAG, "Could not enqueue voice-note transcription", outcome.exception)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "voice-note tap failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val TAG = "VoiceNoteOfferReceiver"
        const val ACTION_TRANSCRIBE = "com.antivocale.app.VOICE_NOTE_TRANSCRIBE"

        /**
         * The share-shaped request for one offer: request type audio, the
         * local path, a fresh task id, the sender and source package when
         * detection matched them, and the voice-note source so History rows
         * and the no-reply rule can tell the origin apart.
         */
        internal fun buildServiceIntent(context: Context, offerIntent: Intent, localPath: String): Intent =
            Intent(context, InferenceService::class.java).apply {
                putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, TaskerRequestReceiver.REQUEST_TYPE_AUDIO)
                putExtra(
                    TaskerRequestReceiver.EXTRA_TASK_ID,
                    // Same granularity as the share path's share_<millis>;
                    // two human taps cannot land in the same millisecond.
                    "voicenote_${System.currentTimeMillis()}")
                putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, localPath)
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
