package com.localai.bridge.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Manages per-app notification preferences using Jetpack DataStore.
 *
 * Allows users to customize notification behavior based on which app
 * shared audio (WhatsApp, Telegram, Signal, etc.).
 *
 * Preferences are stored per package name:
 * - autoCopy: Auto-copy transcription to clipboard
 * - showShareAction: Show share button in notification
 * - notificationSound: Notification sound identifier
 * - quickShareBack: Use one-tap "Send to [App]" vs share sheet
 */
class PerAppPreferencesManager(private val context: Context) {

    companion object {
        /**
         * DataStore file name for per-app notification preferences
         */
        private const val DATASTORE_NAME = "per_app_notification_preferences"

        /**
         * Preference key suffixes
         */
        private const val AUTO_COPY_SUFFIX = "_auto_copy"
        private const val SHOW_SHARE_ACTION_SUFFIX = "_show_share_action"
        private const val NOTIFICATION_SOUND_SUFFIX = "_notification_sound"
        private const val QUICK_SHARE_BACK_SUFFIX = "_quick_share_back"

        /**
         * Package names for common apps
         */
        const val WHATSAPP = "com.whatsapp"
        const val TELEGRAM = "org.telegram.messenger"
        const val SIGNAL = "org.thoughtcrime.securesms"
    }

    /**
     * DataStore instance for per-app preferences
     */
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = DATASTORE_NAME)

    /**
     * Get preference key for auto-copy setting for a specific package
     */
    private fun autoCopyKeyForPackage(packageName: String) =
        booleanPreferencesKey("${packageName}${AUTO_COPY_SUFFIX}")

    /**
     * Get preference key for share action visibility for a specific package
     */
    private fun showShareActionKeyForPackage(packageName: String) =
        booleanPreferencesKey("${packageName}${SHOW_SHARE_ACTION_SUFFIX}")

    /**
     * Get preference key for notification sound for a specific package
     */
    private fun notificationSoundKeyForPackage(packageName: String) =
        stringPreferencesKey("${packageName}${NOTIFICATION_SOUND_SUFFIX}")

    /**
     * Get preference key for Quick Share Back for a specific package
     */
    private fun quickShareBackKeyForPackage(packageName: String) =
        booleanPreferencesKey("${packageName}${QUICK_SHARE_BACK_SUFFIX}")

    /**
     * Observe notification preferences for a specific package.
     *
     * @param packageName Package name (e.g., "com.whatsapp")
     * @return Flow emitting AppNotificationPreferences
     */
    fun getPreferencesForPackage(packageName: String): Flow<AppNotificationPreferences> {
        return context.dataStore.data.map { preferences ->
            val defaultPrefs = getDefaultPreferences(packageName)
            AppNotificationPreferences(
                autoCopy = preferences[autoCopyKeyForPackage(packageName)] ?: defaultPrefs.autoCopy,
                showShareAction = preferences[showShareActionKeyForPackage(packageName)] ?: defaultPrefs.showShareAction,
                notificationSound = preferences[notificationSoundKeyForPackage(packageName)] ?: defaultPrefs.notificationSound,
                quickShareBack = preferences[quickShareBackKeyForPackage(packageName)] ?: defaultPrefs.quickShareBack
            )
        }
    }

    /**
     * Get current preferences for a specific package (blocking).
     *
     * @param packageName Package name
     * @return Current preferences for the package
     */
    suspend fun getCurrentPreferences(packageName: String): AppNotificationPreferences {
        return getPreferencesForPackage(packageName).first()
    }

    /**
     * Update notification preferences for a specific package.
     *
     * @param packageName Package name
     * @param update Function transforming current preferences to new preferences
     */
    suspend fun updatePreferencesForPackage(
        packageName: String,
        update: AppNotificationPreferences.() -> AppNotificationPreferences
    ) {
        context.dataStore.edit { preferences ->
            val current = getCurrentPreferences(packageName)
            val updated = current.update()

            preferences[autoCopyKeyForPackage(packageName)] = updated.autoCopy
            preferences[showShareActionKeyForPackage(packageName)] = updated.showShareAction
            preferences[notificationSoundKeyForPackage(packageName)] = updated.notificationSound
            preferences[quickShareBackKeyForPackage(packageName)] = updated.quickShareBack
        }
    }

    /**
     * Get default preferences for a specific package.
     *
     * Returns sensible defaults based on common app usage patterns:
     * - WhatsApp: Auto-copy ON (users usually paste back into chat)
     * - Telegram: Share action ON (good forwarding capabilities)
     * - Others: Conservative defaults
     *
     * @param packageName Package name
     * @return Default preferences for this package
     */
    private fun getDefaultPreferences(packageName: String): AppNotificationPreferences {
        return when (packageName) {
            WHATSAPP -> AppNotificationPreferences.whatsappDefaults()
            TELEGRAM -> AppNotificationPreferences.telegramDefaults()
            SIGNAL -> AppNotificationPreferences.telegramDefaults() // Similar to Telegram
            else -> AppNotificationPreferences.default()
        }
    }

    /**
     * Clear all preferences for a specific package.
     *
     * This will reset to defaults on next read.
     *
     * @param packageName Package name
     */
    suspend fun clearPreferencesForPackage(packageName: String) {
        context.dataStore.edit { preferences ->
            preferences.remove(autoCopyKeyForPackage(packageName))
            preferences.remove(showShareActionKeyForPackage(packageName))
            preferences.remove(notificationSoundKeyForPackage(packageName))
            preferences.remove(quickShareBackKeyForPackage(packageName))
        }
    }

    /**
     * Clear all per-app preferences.
     *
     * This will reset everything to defaults.
     */
    suspend fun clearAllPreferences() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
