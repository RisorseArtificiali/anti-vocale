package com.antivocale.app.llm

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LlamaCppEngineTest {

    private lateinit var engine: LlamaCppEngine

    @Before
    fun setUp() {
        engine = LlamaCppEngine()
    }

    @Test
    fun `engine is not ready before initialization`() {
        assertFalse(engine.isReady())
    }

    @Test
    fun `getModelPath returns null before initialization`() {
        assertNull(engine.getModelPath())
    }

    @Test
    fun `getMemoryUsageBytes returns null before initialization`() {
        assertNull(engine.getMemoryUsageBytes())
    }

    @Test
    fun `initialize fails for nonexistent file`() {
        val config = GgufInferenceEngine.ModelConfig(
            modelPath = "/nonexistent/model.gguf"
        )

        val result = engine.initialize(config)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertFalse(engine.isReady())
    }

    @Test
    fun `initialize fails for non-gguf file`() {
        val tempFile = File.createTempFile("test", ".bin")
        try {
            val config = GgufInferenceEngine.ModelConfig(
                modelPath = tempFile.absolutePath
            )

            val result = engine.initialize(config)

            assertTrue(result.isFailure)
            val message = result.exceptionOrNull()?.message ?: ""
            assertTrue(message.contains(".gguf"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `generate fails when not initialized`() = runTest {
        val params = GgufInferenceEngine.GenerateParams(prompt = "test")

        val result = engine.generate(params)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `unload is safe to call when not initialized`() {
        engine.unload()
        // No exception thrown
        assertFalse(engine.isReady())
    }

    @Test
    fun `unload can be called multiple times`() {
        engine.unload()
        engine.unload()
        assertFalse(engine.isReady())
    }
}
