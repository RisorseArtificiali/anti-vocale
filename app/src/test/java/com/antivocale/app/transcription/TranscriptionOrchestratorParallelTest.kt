package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.audio.AudioPreprocessor
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorParallelTest : TranscriptionOrchestratorTestBase() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var backend: TranscriptionBackend

    override fun baseSetUp() {
        super.baseSetUp()

        backend = stubWhisperBackend()

        every { preferencesManager.transcriptionBackend } returns flowOf("whisper")
        every { preferencesManager.vadEnabled } returns flowOf(false)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.defaultPrompt } returns flowOf("")
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { preferencesManager.whisperModelPath } returns flowOf("/models/whisper")
        every { preferencesManager.progressiveTranscription } returns flowOf(false)
        coEvery { transcriptionCalibrator.getEstimate(any(), any()) } returns null
    }

    // ---- Helpers ----

    private fun stubPreprocessing(chunkCount: Int, durationSeconds: Double = 120.0): List<ByteArray> {
        val chunks = (1..chunkCount).map { byteArrayOf(0x52, 0x49, 0x46, 0x46, it.toByte()) }
        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = any(),
                cacheDir = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                vadNumThreads = any()
            )
        } returns AudioPreprocessor.PreprocessingResult(
            chunks = chunks,
            totalDurationSeconds = durationSeconds,
            chunkCount = chunkCount,
            isVadSegmented = false
        )
        return chunks
    }

    private suspend fun CoroutineScope.runParallelAudioRequest(
        taskId: String = "test-parallel",
        queuePosition: Int = 1,
        queueTotal: Int = 1
    ): Result<String> {
        val audioFile = File(temporaryFolder.root, "audio.wav")
        audioFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        val context = mockk<Context>(relaxed = true)
        return orchestrator.processRequest(
            taskId = taskId,
            requestType = "audio",
            prompt = "",
            filePath = audioFile.absolutePath,
            source = null,
            sourcePackage = null,
            queuePosition = queuePosition,
            queueTotal = queueTotal,
            context = context,
            cacheDir = temporaryFolder.root,
            listener = listener,
            coroutineScope = this
        )
    }

    // ---- Tests ----

    @Test
    fun `parallel chunks all succeed - results are space-joined`() = runTest {
        val chunkTexts = listOf("chunk1", "chunk2", "chunk3", "chunk4")
        stubPreprocessing(chunkCount = 4)

        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any()) } answers {
            Result.success(chunkTexts[callIndex++])
        }

        val result = runParallelAudioRequest()

        assertTrue("Expected success but got failure: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("chunk1 chunk2 chunk3 chunk4", result.getOrNull())
        verify { listener.onSuccess(eq("test-parallel"), eq("chunk1 chunk2 chunk3 chunk4"), any(), any(), any()) }
    }

    @Test
    fun `parallel chunks with blank results - blank chunks are filtered out`() = runTest {
        val chunkTexts = listOf("chunk1", "", "chunk3", "chunk4")
        stubPreprocessing(chunkCount = 4)

        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any()) } answers {
            Result.success(chunkTexts[callIndex++])
        }

        val result = runParallelAudioRequest()

        assertTrue("Expected success but got failure: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("chunk1 chunk3 chunk4", result.getOrNull())
    }

    @Test
    fun `parallel chunks with one failure - entire result is failure`() = runTest {
        val chunkTexts = listOf("chunk1", "chunk2", "chunk3", "chunk4")
        stubPreprocessing(chunkCount = 4)

        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any()) } answers {
            val idx = callIndex++
            if (idx == 2) {
                Result.failure(RuntimeException("Chunk 3 failed"))
            } else {
                Result.success(chunkTexts[idx])
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val result = scope.runParallelAudioRequest()

            assertTrue("Expected failure", result.isFailure)
            val error = result.exceptionOrNull()
            assertNotNull(error)
            assertTrue(
                "Expected chunk failure message, got: ${error?.message}",
                error?.message?.contains("Chunk") == true || error?.message?.contains("failed") == true
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `parallel chunks records calibration`() = runTest {
        stubPreprocessing(chunkCount = 4)

        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any()) } answers {
            val idx = callIndex++
            Result.success("text$idx")
        }

        val result = runParallelAudioRequest()

        assertTrue("Expected success but got failure: ${result.exceptionOrNull()}", result.isSuccess)

        coVerify {
            transcriptionCalibrator.record(
                backendId = "whisper",
                modelPath = "/models/whisper",
                displayName = any(),
                audioDurationSeconds = any(),
                processingTimeMs = any()
            )
        }
    }

    @Test
    fun `parallel chunks with queue position - listener gets queue-aware labels`() = runTest {
        stubPreprocessing(chunkCount = 4)

        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any()) } answers {
            val idx = callIndex++
            Result.success("text$idx")
        }

        val result = runParallelAudioRequest(queuePosition = 2, queueTotal = 5)

        assertTrue("Expected success but got failure: ${result.exceptionOrNull()}", result.isSuccess)

        assertEquals(
            "Processing audio (2 of 5)…",
            orchestrator.queueAwareAudioLabel(2, 5)
        )
        assertEquals(
            "Processing chunk 2/4 (2 of 5)…",
            orchestrator.queueAwareChunkLabel(2, 4, 2, 5)
        )
    }

    @Test
    fun `parallel chunks all blank - returns failure`() = runTest {
        stubPreprocessing(chunkCount = 3)

        coEvery { backend.transcribeAudio(any(), any()) } returns Result.success("   ")

        val result = runParallelAudioRequest()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No transcription produced") == true)
    }
}
