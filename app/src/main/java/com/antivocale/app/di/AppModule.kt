package com.antivocale.app.di

import android.content.Context
import com.antivocale.app.data.HuggingFaceApiClient
import com.antivocale.app.data.HuggingFaceAuthManager
import com.antivocale.app.data.HuggingFaceTokenManager
import com.antivocale.app.data.HuggingFaceTokenManagerImpl
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.PreferencesManagerImpl
import com.antivocale.app.data.RecentModelUse
import com.antivocale.app.data.ShareShortcutManager
import com.antivocale.app.data.ShortcutIconStore
import com.antivocale.app.data.ShareTargetManager
import com.antivocale.app.data.TranscriptionCalibrator
import com.antivocale.app.data.EXTERNAL_MODELS_DIR_NAME
import com.antivocale.app.data.ExternalModelImporter
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.catalog.BundledModelCatalog
import com.antivocale.app.ui.appearance.LauncherIconManager
import java.util.concurrent.TimeUnit
import com.antivocale.app.data.local.AppDatabase
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.util.CrashReporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * The single process-lifetime scope shared by BridgeApplication,
     * DefaultExternalModelRecordsProvider, HuggingFaceAuthManager and LlmManager
     * (TASK-438 consolidated their four private scopes). No dispatcher: launch
     * sites pass their own, preserving each owner's pre-consolidation execution
     * semantics. Never cancelled (see [ApplicationScope]).
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + CrashReporter.handler)

    @Provides
    @Singleton
    fun provideBundledModelCatalog(@ApplicationContext context: Context): BundledModelCatalog {
        return BundledModelCatalog(context)
    }

    @Provides
    @Singleton
    fun providePreferencesManager(@ApplicationContext context: Context): PreferencesManager {
        return PreferencesManagerImpl(context).apply { initialize() }
    }

    @Provides
    @Singleton
    fun providePerAppPreferencesManager(@ApplicationContext context: Context): PerAppPreferencesManager {
        return PerAppPreferencesManager(context)
    }

    @Provides
    @Singleton
    fun provideTranscriptionCalibrator(@ApplicationContext context: Context): TranscriptionCalibrator {
        return TranscriptionCalibrator(context)
    }

    @Provides
    @Singleton
    fun provideShareTargetManager(
        @ApplicationContext context: Context,
        preferencesManager: PreferencesManager,
        backendRegistry: BackendRegistry,
        externalModelStore: ExternalModelStore,
        @ApplicationScope applicationScope: CoroutineScope
    ): ShareTargetManager {
        return ShareTargetManager(
            context,
            preferencesManager,
            backendRegistry,
            externalModelStore,
            applicationScope,
        )
    }

    @Provides
    @Singleton
    fun provideShortcutIconStore(
        @ApplicationContext context: Context,
    ): ShortcutIconStore = ShortcutIconStore(context)

    /**
     * The dynamic share-shortcut manager's recency source (TASK-393): the
     * calibrator's per-model last-use timestamps, which are keyed by backend id.
     * The Room logs table was rejected as the source: it records the GH #45
     * display name, a localized string that cannot be mapped back to a backend
     * after a locale change, while calibration keys parse losslessly.
     */
    @Provides
    @Singleton
    fun provideShareShortcutManager(
        @ApplicationContext context: Context,
        preferencesManager: PreferencesManager,
        backendRegistry: BackendRegistry,
        launcherIconManager: LauncherIconManager,
        shortcutIconStore: ShortcutIconStore,
        transcriptionCalibrator: TranscriptionCalibrator
    ): ShareShortcutManager {
        return ShareShortcutManager(
            context = context,
            preferencesManager = preferencesManager,
            backendRegistry = backendRegistry,
            launcherIconManager = launcherIconManager,
            shortcutIconStore = shortcutIconStore,
            recentUsage = {
                transcriptionCalibrator.getAllProfiles().map { profile ->
                    RecentModelUse(
                        backendId = TranscriptionCalibrator.backendIdOf(profile.modelId),
                        lastUsedAtMillis = profile.lastTimestamp,
                    )
                }
            },
        )
    }

    @Provides
    @Singleton
    fun provideExternalModelStore(preferencesManager: PreferencesManager): ExternalModelStore =
        ExternalModelStore(preferencesManager)

    // TASK-670 (GH #83): the voiceprint store, app-private files only.
    @Provides
    @Singleton
    fun provideSpeakerIdentityStore(
        @ApplicationContext context: Context
    ): com.antivocale.app.transcription.diarization.SpeakerIdentityStore =
        com.antivocale.app.transcription.diarization.SpeakerIdentityStore(
            com.antivocale.app.transcription.diarization.SpeakerIdentityStore.dir(context))

    // TASK-670 simplify F2: the enrollment pipeline behind the Settings
    // file pick; the environment seams (SAF copy, metadata probe, model
    // download, titanet session) close over the app context inside create.
    @Provides
    @Singleton
    fun provideSpeakerEnroller(
        @ApplicationContext context: Context,
        preprocessor: com.antivocale.app.audio.AudioPreprocessor,
        preferencesManager: PreferencesManager,
        store: com.antivocale.app.transcription.diarization.SpeakerIdentityStore,
    ): com.antivocale.app.transcription.diarization.SpeakerEnroller =
        com.antivocale.app.transcription.diarization.SpeakerEnroller.create(
            context = context,
            preprocessor = preprocessor,
            threadCount = { preferencesManager.threadCount.first() },
            store = store,
        )

    @Provides
    @Singleton
    fun provideExternalModelImporter(
        store: ExternalModelStore,
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
    ): com.antivocale.app.data.ExternalModelImportOperations =
        ExternalModelImporter(
            store = store,
            filesRoot = { java.io.File(context.filesDir, EXTERNAL_MODELS_DIR_NAME) },
            repoListing = com.antivocale.app.data.HuggingFaceRepoListing(okHttpClient),
        )

    @Provides
    @Singleton
    fun provideLitertLmUrlImporter(
        okHttpClient: OkHttpClient,
    ): com.antivocale.app.data.LitertLmUrlImporter =
        com.antivocale.app.data.LitertLmUrlImporter(
            com.antivocale.app.data.HuggingFaceRepoListing(okHttpClient))

    @Provides
    @Singleton
    fun provideExternalCatalogRepository(
        preferencesManager: PreferencesManager,
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
    ): com.antivocale.app.data.ExternalCatalogRepository =
        com.antivocale.app.data.ExternalCatalogRepository(
            context = context,
            catalogUrl = { preferencesManager.externalCatalogUrl.first() },
            fetchText = { url ->
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.antivocale.app.data.HuggingFaceRepoListing(okHttpClient).fetchText(url)
                }
            },
        )

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    fun provideLogDao(database: AppDatabase): LogDao {
        return database.logDao()
    }

    @Provides
    @Singleton
    fun provideHuggingFaceTokenManager(@ApplicationContext context: Context): HuggingFaceTokenManager {
        return HuggingFaceTokenManagerImpl(context).apply { initialize() }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideHuggingFaceAuthManager(
        @ApplicationContext context: Context,
        tokenManager: HuggingFaceTokenManager,
        apiClient: HuggingFaceApiClient,
        @ApplicationScope scope: CoroutineScope
    ): HuggingFaceAuthManager {
        return HuggingFaceAuthManager(context, tokenManager, apiClient, scope)
    }
}
