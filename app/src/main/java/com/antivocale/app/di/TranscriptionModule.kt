package com.antivocale.app.di

import com.antivocale.app.data.DefaultExternalModelRecordsProvider
import com.antivocale.app.data.ExternalModelRecordsProvider
import com.antivocale.app.manager.LlmManager
import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.LlmTranscriptionBackend
import com.antivocale.app.transcription.SherpaBackend
import com.antivocale.app.transcription.TranscriptionBackend
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

        // One SherpaBackend instance per bundled catalog entry: the model family and
        // tuning all come from the catalog, so the instances differ only in entry id.
        @Provides
        @IntoSet
        @Singleton
        fun provideParakeetBackend(): TranscriptionBackend = SherpaBackend(BuiltInBackendIds.PARAKEET)

        @Provides
        @IntoSet
        @Singleton
        fun provideWhisperBackend(): TranscriptionBackend = SherpaBackend(BuiltInBackendIds.WHISPER)

        @Provides
        @IntoSet
        @Singleton
        fun provideQwen3AsrBackend(): TranscriptionBackend = SherpaBackend(BuiltInBackendIds.QWEN3_ASR)

        @Provides
        @IntoSet
        @Singleton
        fun provideNemotronBackend(): TranscriptionBackend = SherpaBackend(BuiltInBackendIds.NEMOTRON)

        @Provides
        @IntoSet
        @Singleton
        fun provideGigaAmBackend(): TranscriptionBackend = SherpaBackend(BuiltInBackendIds.GIGAAM)

        @Provides
        @Singleton
        fun provideExternalModelRecordsProvider(
            impl: DefaultExternalModelRecordsProvider
        ): ExternalModelRecordsProvider = impl
    }
}
