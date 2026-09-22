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
        every { preferencesManager.sherpaModelPath("whisper") } returns flowOf("/models/whisper")
        every { preferencesManager.progressiveTranscription } returns flowOf(true)
        every { preferencesManager.inferenceProvider } returns flowOf("auto")
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

    private fun stubVadPreprocessing(
        chunks: List<FloatArray>,
        rangesMs: List<Pair<Long, Long>>? = null,
    ) {
        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = audioFile.absolutePath,
                cacheDir = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                vadNumThreads = any(),
                vadProvider = any(),
                availableRamBytes = any(),
                maxHeapBytes = any())
        } returns PreprocessingResult(
            chunks = chunks,
            sampleRate = 16000,
            totalDurationSeconds = 30.0,
            chunkCount = chunks.size,
            isVadSegmented = true,
            chunkRangesMs = rangesMs ?: emptyList()
        )
    }

    // ---- Tests ----

    @Test
    fun `progressive segments all succeed`() = runTest {
        val chunk1 = FloatArray(100) { 1.0f }
        val chunk2 = FloatArray(100) { 2.0f }
        val chunk3 = FloatArray(100) { 3.0f }
        stubVadPreprocessing(listOf(chunk1, chunk2, chunk3))

        val results = listOf(
            Result.success(TranscriptionResult(text = "seg1")),
            Result.success(TranscriptionResult(text = "seg2")),
            Result.success(TranscriptionResult(text = "seg3"))
        )
        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            results[callIndex++]
        }

        val result = runProcessRequest(scope = this)

        assertTrue(result.isSuccess)
        assertEquals("seg1 seg2 seg3", result.getOrNull())

        verifyOrder {
            listener.onInterimResult(
                contentText = "seg1",
                bigText = "seg1",
                subText = "Segment 1/3",
                chunkIndex = 0,
                chunkText = "seg1",
                totalChunks = 3
            )
            listener.onInterimResult(
                contentText = "seg2",
                bigText = "seg2",
                subText = "Segment 2/3",
                chunkIndex = 1,
                chunkText = "seg2",
                totalChunks = 3
            )
            listener.onInterimResult(
                contentText = "seg3",
                bigText = "seg3",
                subText = "Segment 3/3",
                chunkIndex = 2,
                chunkText = "seg3",
                totalChunks = 3
            )
        }

        verify { listener.onStatusUpdate("Transcribing segment 1…") }
    }

    @Test
    fun `progressive segments with one failure`() = runTest {
        val chunk1 = FloatArray(100) { 1.0f }
        val chunk2 = FloatArray(100) { 2.0f }
        val chunk3 = FloatArray(100) { 3.0f }
        stubVadPreprocessing(listOf(chunk1, chunk2, chunk3))

        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            callIndex++
            when (callIndex) {
                1 -> Result.success(TranscriptionResult(text = "seg1"))
                2 -> Result.failure(RuntimeException("backend error"))
                else -> Result.success(TranscriptionResult(text = "seg3"))
            }
        }

        val result = runProcessRequest(scope = this)

        assertTrue(result.isSuccess)
        assertEquals("seg1 seg3", result.getOrNull())

        verify(exactly = 2) { listener.onInterimResult(any(), any(), any(), any(), any(), any()) }

        verify {
            listener.onSuccess(
                eq("vad-test"), eq("seg1 seg3"), any(), any(), any(),
                confidence = any(), detectedLanguage = any(),
                isPartial = true, failedChunkCount = eq(1)
            )
        }
    }

    @Test
    fun `progressive segments all fail`() = runTest {
        val chunk1 = FloatArray(100) { 1.0f }
        val chunk2 = FloatArray(100) { 2.0f }
        val chunk3 = FloatArray(100) { 3.0f }
        stubVadPreprocessing(listOf(chunk1, chunk2, chunk3))

        coEvery { backend.transcribeAudio(any(), any(), any()) } returns Result.failure(
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
    fun `progressive blank segment is not a failure`() = runTest {
        // TASK-622 / GH #96: a blank decode is silence by design, so a run
        // with one blank segment succeeds NON-partial with failedChunkCount 0
        // (the blank count lands in ProcessingContext, not the failure
        // counters). The three blank-segment end state is the next test.
        val chunk1 = FloatArray(100) { 1.0f }
        val chunk2 = FloatArray(100) { 2.0f }
        val chunk3 = FloatArray(100) { 3.0f }
        stubVadPreprocessing(listOf(chunk1, chunk2, chunk3))

        val results = listOf(
            Result.success(TranscriptionResult(text = "seg1")),
            Result.success(TranscriptionResult(text = "   ")),
            Result.success(TranscriptionResult(text = "seg3")),
        )
        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            results[callIndex++]
        }

        val result = runProcessRequest(scope = this)

        assertTrue(result.isSuccess)
        assertEquals("seg1 seg3", result.getOrNull())
        verify {
            listener.onSuccess(
                eq("vad-test"), eq("seg1 seg3"), any(), any(), any(),
                confidence = any(), detectedLanguage = any(),
                isPartial = false, failedChunkCount = eq(0)
            )
        }
    }

    @Test
    fun `progressive mixed failure and blank counts both in the message`() = runTest {
        // TASK-622: the composite terminal state names both counters.
        val chunk1 = FloatArray(100) { 1.0f }
        val chunk2 = FloatArray(100) { 2.0f }
        stubVadPreprocessing(listOf(chunk1, chunk2))

        val results = listOf(
            Result.failure(RuntimeException("backend error")),
            Result.success(TranscriptionResult(text = "  ")),
        )
        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            results[callIndex++]
        }

        val result = runProcessRequest(scope = this)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "Expected the mixed-counts message but got: ${error?.message}",
            error?.message?.contains("1 failed, 1 blank") == true)
    }

    @Test
    fun `progressive segments with blank results`() = runTest {
        val chunk1 = FloatArray(100) { 1.0f }
        val chunk2 = FloatArray(100) { 2.0f }
        val chunk3 = FloatArray(100) { 3.0f }
        stubVadPreprocessing(listOf(chunk1, chunk2, chunk3))

        val results = listOf(
            Result.success(TranscriptionResult(text = "   ")),
            Result.success(TranscriptionResult(text = "")),
            Result.success(TranscriptionResult(text = "\t\n"))
        )
        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            results[callIndex++]
        }

        val result = runProcessRequest(scope = this)

        assertTrue(result.isFailure)
        // TASK-622: every segment SUCCEEDED empty; the old message called
        // that a failure and misdirected the first debugging pass.
        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertTrue(
            "Expected the blank-specific message but got: ${error?.message}",
            error?.message?.contains("All 3 segments decoded blank") == true
        )

        verify(exactly = 0) { listener.onInterimResult(any(), any(), any()) }
    }

    @Test
    fun `progressive segments skips VAD when progressive disabled`() = runTest {
        every { preferencesManager.progressiveTranscription } returns flowOf(false)

        val chunk1 = FloatArray(100) { 1.0f }
        val chunk2 = FloatArray(100) { 2.0f }
        val chunk3 = FloatArray(100) { 3.0f }
        stubVadPreprocessing(listOf(chunk1, chunk2, chunk3))

        coEvery { backend.transcribeAudio(any(), any(), any()) } returns Result.success(TranscriptionResult(text = "text"))

        val result = runProcessRequest(scope = this)

        verify(exactly = 0) { listener.onInterimResult(any(), any(), any()) }

        assertTrue(result.isSuccess)
    }

    @Test
    fun `progressive segments records calibration`() = runTest {
        val chunk1 = FloatArray(100) { 1.0f }
        val chunk2 = FloatArray(100) { 2.0f }
        stubVadPreprocessing(listOf(chunk1, chunk2))

        val results = listOf(Result.success(TranscriptionResult(text = "first")), Result.success(TranscriptionResult(text = "second")))
        var callIndex = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
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

    // ---- GH #92: sentence-cue selection on the assembly path ----

    @Test
    fun `progressive segments prefer sentence cues when tokens are present`() = runTest {
        stubVadPreprocessing(
            listOf(FloatArray(100) { 1.0f }, FloatArray(100) { 2.0f }),
            rangesMs = listOf(0L to 6000L, 6000L to 12000L),
        )

        // Chunk-relative tokens; each of the two chunks returns the same decode,
        // so the second chunk's cues must carry its 6000ms offset.
        val tokens = listOf(
            TimedToken("▁Prima", 0, 900),
            TimedToken("frase.", 1000, 1900),
            TimedToken("▁Seconda", 2000, 2900),
            TimedToken("frase.", 3000, 3900),
        )
        coEvery { backend.transcribeAudio(any(), any(), any()) } returns Result.success(
            TranscriptionResult(text = "Prima frase. Seconda frase.", tokens = tokens))

        runProcessRequest(scope = this)

        val segmentsSlot = slot<List<TimedSegment>>()
        verify { listener.onSuccess(any(), any(), any(), any(), any(), segments = capture(segmentsSlot)) }
        assertEquals(
            listOf(
                TimedSegment(0, 1900, "Prima frase."),
                TimedSegment(2000, 3900, "Seconda frase."),
                TimedSegment(6000, 7900, "Prima frase."),
                TimedSegment(8000, 9900, "Seconda frase."),
            ),
            segmentsSlot.captured,
        )
    }

    @Test
    fun `progressive segments fall back to chunk cues without tokens`() = runTest {
        stubVadPreprocessing(
            listOf(FloatArray(100) { 1.0f }, FloatArray(100) { 2.0f }),
            rangesMs = listOf(0L to 6000L, 6000L to 12000L),
        )

        coEvery { backend.transcribeAudio(any(), any(), any()) } returns Result.success(
            TranscriptionResult(text = "plain chunk text"))

        runProcessRequest(scope = this)

        val segmentsSlot = slot<List<TimedSegment>>()
        verify { listener.onSuccess(any(), any(), any(), any(), any(), segments = capture(segmentsSlot)) }
        assertEquals(
            listOf(
                TimedSegment(0, 6000, "plain chunk text"),
                TimedSegment(6000, 12000, "plain chunk text"),
            ),
            segmentsSlot.captured,
        )
    }

    @Test
    fun `progressive segment timeout aborts the run at the first wedged segment`() = runTest {
        // TASK-606 F2: the VAD loop's old shape counted the failure and kept
        // going, one full generation ceiling per remaining segment.
        val chunk1 = FloatArray(100) { 1.0f }
        val chunk2 = FloatArray(100) { 2.0f }
        val chunk3 = FloatArray(100) { 3.0f }
        stubVadPreprocessing(listOf(chunk1, chunk2, chunk3))
        var calls = 0
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            calls++
            Result.failure(com.antivocale.app.manager.EngineWedgeTimeoutException("LiteRT audio generation timed out after 300s"))
        }

        val result = runProcessRequest(scope = this)

        assertTrue(result.isFailure)
        assertTrue("expected the wedge abort, got: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("wedged") == true)
        assertEquals("the run stops at the first timed-out segment", 1, calls)
    }
}
