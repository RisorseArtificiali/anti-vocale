package com.antivocale.app.manager

import com.antivocale.app.transcription.BackendConfig
import com.antivocale.app.transcription.LlmTranscriptionBackend
import com.google.ai.edge.litertlm.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests verifying the LlmManager API contract for LiteRT-LM v0.12.0 compatibility.
 *
 * LiteRT-LM uses native JNI libraries that cannot be loaded in unit tests,
 * so we use MockK to mock LlmManager and verify delegation contracts.
 *
 * The API type verification test provides compile-time proof that the v0.12.0
 * API surface is unchanged -- if any class/function/constructor was removed,
 * that test will fail to compile.
 */
class LlmManagerLiteRTUpgradeTest {

    // -------------------------------------------------------------------------
    // 1. LlmManager contract tests (mock-based)
    // -------------------------------------------------------------------------

    @Test
    fun `isLiteRTAvailable always returns true`() {
        val manager = LlmManager()
        assertTrue(manager.isLiteRTAvailable())
    }

    @Test
    fun `isReady returns false before initialization`() {
        val manager = LlmManager()
        assertFalse(manager.isReady())
    }

    @Test
    fun `isAudioSupported returns false before initialization`() {
        val manager = LlmManager()
        assertFalse(manager.isAudioSupported())
    }

    @Test
    fun `getModelPath returns null before initialization`() {
        val manager = LlmManager()
        assertNull(manager.getModelPath())
    }

    @Test
    fun `unload is safe to call without initialization`() {
        val manager = LlmManager()
        // Must not throw
        manager.unload()
        assertFalse(manager.isReady())
    }

    @Test
    fun `generateText returns failure when not initialized`() = runTest {
        val manager = LlmManager()
        val result = manager.generateText("test prompt")
        assertTrue("generateText should return failure", result.isFailure)
        assertTrue(
            "Failure should mention 'not initialized'",
            result.exceptionOrNull()?.message?.contains("not initialized", ignoreCase = true) == true
        )
    }

    @Test
    fun `generateFromAudio returns failure when not initialized`() = runTest {
        val manager = LlmManager()
        val result = manager.generateFromAudio("transcribe", ByteArray(0))
        assertTrue("generateFromAudio should return failure", result.isFailure)
        assertTrue(
            "Failure should mention 'not initialized'",
            result.exceptionOrNull()?.message?.contains("not initialized", ignoreCase = true) == true
        )
    }

    // -------------------------------------------------------------------------
    // 2. LlmTranscriptionBackend delegation tests (mock LlmManager)
    // -------------------------------------------------------------------------

    @Test
    fun `isReady delegates to llmManager`() {
        val llmManager = mockk<LlmManager>()
        every { llmManager.isReady() } returns true

        val backend = LlmTranscriptionBackend(llmManager)
        assertTrue(backend.isReady())
        verify { llmManager.isReady() }
    }

    @Test
    fun `isAudioSupported delegates to llmManager`() {
        val llmManager = mockk<LlmManager>()
        every { llmManager.isAudioSupported() } returns true

        val backend = LlmTranscriptionBackend(llmManager)
        assertTrue(backend.isAudioSupported())
        verify { llmManager.isAudioSupported() }
    }

    @Test
    fun `unload delegates to llmManager`() {
        val llmManager = mockk<LlmManager>(relaxed = true)
        val backend = LlmTranscriptionBackend(llmManager)

        backend.unload()
        verify { llmManager.unload() }
    }

    @Test
    fun `getModelPath delegates to llmManager`() {
        val llmManager = mockk<LlmManager>()
        every { llmManager.getModelPath() } returns "/data/models/gemma.litertlm"

        val backend = LlmTranscriptionBackend(llmManager)
        assertEquals("/data/models/gemma.litertlm", backend.getModelPath())
        verify { llmManager.getModelPath() }
    }

    @Test
    fun `transcribeAudio delegates to llmManager generateFromAudio and maps result`() = runTest {
        val llmManager = mockk<LlmManager>()
        coEvery { llmManager.generateFromAudio(any(), any()) } returns Result.success("Ciao mondo")

        val backend = LlmTranscriptionBackend(llmManager)
        val result = backend.transcribeAudio(FloatArray(16000), 16000, "Transcribe:")
        assertTrue("transcribeAudio should succeed", result.isSuccess)
        assertEquals("Ciao mondo", result.getOrNull()?.text)
    }

    @Test
    fun `transcribeAudio maps failure from generateFromAudio`() = runTest {
        val llmManager = mockk<LlmManager>()
        coEvery { llmManager.generateFromAudio(any(), any()) } returns
            Result.failure(IllegalStateException("Model not initialized"))

        val backend = LlmTranscriptionBackend(llmManager)
        val result = backend.transcribeAudio(FloatArray(16000), 16000, "Transcribe:")
        assertTrue("transcribeAudio should fail", result.isFailure)
    }

    @Test
    fun `generateText delegates to llmManager`() = runTest {
        val llmManager = mockk<LlmManager>()
        coEvery { llmManager.generateText("hello") } returns Result.success("world")

        val backend = LlmTranscriptionBackend(llmManager)
        val result = backend.generateText("hello")
        assertTrue("generateText should succeed", result.isSuccess)
        assertEquals("world", result.getOrNull())
    }

    @Test
    fun `initialize with wrong config type returns failure`() = runTest {
        val llmManager = mockk<LlmManager>(relaxed = true)
        val backend = LlmTranscriptionBackend(llmManager)

        val wrongConfig = BackendConfig.SherpaOnnxConfig(
            modelDir = "/fake",
            numThreads = 4
        )
        val result = backend.initialize(mockk(relaxed = true), wrongConfig)

        assertTrue("initialize should fail with wrong config", result.isFailure)
        assertTrue(
            "Failure should mention invalid config",
            result.exceptionOrNull()?.message?.contains("Invalid config", ignoreCase = true) == true
        )
    }

    @Test
    fun `initialize with LiteRTConfig delegates to llmManager`() = runTest {
        val llmManager = mockk<LlmManager>()
        every { llmManager.initialize(any(), any()) } returns Result.success(Unit)

        val backend = LlmTranscriptionBackend(llmManager)
        val config = BackendConfig.LiteRTConfig(modelPath = "/data/models/gemma.litertlm")
        val result = backend.initialize(mockk(relaxed = true), config)

        assertTrue("initialize should succeed", result.isSuccess)
        verify { llmManager.initialize(any(), "/data/models/gemma.litertlm") }
    }

    // -------------------------------------------------------------------------
    // 3. API type verification (compile-time proof for v0.12.0)
    // -------------------------------------------------------------------------

    @Test
    fun `litertLM_v0_12_0_api_surface_is_accessible`() {
        // Compile-time proof that all APIs used by LlmManager exist in v0.12.0.
        // If v0.12.0 removed or renamed any of these, this file will not compile.

        val backend = Backend.CPU()
        val textContent = Content.Text("test")
        val audioContent = Content.AudioBytes(ByteArray(0))
        val samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8)
        val conversationConfig = ConversationConfig(samplerConfig = samplerConfig)
        val textOnly = Contents.of(Content.Text("prompt"))
        val multiModal = Contents.of(Content.AudioBytes(ByteArray(0)), Content.Text("Transcribe:"))

        assertNotNull(backend)
        assertNotNull(textContent)
        assertNotNull(audioContent)
        assertNotNull(samplerConfig)
        assertNotNull(conversationConfig)
        assertNotNull(textOnly)
        assertNotNull(multiModal)
    }
}
