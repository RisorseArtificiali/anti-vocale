package com.antivocale.app.receiver

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.antivocale.app.data.AppNotificationPreferences
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.service.ResultNotificationFactory
import com.antivocale.app.service.ResultNotificationSpec
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
        val text = intent.getStringExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT)
        val notificationId = intent.getIntExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, -1)
        if (text.isNullOrBlank() || notificationId == -1 ||
            text.length > TranscriptPager.MAX_PAGED_LENGTH
        ) {
            Log.w(TAG, "Page action with unusable extras (id=$notificationId, ${text?.length ?: 0} chars); ignoring")
            return
        }
        // One split pass; the guards above already ruled out the unpageable cases.
        val pageCount = TranscriptPager.pagesFor(text).size
        if (pageCount < 2) {
            Log.w(TAG, "Page action for an unpaged text (${text.length} chars); ignoring")
            return
        }
        val current = intent.getIntExtra(NotificationActionReceiver.EXTRA_PAGE_INDEX, 0)
            .coerceIn(0, pageCount - 1)
        val target = if (intent.action == NotificationActionReceiver.ACTION_PAGE_PREV) {
            (current - 1).coerceAtLeast(0)
        } else {
            (current + 1).coerceAtMost(pageCount - 1)
        }
        val sourcePackage = intent.getStringExtra(NotificationActionReceiver.EXTRA_SOURCE_PACKAGE)
        val prefs = try {
            if (sourcePackage != null) {
                PerAppPreferencesManager(appContext).getCurrentPreferences(sourcePackage)
            } else {
                AppNotificationPreferences.default()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get per-app preferences for $sourcePackage, using defaults", e)
            AppNotificationPreferences.default()
        }
        val spec = ResultNotificationSpec(
            transcriptionText = text,
            signatureText = intent.getStringExtra(NotificationActionReceiver.EXTRA_SIGNATURE_TEXT) ?: "",
            signaturePosition = intent.getStringExtra(NotificationActionReceiver.EXTRA_SIGNATURE_POSITION) ?: "append",
            taskId = intent.getStringExtra(NotificationActionReceiver.EXTRA_TASK_ID),
            sourcePackage = sourcePackage,
            confidence = if (intent.hasExtra(NotificationActionReceiver.EXTRA_CONFIDENCE)) {
                intent.getFloatExtra(NotificationActionReceiver.EXTRA_CONFIDENCE, 0f)
            } else null,
            detectedLanguage = intent.getStringExtra(NotificationActionReceiver.EXTRA_DETECTED_LANGUAGE),
            isPartial = intent.getBooleanExtra(NotificationActionReceiver.EXTRA_IS_PARTIAL, false),
            failedChunkCount = intent.getIntExtra(NotificationActionReceiver.EXTRA_FAILED_CHUNK_COUNT, 0),
            pageIndex = target,
            notificationId = notificationId,
            firstPostedAt = intent.getLongExtra(
                NotificationActionReceiver.EXTRA_FIRST_POSTED_AT,
                System.currentTimeMillis()
            ),
            repost = true
        )
        val notification = ResultNotificationFactory(appContext).build(spec, prefs)
        // The repost happens outside any Activity, so check POST_NOTIFICATIONS
        // explicitly: the receiver only fires from a notification action tap
        // (implying notifications were deliverable), but lint is right that the
        // code path itself must not assume the grant.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(appContext).notify(notificationId, notification)
        }
        Log.i(TAG, "Paged result notification to page ${target + 1}/$pageCount (id=$notificationId)")
    }
}
