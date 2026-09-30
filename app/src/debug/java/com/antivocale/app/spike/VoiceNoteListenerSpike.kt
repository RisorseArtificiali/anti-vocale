package com.antivocale.app.spike

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antivocale.app.BuildConfig

/**
 * TASK-269 spike (DEBUG BUILDS ONLY, both flavors: lives in the build-type
 * source set exactly like TestSpiReceiver, so release builds carry neither
 * the class nor the manifest service; a BuildConfig.DEBUG refusal is the
 * second gate, same defense-in-depth the SPI receiver carries). Observation
 * prototype for the semi-automatic voice-note family (the TASK-273 GO
 * verdict): it answers, from a live device, the three unknowns the
 * 2026-07-12 research left open:
 *
 *  1. Sender extraction: does Notification.MessagingStyle carry a usable
 *     Person name for WhatsApp/Telegram voice notes (AC1)?
 *  2. Grouping: how do grouped/summary notifications present themselves
 *     (FLAG_GROUP_SUMMARY, groupKey vs key) so a real detector never counts
 *     a summary as a message (AC2)?
 *  3. The voice-note signature: what text/marker distinguishes a voice
 *     message from a plain text message in the notification (localized
 *     strings make hard-coding wrong; the device run supplies the truth,
 *     AC4)?
 *
 * Exposure is bounded on purpose: each message logs only its sender and a
 * 40-char text excerpt of the NEWEST 3 messages (the AC4 marker is short;
 * never conversation history in full), never audio, never action
 * invocations. Every line is one greppable VNSPIKE record so the device
 * trial yields AC evidence directly from logcat; both entry points log, so
 * a dead instrument is distinguishable from a no-data trial (Realme/Oplus
 * aggressively unbinds listeners).
 *
 * Enable on the test device WITHOUT UI (special access is not a runtime
 * permission):
 *   adb shell cmd notification allow_listener \
 *     com.antivocale.app.debug/com.antivocale.app.spike.VoiceNoteListenerSpike
 * (debug package carries the .debug suffix; see docs/testing-spi.md for the
 * same trap on the SPI receiver.)
 */
class VoiceNoteListenerSpike : NotificationListenerService() {

    override fun onListenerConnected() {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "connected; activeNotifications=${activeNotifications.size}")
        // The already-live posts are the richest immediate sample (existing
        // group summaries included); a trial session with no incoming
        // traffic still yields AC records from them.
        activeNotifications.forEach { record(it) }
    }

    override fun onListenerDisconnected() {
        // The one line that keeps a silent unbind (an Oplus specialty)
        // distinguishable from "no voice-note signature found".
        Log.w(TAG, "disconnected; records after this line are missing, not absent")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!BuildConfig.DEBUG) return
        sbn ?: return
        record(sbn)
    }

    private fun record(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        // Observe message-class notifications only (CATEGORY_MESSAGE covers
        // WhatsApp/Telegram/Signal style messengers); everything else is
        // noise for this spike. Group summaries also carry the category and
        // are logged deliberately: AC2 needs their shape on record.
        val isMessage = n.category == Notification.CATEGORY_MESSAGE
        val pkg = sbn.packageName ?: "?"
        if (!isMessage && !pkg.contains("whatsapp", ignoreCase = true) &&
            !pkg.contains("telegram", ignoreCase = true)
        ) return

        // The compat extractor (public path; the bundle-array helpers are
        // package-private): reads the style on every API level the app
        // supports (the framework Person is 28+; compat mirrors it) and
        // yields the messages with text + person attached. style= is logged
        // beside it so senders=[]/texts=[] stays distinguishable between
        // "no MessagingStyle on the notification" and "the extractor failed
        // on this payload" (instrument failure vs platform truth).
        val stylePresent = n.extras.containsKey(Notification.EXTRA_MESSAGES)
        val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            ?.messages.orEmpty()
        val senders = messages.mapNotNull { it.person?.name?.toString() }.distinct()
        val texts = messages.takeLast(3).mapNotNull { it.text?.toString() }
            .map { it.take(40) }
        val conversationTitle = n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
        val groupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        Log.i(
            TAG,
            "pkg=$pkg ts=${sbn.postTime} key=${sbn.key} groupKey=${sbn.groupKey} " +
                "groupSummary=$groupSummary style=$stylePresent " +
                "conversationTitle=$conversationTitle senders=$senders texts=$texts",
        )
    }

    private companion object {
        const val TAG = "VNSPIKE"
    }
}
