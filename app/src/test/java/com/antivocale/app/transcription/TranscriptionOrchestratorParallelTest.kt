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
import kotlinx.coroutines.flow.flow
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
        every { preferencesManager.sherpaModelPath("whisper") } returns flowOf("/models/whisper")
        every { preferencesManager.inferenceProvider } returns flowOf("auto")
        every { preferencesManager.progressiveTranscription } returns flowOf(false)
        coEvery { transcriptionCalibrator.getEstimate(any(), any()) } returns null
    }

    // ---- Helpers ----

    private fun stubPreprocessing(
        chunkCount: Int,
        durationSeconds: Double = 120.0,
        /** Optional per-index fill value so tests can tell chunks apart by content. */
        chunkValues: Map<Int, Float> = emptyMap(),
    ): List<FloatArray> {
        val chunks = (0 until chunkCount).map { i -> FloatArray(1000) { chunkValues[i] ?: 0.5f } }
        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = any(),
                cacheDir = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                vadNumThreads = any(),
                vadProvider = any(),
                availableRamBytes = any(),
                maxHeapBytes = any())
        } returns AudioPreprocessor.PreprocessingResult(
            chunks = chunks,
            sampleRate = 16000,
            totalDurationSeconds = durationSeconds,
            chunkCount = chunkCount,
            isVadSegmented = false
        )

        // Also mock the streaming pipeline path used when pipeline is enabled
        val streamEvents = buildList {
            add(AudioPreprocessor.StreamEvent.Header(
                AudioPreprocessor.StreamHeader(
                    sampleRate = 16000,
                    totalDurationSeconds = durationSeconds,
                    expectedChunkCount = chunkCount
                )
            ))
            chunks.forEachIndexed { index, samples ->
                add(AudioPreprocessor.StreamEvent.Chunk(
                    AudioPreprocessor.StreamChunk(
                        samples = samples,
                        sampleRate = 16000,
                        chunkIndex = index,
                        isLast = index == chunks.lastIndex
                    )
                ))
            }
        }
        every {
            audioPreprocessor.prepareAudioStream(
                inputPath = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                availableRamBytes = any(),
                maxHeapBytes = any())
        } returns flow {
            streamEvents.forEach { emit(it) }
        }

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
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            Result.success(TranscriptionResult(text = chunkTexts[callIndex++]))
        }

        val result = runParallelAudioRequest()

        assertTrue("Expected success but got failure: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("chunk1 chunk2 chunk3 chunk4", result.getOrNull())
        verify { listener.onSuccess(eq("test-parallel"), eq("chunk1 chunk2 chunk3 chunk4"), any(), any(), any(), segments = any()) }
    }

    @Test
    fun `parallel chunks with blank results - blank chunks are filtered out`() = runTest {
        // TASK-664: content-keyed, because the blank chunk's ladder adds
        // re-feed calls (answered blank here; overlap feeds are not
        // chunk-sized).
        stubPreprocessing(chunkCount = 4, chunkValues = mapOf(0 to 1f, 1 to 2f, 2 to 3f, 3 to 4f))

        backend.stubContentKeyedDecodes { size, first ->
            when {
                size == 1000 && first == 1.0f -> Result.success(TranscriptionResult(text = "chunk1"))
                size == 1000 && first == 3.0f -> Result.success(TranscriptionResult(text = "chunk3"))
                size == 1000 && first == 4.0f -> Result.success(TranscriptionResult(text = "chunk4"))
                else -> Result.success(TranscriptionResult(text = ""))
            }
        }

        val result = runParallelAudioRequest()

        assertTrue("Expected success but got failure: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("chunk1 chunk3 chunk4", result.getOrNull())
    }

    @Test
    fun `parallel chunks with one failure - succeeds with remaining chunks`() = runTest {
        val chunkTexts = listOf("chunk1", "chunk2", "chunk3", "chunk4")
        stubPreprocessing(chunkCount = 4)

        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            val idx = callIndex++
            // Chunk 2 fails first attempt and retry (idx=2 and idx=4+)
            if (idx == 2 || idx >= 4) {
                Result.failure(RuntimeException("Chunk 3 failed"))
            } else {
                Result.success(TranscriptionResult(text = chunkTexts[idx]))
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val result = scope.runParallelAudioRequest()

            assertTrue("Expected success with partial results", result.isSuccess)
            assertEquals("chunk1 chunk2 chunk4", result.getOrNull())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `parallel chunks records calibration`() = runTest {
        stubPreprocessing(chunkCount = 4)

        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            val idx = callIndex++
            Result.success(TranscriptionResult(text = "text$idx"))
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
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            val idx = callIndex++
            Result.success(TranscriptionResult(text = "text$idx"))
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

        coEvery { backend.transcribeAudio(any(), any(), any()) } returns Result.success(TranscriptionResult(text = "   "))

        val result = runParallelAudioRequest()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No transcription produced") == true)
    }
}
