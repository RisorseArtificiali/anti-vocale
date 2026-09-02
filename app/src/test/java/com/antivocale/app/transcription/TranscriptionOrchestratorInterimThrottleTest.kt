package com.antivocale.app.transcription

import com.antivocale.app.audio.AudioPreprocessor
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TASK-340 Fix 2b: interim Room writes are throttled to the 5s partial-save cadence.
 * Two interim updates within 5s produce ONE Room write; the final (logSuccess) write
 * always lands regardless of the throttle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorInterimThrottleTest : TranscriptionOrchestratorTestBase() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var backend: TranscriptionBackend

    /** Fake throttle clock under direct test control (orchestrator.throttleClock). */
    private var fakeNowMs = 1_000_000L

    @Before
    fun setUp() {
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

        orchestrator.throttleClock = { fakeNowMs }
    }

    @After
    fun tearDown() {
        orchestrator.throttleClock = System::currentTimeMillis
    }

    private fun stubMultiChunkStream(chunkCount: Int) {
        val events = buildList {
            add(AudioPreprocessor.StreamEvent.Header(AudioPreprocessor.StreamHeader(
                sampleRate = 16000, totalDurationSeconds = 120.0, expectedChunkCount = chunkCount)))
            repeat(chunkCount) { index ->
                add(AudioPreprocessor.StreamEvent.Chunk(AudioPreprocessor.StreamChunk(
                    samples = FloatArray(100), sampleRate = 16000,
                    chunkIndex = index, isLast = index == chunkCount - 1)))
            }
        }
        every {
            audioPreprocessor.prepareAudioStream(
                inputPath = any(), maxChunkDurationSeconds = any(),
                context = any(), enableVad = any(), availableRamBytes = any(), maxHeapBytes = any())
        } returns kotlinx.coroutines.flow.flow { events.forEach { emit(it) } }
    }

    private suspend fun runPipelineRequest(taskId: String = "throttle-task"): Result<String> {
        val audioFile = File(temporaryFolder.root, "audio.wav")
        audioFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        return orchestrator.processRequest(
            taskId = taskId, requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
    }

    @Test
    fun `interim updates within 5s produce one Room write`() = runTest {
        stubMultiChunkStream(chunkCount = 3)
        coEvery { logDao.getByTaskId("throttle-task") } returns com.antivocale.app.data.local.LogEntity(
            id = "1", timestamp = 0L, taskId = "throttle-task",
            type = "AUDIO", status = "PROCESSING", prompt = "")

        val texts = listOf("first", "second", "third")
        var i = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            // All three chunks land inside the same frozen 5s window.
            Result.success(TranscriptionResult(text = texts[i++]))
        }

        val result = runPipelineRequest()
        assertTrue(result.isSuccess)

        // TASK-390: interim writes go through the column-scoped DAO call.
        val interimTexts = mutableListOf<String>()
        coVerify(atLeast = 0) { logDao.updateInterimResult("throttle-task", capture(interimTexts), any()) }
        // First interim write lands immediately; the two within the interval are skipped.
        assertEquals(listOf("first"), interimTexts)
    }

    @Test
    fun `final write always lands despite throttle`() = runTest {
        stubMultiChunkStream(chunkCount = 3)
        coEvery { logDao.getByTaskId("throttle-task") } returns com.antivocale.app.data.local.LogEntity(
            id = "1", timestamp = 0L, taskId = "throttle-task",
            type = "AUDIO", status = "PROCESSING", prompt = "")

        val texts = listOf("first", "second", "third")
        var i = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            Result.success(TranscriptionResult(text = texts[i++]))
        }

        val result = runPipelineRequest()
        assertTrue(result.isSuccess)

        val interimTexts = mutableListOf<String>()
        coVerify(atLeast = 0) { logDao.updateInterimResult("throttle-task", capture(interimTexts), any()) }
        val finals = mutableListOf<com.antivocale.app.data.local.LogEntity>()
        coVerify(atLeast = 0) { logDao.update(capture(finals)) }
        // logSuccess is not throttled: the full text is persisted even when all interim writes but the first were skipped.
        assertTrue(finals.any { it.result == "first second third" && it.status == "SUCCESS" })
    }

    @Test
    fun `interim writes resume after the 5s interval elapses`() = runTest {
        stubMultiChunkStream(chunkCount = 3)
        coEvery { logDao.getByTaskId("throttle-task") } returns com.antivocale.app.data.local.LogEntity(
            id = "1", timestamp = 0L, taskId = "throttle-task",
            type = "AUDIO", status = "PROCESSING", prompt = "")

        val texts = listOf("first", "second", "third")
        var i = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            fakeNowMs += 6000 // each chunk transcription takes 6s of wall time
            Result.success(TranscriptionResult(text = texts[i++]))
        }

        val result = runPipelineRequest()
        assertTrue(result.isSuccess)

        val interimTexts = mutableListOf<String>()
        coVerify(atLeast = 0) { logDao.updateInterimResult("throttle-task", capture(interimTexts), any()) }
        assertEquals(listOf("first", "first second", "first second third"), interimTexts)
    }
}
