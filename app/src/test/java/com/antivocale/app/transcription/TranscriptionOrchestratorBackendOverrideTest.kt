package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.data.PreferencesManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorBackendOverrideTest : TranscriptionOrchestratorTestBase() {

    private val tempDirs = mutableListOf<File>()

    override fun baseSetUp() {
        super.baseSetUp()
        every { preferencesManager.inferenceProvider } returns flowOf("auto")
    }

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun createTempModelDir(prefix: String = "model"): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}")
        dir.mkdirs()
        tempDirs.add(dir)
        return dir
    }

    @Test
    fun `backend override loads specified backend instead of preference`() = runTest {
        val parakeetDir = createTempModelDir("parakeet")

        // Preference says whisper, but override says sherpa-onnx
        every { preferencesManager.transcriptionBackend } returns flowOf(WhisperBackend.BACKEND_ID)
        every { preferencesManager.parakeetModelPath } returns flowOf(parakeetDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-override-parakeet",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = "share",
            sourcePackage = null,
            backendOverride = SherpaOnnxBackend.BACKEND_ID,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        // Should load sherpa-onnx (Parakeet), not Whisper from preferences
        coVerify {
            backendManager.setActiveBackend(
                backendId = SherpaOnnxBackend.BACKEND_ID,
                context = context,
                config = match {
                    it is BackendConfig.SherpaOnnxConfig &&
                        it.modelDir == parakeetDir.absolutePath
                }
            )
        }
    }

    @Test
    fun `backend override with whisper loads whisper backend`() = runTest {
        val whisperDir = createTempModelDir("whisper")

        // Preference says llm, but override says whisper
        every { preferencesManager.transcriptionBackend } returns flowOf("llm")
        every { preferencesManager.whisperModelPath } returns flowOf(whisperDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.transcriptionLanguage } returns flowOf("auto")
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-override-whisper",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = "share",
            sourcePackage = null,
            backendOverride = WhisperBackend.BACKEND_ID,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        coVerify {
            backendManager.setActiveBackend(
                backendId = WhisperBackend.BACKEND_ID,
                context = context,
                config = any()
            )
        }
    }

    @Test
    fun `no override falls back to preference`() = runTest {
        val modelDir = createTempModelDir("parakeet")

        every { preferencesManager.transcriptionBackend } returns flowOf(SherpaOnnxBackend.BACKEND_ID)
        every { preferencesManager.parakeetModelPath } returns flowOf(modelDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-no-override",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = "share",
            sourcePackage = null,
            backendOverride = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        coVerify {
            backendManager.setActiveBackend(
                backendId = SherpaOnnxBackend.BACKEND_ID,
                context = context,
                config = any()
            )
        }
    }

    @Test
    fun `override forces reload when active backend differs`() = runTest {
        val parakeetDir = createTempModelDir("parakeet")

        // Active backend is whisper, preference is whisper, but override demands parakeet
        val whisperBackend = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns WhisperBackend.BACKEND_ID
            every { isReady() } returns true
        }
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns whisperBackend
        every { preferencesManager.transcriptionBackend } returns flowOf(WhisperBackend.BACKEND_ID)
        every { preferencesManager.parakeetModelPath } returns flowOf(parakeetDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-override-reload",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = "share",
            sourcePackage = null,
            backendOverride = SherpaOnnxBackend.BACKEND_ID,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        verify { backendManager.unloadActiveBackend() }
        coVerify {
            backendManager.setActiveBackend(
                backendId = SherpaOnnxBackend.BACKEND_ID,
                context = context,
                config = any()
            )
        }
    }

    @Test
    fun `override with missing model path returns failure`() = runTest {
        // Override asks for parakeet but no model is configured
        every { preferencesManager.parakeetModelPath } returns flowOf(null)
        every { backendManager.hasActiveBackend() } returns false

        val context = mockk<Context>(relaxed = true)
        val result = orchestrator.processRequest(
            taskId = "test-override-no-model",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = "share",
            sourcePackage = null,
            backendOverride = SherpaOnnxBackend.BACKEND_ID,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        assertTrue(result.isFailure)
        assertTrue(
            "Expected 'No Parakeet model configured' but got: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("No Parakeet model configured") == true
        )
    }

    @Test
    fun `override unloads backend after transcription`() = runTest {
        val parakeetDir = createTempModelDir("parakeet")

        // Override says sherpa-onnx
        every { preferencesManager.parakeetModelPath } returns flowOf(parakeetDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)
        every { backendManager.unloadActiveBackend() } returns Unit

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-restore",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = "share",
            sourcePackage = null,
            backendOverride = SherpaOnnxBackend.BACKEND_ID,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        // Should load sherpa-onnx for the override, then unload it
        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            backendManager.setActiveBackend(
                backendId = SherpaOnnxBackend.BACKEND_ID,
                context = context,
                config = any()
            )
        }
        verify { backendManager.unloadActiveBackend() }
    }
}
