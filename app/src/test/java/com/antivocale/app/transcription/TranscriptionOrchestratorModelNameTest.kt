package com.antivocale.app.transcription

import com.antivocale.app.R
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * GH #45: the log entry must record which model produced the transcription,
 * written as soon as the backend is loaded (before the result lands). Since
 * TASK-436 the name is variant-aware for multi-variant catalog backends
 * ("Whisper Small", not the bare "Whisper" family label).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorModelNameTest : TranscriptionOrchestratorTestBase() {

    @Test
    fun `processRequest records the backend display name on the log row`() = runTest {
        val backend = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns "llm"
            every { isReady() } returns true
            every { supportsText } returns true
            every { displayName } returns "Gemma 4 E2B"
        }
        coEvery { backend.generateText(any()) } returns Result.success("OK")
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns backend
        every { preferencesManager.transcriptionBackend } returns flowOf("llm")
        // modelPathForBackend reads this before the display-name derivation
        every { preferencesManager.modelPath } returns flowOf("/models/gemma")
        // The registry resolves the LLM backend name through a string resource
        val context = mockk<android.content.Context>(relaxed = true)
        every { context.getString(any()) } returns "Gemma 4 E2B"

        orchestrator.processRequest(
            taskId = "task-model",
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
            coroutineScope = this,
        )

        coVerify { logDao.setModelName("task-model", "Gemma 4 E2B") }
    }

    @Test
    fun `processRequest records the variant title for a multi-variant catalog backend`() = runTest {
        val backend = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns "whisper"
            every { isReady() } returns true
            every { supportsText } returns true
            every { displayName } returns "Whisper"
        }
        coEvery { backend.generateText(any()) } returns Result.success("OK")
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns backend
        every { preferencesManager.transcriptionBackend } returns flowOf("whisper")
        // The saved path's last segment is the Small variant's catalog dirName,
        // which the shared derivation resolves to the variant title.
        every { preferencesManager.sherpaModelPath("whisper") } returns flowOf("/models/sherpa-onnx-whisper-small")
        val context = mockk<android.content.Context>()
        every { context.getString(R.string.whisper_title) } returns "Whisper"
        every { context.getString(R.string.whisper_small_title) } returns "Whisper Small"

        orchestrator.processRequest(
            taskId = "task-model",
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
            coroutineScope = this,
        )

        coVerify { logDao.setModelName("task-model", "Whisper Small") }
    }
}
