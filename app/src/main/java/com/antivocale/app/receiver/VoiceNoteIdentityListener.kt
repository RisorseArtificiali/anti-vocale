package com.antivocale.app.receiver

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * TASK-735: the production voice-note identity listener (maintainer
 * decision 2026-10-01, from the TASK-269 spike). Opt-in twice: the user
 * grants notification access in system settings AND turns our toggle on;
 * until both hold, the service reads nothing (the pref is cached by a
 * collector at connect time and checked before any extraction).
 *
 * PRIVACY CONTRACT (the toggle copy states it): only message-category
 * notifications from messaging packages are inspected; only the sender,
 * the duration marker and the timestamp are kept. The CACHE is in memory
 * only (never Crashlytics, never any report; process death forgets it;
 * toggle off clears it and stops all reading). The durable part is the
 * LABEL the share flow writes on the History row, which persists exactly
 * like the transcript it names; that is the feature, stated in the copy.
 */
@AndroidEntryPoint
class VoiceNoteIdentityListener : NotificationListenerService() {

    @Inject lateinit var cache: VoiceNoteIdentityCache
    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    @Volatile private var enabled = false
    private var prefJob: Job? = null

    override fun onListenerConnected() {
        // The collector keeps the check in onNotificationPosted cheap and
        // current; default false until the first emission says otherwise.
        // One collector at a time: a reconnect cancels the previous one
        // (review: an eternal collect per reconnect into the app scope
        // accumulates).
        prefJob?.cancel()
        // No synchronous read: these callbacks arrive on the service's MAIN
        // looper, and runBlocking there parks the thread on a DataStore disk
        // read at every rebind (Oplus rebinds aggressively). The collector's
        // first emission arms the gate a few ms later; a voice note posted
        // in that window stays unlabeled, which is the honest default
        // (unlabeled beats mislabeled) and strictly better than a stall.
        prefJob = scope.launch {
            preferencesManager.voiceNoteIdentityEnabled.collect {
                enabled = it
                // Toggle off must also forget what was captured: the RAM-only
                // contract means off leaves nothing readable.
                if (!it) cache.clear()
            }
        }
        Log.i(TAG, "connected; enabled=$enabled (pref collector armed)")
    }

    override fun onListenerDisconnected() {
        prefJob?.cancel()
        prefJob = null
        // One line, same as the spike: an unbind (an Oplus specialty) stays
        // distinguishable from silence.
        Log.w(TAG, "disconnected; identities after this line are missing, not absent")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!enabled) return
        sbn ?: return
        val n = sbn.notification ?: return
        // Group summaries carry the GROUP name in the title, never a sender.
        val isGroupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        // Lazy: the Person unmarshal runs only when the extractor's cheap
        // gates (category, marker) already accepted the notification.
        val stylePersons = {
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(n)?.messages.orEmpty()
                .map { it.person?.name?.toString() }
        }
        val note = VoiceNoteIdentityExtractor.fromNotification(
            category = n.category,
            packageName = sbn.packageName.orEmpty(),
            title = n.extras.getCharSequence(Notification.EXTRA_TITLE),
            text = n.extras.getCharSequence(Notification.EXTRA_TEXT),
            conversationTitle = n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            messagingPersons = stylePersons,
            isGroupSummary = isGroupSummary,
            postedAtMs = sbn.postTime,
        ) ?: return
        cache.remember(note)
        // No sender names in logcat either: the entry point and the package
        // only, so a working instrument stays distinguishable from silence.
        Log.i(TAG, "voice note from ${note.packageName} (${note.durationSeconds}s) cached")
    }

    private companion object {
        const val TAG = "VoiceNoteIdentity"
    }
}

/**
 * TASK-735: the RAM-only identity cache the listener fills and the share
 * flow (TASK-736) will match against. Bounded to [MAX_ENTRIES] newest and
 * pruned past [TTL_MS]; a process death clears it by design (labels are
 * best-effort; the privacy contract says nothing persists).
 */
@Singleton
class VoiceNoteIdentityCache @Inject constructor() {

    private val entries = ArrayDeque<VoiceNoteIdentityExtractor.VoiceNote>()

    @Synchronized
    fun remember(note: VoiceNoteIdentityExtractor.VoiceNote) {
        // Re-posts of the SAME notification (app open, widget refresh,
        // direct-reply) must not duplicate the entry and evict distinct
        // recent notes from the bound.
        entries.removeAll {
            it.packageName == note.packageName && it.postedAtMs == note.postedAtMs
        }
        entries.addLast(note)
        prune(System.currentTimeMillis())
    }

    /** The cache entries for [packageName], newest first (the matcher's input). */
    @Synchronized
    fun forPackage(packageName: String): List<VoiceNoteIdentityExtractor.VoiceNote> {
        // Prune on READ too (review): a quiet chat's last entry must not
        // outlive the TTL just because nothing newer arrived.
        prune(System.currentTimeMillis())
        return entries.filter { it.packageName == packageName }.sortedByDescending { it.postedAtMs }
    }

    @Synchronized
    fun clear() = entries.clear()

    private fun prune(nowMs: Long) {
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        entries.removeAll { nowMs - it.postedAtMs > TTL_MS }
    }

    private companion object {
        /** The share usually follows the note by seconds-to-minutes; 50 covers a busy chat. */
        const val MAX_ENTRIES = 50

        /** A note shared many hours late is better unlabeled than mislabeled. */
        const val TTL_MS = 6 * 60 * 60 * 1000L
    }
}
