package com.antivocale.app.di

import com.antivocale.app.manager.LlmManager
import com.antivocale.app.transcription.LlmTranscriptionBackend
import com.antivocale.app.transcription.OmnilingualAsrBackend
import com.antivocale.app.transcription.Qwen3AsrBackend
import com.antivocale.app.transcription.SherpaOnnxBackend
import com.antivocale.app.transcription.TranscriptionBackend
import com.antivocale.app.transcription.WhisperBackend
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

        @Provides
        @IntoSet
        @Singleton
        fun provideSherpaOnnxBackend(): TranscriptionBackend = SherpaOnnxBackend()

        @Provides
        @IntoSet
        @Singleton
        fun provideWhisperBackend(): TranscriptionBackend = WhisperBackend()

        @Provides
        @IntoSet
        @Singleton
        fun provideQwen3AsrBackend(): TranscriptionBackend = Qwen3AsrBackend()

        @Provides
        @IntoSet
        @Singleton
        fun provideOmnilingualAsrBackend(): TranscriptionBackend = OmnilingualAsrBackend()

        // GGUF: re-enable by moving files from gguf-disabled/ and adding back:
        // @Binds abstract fun bindGgufInferenceEngine(impl: LlamaBroEngine): GgufInferenceEngine
        // @Provides @IntoSet @Singleton fun provideGemma4GgufBackend(engine: GgufInferenceEngine): TranscriptionBackend = Gemma4GgufBackend(engine)
    }
}
