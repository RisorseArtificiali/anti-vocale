package com.antivocale.app.receiver

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.data.prefsOrDefault
import com.antivocale.app.service.ResultNotificationFactory
import com.antivocale.app.service.ResultNotificationSpecNav
import com.antivocale.app.service.TranscriptPager

/**
 * Recomputes the neighbor page for a prev/next tap and re-posts the result
 * notification under its original id (TASK-327). All inputs come from the
 * intent extras the factory embedded; nothing is read from process state, so
 * paging works after InferenceService died and after process death (not
 * force-stop: the platform cancels notifications and blocks receivers for
 * stopped packages).
 */
object ResultNotificationRefresher {

    private const val TAG = "ResultNotificationRefresher"

    suspend fun refresh(appContext: Context, intent: Intent) {
        // TASK-764: the ONE read side of the round trip.
        val baked = ResultNotificationSpecNav.readFrom(intent)
        if (baked == null || baked.transcriptionText.length > TranscriptPager.MAX_PAGED_LENGTH) {
            Log.w(TAG, "Page action with unusable extras (id=${baked?.notificationId}, " +
                "${baked?.transcriptionText?.length ?: 0} chars); ignoring")
            return
        }
        // One split pass; the guard above already ruled out the unpageable case.
        val pageCount = TranscriptPager.pagesFor(baked.transcriptionText).size
        if (pageCount < 2) {
            Log.w(TAG, "Page action for an unpaged text (${baked.transcriptionText.length} chars); ignoring")
            return
        }
        val current = baked.pageIndex.coerceIn(0, pageCount - 1)
        val target = if (intent.action == NotificationActionReceiver.ACTION_PAGE_PREV) {
            (current - 1).coerceAtLeast(0)
        } else {
            (current + 1).coerceAtMost(pageCount - 1)
        }
        // TASK-757: the ONE per-app-prefs-with-defaults read (the data-layer
        // extension postResult also uses).
        val prefs = PerAppPreferencesManager(appContext).prefsOrDefault(baked.sourcePackage)
        // TASK-650 F7: while this process has resolved the signature at
        // least once, the LIVE preference wins over the values baked at
        // post time (an edit or disabling mid-notification must apply);
        // in a fresh process the baked extras are the best knowledge.
        val live = com.antivocale.app.util.TranscriptSignature.lastResolvedIsLive
        val sigText = if (live) com.antivocale.app.util.TranscriptSignature.lastResolved.text else baked.signatureText
        val sigPos = if (live) com.antivocale.app.util.TranscriptSignature.lastResolved.position else baked.signaturePosition
        // TASK-722/758: every status fact survives the page repost through
        // the round trip; a notification posted before a field's extra
        // existed reposts with that field at its default (once, at update).
        val spec = baked.copy(
            signatureText = sigText,
            signaturePosition = sigPos,
            pageIndex = target,
            repost = true
        )
        val notification = ResultNotificationFactory(appContext).build(spec, prefs)
        // The repost happens outside any Activity, so check POST_NOTIFICATIONS
        // explicitly: the receiver only fires from a notification action tap
        // (implying notifications were deliverable), but lint is right that the
        // code path itself must not assume the grant.
        ResultNotificationFactory(appContext).postIfPermitted(baked.notificationId, notification)
        Log.i(TAG, "Paged result notification to page ${target + 1}/$pageCount (id=${baked.notificationId})")
    }
}
