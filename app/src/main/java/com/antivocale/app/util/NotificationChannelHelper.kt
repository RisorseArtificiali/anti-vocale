package com.antivocale.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.antivocale.app.R

/**
 * Centralized definitions for the app's notification channels.
 *
 * Each enum entry encapsulates the channel ID, display name, description,
 * importance level, and badge preference. Using an enum ensures compile-time
 * safety for channel references and makes it easy to audit all channels
 * in one place.
 *
 * Android's [NotificationManager.createNotificationChannel] is idempotent —
 * calling [create] multiple times with the same values is a no-op.
 */
enum class AppNotificationChannel(
    val id: String,
    val nameResId: Int,
    val descriptionResId: Int,
    val importance: Int,
    val showBadge: Boolean
) {
    INFERENCE(
        id = "inference_channel",
        nameResId = R.string.notification_channel_inference,
        descriptionResId = R.string.notification_channel_inference_description,
        importance = NotificationManager.IMPORTANCE_LOW,
        showBadge = false
    ),
    TRANSCRIPTION_RESULT(
        id = "transcription_result_channel",
        nameResId = R.string.notification_channel_result,
        descriptionResId = R.string.notification_channel_result_description,
        importance = NotificationManager.IMPORTANCE_HIGH,
        showBadge = true
    ),
    EXTRACTION(
        id = "extraction_channel",
        nameResId = R.string.notification_channel_extraction,
        descriptionResId = R.string.notification_channel_extraction_description,
        importance = NotificationManager.IMPORTANCE_LOW,
        showBadge = false
    ),
    TASKER_FALLBACK(
        id = "tasker_fallback_channel",
        nameResId = R.string.notification_channel_tasker_fallback,
        descriptionResId = R.string.notification_channel_tasker_fallback_description,
        importance = NotificationManager.IMPORTANCE_HIGH,
        showBadge = true
    );

    /**
     * Creates (registers) this notification channel with the system.
     * Safe to call multiple times — Android ignores duplicate registrations
     * with identical configuration.
     */
    fun create(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(id, context.getString(nameResId), importance).apply {
            description = context.getString(descriptionResId)
            setShowBadge(showBadge)
        }
        notificationManager.createNotificationChannel(channel)
    }
}
