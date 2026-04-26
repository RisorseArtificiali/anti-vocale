package com.antivocale.app.di

import android.content.Context
import com.antivocale.app.data.HuggingFaceApiClient
import com.antivocale.app.data.HuggingFaceAuthManager
import com.antivocale.app.data.HuggingFaceTokenManager
import com.antivocale.app.data.HuggingFaceTokenManagerImpl
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.PreferencesManagerImpl
import com.antivocale.app.data.TranscriptionCalibrator
import java.util.concurrent.TimeUnit
import com.antivocale.app.data.local.AppDatabase
import com.antivocale.app.data.local.LogDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

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
        apiClient: HuggingFaceApiClient
    ): HuggingFaceAuthManager {
        return HuggingFaceAuthManager(context, tokenManager, apiClient)
    }
}
