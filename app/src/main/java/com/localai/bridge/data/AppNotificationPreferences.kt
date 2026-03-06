package com.localai.bridge.data

import androidx.compose.runtime.Immutable

/**
 * Notification preferences for a specific app.
 *
 * Each app can have different notification behaviors:
 * - autoCopy: Automatically copy transcription to clipboard (useful for WhatsApp)
 * - showShareAction: Show share button in notification (useful for Telegram)
 * - notificationSound: Which notification sound to play
 *
 * @param autoCopy Whether to auto-copy transcription to clipboard
 * @param showShareAction Whether to show share button in notification
 * @param notificationSound Notification sound identifier
 */
@Immutable
data class AppNotificationPreferences(
    val autoCopy: Boolean,
    val showShareAction: Boolean,
    val notificationSound: String
) {
    companion object {
        /**
         * Default sound identifier
         */
        const val DEFAULT_SOUND = "default"

        /**
         * Default preferences for WhatsApp (auto-copy enabled by default)
         */
        fun whatsappDefaults() = AppNotificationPreferences(
            autoCopy = true,
            showShareAction = true,
            notificationSound = DEFAULT_SOUND
        )

        /**
         * Default preferences for Telegram (share action enabled)
         */
        fun telegramDefaults() = AppNotificationPreferences(
            autoCopy = false,
            showShareAction = true,
            notificationSound = DEFAULT_SOUND
        )

        /**
         * Default preferences for unknown/fallback apps
         */
        fun default() = AppNotificationPreferences(
            autoCopy = false,
            showShareAction = true,
            notificationSound = DEFAULT_SOUND
        )
    }
}
