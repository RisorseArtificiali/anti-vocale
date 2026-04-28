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
class TranscriptionOrchestratorBackendLoadTest : TranscriptionOrchestratorTestBase() {

    private val tempDirs = mutableListOf<File>()

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

    // ------------------------------------------------------------------ //
    //  Success-path loading tests
    // ------------------------------------------------------------------ //

    @Test
    fun `load SherpaOnnx backend when no active backend`() = runTest {
        val modelDir = createTempModelDir("parakeet")

        every { preferencesManager.transcriptionBackend } returns flowOf(SherpaOnnxBackend.BACKEND_ID)
        every { preferencesManager.parakeetModelPath } returns flowOf(modelDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-load-sherpa",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
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
                config = match {
                    it is BackendConfig.SherpaOnnxConfig &&
                        it.modelDir == modelDir.absolutePath &&
                        it.numThreads == 4 &&
                        it.language == ""
                }
            )
        }
    }

    @Test
    fun `load Whisper backend when no active backend`() = runTest {
        val modelDir = createTempModelDir("whisper")

        every { preferencesManager.transcriptionBackend } returns flowOf(WhisperBackend.BACKEND_ID)
        every { preferencesManager.whisperModelPath } returns flowOf(modelDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.transcriptionLanguage } returns flowOf("it")
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-load-whisper",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
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
                config = match {
                    it is BackendConfig.SherpaOnnxConfig &&
                        it.modelDir == modelDir.absolutePath &&
                        it.numThreads == 4 &&
                        it.language == "it"
                }
            )
        }
    }

    @Test
    fun `load Whisper backend with auto language resolves to empty string`() = runTest {
        val modelDir = createTempModelDir("whisper")

        every { preferencesManager.transcriptionBackend } returns flowOf(WhisperBackend.BACKEND_ID)
        every { preferencesManager.whisperModelPath } returns flowOf(modelDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.transcriptionLanguage } returns flowOf("auto")
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-load-whisper-auto",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
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
                config = match {
                    it is BackendConfig.SherpaOnnxConfig && it.language == ""
                }
            )
        }
    }

    @Test
    fun `load Qwen3-ASR backend when no active backend`() = runTest {
        val modelDir = createTempModelDir("qwen3")

        every { preferencesManager.transcriptionBackend } returns flowOf(Qwen3AsrBackend.BACKEND_ID)
        every { preferencesManager.qwen3AsrModelPath } returns flowOf(modelDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-load-qwen3",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        coVerify {
            backendManager.setActiveBackend(
                backendId = Qwen3AsrBackend.BACKEND_ID,
                context = context,
                config = match {
                    it is BackendConfig.SherpaOnnxConfig &&
                        it.modelDir == modelDir.absolutePath &&
                        it.numThreads == 4
                }
            )
        }
    }

    @Test
    fun `load GGUF backend when no active backend`() = runTest {
        every { preferencesManager.transcriptionBackend } returns flowOf(Gemma4GgufBackend.BACKEND_ID)
        every { preferencesManager.ggufModelPath } returns flowOf("/models/gemma.gguf")
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-load-gguf",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        coVerify {
            backendManager.setActiveBackend(
                backendId = Gemma4GgufBackend.BACKEND_ID,
                context = context,
                config = match {
                    it is BackendConfig.GgufConfig &&
                        it.modelPath == "/models/gemma.gguf" &&
                        it.threadCount == 4
                }
            )
        }
    }

    @Test
    fun `load LLM default backend when no active backend`() = runTest {
        every { preferencesManager.transcriptionBackend } returns flowOf("llm")
        every { preferencesManager.modelPath } returns flowOf("/models/model.tflite")
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-load-llm",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        coVerify {
            backendManager.setActiveBackend(
                backendId = PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND,
                context = context,
                config = match {
                    it is BackendConfig.LiteRTConfig &&
                        it.modelPath == "/models/model.tflite"
                }
            )
        }
    }

    // ------------------------------------------------------------------ //
    //  Keep-alive timeout
    // ------------------------------------------------------------------ //

    @Test
    fun `keep-alive timeout set after successful load`() = runTest {
        every { preferencesManager.transcriptionBackend } returns flowOf("llm")
        every { preferencesManager.modelPath } returns flowOf("/models/model.tflite")
        every { preferencesManager.keepAliveTimeout } returns flowOf(10)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-keepalive",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        verify { backendManager.setKeepAliveTimeout(10) }
    }

    // ------------------------------------------------------------------ //
    //  Error-path tests: missing model paths
    // ------------------------------------------------------------------ //

    @Test
    fun `SherpaOnnx load fails with missing model path`() = runTest {
        every { preferencesManager.transcriptionBackend } returns flowOf(SherpaOnnxBackend.BACKEND_ID)
        every { preferencesManager.parakeetModelPath } returns flowOf(null)
        every { backendManager.hasActiveBackend() } returns false

        val context = mockk<Context>(relaxed = true)
        val result = orchestrator.processRequest(
            taskId = "test-missing-sherpa",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertTrue(
            "Expected 'No Parakeet model configured' but got: ${error?.message}",
            error?.message?.contains("No Parakeet model configured") == true
        )
    }

    @Test
    fun `Whisper load fails with missing model path`() = runTest {
        every { preferencesManager.transcriptionBackend } returns flowOf(WhisperBackend.BACKEND_ID)
        every { preferencesManager.whisperModelPath } returns flowOf(null)
        every { preferencesManager.transcriptionLanguage } returns flowOf("it")
        every { backendManager.hasActiveBackend() } returns false

        val context = mockk<Context>(relaxed = true)
        val result = orchestrator.processRequest(
            taskId = "test-missing-whisper",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertTrue(
            "Expected 'No Whisper model configured' but got: ${error?.message}",
            error?.message?.contains("No Whisper model configured") == true
        )
    }

    @Test
    fun `GGUF load fails with missing model path`() = runTest {
        every { preferencesManager.transcriptionBackend } returns flowOf(Gemma4GgufBackend.BACKEND_ID)
        every { preferencesManager.ggufModelPath } returns flowOf(null)
        every { backendManager.hasActiveBackend() } returns false

        val context = mockk<Context>(relaxed = true)
        val result = orchestrator.processRequest(
            taskId = "test-missing-gguf",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertTrue(
            "Expected 'No GGUF model configured' but got: ${error?.message}",
            error?.message?.contains("No GGUF model configured") == true
        )
    }

    @Test
    fun `LLM load fails with missing model path`() = runTest {
        every { preferencesManager.transcriptionBackend } returns flowOf("llm")
        every { preferencesManager.modelPath } returns flowOf(null)
        every { backendManager.hasActiveBackend() } returns false

        val context = mockk<Context>(relaxed = true)
        val result = orchestrator.processRequest(
            taskId = "test-missing-llm",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertTrue(
            "Expected 'No LLM model configured' but got: ${error?.message}",
            error?.message?.contains("No LLM model configured") == true
        )
    }

    // ------------------------------------------------------------------ //
    //  Skip / reload logic
    // ------------------------------------------------------------------ //

    @Test
    fun `skips load when backend matches and is ready`() = runTest {
        val backend = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns SherpaOnnxBackend.BACKEND_ID
            every { isReady() } returns true
            every { supportsText } returns true
        }
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns backend
        every { preferencesManager.transcriptionBackend } returns flowOf(SherpaOnnxBackend.BACKEND_ID)
        coEvery { backend.generateText(any()) } returns Result.success("OK")

        val context = mockk<Context>(relaxed = true)
        val result = orchestrator.processRequest(
            taskId = "test-skip-load",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { backendManager.setActiveBackend(any(), any(), any()) }
        verify(exactly = 0) { backendManager.unloadActiveBackend() }
    }

    @Test
    fun `reloads when backend is not ready`() = runTest {
        val modelDir = createTempModelDir("parakeet")

        val staleBackend = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns SherpaOnnxBackend.BACKEND_ID
            every { isReady() } returns false
        }
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns staleBackend
        every { preferencesManager.transcriptionBackend } returns flowOf(SherpaOnnxBackend.BACKEND_ID)
        every { preferencesManager.parakeetModelPath } returns flowOf(modelDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-reload-not-ready",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        verify { backendManager.unloadActiveBackend() }
        coVerify(exactly = 1) { backendManager.setActiveBackend(any(), any(), any()) }
    }

    @Test
    fun `reloads when backend ID mismatches preference`() = runTest {
        val modelDir = createTempModelDir("parakeet")

        val wrongBackend = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns WhisperBackend.BACKEND_ID
            every { isReady() } returns true
        }
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns wrongBackend
        every { preferencesManager.transcriptionBackend } returns flowOf(SherpaOnnxBackend.BACKEND_ID)
        every { preferencesManager.parakeetModelPath } returns flowOf(modelDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-reload-mismatch",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
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
    fun `load fails when backend initialization returns failure`() = runTest {
        val modelDir = createTempModelDir("parakeet")

        every { preferencesManager.transcriptionBackend } returns flowOf(SherpaOnnxBackend.BACKEND_ID)
        every { preferencesManager.parakeetModelPath } returns flowOf(modelDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { backendManager.hasActiveBackend() } returns false
        coEvery {
            backendManager.setActiveBackend(any(), any(), any())
        } returns Result.failure(IllegalStateException("Native library load failed"))

        val context = mockk<Context>(relaxed = true)
        val result = orchestrator.processRequest(
            taskId = "test-load-failure",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("Native library load failed") == true
        )
        verify(exactly = 0) { backendManager.setKeepAliveTimeout(any()) }
    }

    @Test
    fun `unloads previous backend before loading new one`() = runTest {
        val modelDir = createTempModelDir("parakeet")

        val oldBackend = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns "llm"
            every { isReady() } returns true
        }
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns oldBackend
        every { preferencesManager.transcriptionBackend } returns flowOf(SherpaOnnxBackend.BACKEND_ID)
        every { preferencesManager.parakeetModelPath } returns flowOf(modelDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = mockk<Context>(relaxed = true)
        orchestrator.processRequest(
            taskId = "test-unload-previous",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        verify { backendManager.unloadActiveBackend() }
        coVerify { backendManager.setActiveBackend(any(), any(), any()) }
    }
}
