package com.antivocale.app

import android.app.Application
import com.antivocale.app.di.AppContainer
import com.antivocale.app.util.LocaleManager
import kotlinx.coroutines.runBlocking

class BridgeApplication : Application() {

    companion object {
        private const val PREFS_NAME = "localai_migration_prefs"
        private const val KEY_LANGUAGE_MIGRATED = "language_preference_migrated_v2"
    }

    override fun onCreate() {
        super.onCreate()
        AppContainer.initialize(this)
        // Clean up old shared audio files
        com.antivocale.app.util.SharedAudioHandler.cleanupOldFiles(this)
        // Migrate language preference from DataStore to Per-App Language API
        migrateLanguagePreference()
    }

    /**
     * Migrates existing language preference from DataStore to the new Per-App Language API.
     * This only runs once for existing users; new users won't have anything to migrate.
     */
    private fun migrateLanguagePreference() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LANGUAGE_MIGRATED, false)) {
            return // Already migrated
        }

        // Read the old preference from DataStore and apply it
        runBlocking {
            val savedLanguage = AppContainer.preferencesManager.getLegacyLanguagePreference()
            // Only apply if not system default (system is the default for new API too)
            if (savedLanguage != "system") {
                LocaleManager.setLocale(savedLanguage)
            }
        }

        // Mark as migrated
        prefs.edit().putBoolean(KEY_LANGUAGE_MIGRATED, true).apply()
    }
}
