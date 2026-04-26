package com.antivocale.app.di

import com.antivocale.app.data.HuggingFaceApiClient
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.AppDatabase
import com.antivocale.app.transcription.Gemma4GgufBackend
import com.antivocale.app.transcription.LlmTranscriptionBackend
import com.antivocale.app.transcription.Qwen3AsrBackend
import com.antivocale.app.transcription.SherpaOnnxBackend
import com.antivocale.app.transcription.TranscriptionBackend
import com.antivocale.app.transcription.WhisperBackend
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests verifying Hilt DI module bindings produce correct instances.
 *
 * Tests TranscriptionModule @Provides methods and AppModule methods
 * that don't require Android framework dependencies.
 */
class HiltModuleTest {

    // --- TranscriptionModule ---

    @Test
    fun `TranscriptionModule provides LlmTranscriptionBackend`() {
        val backend = TranscriptionModule.provideLlmBackend()
        assertNotNull(backend)
        assertTrue(backend is LlmTranscriptionBackend)
    }

    @Test
    fun `TranscriptionModule provides SherpaOnnxBackend`() {
        val backend = TranscriptionModule.provideSherpaOnnxBackend()
        assertNotNull(backend)
        assertTrue(backend is SherpaOnnxBackend)
    }

    @Test
    fun `TranscriptionModule provides WhisperBackend`() {
        val backend = TranscriptionModule.provideWhisperBackend()
        assertNotNull(backend)
        assertTrue(backend is WhisperBackend)
    }

    @Test
    fun `TranscriptionModule provides Qwen3AsrBackend`() {
        val backend = TranscriptionModule.provideQwen3AsrBackend()
        assertNotNull(backend)
        assertTrue(backend is Qwen3AsrBackend)
    }

    @Test
    fun `TranscriptionModule provides Gemma4GgufBackend`() {
        val backend = TranscriptionModule.provideGemma4GgufBackend()
        assertNotNull(backend)
        assertTrue(backend is Gemma4GgufBackend)
    }

    @Test
    fun `all provided backends have unique ids`() {
        val backends: List<TranscriptionBackend> = listOf(
            TranscriptionModule.provideLlmBackend(),
            TranscriptionModule.provideSherpaOnnxBackend(),
            TranscriptionModule.provideWhisperBackend(),
            TranscriptionModule.provideQwen3AsrBackend(),
            TranscriptionModule.provideGemma4GgufBackend()
        )
        val ids = backends.map { it.id }
        assertEquals("All backend IDs must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `all provided backends have non-empty display names`() {
        val backends: List<TranscriptionBackend> = listOf(
            TranscriptionModule.provideLlmBackend(),
            TranscriptionModule.provideSherpaOnnxBackend(),
            TranscriptionModule.provideWhisperBackend(),
            TranscriptionModule.provideQwen3AsrBackend(),
            TranscriptionModule.provideGemma4GgufBackend()
        )
        backends.forEach { backend ->
            assertTrue("${backend.id} has empty display name", backend.displayName.isNotBlank())
        }
    }

    // --- AppModule (non-Android-dependent) ---

    @Test
    fun `AppModule provides OkHttpClient`() {
        val client = AppModule.provideOkHttpClient()
        assertNotNull(client)
    }

    @Test
    fun `AppModule provides LogDao from database`() {
        val mockDao = mockk<LogDao>(relaxed = true)
        val mockDb = mockk<AppDatabase>(relaxed = true)
        every { mockDb.logDao() } returns mockDao

        val dao = AppModule.provideLogDao(mockDb)
        assertSame(mockDao, dao)
    }
}
