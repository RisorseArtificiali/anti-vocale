package com.antivocale.app

import android.app.Application
import com.antivocale.app.di.AppContainer
import com.antivocale.app.util.CrashReporter
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
        com.antivocale.app.util.SharedAudioHandler.cleanupOldFiles(this)
        migrateLanguagePreference()
        installGlobalExceptionHandler()
    }

    /**
     * Wraps the default uncaught exception handler so that every crash
     * is reported to Crashlytics before the process terminates.
     */
    private fun installGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashReporter.report(throwable, "Uncaught exception on ${thread.name}")
            defaultHandler?.uncaughtException(thread, throwable)
        }
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

        runBlocking {
            val savedLanguage = AppContainer.preferencesManager.getLegacyLanguagePreference()
            if (savedLanguage != "system") {
                LocaleManager.setLocale(savedLanguage)
            }
        }

        prefs.edit().putBoolean(KEY_LANGUAGE_MIGRATED, true).apply()
    }
}
