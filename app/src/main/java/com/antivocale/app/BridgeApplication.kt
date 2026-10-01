package com.antivocale.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.flow.first
import androidx.work.Configuration
import com.antivocale.app.audio.MemoryReadings
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.ShareShortcutManager
import com.antivocale.app.data.ShareTargetManager
import com.antivocale.app.di.ApplicationScope
import com.antivocale.app.util.CrashReporter
import com.antivocale.app.util.LocaleManager
import androidx.hilt.work.HiltWorkerFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class BridgeApplication : Application(), Configuration.Provider {

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var shareTargetManager: ShareTargetManager
    @Inject lateinit var shareShortcutManager: ShareShortcutManager
    @Inject lateinit var voiceNoteIdentityCache: com.antivocale.app.receiver.VoiceNoteIdentityCache
    @Inject lateinit var launcherIconManager: com.antivocale.app.ui.appearance.LauncherIconManager
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var externalModelStore: com.antivocale.app.data.ExternalModelStore
    @Inject lateinit var logDao: com.antivocale.app.data.local.LogDao

    /**
     * Shared process-lifetime scope for startup work that must not block the
     * first frame (TASK-438; see [ApplicationScope]). Launch sites pass an
     * explicit dispatcher because the scope itself carries none.
     */
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

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
        // TASK-684 review: the suspension sweep (below) posts a Retry
        // notification pointing at files/shared_audio; this 24h cleanup must
        // not delete the file BEFORE the offer (a freezer kill + reopen >24h
        // would deterministically offer a re-run of a just-deleted file). The
        // sweep itself guards on file existence, so this order is sufficient.
        runCatching {
            // TASK-684 review: ORDERING. The suspension sweep (further down)
        // posts a Retry notification pointing at files/shared_audio; this
        // 24h cleanup must not delete that file BEFORE the offer. The sweep
        // itself now guards on the file's existence (drops a dead Retry
        // action rather than offering a doomed one), so both orders stay
        // honest; keep the cleanup where it is (early) and let the sweep's
        // guard decide per row.
        runCatching {
            com.antivocale.app.util.SharedAudioHandler.cleanupOldFiles(this)
        }.onFailure { e ->
            android.util.Log.w("BridgeApplication", "shared_audio cleanup failed", e)
        }
        }.onFailure { Log.w("BridgeApplication", "shared_audio cleanup failed", it) }
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
        // TASK-736 hardening: the identity listener's COMPONENT follows the
        // preference (ships disabled; the toggle, TEST_SPI, or any other
        // writer flips it here, one owner). distinctUntilChanged: the flow
        // replays the cached value at startup and the write is a binder
        // call. Off also clears the RAM identity cache: the service's own
        // collector is cancelled by the disable itself, so this owner must
        // do it (the RAM-only contract: off leaves nothing readable).
        applicationScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            preferencesManager.voiceNoteIdentityEnabled.distinctUntilChanged().collect { enabled ->
                voiceNoteIdentityCache.accepting = enabled
                com.antivocale.app.receiver.VoiceNoteIdentityComponent.setEnabled(
                    this@BridgeApplication, enabled)
                if (!enabled) voiceNoteIdentityCache.clear()
            }
        }

        // TASK-643: builds <=1.13.x persisted the unsuffixed catalog URL on
        // "Restore"; that literal is now the FROZEN legacy index and would
        // read as a phantom override (custom-source badge, no asset fallback,
        // never sees new entries). Clear it once here; a genuine custom URL
        // never equals the legacy default.
        runCatching {
            kotlinx.coroutines.runBlocking {
                if (preferencesManager.externalCatalogUrl.first() ==
                    com.antivocale.app.data.ExternalCatalogRepository.LEGACY_DEFAULT_CATALOG_URL
                ) {
                    preferencesManager.clearExternalCatalogUrl()
                }
            }
        }.onFailure { e ->
            android.util.Log.e("BridgeApplication", "Legacy catalog-URL cleanup failed (phantom pin stays; retries next launch)", e)
        }

        // TASK-640: a leaked pendingBackendLoad marker means the previous
        // launch died inside a native model load. Quarantine the external
        // record (it becomes unresolvable for the cleaner below) and tell the
        // user, instead of reloading the same crashing model on every launch.
        runCatching {
            kotlinx.coroutines.runBlocking {
                com.antivocale.app.data.CrashQuarantineCheck(preferencesManager, externalModelStore)
                    .check(this@BridgeApplication)
            }
        }.onFailure { e ->
            android.util.Log.e("BridgeApplication", "Crash-quarantine check failed (marker cleared, no quarantine applied)", e)
        }
        // Also before syncAll: a persisted external backend id whose record is gone
        // (deleted through another path, files vanished, quarantined) must fall back
        // to the default backend, or every transcription request fails on an
        // unloadable id (TASK-342).
        runCatching {
            kotlinx.coroutines.runBlocking {
                com.antivocale.app.data.DanglingBackendCleaner(preferencesManager, externalModelStore).cleanIfNeeded()
            }
        }.onFailure { e ->
            android.util.Log.e("BridgeApplication", "Dangling-backend cleanup failed (will retry on next launch)", e)
        }
        // TASK-657 (GH #117): external dirs whose record was deleted in an earlier
        // run accumulate invisibly; reconcile the external root against the store's
        // records. Launched, not runBlocking: the delete can span GBs and must not
        // block cold start; contained so a sweep failure never crashes startup.
        // No import can be in flight yet (every import entry is UI or debug-SPI
        // driven, in-process), so no in-flight dir needs excluding.
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                com.antivocale.app.data.OrphanedExternalModelDirCleaner(externalModelStore) {
                    java.io.File(filesDir, com.antivocale.app.data.EXTERNAL_MODELS_DIR_NAME)
                }.cleanIfNeeded()
            }.onFailure { e ->
                android.util.Log.e("BridgeApplication", "External-model dir sweep failed (will retry on next launch)", e)
            }
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
                // TASK-684 (GH #109): the sweep grew a classifier. Rows a
                // process death orphaned still all close (the GH #51 guarantee:
                // nothing renders as in-flight forever), but a death the
                // heartbeat evidence proves was an OEM-freezer suspension now
                // gets the honest label, the re-run notification, and the
                // battery-exemption link, instead of the bare "Interrupted by
                // app restart" that blamed a restart that never happened.
                com.antivocale.app.service.SuspendedRunRecovery
                    .closeInterruptedRuns(
                        this@BridgeApplication, logDao, wasOOMCrash,
                        // TASK-684: the user preference gates the generic
                        // class's summary notification (suspended always
                        // notifies). The one-shot getter reads DataStore
                        // directly, so the sweep never depends on cache
                        // coherence. Contained on its own: a DataStore
                        // failure must not abort the row-close sweep this
                        // runCatching guards (the GH #51 guarantee).
                        notifyGenericInterrupted =
                            runCatching { preferencesManager.getInterruptedRunNotifications() }
                                .getOrDefault(PreferencesManager.DEFAULT_INTERRUPTED_RUN_NOTIFICATIONS),
                    )
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
        // Explicit Default: preserves the pre-TASK-438 private scope's built-in
        // dispatcher; the shared scope carries none.
        // TASK-472b: silent-death telemetry OUTSIDE the launched sync, so an
        // OEM/LMK kill ending the process mid-sync still left its record.
        com.antivocale.app.util.NativeCrashDetector.reportUnreportedDeaths(this)
        applicationScope.launch(Dispatchers.Default) {
            // Launcher-alias heal BEFORE the shortcut refresh: a user coming
            // from a retired icon variant (TASK-473) has every alias disabled,
            // and the shortcuts must anchor to the healed, enabled Default.
            launcherIconManager.healIfNoAliasEnabled()
            // Ordered chain, TASK-738: the heal, the alias sync and the shortcut
            // refresh must land in this sequence, so the suspend form stays.
            shareTargetManager.syncAllNow()
            // Dynamic long-press share shortcuts (TASK-393): same startup slot,
            // after the alias sync so the components the shortcut intents launch
            // are already in their persisted state.
            shareShortcutManager.refresh()
        }
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
