package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.audio.AudioPreprocessor.PreprocessingResult
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorVadTest : TranscriptionOrchestratorTestBase() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var backend: TranscriptionBackend
    private lateinit var audioFile: File

    override fun baseSetUp() {
        super.baseSetUp()

        backend = stubWhisperBackend()

        every { preferencesManager.transcriptionBackend } returns flowOf("whisper")
        every { preferencesManager.vadEnabled } returns flowOf(true)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.defaultPrompt } returns flowOf("")
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { preferencesManager.whisperModelPath } returns flowOf("/models/whisper")
        every { preferencesManager.progressiveTranscription } returns flowOf(true)
        every { preferencesManager.transcriptionLanguage } returns flowOf("it")

        audioFile = temporaryFolder.newFile("test_audio.wav")
        audioFile.writeBytes(ByteArray(1024))
    }

    // ---- Helper ----

    private suspend fun runProcessRequest(
        taskId: String = "vad-test",
        scope: CoroutineScope
    ) = orchestrator.processRequest(
        taskId = taskId,
        requestType = "audio",
        prompt = "",
        filePath = audioFile.absolutePath,
        source = null,
        sourcePackage = null,
        queuePosition = 1,
        queueTotal = 1,
        context = mockk(relaxed = true),
        cacheDir = temporaryFolder.newFolder("cache"),
        listener = listener,
        coroutineScope = scope
    )

    private fun stubVadPreprocessing(chunks: List<ByteArray>) {
        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = audioFile.absolutePath,
                cacheDir = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                vadNumThreads = any()
            )
        } returns PreprocessingResult(
            chunks = chunks,
            totalDurationSeconds = 30.0,
            chunkCount = chunks.size,
            isVadSegmented = true
        )
    }

    // ---- Tests ----

    @Test
    fun `progressive segments all succeed`() = runTest {
        val chunk1 = ByteArray(100) { 1 }
        val chunk2 = ByteArray(100) { 2 }
        val chunk3 = ByteArray(100) { 3 }
        stubVadPreprocessing(listOf(chunk1, chunk2, chunk3))

        val results = listOf(
            Result.success("seg1"),
            Result.success("seg2"),
            Result.success("seg3")
        )
        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any()) } answers {
            results[callIndex++]
        }

        val result = runProcessRequest(scope = this)

        assertTrue(result.isSuccess)
        assertEquals("seg1 seg2 seg3", result.getOrNull())

        verifyOrder {
            listener.onInterimResult(
                contentText = "seg1",
                bigText = "seg1",
                subText = "Segment 1/3"
            )
            listener.onInterimResult(
                contentText = "seg2",
                bigText = "seg2",
                subText = "Segment 2/3"
            )
            listener.onInterimResult(
                contentText = "seg3",
                bigText = "seg3",
                subText = "Segment 3/3"
            )
        }

        verify { listener.onStatusUpdate("Transcribing segment 1…") }
    }

    @Test
    fun `progressive segments with one failure`() = runTest {
        val chunk1 = ByteArray(100) { 1 }
        val chunk2 = ByteArray(100) { 2 }
        val chunk3 = ByteArray(100) { 3 }
        stubVadPreprocessing(listOf(chunk1, chunk2, chunk3))

        val results = listOf(
            Result.success("seg1"),
            Result.failure<Nothing>(RuntimeException("backend error")),
            Result.success("seg3")
        )
        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any()) } answers {
            results[callIndex++]
        }

        val result = runProcessRequest(scope = this)

        assertTrue(result.isSuccess)
        assertEquals("seg1 seg3", result.getOrNull())

        verify(exactly = 2) { listener.onInterimResult(any(), any(), any()) }

        verify { listener.onSuccess(eq("vad-test"), eq("seg1 seg3"), any(), any(), any()) }
    }

    @Test
    fun `progressive segments all fail`() = runTest {
        val chunk1 = ByteArray(100) { 1 }
        val chunk2 = ByteArray(100) { 2 }
        val chunk3 = ByteArray(100) { 3 }
        stubVadPreprocessing(listOf(chunk1, chunk2, chunk3))

        coEvery { backend.transcribeAudio(any(), any()) } returns Result.failure(
            RuntimeException("backend error")
        )

        val result = runProcessRequest(scope = this)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertTrue(
            "Expected 'All 3 segments failed' but got: ${error?.message}",
            error?.message?.contains("All 3 segments failed") == true
        )

        verify(exactly = 0) { listener.onInterimResult(any(), any(), any()) }
    }

    @Test
    fun `progressive segments with blank results`() = runTest {
        val chunk1 = ByteArray(100) { 1 }
        val chunk2 = ByteArray(100) { 2 }
        val chunk3 = ByteArray(100) { 3 }
        stubVadPreprocessing(listOf(chunk1, chunk2, chunk3))

        val results = listOf(
            Result.success("   "),
            Result.success(""),
            Result.success("\t\n")
        )
        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any()) } answers {
            results[callIndex++]
        }

        val result = runProcessRequest(scope = this)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertTrue(
            "Expected 'All 3 segments failed' but got: ${error?.message}",
            error?.message?.contains("All 3 segments failed") == true
        )

        verify(exactly = 0) { listener.onInterimResult(any(), any(), any()) }
    }

    @Test
    fun `progressive segments skips VAD when progressive disabled`() = runTest {
        every { preferencesManager.progressiveTranscription } returns flowOf(false)

        val chunk1 = ByteArray(100) { 1 }
        val chunk2 = ByteArray(100) { 2 }
        val chunk3 = ByteArray(100) { 3 }
        stubVadPreprocessing(listOf(chunk1, chunk2, chunk3))

        coEvery { backend.transcribeAudio(any(), any()) } returns Result.success("text")

        val result = runProcessRequest(scope = this)

        verify(exactly = 0) { listener.onInterimResult(any(), any(), any()) }

        assertTrue(result.isSuccess)
    }

    @Test
    fun `progressive segments records calibration`() = runTest {
        val chunk1 = ByteArray(100) { 1 }
        val chunk2 = ByteArray(100) { 2 }
        stubVadPreprocessing(listOf(chunk1, chunk2))

        val results = listOf(Result.success("first"), Result.success("second"))
        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any()) } answers {
            results[callIndex++]
        }

        runProcessRequest(scope = this)

        coVerify {
            transcriptionCalibrator.record(
                backendId = "whisper",
                modelPath = "/models/whisper",
                displayName = "Whisper Whisper",
                audioDurationSeconds = any(),
                processingTimeMs = any()
            )
        }
    }
}
