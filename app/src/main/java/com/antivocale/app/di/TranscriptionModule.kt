package com.antivocale.app.di

import com.antivocale.app.llm.LlamaBroEngine
import com.antivocale.app.manager.LlmManager
import com.antivocale.app.transcription.Gemma4GgufBackend
import com.antivocale.app.transcription.LlmTranscriptionBackend
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
object TranscriptionModule {

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
    fun provideGemma4GgufBackend(): TranscriptionBackend = Gemma4GgufBackend(LlamaBroEngine())
}
