package com.antivocale.app.di

import android.content.Context
import com.antivocale.app.data.HuggingFaceApiClient
import com.antivocale.app.data.HuggingFaceAuthManager
import com.antivocale.app.data.HuggingFaceTokenManager
import com.antivocale.app.data.HuggingFaceTokenManagerImpl
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.PreferencesManagerImpl
import com.antivocale.app.data.ShareTargetManager
import com.antivocale.app.data.TranscriptionCalibrator
import com.antivocale.app.data.ExternalModelImporter
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.catalog.BundledModelCatalog
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
        externalModelStore: ExternalModelStore
    ): ShareTargetManager {
        return ShareTargetManager(context, preferencesManager, backendRegistry, externalModelStore)
    }

    @Provides
    @Singleton
    fun provideExternalModelStore(preferencesManager: PreferencesManager): ExternalModelStore =
        ExternalModelStore(preferencesManager)

    @Provides
    @Singleton
    fun provideExternalModelImporter(
        store: ExternalModelStore,
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
    ): com.antivocale.app.data.ExternalModelImportOperations =
        ExternalModelImporter(
            store = store,
            filesRoot = { java.io.File(context.filesDir, "models/external") },
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
