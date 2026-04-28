package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.llm.GgufInferenceEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// Tests for Gemma4GgufBackend wiring to GgufInferenceEngine.
// Engine integration tests require native libs — see TASK-121, TASK-154.

class Gemma4GgufBackendTest {

    private lateinit var engine: GgufInferenceEngine
    private lateinit var backend: Gemma4GgufBackend
    private lateinit var context: Context

    @Before
    fun setUp() {
        engine = mockk(relaxed = true)
        backend = Gemma4GgufBackend(engine)
        context = mockk(relaxed = true)
    }

    @Test
    fun `backend has correct id`() {
        assertEquals("gemma4_gguf", backend.id)
    }

    @Test
    fun `backend has correct display name`() {
        assertEquals("Gemma 4 (GGUF)", backend.displayName)
    }

    @Test
    fun `backend does not support audio`() {
        assertFalse(backend.supportsAudio)
        assertFalse(backend.isAudioSupported())
    }

    @Test
    fun `backend supports text`() {
        assertTrue(backend.supportsText)
    }

    @Test
    fun `backend has no chunk duration limit`() {
        assertNull(backend.maxChunkDurationSeconds)
    }

    @Test
    fun `initialize delegates to engine with correct config`() = runTest {
        val config = BackendConfig.GgufConfig(
            modelPath = "/data/models/gguf/model.gguf",
            contextSize = 4096,
            threadCount = 6
        )
        coEvery { engine.initialize(any()) } returns Result.success(Unit)

        val result = backend.initialize(context, config)

        assertTrue(result.isSuccess)
        coVerify {
            engine.initialize(GgufInferenceEngine.ModelConfig(
                modelPath = "/data/models/gguf/model.gguf",
                contextSize = 4096,
                threadCount = 6
            ))
        }
    }

    @Test
    fun `initialize rejects wrong config type`() = runTest {
        val wrongConfig = BackendConfig.LiteRTConfig(modelPath = "/path/to/model")

        val result = backend.initialize(context, wrongConfig)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `initialize uses default context size and thread count`() = runTest {
        val config = BackendConfig.GgufConfig(modelPath = "/path/model.gguf")
        coEvery { engine.initialize(any()) } returns Result.success(Unit)

        backend.initialize(context, config)

        coVerify {
            engine.initialize(GgufInferenceEngine.ModelConfig(
                modelPath = "/path/model.gguf",
                contextSize = 2048,
                threadCount = 4
            ))
        }
    }

    @Test
    fun `generateText delegates to engine`() = runTest {
        coEvery { engine.generate(any()) } returns Result.success("Generated text here")

        val result = backend.generateText("Summarize this text")

        assertTrue(result.isSuccess)
        assertEquals("Generated text here", result.getOrNull())
    }

    @Test
    fun `generateText passes prompt with default params`() = runTest {
        coEvery { engine.generate(any()) } returns Result.success("ok")

        backend.generateText("test prompt")

        coVerify {
            engine.generate(GgufInferenceEngine.GenerateParams(prompt = "test prompt"))
        }
    }

    @Test
    fun `transcribeAudio returns failure`() = runTest {
        val result = backend.transcribeAudio(FloatArray(100), 16000, "transcribe")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UnsupportedOperationException)
    }

    @Test
    fun `isReady delegates to engine`() {
        every { engine.isReady() } returns true
        assertTrue(backend.isReady())

        every { engine.isReady() } returns false
        assertFalse(backend.isReady())
    }

    @Test
    fun `unload delegates to engine`() {
        backend.unload()
        verify { engine.unload() }
    }

    @Test
    fun `getModelPath delegates to engine`() {
        every { engine.getModelPath() } returns "/path/model.gguf"
        assertEquals("/path/model.gguf", backend.getModelPath())

        every { engine.getModelPath() } returns null
        assertNull(backend.getModelPath())
    }

    @Test
    fun `setKeepAliveTimeout does not crash`() {
        backend.setKeepAliveTimeout(15)
    }
}
