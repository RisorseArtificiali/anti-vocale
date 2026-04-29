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
        every { preferencesManager.whisperModelPath } returns flowOf("/models/whisper")
        every { preferencesManager.inferenceProvider } returns flowOf("auto")
        every { preferencesManager.progressiveTranscription } returns flowOf(true)
        coEvery { transcriptionCalibrator.getEstimate(any(), any()) } returns null
    }

    // ---- Helpers ----

    private fun stubMultiChunkStream(
        chunkCount: Int,
        durationSeconds: Double = 120.0
    ): List<FloatArray> {
        val chunks = (1..chunkCount).map { FloatArray(1000) { 0.5f } }

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
                enableVad = any()
            )
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
            Result.success(chunkTexts[callIndex++])
        }

        val result = runPipelineRequest()

        assertTrue("Expected success but got: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("first second third", result.getOrNull())

        verify(ordering = io.mockk.Ordering.ORDERED) {
            listener.onInterimResult(
                contentText = "first",
                bigText = "first",
                subText = "Chunk 1/3"
            )
            listener.onInterimResult(
                contentText = "second",
                bigText = "second",
                subText = "Chunk 2/3"
            )
            listener.onInterimResult(
                contentText = "third",
                bigText = "third",
                subText = "Chunk 3/3"
            )
        }
    }

    @Test
    fun `pipeline with progressive ON updates interim result in database`() = runTest {
        stubMultiChunkStream(chunkCount = 2)

        val entity = com.antivocale.app.data.local.LogEntity(
            id = "1", timestamp = 0L, taskId = "test-pipeline",
            type = "AUDIO", status = "PENDING", prompt = ""
        )
        coEvery { logDao.getByTaskId("test-pipeline") } returns entity

        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            Result.success(listOf("hello", "world")[callIndex++])
        }

        val result = runPipelineRequest()

        assertTrue(result.isSuccess)
        coVerify(atLeast = 1) { logDao.update(any()) }
    }

    @Test
    fun `pipeline with progressive ON skips blank chunks in interim results`() = runTest {
        stubMultiChunkStream(chunkCount = 3)

        val chunkTexts = listOf("first", "   ", "third")
        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            Result.success(chunkTexts[callIndex++])
        }

        val result = runPipelineRequest()

        assertTrue(result.isSuccess)
        assertEquals("first third", result.getOrNull())

        verify(exactly = 1) {
            listener.onInterimResult(
                contentText = "first",
                bigText = "first",
                subText = "Chunk 1/3"
            )
        }
        verify(exactly = 1) {
            listener.onInterimResult(
                contentText = "third",
                bigText = "third",
                subText = "Chunk 3/3"
            )
        }
    }

    @Test
    fun `pipeline with progressive ON handles chunk failures gracefully`() = runTest {
        stubMultiChunkStream(chunkCount = 3)

        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            val idx = callIndex++
            if (idx == 1) Result.failure(RuntimeException("Chunk failed"))
            else Result.success(listOf("first", "second", "third")[idx])
        }

        val result = runPipelineRequest()

        assertTrue("Expected success with partial results", result.isSuccess)
        assertEquals("first third", result.getOrNull())

        verify(exactly = 1) {
            listener.onInterimResult(
                contentText = "first",
                bigText = "first",
                subText = "Chunk 1/3"
            )
        }
        verify(exactly = 1) {
            listener.onInterimResult(
                contentText = "third",
                bigText = "third",
                subText = "Chunk 3/3"
            )
        }
    }

    @Test
    fun `pipeline with progressive OFF does not emit interim results`() = runTest {
        every { preferencesManager.progressiveTranscription } returns flowOf(false)
        stubMultiChunkStream(chunkCount = 3)

        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            Result.success(listOf("a", "b", "c")[callIndex++])
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
            Result.success(chunkTexts[callIndex++])
        }

        val result = runPipelineRequest()

        assertTrue(result.isSuccess)
        assertEquals("Hello world from pipeline", result.getOrNull())
        verify { listener.onSuccess(eq("test-pipeline"), eq("Hello world from pipeline"), any(), any(), any()) }
    }
}
