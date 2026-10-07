package com.antivocale.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antivocale.app.R
import com.antivocale.app.data.VoiceNoteOffer
import com.antivocale.app.data.VoiceNoteOfferPoster
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.receiver.VoiceNoteOfferReceiver
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.util.AudioDurationFormat
import com.antivocale.app.util.canPostNotification
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TASK-271: the production [VoiceNoteOfferPoster]. ONE notification (fixed
 * id [OFFER_NOTIFICATION_ID]) represents the newest un-transcribed voice
 * note: every post replaces whatever offer is on screen, and the
 * Transcribe action hands the offer to [VoiceNoteOfferReceiver] without
 * opening any activity.
 */
@Singleton
class VoiceNoteOfferNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceNoteOfferPoster {

    override fun canPost(): Boolean =
        canPostNotification(context, AppNotificationChannel.VOICE_NOTE_OFFER)

    override fun post(offer: VoiceNoteOffer) {
        AppNotificationChannel.VOICE_NOTE_OFFER.create(context)
        // The can-this-notification-actually-post gate (both halves:
        // app-level denial and per-channel block): notify() discards
        // silently in those cases, and a lost offer is a lost detection.
        if (!canPost()) {
            Log.w(TAG, "offer not posted: notifications disabled or channel blocked")
            return
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return

        val actionIntent = Intent(context, VoiceNoteOfferReceiver::class.java).apply {
            action = VoiceNoteOfferReceiver.ACTION_TRANSCRIBE
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, offer.filePath)
            offer.senderName?.let { putExtra(InferenceService.EXTRA_SENDER_NAME, it) }
            offer.ownerPackage?.let { putExtra(InferenceService.EXTRA_SOURCE_PACKAGE, it) }
        }
        // One slot, one offer at a time: a fixed request code plus
        // FLAG_UPDATE_CURRENT replaces the previous offer's intent, the
        // same class of guarantee InferenceEnqueue's fallback slot carries.
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(
            context, AppNotificationChannel.VOICE_NOTE_OFFER.id)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(R.string.voice_note_offer_title))
            .setContentText(offerText(offer))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // Not auto-cancel and no content intent on purpose: the body is
            // inert (an accidental tap must not start a transcription); the
            // ONE action button is the deliberate path.
            .setAutoCancel(false)
            .addAction(
                /* icon = */ 0,
                context.getString(R.string.voice_note_offer_action),
                pendingIntent)
            .build()
        notificationManager.notify(OFFER_NOTIFICATION_ID, notification)
    }

    /** Best-effort text: the sender when matched, the duration when probed. */
    internal fun offerText(offer: VoiceNoteOffer): String {
        val duration = offer.durationSeconds?.let { AudioDurationFormat.format(it) }
        return when {
            offer.senderName != null && duration != null ->
                context.getString(
                    R.string.voice_note_offer_text_with_sender, offer.senderName, duration)
            duration != null ->
                context.getString(R.string.voice_note_offer_text_duration, duration)
            else -> context.getString(R.string.voice_note_offer_text_plain)
        }
    }

    companion object {
        /**
         * Reserved-range contract (see ResultNotificationFactory's table):
         * the single voice-note offer slot, below the result allocator's
         * base, in the documented free headroom above the interrupted-runs
         * ids. Pinned by ReservedNotificationIdContractTest.
         */
        const val OFFER_NOTIFICATION_ID = 2601

        private const val REQUEST_CODE = 1
        private const val TAG = "VoiceNoteOffer"

    }
}
