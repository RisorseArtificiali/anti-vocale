package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.audio.AudioPreprocessor
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorPipelineProgressiveTest : TranscriptionOrchestratorTestBase() {

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
        every { preferencesManager.progressiveTranscription } returns flowOf(true)
        coEvery { transcriptionCalibrator.getEstimate(any(), any()) } returns null
    }

    // ---- Helpers ----

    private fun stubMultiChunkStream(
        chunkCount: Int,
        durationSeconds: Double = 120.0
    ): List<FloatArray> {
        val chunks = (1..chunkCount).map { idx -> FloatArray(1000) { idx.toFloat() } }

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

    private suspend fun CoroutineScope.runPipelineRequest(
        taskId: String = "test-pipeline"
    ): Result<String> {
        val audioFile = File(temporaryFolder.root, "audio.wav")
        audioFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        return orchestrator.processRequest(
            taskId = taskId,
            requestType = "audio",
            prompt = "",
            filePath = audioFile.absolutePath,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = mockk(relaxed = true),
            cacheDir = temporaryFolder.root,
            listener = listener,
            coroutineScope = this
        )
    }

    // ---- Progressive pipeline tests ----

    @Test
    fun `pipeline with progressive ON emits interim results after each chunk`() = runTest {
        stubMultiChunkStream(chunkCount = 3)

        val chunkTexts = listOf("first", "second", "third")
        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            Result.success(TranscriptionResult(text = chunkTexts[callIndex++]))
        }

        val result = runPipelineRequest()

        assertTrue("Expected success but got: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("first second third", result.getOrNull())

        verify(ordering = io.mockk.Ordering.ORDERED) {
            listener.onInterimResult(
                contentText = "first",
                bigText = "first",
                subText = "Chunk 1/3",
                chunkIndex = 0,
                chunkText = "first",
                totalChunks = 3
            )
            listener.onInterimResult(
                contentText = "second",
                bigText = "second",
                subText = "Chunk 2/3",
                chunkIndex = 1,
                chunkText = "second",
                totalChunks = 3
            )
            listener.onInterimResult(
                contentText = "third",
                bigText = "third",
                subText = "Chunk 3/3",
                chunkIndex = 2,
                chunkText = "third",
                totalChunks = 3
            )
        }
    }

    @Test
    fun `pipeline with progressive ON updates interim result in database`() = runTest {
        stubMultiChunkStream(chunkCount = 2)

        val entity = com.antivocale.app.data.local.LogEntity(
            id = "1", timestamp = 0L, taskId = "test-pipeline",
            type = "AUDIO", status = "PROCESSING", prompt = ""
        )
        coEvery { logDao.getByTaskId("test-pipeline") } returns entity

        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            Result.success(TranscriptionResult(text = listOf("hello", "world")[callIndex++]))
        }

        val result = runPipelineRequest()

        assertTrue(result.isSuccess)
        coVerify(atLeast = 1) { logDao.updateInterimResult("test-pipeline", any(), any()) }
    }

    @Test
    fun `pipeline with progressive ON skips blank chunks in interim results`() = runTest {
        stubMultiChunkStream(chunkCount = 3)

        val chunkTexts = listOf("first", "   ", "third")
        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            Result.success(TranscriptionResult(text = chunkTexts[callIndex++]))
        }

        val result = runPipelineRequest()

        assertTrue(result.isSuccess)
        assertEquals("first third", result.getOrNull())

        verify(exactly = 1) {
            listener.onInterimResult(
                contentText = "first",
                bigText = "first",
                subText = "Chunk 1/3",
                chunkIndex = 0,
                chunkText = "first",
                totalChunks = 3
            )
        }
        verify(exactly = 1) {
            listener.onInterimResult(
                contentText = "third",
                bigText = "third",
                subText = "Chunk 3/3",
                chunkIndex = 2,
                chunkText = "third",
                totalChunks = 3
            )
        }
    }

    @Test
    fun `pipeline with progressive ON handles chunk failures gracefully`() = runTest {
        stubMultiChunkStream(chunkCount = 3)

        var callIndex = 0
        val failedSampleRef = mutableSetOf<FloatArray>()
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            val samples = firstArg<FloatArray>()
            // Fail if we've seen this exact samples array fail before (retry detection)
            if (failedSampleRef.any { it.contentEquals(samples) }) {
                Result.failure(RuntimeException("Chunk retry failed"))
            } else {
                val idx = callIndex++
                if (idx == 1) {
                    failedSampleRef.add(samples)
                    Result.failure(RuntimeException("Chunk failed"))
                } else {
                    Result.success(TranscriptionResult(text = listOf("first", "second", "third")[idx]))
                }
            }
        }

        val result = runPipelineRequest()

        assertTrue("Expected success with partial results", result.isSuccess)
        assertEquals("first third", result.getOrNull())

        verify(exactly = 1) {
            listener.onInterimResult(
                contentText = "first",
                bigText = "first",
                subText = "Chunk 1/3",
                chunkIndex = 0,
                chunkText = "first",
                totalChunks = 3
            )
        }
        verify(exactly = 1) {
            listener.onInterimResult(
                contentText = "third",
                bigText = "third",
                subText = "Chunk 3/3",
                chunkIndex = 2,
                chunkText = "third",
                totalChunks = 3
            )
        }
    }

    @Test
    fun `pipeline with progressive OFF does not emit interim results`() = runTest {
        every { preferencesManager.progressiveTranscription } returns flowOf(false)
        stubMultiChunkStream(chunkCount = 3)

        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            Result.success(TranscriptionResult(text = listOf("a", "b", "c")[callIndex++]))
        }

        val result = runPipelineRequest()

        assertTrue(result.isSuccess)
        assertEquals("a b c", result.getOrNull())
        verify(exactly = 0) { listener.onInterimResult(any(), any(), any()) }
    }

    @Test
    fun `pipeline with progressive ON returns correct final combined text`() = runTest {
        stubMultiChunkStream(chunkCount = 4)

        val chunkTexts = listOf("Hello", "world", "from", "pipeline")
        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            Result.success(TranscriptionResult(text = chunkTexts[callIndex++]))
        }

        val result = runPipelineRequest()

        assertTrue(result.isSuccess)
        assertEquals("Hello world from pipeline", result.getOrNull())
        verify { listener.onSuccess(eq("test-pipeline"), eq("Hello world from pipeline"), any(), any(), any(), segments = any()) }
    }

    // ---- TASK-568: mid-stream failure preserves the transcribed text ----

    /**
     * The stream dies after two decoded chunks (progressive OFF: no interim
     * write ever ran). The catch must persist the accumulated text and the
     * decoded-at-failure seconds before failing, or the ERROR row would show
     * zero output exactly like the v1.5.x reports.
     */
    @Test
    fun `mid-stream failure with progressive OFF persists accumulated text and decoded seconds`() = runTest {
        every { preferencesManager.progressiveTranscription } returns flowOf(false)

        // Header + 2 chunks, then the decode stream itself dies.
        every {
            audioPreprocessor.prepareAudioStream(
                inputPath = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                availableRamBytes = any(),
                maxHeapBytes = any())
        } returns flow {
            emit(AudioPreprocessor.StreamEvent.Header(
                AudioPreprocessor.StreamHeader(
                    sampleRate = 16000,
                    totalDurationSeconds = 300.0,
                    expectedChunkCount = 6)))
            emit(AudioPreprocessor.StreamEvent.Chunk(
                AudioPreprocessor.StreamChunk(
                    samples = FloatArray(1000), sampleRate = 16000, chunkIndex = 0, isLast = false)))
            emit(AudioPreprocessor.StreamEvent.Chunk(
                AudioPreprocessor.StreamChunk(
                    samples = FloatArray(1000), sampleRate = 16000, chunkIndex = 1, isLast = false)))
            throw IllegalStateException("decode died")
        }

        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            Result.success(TranscriptionResult(text = listOf("first", "second")[callIndex++]))
        }

        val result = runPipelineRequest()

        assertTrue(result.isFailure)
        // The FINAL unthrottled persist: everything transcribed before death.
        coVerify(exactly = 1) { logDao.updateInterimResult("test-pipeline", "first second", true) }
        // 2000 samples at 16 kHz = 0.125 s decoded at the failure point.
        coVerify(exactly = 1) { logDao.updateFailureDecodedMs("test-pipeline", 125L) }
        // TASK-570: the structured diagnostics ride the same failure (the
        // JSON is codec-tested separately; here we pin that it was written
        // with the chunk coverage and durations of this run).
        coVerify(exactly = 1) {
            logDao.updateFailureContext("test-pipeline", match { json ->
                json!!.contains("\"processedChunks\":2") && json.contains("\"decodedSeconds\":0.125")
            })
        }
        // The failure carries the decoded-of-total context for the notification.
        val err = result.exceptionOrNull()
        assertTrue("expected PipelineFailure, got $err",
            err is TranscriptionOrchestrator.PipelineFailure)
        err as TranscriptionOrchestrator.PipelineFailure
        assertEquals(0.125, err.decodedSeconds, 1e-9)
        assertEquals(300.0, err.totalSeconds, 1e-9)
    }

    /**
     * TASK-568: logError must PRESERVE the decoded-at-failure ms the catch
     * wrote (updateFailureDecodedMs) instead of clobbering it with an
     * elapsed value; the failure site passes no duration, so the row keeps
     * whatever the entity already carried.
     */
    @Test
    fun `failure writeback preserves the row's decoded-at-failure duration`() = runTest {
        every { preferencesManager.progressiveTranscription } returns flowOf(false)

        val entity = com.antivocale.app.data.local.LogEntity(
            id = "1", timestamp = 0L, taskId = "test-pipeline",
            type = "AUDIO", status = "PROCESSING", prompt = "",
            durationMs = 125L)
        coEvery { logDao.getByTaskId("test-pipeline") } returns entity

        every {
            audioPreprocessor.prepareAudioStream(
                inputPath = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                availableRamBytes = any(),
                maxHeapBytes = any())
        } returns flow {
            emit(AudioPreprocessor.StreamEvent.Header(
                AudioPreprocessor.StreamHeader(
                    sampleRate = 16000, totalDurationSeconds = 60.0, expectedChunkCount = 1)))
            emit(AudioPreprocessor.StreamEvent.Chunk(
                AudioPreprocessor.StreamChunk(
                    samples = FloatArray(1000), sampleRate = 16000, chunkIndex = 0, isLast = true)))
            throw IllegalStateException("decode died")
        }
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            Result.success(TranscriptionResult(text = "partial"))
        }

        val result = runPipelineRequest()

        assertTrue(result.isFailure)
        val slot = slot<com.antivocale.app.data.local.LogEntity>()
        coVerify(atLeast = 1) { logDao.update(capture(slot)) }
        val errorWrite = slot.captured.status == "ERROR"
        assertTrue("no ERROR write captured", errorWrite)
        assertEquals("decoded-at-failure ms must survive the error writeback",
            125L, slot.captured.durationMs)
    }

    /** A failure with NOTHING transcribed must not write an empty result. */
    @Test
    fun `mid-stream failure with no accumulated text writes no interim result`() = runTest {
        every { preferencesManager.progressiveTranscription } returns flowOf(false)

        every {
            audioPreprocessor.prepareAudioStream(
                inputPath = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                availableRamBytes = any(),
                maxHeapBytes = any())
        } returns flow {
            emit(AudioPreprocessor.StreamEvent.Header(
                AudioPreprocessor.StreamHeader(
                    sampleRate = 16000,
                    totalDurationSeconds = 60.0,
                    expectedChunkCount = 2)))
            throw IllegalStateException("decode died before any chunk")
        }

        val result = runPipelineRequest()

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { logDao.updateInterimResult(any(), any(), any()) }
        coVerify(exactly = 0) { logDao.updateFailureDecodedMs(any(), any()) }
    }
}
