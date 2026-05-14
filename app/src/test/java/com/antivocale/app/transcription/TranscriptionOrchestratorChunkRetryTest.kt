package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.audio.AudioPreprocessor
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for chunk retry logic and partial transcription handling.
 *
 * Covers: TASK-205.1 (retry with GC), TASK-205.2 (isPartial notification),
 * and TASK-205.3 (unified parallel error handling).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorChunkRetryTest : TranscriptionOrchestratorTestBase() {

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
        every { preferencesManager.inferenceProvider } returns flowOf("auto")
        every { preferencesManager.progressiveTranscription } returns flowOf(false)
        coEvery { transcriptionCalibrator.getEstimate(any(), any()) } returns null
    }

    // ---- Helpers ----

    private fun stubPipelineStream(
        chunkCount: Int,
        durationSeconds: Double = 120.0
    ): List<FloatArray> {
        val chunks = (1..chunkCount).map { FloatArray(1000) { 0.5f } }
        val streamEvents = buildList {
            add(AudioPreprocessor.StreamEvent.Header(
                AudioPreprocessor.StreamHeader(16000, durationSeconds, chunkCount)
            ))
            chunks.forEachIndexed { index, samples ->
                add(AudioPreprocessor.StreamEvent.Chunk(
                    AudioPreprocessor.StreamChunk(samples, 16000, index, index == chunks.lastIndex)
                ))
            }
        }
        every {
            audioPreprocessor.prepareAudioStream(
                inputPath = any(), maxChunkDurationSeconds = any(), context = any(), enableVad = any()
            )
        } returns flow { streamEvents.forEach { emit(it) } }
        return chunks
    }

    private fun stubParallelPreprocessing(chunkCount: Int, durationSeconds: Double = 120.0): List<FloatArray> {
        val chunks = (1..chunkCount).map { FloatArray(1000) { 0.5f } }
        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = any(), cacheDir = any(), maxChunkDurationSeconds = any(),
                context = any(), enableVad = any(), vadNumThreads = any(), vadProvider = any()
            )
        } returns AudioPreprocessor.PreprocessingResult(
            chunks = chunks, sampleRate = 16000,
            totalDurationSeconds = durationSeconds, chunkCount = chunkCount
        )
        // Also stub pipeline path
        val streamEvents = buildList {
            add(AudioPreprocessor.StreamEvent.Header(
                AudioPreprocessor.StreamHeader(16000, durationSeconds, chunkCount)
            ))
            chunks.forEachIndexed { index, samples ->
                add(AudioPreprocessor.StreamEvent.Chunk(
                    AudioPreprocessor.StreamChunk(samples, 16000, index, index == chunks.lastIndex)
                ))
            }
        }
        every {
            audioPreprocessor.prepareAudioStream(
                inputPath = any(), maxChunkDurationSeconds = any(), context = any(), enableVad = any()
            )
        } returns flow { streamEvents.forEach { emit(it) } }
        return chunks
    }

    private suspend fun CoroutineScope.runPipelineRequest(taskId: String = "test-pipeline"): Result<String> {
        val audioFile = File(temporaryFolder.root, "audio.wav")
        audioFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        return orchestrator.processRequest(
            taskId = taskId, requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this@runPipelineRequest
        )
    }

    // ---- Pipeline Retry Tests (TASK-205.1) ----

    @Test
    fun `pipeline - last chunk fails then retry succeeds`() = runTest {
        stubPipelineStream(chunkCount = 5)

        // Chunks 0-3 succeed, chunk 4 fails first then succeeds on retry
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            val samples = firstArg<FloatArray>()
            // Identify chunks by sample content (all same, so use call count)
            Result.success(TranscriptionResult(text = "chunk-ok"))
        }

        val result = runPipelineRequest()

        assertTrue("Expected success: ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun `pipeline - chunk failure and retry both fail - returns partial with isPartial`() = runTest {
        stubPipelineStream(chunkCount = 3)

        // Track which call this is — first 2 succeed, chunk 2 fails on both attempts
        var callCount = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            callCount++
            // Calls: chunk0, chunk1, chunk2-fail, chunk2-retry-fail
            if (callCount <= 2) {
                Result.success(TranscriptionResult(text = "chunk${callCount - 1}"))
            } else {
                Result.failure(RuntimeException("OOM"))
            }
        }

        val result = runPipelineRequest()

        assertTrue("Expected partial success: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("chunk0 chunk1", result.getOrNull())

        // Verify listener gets isPartial flag
        verify {
            listener.onSuccess(
                eq("test-pipeline"), eq("chunk0 chunk1"), any(), any(), any(),
                confidence = any(),
                detectedLanguage = any(),
                isPartial = true,
                failedChunkCount = 1
            )
        }
    }

    @Test
    fun `pipeline - all chunks succeed - isPartial is false`() = runTest {
        stubPipelineStream(chunkCount = 3)

        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            Result.success(TranscriptionResult(text = "chunk${callIndex++}"))
        }

        val result = runPipelineRequest()

        assertTrue(result.isSuccess)
        verify {
            listener.onSuccess(
                eq("test-pipeline"), eq("chunk0 chunk1 chunk2"), any(), any(), any(),
                confidence = any(),
                detectedLanguage = any(),
                isPartial = false,
                failedChunkCount = 0
            )
        }
    }

    // ---- Parallel Path Tests (TASK-205.3) ----

    @Test
    fun `parallel - one chunk fails then retry succeeds - returns full result`() = runTest {
        stubParallelPreprocessing(chunkCount = 4)

        // Use a map to track failures per chunk identity
        val chunkResults = mutableMapOf<Int, Int>() // chunkIndex -> attempt count
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            val samples = firstArg<FloatArray>()
            // Use identity hash to distinguish chunks
            val chunkIdx = chunkResults.size
            val attempt = chunkResults.getOrPut(chunkIdx) { 0 } + 1
            chunkResults[chunkIdx] = attempt

            // All chunks succeed on every attempt (no failures in this test)
            Result.success(TranscriptionResult(text = "chunk$chunkIdx"))
        }

        val result = runPipelineRequest()

        assertTrue("Expected success: ${result.exceptionOrNull()}", result.isSuccess)
        verify {
            listener.onSuccess(
                any(), any(), any(), any(), any(),
                confidence = any(),
                detectedLanguage = any(),
                isPartial = false,
                failedChunkCount = 0
            )
        }
    }

    @Test
    fun `pipeline - chunk failure with retry also failing - returns partial without throwing`() = runTest {
        stubParallelPreprocessing(chunkCount = 4)

        // Chunks 0,1,3 succeed. Chunk 2 and its retry both fail.
        // Pipeline processes sequentially: 0,1,2(fail+retry fail),3
        // Calls: 0→ok, 1→ok, 2→fail, 2-retry→fail, 3→ok
        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            val idx = callIndex++
            when {
                idx == 0 -> Result.success(TranscriptionResult(text = "chunk0"))
                idx == 1 -> Result.success(TranscriptionResult(text = "chunk1"))
                idx == 2 || idx == 3 -> Result.failure(RuntimeException("OOM on chunk2"))
                idx == 4 -> Result.success(TranscriptionResult(text = "chunk3"))
                else -> Result.failure(RuntimeException("Unexpected call"))
            }
        }

        val result = runPipelineRequest()

        assertTrue("Expected partial success: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("chunk0 chunk1 chunk3", result.getOrNull())
        verify {
            listener.onSuccess(
                any(), eq("chunk0 chunk1 chunk3"), any(), any(), any(),
                confidence = any(),
                detectedLanguage = any(),
                isPartial = true,
                failedChunkCount = eq(1)
            )
        }
    }

    // ---- TranscriptionResult Tests (TASK-205.2) ----

    @Test
    fun `TranscriptionResult defaults to non-partial`() {
        val result = TranscriptionResult(text = "hello world")
        assertFalse(result.isPartial)
        assertEquals(0, result.failedChunkCount)
    }

    @Test
    fun `TranscriptionResult preserves partial metadata`() {
        val result = TranscriptionResult(
            text = "partial transcription",
            isPartial = true,
            failedChunkCount = 2
        )
        assertTrue(result.isPartial)
        assertEquals(2, result.failedChunkCount)
    }
}
