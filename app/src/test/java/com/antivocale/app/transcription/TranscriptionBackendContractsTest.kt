package com.antivocale.app.transcription

import com.antivocale.app.manager.LlmManager
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TranscriptionBackendContractsTest {

    @Before
    fun seedCatalog() {
        // SherpaBackend resolves flags (chunking, streaming) from the bundled
        // catalog; without seeding, byId returns null and flag tests pass vacuously.
        seedCatalogForTest()
    }

    // The whole sherpa-onnx family shares one generic [SherpaBackend]; the
    // per-model backends are gone, so the contract is pinned per catalog entry.

    private fun parakeet() = SherpaBackend(BuiltInBackendIds.PARAKEET)
    private fun whisper() = SherpaBackend(BuiltInBackendIds.WHISPER)
    private fun qwen3() = SherpaBackend(BuiltInBackendIds.QWEN3_ASR)

    // --- SherpaBackend (Parakeet) ---

    @Test
    fun `parakeet has correct id`() {
        assertEquals("sherpa-onnx", parakeet().id)
    }

    @Test
    fun `parakeet has non-empty display name`() {
        assertTrue(parakeet().displayName.isNotBlank())
    }

    @Test
    fun `parakeet supports audio`() {
        assertTrue(parakeet().supportsAudio)
    }

    @Test
    fun `parakeet does not support text`() {
        assertFalse(parakeet().supportsText)
    }

    @Test
    fun `parakeet is not ready before init`() {
        assertFalse(parakeet().isReady())
    }

    @Test
    fun `parakeet isAudioSupported matches supportsAudio`() {
        val backend = parakeet()
        assertEquals(backend.supportsAudio, backend.isAudioSupported())
    }

    @Test
    fun `parakeet unload is safe without init`() {
        parakeet().unload() // should not throw
    }

    @Test
    fun `parakeet model path is null before init`() {
        assertNull(parakeet().getModelPath())
    }

    @Test
    fun `parakeet maxChunkDurationSeconds is set below the 400s native cap (GH #50)`() {
        // The model's attention hard-caps at 400s (max_position_embeddings 5000);
        // the app must chunk below it so long inputs never reach the native failure.
        val chunkSeconds = parakeet().maxChunkDurationSeconds
        assertNotNull(chunkSeconds)
        // TASK-406: default 60 (memory budget; see ParakeetCatalogChunkingTest),
        // tightened per device at runtime by TranscriptionMemoryPolicy.
        assertTrue(chunkSeconds in 30..390)
    }

    // --- SherpaBackend (Whisper) ---

    @Test
    fun `whisper has correct id`() {
        assertEquals("whisper", whisper().id)
    }

    @Test
    fun `whisper has non-empty display name`() {
        assertTrue(whisper().displayName.isNotBlank())
    }

    @Test
    fun `whisper supports audio`() {
        assertTrue(whisper().supportsAudio)
    }

    @Test
    fun `whisper does not support text`() {
        assertFalse(whisper().supportsText)
    }

    @Test
    fun `whisper isAudioSupported matches supportsAudio`() {
        val backend = whisper()
        assertEquals(backend.supportsAudio, backend.isAudioSupported())
    }

    @Test
    fun `whisper unload is safe without init`() {
        whisper().unload() // should not throw
    }

    @Test
    fun `whisper model path is null before init`() {
        assertNull(whisper().getModelPath())
    }

    // --- SherpaBackend (Qwen3-ASR) ---

    @Test
    fun `qwen3-asr has correct id`() {
        assertEquals("qwen3-asr", qwen3().id)
    }

    @Test
    fun `qwen3-asr has non-empty display name`() {
        assertTrue(qwen3().displayName.isNotBlank())
    }

    @Test
    fun `qwen3-asr supports audio`() {
        assertTrue(qwen3().supportsAudio)
    }

    @Test
    fun `qwen3-asr does not support text`() {
        assertFalse(qwen3().supportsText)
    }

    @Test
    fun `qwen3-asr is not ready before init`() {
        assertFalse(qwen3().isReady())
    }

    @Test
    fun `qwen3-asr isAudioSupported matches supportsAudio`() {
        val backend = qwen3()
        assertEquals(backend.supportsAudio, backend.isAudioSupported())
    }

    @Test
    fun `qwen3-asr unload is safe without init`() {
        qwen3().unload() // should not throw
    }

    @Test
    fun `qwen3-asr model path is null before init`() {
        assertNull(qwen3().getModelPath())
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
            parakeet().id,
            whisper().id,
            qwen3().id,
            LlmTranscriptionBackend(llm).id
        )
        assertEquals("All backend IDs must be unique", ids.size, ids.toSet().size)
    }
}