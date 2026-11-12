package com.antivocale.app.di

import com.antivocale.app.data.DefaultExternalModelRecordsProvider
import com.antivocale.app.data.ExternalModelRecordsProvider
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.manager.LlmManager
import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.LlmTranscriptionBackend
import com.antivocale.app.transcription.RemoteOmnivoiceBackend
import com.antivocale.app.transcription.SherpaBackend
import com.antivocale.app.transcription.TranscriptionBackend
import com.antivocale.app.transcription.WhistleBackend
import kotlinx.coroutines.CoroutineScope

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class TranscriptionModule {

    companion object {
        @Provides
        @IntoSet
        @Singleton
        fun provideLlmBackend(llmManager: LlmManager): TranscriptionBackend =
            LlmTranscriptionBackend(llmManager)

        // TASK-681: the opt-in LAN-offload backend (the user's own OmniVoice
        // server); derives its own timeouts from the shared OkHttp client.
        @Provides
        @IntoSet
        @Singleton
        fun provideRemoteOmnivoiceBackend(
            client: okhttp3.OkHttpClient
        ): TranscriptionBackend = RemoteOmnivoiceBackend(client)

        // One SherpaBackend instance per bundled catalog entry: the model family and
        // tuning all come from the catalog, so the instances differ only in entry id.
        // TASK-853: the Cactus Whistle engine (17MB, seven languages,
        // the Needle runtime through WhistleNative).
        @Provides
        @IntoSet
        @Singleton
        fun provideWhistleBackend(
            @ApplicationScope scope: CoroutineScope,
            preferences: PreferencesManager,
        ): TranscriptionBackend = WhistleBackend(scope, preferences)

        @Provides
        @IntoSet
        @Singleton
        fun provideParakeetBackend(preferences: PreferencesManager): TranscriptionBackend = SherpaBackend(BuiltInBackendIds.PARAKEET, preferences)

        @Provides
        @IntoSet
        @Singleton
        fun provideWhisperBackend(preferences: PreferencesManager): TranscriptionBackend = SherpaBackend(BuiltInBackendIds.WHISPER, preferences)

        @Provides
        @IntoSet
        @Singleton
        fun provideQwen3AsrBackend(preferences: PreferencesManager): TranscriptionBackend = SherpaBackend(BuiltInBackendIds.QWEN3_ASR, preferences)

        @Provides
        @IntoSet
        @Singleton
        fun provideNemotronBackend(preferences: PreferencesManager): TranscriptionBackend = SherpaBackend(BuiltInBackendIds.NEMOTRON, preferences)

        @Provides
        @IntoSet
        @Singleton
        fun provideGigaAmBackend(preferences: PreferencesManager): TranscriptionBackend = SherpaBackend(BuiltInBackendIds.GIGAAM, preferences)

        @Provides
        @Singleton
        fun provideExternalModelRecordsProvider(
            impl: DefaultExternalModelRecordsProvider
        ): ExternalModelRecordsProvider = impl
    }
}
