package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.data.TranscriptionCalibrator
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorTest : TranscriptionOrchestratorTestBase() {

    // --- Pure Utility Tests ---

    @Test
    fun `ceilDiv computes ceiling division correctly`() {
        assertEquals(1, orchestrator.ceilDiv(1, 2))
        assertEquals(2, orchestrator.ceilDiv(3, 2))
        assertEquals(3, orchestrator.ceilDiv(5, 2))
        assertEquals(1, orchestrator.ceilDiv(2, 2))
    }

    @Test
    fun `deriveDisplayName extracts Whisper variant from path`() {
        val result = orchestrator.deriveDisplayName(
            WhisperBackend.BACKEND_ID,
            "/data/models/sherpa-onnx-whisper-turbo",
            "Whisper"
        )
        assertEquals("Whisper Turbo", result)
    }

    @Test
    fun `deriveDisplayName falls back for unknown backend`() {
        val result = orchestrator.deriveDisplayName(
            "unknown_backend",
            "/some/path",
            "Fallback"
        )
        assertEquals("Fallback", result)
    }

    @Test
    fun `deriveDisplayName extracts Qwen3 variant`() {
        val result = orchestrator.deriveDisplayName(
            Qwen3AsrBackend.BACKEND_ID,
            "/data/models/sherpa-onnx-qwen3-asr-large-int8",
            "Qwen3-ASR"
        )
        assertEquals("Large", result)
    }

    @Test
    fun `isNoModelConfiguredError detects model config errors`() {
        val error = IllegalStateException("No LLM model configured")
        assertTrue(orchestrator.isNoModelConfiguredError(error))
    }

    @Test
    fun `isNoModelConfiguredError rejects non-config errors`() {
        val error = IllegalStateException("Audio preprocessing failed")
        assertFalse(orchestrator.isNoModelConfiguredError(error))
    }

    @Test
    fun `isNoModelConfiguredError handles null message`() {
        val error = RuntimeException(null as String?)
        assertFalse(orchestrator.isNoModelConfiguredError(error))
    }

    @Test
    fun `formatEta shows seconds for short durations with HIGH confidence`() {
        val profile = TranscriptionCalibrator.CalibrationProfile.Confidence.HIGH
        assertEquals("30s remaining", orchestrator.formatEta(30, profile))
    }

    @Test
    fun `formatEta shows minutes and seconds for medium durations`() {
        val profile = TranscriptionCalibrator.CalibrationProfile.Confidence.HIGH
        val result = orchestrator.formatEta(125, profile)
        assertEquals("~2m 5s remaining", result)
    }

    @Test
    fun `formatEta shows hours for long durations`() {
        val profile = TranscriptionCalibrator.CalibrationProfile.Confidence.HIGH
        val result = orchestrator.formatEta(3725, profile)
        assertEquals("~1h 2m remaining", result)
    }

    @Test
    fun `formatEta returns empty for NONE confidence`() {
        val profile = TranscriptionCalibrator.CalibrationProfile.Confidence.NONE
        assertEquals("", orchestrator.formatEta(60, profile))
    }

    @Test
    fun `formatEta returns empty for zero remaining`() {
        val profile = TranscriptionCalibrator.CalibrationProfile.Confidence.HIGH
        assertEquals("", orchestrator.formatEta(0, profile))
    }

    @Test
    fun `queueAwareAudioLabel shows position when multiple items`() {
        assertEquals("Processing audio (2 of 5)…", orchestrator.queueAwareAudioLabel(2, 5))
    }

    @Test
    fun `queueAwareAudioLabel omits position when single item`() {
        assertEquals("Processing audio…", orchestrator.queueAwareAudioLabel(1, 1))
    }

    @Test
    fun `queueAwareChunkLabel shows chunk and queue position`() {
        val result = orchestrator.queueAwareChunkLabel(3, 5, 2, 4)
        assertEquals("Processing chunk 3/5 (2 of 4)…", result)
    }

    @Test
    fun `queueAwareChunkLabel omits queue position when single item`() {
        val result = orchestrator.queueAwareChunkLabel(2, 3, 1, 1)
        assertEquals("Processing chunk 2/3…", result)
    }

    // --- processRequest Tests ---

    @Test
    fun `processRequest returns error when no backend configured`() = runTest {
        every { preferencesManager.modelPath } returns flowOf(null)
        every { preferencesManager.transcriptionBackend } returns flowOf("llm")
        every { backendManager.hasActiveBackend() } returns false

        val context = mockk<Context>(relaxed = true)
        val result = orchestrator.processRequest(
            taskId = "test-1",
            requestType = "audio",
            prompt = "",
            filePath = "/path/to/audio.wav",
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = java.io.File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        assertTrue(result.isFailure)
        verify { listener.onError(eq("test-1"), eq("BACKEND_LOAD_FAILED"), any(), eq(false), eq(true), any()) }
    }

    @Test
    fun `processRequest returns error for empty text prompt`() = runTest {
        val backend = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns "llm"
            every { isReady() } returns true
        }
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns backend
        every { preferencesManager.transcriptionBackend } returns flowOf("llm")

        val context = mockk<Context>(relaxed = true)
        val result = orchestrator.processRequest(
            taskId = "test-2",
            requestType = "text",
            prompt = "",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = java.io.File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        assertTrue(result.isFailure)
        verify { listener.onError(eq("test-2"), eq("INFERENCE_ERROR"), any(), eq(false), eq(false), any()) }
    }

    @Test
    fun `processRequest returns error for null file path on audio request`() = runTest {
        val backend = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns "whisper"
            every { isReady() } returns true
        }
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns backend
        every { preferencesManager.transcriptionBackend } returns flowOf("whisper")

        val context = mockk<Context>(relaxed = true)
        val result = orchestrator.processRequest(
            taskId = "test-3",
            requestType = "audio",
            prompt = "",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = java.io.File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        assertTrue(result.isFailure)
        verify { listener.onError(eq("test-3"), eq("INFERENCE_ERROR"), any(), eq(false), eq(false), any()) }
    }

    @Test
    fun `processRequest calls onSuccess on text generation success`() = runTest {
        val backend = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns "llm"
            every { isReady() } returns true
            every { supportsText } returns true
        }
        coEvery { backend.generateText("Hello") } returns Result.success("World")

        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns backend
        every { preferencesManager.transcriptionBackend } returns flowOf("llm")

        val context = mockk<Context>(relaxed = true)
        val result = orchestrator.processRequest(
            taskId = "test-4",
            requestType = "text",
            prompt = "Hello",
            filePath = null,
            source = "share",
            sourcePackage = "com.whatsapp",
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = java.io.File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        assertTrue(result.isSuccess)
        assertEquals("World", result.getOrNull())
        verify { listener.onSuccess(eq("test-4"), eq("World"), eq(true), eq("com.whatsapp"), any()) }
    }

    @Test
    fun `processRequest returns error when backend does not support text`() = runTest {
        val backend = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns "whisper"
            every { isReady() } returns true
            every { supportsText } returns false
            every { displayName } returns "Whisper"
        }
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns backend
        every { preferencesManager.transcriptionBackend } returns flowOf("whisper")

        val context = mockk<Context>(relaxed = true)
        val result = orchestrator.processRequest(
            taskId = "test-5",
            requestType = "text",
            prompt = "Hello",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = java.io.File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `processRequest logs request to DB`() = runTest {
        val backend = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns "llm"
            every { isReady() } returns true
            every { supportsText } returns true
        }
        coEvery { backend.generateText(any()) } returns Result.success("OK")

        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns backend
        every { preferencesManager.transcriptionBackend } returns flowOf("llm")

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-log",
            requestType = "text",
            prompt = "test",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = java.io.File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        coVerify { logDao.insert(match { it.taskId == "test-log" }) }
    }

    // --- Backend Loading Tests ---

    @Test
    fun `ensureBackendLoaded unloads mismatched backend`() = runTest {
        val oldBackend = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns "whisper"
            every { isReady() } returns true
        }
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns oldBackend
        every { preferencesManager.transcriptionBackend } returns flowOf("sherpa_onnx")
        every { preferencesManager.parakeetModelPath } returns flowOf("/models/parakeet")
        every { preferencesManager.threadCount } returns flowOf(4)

        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-backend",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = java.io.File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        verify { backendManager.unloadActiveBackend() }
    }
}
