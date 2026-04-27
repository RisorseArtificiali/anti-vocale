package com.antivocale.app.transcription

import com.antivocale.app.manager.LlmManager
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class TranscriptionBackendContractsTest {

    // --- SherpaOnnxBackend ---

    @Test
    fun `SherpaOnnxBackend has correct id`() {
        assertEquals("sherpa-onnx", SherpaOnnxBackend().id)
    }

    @Test
    fun `SherpaOnnxBackend has non-empty display name`() {
        assertTrue(SherpaOnnxBackend().displayName.isNotBlank())
    }

    @Test
    fun `SherpaOnnxBackend supports audio`() {
        assertTrue(SherpaOnnxBackend().supportsAudio)
    }

    @Test
    fun `SherpaOnnxBackend does not support text`() {
        assertFalse(SherpaOnnxBackend().supportsText)
    }

    @Test
    fun `SherpaOnnxBackend is not ready before init`() {
        assertFalse(SherpaOnnxBackend().isReady())
    }

    @Test
    fun `SherpaOnnxBackend isAudioSupported matches supportsAudio`() {
        val backend = SherpaOnnxBackend()
        assertEquals(backend.supportsAudio, backend.isAudioSupported())
    }

    @Test
    fun `SherpaOnnxBackend unload is safe without init`() {
        SherpaOnnxBackend().unload() // should not throw
    }

    @Test
    fun `SherpaOnnxBackend model path is null before init`() {
        assertNull(SherpaOnnxBackend().getModelPath())
    }

    @Test
    fun `SherpaOnnxBackend maxChunkDurationSeconds is null (no chunking limit)`() {
        assertNull(SherpaOnnxBackend().maxChunkDurationSeconds)
    }

    // --- WhisperBackend ---

    @Test
    fun `WhisperBackend has correct id`() {
        assertEquals("whisper", WhisperBackend().id)
    }

    @Test
    fun `WhisperBackend has non-empty display name`() {
        assertTrue(WhisperBackend().displayName.isNotBlank())
    }

    @Test
    fun `WhisperBackend supports audio`() {
        assertTrue(WhisperBackend().supportsAudio)
    }

    @Test
    fun `WhisperBackend does not support text`() {
        assertFalse(WhisperBackend().supportsText)
    }

    @Test
    fun `WhisperBackend isAudioSupported matches supportsAudio`() {
        val backend = WhisperBackend()
        assertEquals(backend.supportsAudio, backend.isAudioSupported())
    }

    @Test
    fun `WhisperBackend unload is safe without init`() {
        WhisperBackend().unload() // should not throw
    }

    @Test
    fun `WhisperBackend model path is null before init`() {
        assertNull(WhisperBackend().getModelPath())
    }

    // --- Qwen3AsrBackend ---

    @Test
    fun `Qwen3AsrBackend has correct id`() {
        assertEquals("qwen3-asr", Qwen3AsrBackend().id)
    }

    @Test
    fun `Qwen3AsrBackend has non-empty display name`() {
        assertTrue(Qwen3AsrBackend().displayName.isNotBlank())
    }

    @Test
    fun `Qwen3AsrBackend supports audio`() {
        assertTrue(Qwen3AsrBackend().supportsAudio)
    }

    @Test
    fun `Qwen3AsrBackend does not support text`() {
        assertFalse(Qwen3AsrBackend().supportsText)
    }

    @Test
    fun `Qwen3AsrBackend is not ready before init`() {
        assertFalse(Qwen3AsrBackend().isReady())
    }

    @Test
    fun `Qwen3AsrBackend isAudioSupported matches supportsAudio`() {
        val backend = Qwen3AsrBackend()
        assertEquals(backend.supportsAudio, backend.isAudioSupported())
    }

    @Test
    fun `Qwen3AsrBackend unload is safe without init`() {
        Qwen3AsrBackend().unload() // should not throw
    }

    @Test
    fun `Qwen3AsrBackend model path is null before init`() {
        assertNull(Qwen3AsrBackend().getModelPath())
    }

    // --- LlmTranscriptionBackend ---

    @Test
    fun `LlmTranscriptionBackend has correct id`() {
        val llm = mockk<LlmManager>(relaxed = true)
        assertEquals("llm", LlmTranscriptionBackend(llm).id)
    }

    @Test
    fun `LlmTranscriptionBackend has non-empty display name`() {
        val llm = mockk<LlmManager>(relaxed = true)
        assertTrue(LlmTranscriptionBackend(llm).displayName.isNotBlank())
    }

    // --- Backend ID uniqueness ---

    @Test
    fun `all backend IDs are unique`() {
        val llm = mockk<LlmManager>(relaxed = true)
        val ids = listOf(
            SherpaOnnxBackend().id,
            WhisperBackend().id,
            Qwen3AsrBackend().id,
            LlmTranscriptionBackend(llm).id
        )
        assertEquals("All backend IDs must be unique", ids.size, ids.toSet().size)
    }
}
