package com.antivocale.app

import android.app.Application
import androidx.work.Configuration
import com.antivocale.app.audio.MemoryReadings
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.ShareTargetManager
import com.antivocale.app.util.CrashReporter
import com.antivocale.app.util.LocaleManager
import androidx.hilt.work.HiltWorkerFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class BridgeApplication : Application(), Configuration.Provider {

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var shareTargetManager: ShareTargetManager
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var externalModelStore: com.antivocale.app.data.ExternalModelStore
    @Inject lateinit var logDao: com.antivocale.app.data.local.LogDao

    /**
     * Application-owned scope for startup work that must not block the first frame
     * (same idiom as HuggingFaceAuthManager/LlmManager: SupervisorJob + dispatcher +
     * CrashReporter.handler, never cancelled because the Application lives for the
     * whole process).
     */
    private val applicationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashReporter.handler)

    /**
     * Provides the Hilt-aware [androidx.work.WorkManager] configuration so that
     * `@HiltWorker`-annotated Workers (e.g. SubtitleChoiceTimeoutWorker) get their
     * dependencies injected. The manifest disables WorkManager's default
     * initializer so this factory wins.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    companion object {
        private const val PREFS_NAME = "localai_migration_prefs"
        private const val KEY_LANGUAGE_MIGRATED = "language_preference_migrated_v2"
    }

    override fun onCreate() {
        super.onCreate()
        com.antivocale.app.data.catalog.BundledCatalog.attach(this)
        com.antivocale.app.util.SharedAudioHandler.cleanupOldFiles(this)
        // BEFORE syncAll: a persisted "custom-transductor" id must already resolve to an
        // external record, or the share sync (and any early transcription) would see a
        // registry without it and silently fall through to the LLM loader.
        // Contained: any IO failure must not crash Application.onCreate (which runs
        // before the global exception handler is installed).
        runCatching {
            kotlinx.coroutines.runBlocking {
                com.antivocale.app.data.CustomTransducerMigrator(preferencesManager, externalModelStore).migrate()
            }
        }.onFailure { e ->
            android.util.Log.e("BridgeApplication", "External-model migration failed (will retry on next launch)", e)
            // Clear the done-marker so the migration retries on the next launch.
            kotlinx.coroutines.runBlocking {
                preferencesManager.saveExternalMigrationDone(false)
            }
        }
        // Also before syncAll: a persisted external backend id whose record is gone
        // (deleted through another path, files vanished) must fall back to the default
        // backend, or every transcription request fails on an unloadable id (TASK-342).
        runCatching {
            kotlinx.coroutines.runBlocking {
                com.antivocale.app.data.DanglingBackendCleaner(preferencesManager, externalModelStore).cleanIfNeeded()
            }
        }.onFailure { e ->
            android.util.Log.e("BridgeApplication", "Dangling-backend cleanup failed (will retry on next launch)", e)
        }
        // GH #51: rows left QUEUED/PROCESSING by a process death can never complete
        // (START_NOT_STICKY restores nothing); fail them so they don't render as a
        // permanently in-flight queue. Runs at process start, before the service can
        // exist in this process, so no live row can be caught.
        // TASK-396: set BEFORE the sweep (it calls consumeLastCrashWasOOM)
        CrashReporter.filesDir = filesDir
        // TASK-430: annotate every crash report with the device's memory
        // profile, so OOM/kill reports arrive with their heap-class context.
        // Contained: a telemetry failure must not break startup.
        runCatching {
            CrashReporter.setMemoryInfo(
                MemoryReadings.memoryClassMb(this),
                MemoryReadings.totalRamBytes(this),
                MemoryReadings.isLowRamDevice(this))
        }.onFailure { e ->
            android.util.Log.e("BridgeApplication", "Memory-info telemetry failed", e)
        }
        runCatching {
            val wasOOMCrash = CrashReporter.consumeLastCrashWasOOM()
            kotlinx.coroutines.runBlocking {
                // TASK-396 pt.2: when the previous process died on an OOM, the
                // sweep reason carries the mitigation advice instead of the bare
                // technical "Interrupted by app restart" (the Crashlytics pattern:
                // 4 reports, users had no hint the cause was memory).
                val reason = if (wasOOMCrash) {
                    "Interrupted by app restart: out of memory. Try a shorter file, a smaller model, or close other apps."
                } else {
                    "Interrupted by app restart"
                }
                logDao.failAllNonTerminal(reason)
            }
        }.onFailure { e ->
            android.util.Log.e("BridgeApplication", "Non-terminal log sweep failed", e)
        }
        // TASK-264: the share-target sync's DataStore reads + PackageManager IPCs must
        // not block the main thread at cold start, so it launches on the application
        // scope. Ordering: launched after the migrator and dangling-backend cleaner
        // above complete (they are synchronous), so the store/registry it reads is
        // settled. Race: component enable/disable is idempotent; a share intent
        // racing this async sync resolves its alias against the PREVIOUS sync's
        // component state, which was correct when the app last ran (component state
        // persists in PackageManager), and share-target state only changes when a
        // model is downloaded or deleted.
        applicationScope.launch { shareTargetManager.syncAll() }
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
            val savedLanguage = preferencesManager.getLegacyLanguagePreference()
            if (savedLanguage != "system") {
                LocaleManager.setLocale(savedLanguage)
            }
        }

        prefs.edit().putBoolean(KEY_LANGUAGE_MIGRATED, true).apply()
    }
}
