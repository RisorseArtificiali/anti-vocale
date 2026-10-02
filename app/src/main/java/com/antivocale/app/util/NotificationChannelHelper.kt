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
/**
 * Range review (REUSE): the can-this-notification-actually-post gate that
 * InferenceEnqueue, MemoryKillStartupCheck and ModelShortcutActivity each
 * hand-rolled - and drifted (the trampoline copy missed the
 * channel-blocked half, silently dropping the switch-FAILURE notice for
 * users who silenced the result channel). One gate, both predicates:
 * app-level denial and per-channel block.
 */
fun canPostNotification(context: android.content.Context, channel: AppNotificationChannel): Boolean {
    val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return false
    if (!nm.areNotificationsEnabled()) return false
    return nm.getNotificationChannel(channel.id)?.importance !=
        android.app.NotificationManager.IMPORTANCE_NONE
}

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
    /**
     * TASK-684: the generic interrupted-runs summary. IMPORTANCE_DEFAULT,
     * a step down from [TRANSCRIPTION_RESULT] on purpose: the proven
     * freezer suspension pops heads-up on the result channel, while this
     * honest-but-unproven close lands as a normal status-bar entry. Its
     * own channel also gives the user a system-level toggle (the in-app
     * preference gates it too, default on).
     */
    INTERRUPTED_RUNS(
        id = "interrupted_runs_channel",
        nameResId = R.string.notification_channel_interrupted_runs,
        descriptionResId = R.string.notification_channel_interrupted_runs_description,
        importance = NotificationManager.IMPORTANCE_DEFAULT,
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
