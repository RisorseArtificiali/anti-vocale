package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.transcription.diarization.DiarizedSegment
import com.antivocale.app.transcription.diarization.SpeakerLabelTail
import com.antivocale.app.transcription.diarization.WindowedDiarization
import com.antivocale.app.transcription.diarization.WindowedDiarizationStats
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.ref.WeakReference

/**
 * TASK-1060.4: which speaker-pass arm a run takes. A pipelined run whose
 * audio fits the whole-file memory budget keeps the whole-file pass over its
 * retained chunks; a run over the budget (the TASK-597 metadata gate, or the
 * TASK-728 runtime guard when the duration is unreadable) takes the windowed
 * pass, which re-decodes the file with the run's exact decode length. The
 * native diarization behind each arm is faked through the orchestrator's
 * seams, so the delivered cues carry the labels each arm produced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorSpeakerPassTest : TranscriptionOrchestratorTestBase() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var backend: TranscriptionBackend
    private var deliveredCues: List<TimedSegment> = emptyList()
    private var wholeFileCalls = 0
    private val windowedCalls = mutableListOf<Pair<String, Long?>>()

    private val helloTokens = listOf(
        TimedToken("▁Hello", 0, 300),
        TimedToken("▁world", 350, 600),
        TimedToken("▁today", 650, 900),
    )

    override fun baseSetUp() {
        super.baseSetUp()
        backend = stubWhisperBackend()
        stubDefaultWhisperPreferences()
        every { preferencesManager.speakerLabelsEnabled } returns flowOf(true)
        every { preferencesManager.speakerIdEnabled } returns flowOf(false)
        coEvery { backend.transcribeAudio(any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = "Hello world today", tokens = helloTokens))
        every { audioPreprocessor.mergeAndResample(any(), any()) } answers {
            firstArg<MutableList<FloatArray>>().first() to secondArg()
        }
        every {
            listener.onSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            @Suppress("UNCHECKED_CAST")
            deliveredCues = args[10] as List<TimedSegment>
        }
        orchestrator.ensureDiarizationModels = { Result.success(Unit) }
        orchestrator.wholeFileDiarization = { _, _, _, _ ->
            wholeFileCalls++
            listOf(DiarizedSegment(0f, 1f, 0, null))
        }
        orchestrator.windowedDiarization = { _, filePath, totalSamples, _, _ ->
            windowedCalls += filePath to totalSamples
            WindowedDiarization(listOf(DiarizedSegment(0f, 1f, 1, null)), emptyMap(), WindowedDiarizationStats(0, 0, 0, 0, 0.0, 0, 0.0))
        }
    }

    private fun stubStream(samples: FloatArray) {
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
                    sampleRate = 16000, totalDurationSeconds = 1.0, expectedChunkCount = 1)))
            emit(AudioPreprocessor.StreamEvent.Chunk(
                AudioPreprocessor.StreamChunk(
                    samples = samples, sampleRate = 16000, chunkIndex = 0, isLast = true)))
        }
    }

    private suspend fun CoroutineScope.run(): Pair<Result<String>, String> {
        val audioFile = temporaryFolder.newFile("audio.ogg")
        val result = orchestrator.processRequest(
            taskId = "speakers", requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk<Context>(relaxed = true),
            cacheDir = temporaryFolder.root,
            listener = listener,
            coroutineScope = this,
        )
        return result to audioFile.absolutePath
    }

    @Test
    fun `an in-budget run labels its cues through the whole-file pass`() = runTest {
        every { audioPreprocessor.getAudioDuration(any()) } returns 1.0
        stubStream(FloatArray(16000))

        val (result, _) = run()

        assertTrue(result.isSuccess)
        assertEquals(1, wholeFileCalls)
        assertTrue(windowedCalls.isEmpty())
        assertEquals(listOf(0), deliveredCues.map { it.speaker })
    }

    @Test
    fun `an over-budget run labels its cues through the windowed pass with the exact decode length`() = runTest {
        // Enrico's case: two hours, far above any whole-file budget.
        every { audioPreprocessor.getAudioDuration(any()) } returns 2 * 3600.0
        stubStream(FloatArray(16000))

        val (result, filePath) = run()

        assertTrue(result.isSuccess)
        assertEquals(0, wholeFileCalls)
        assertEquals(listOf(filePath to 16000L), windowedCalls)
        assertEquals(listOf(1), deliveredCues.map { it.speaker })
    }

    @Test
    fun `a run whose retention crosses the budget mid-stream switches to the windowed pass`() = runTest {
        // No readable duration: the metadata gate fails open and the runtime
        // guard trips on the first chunk, over the unreadable-memory budget
        // of 600 s worth of PCM across three copies (~13 MB).
        every { audioPreprocessor.getAudioDuration(any()) } throws IllegalStateException("no metadata")
        val oversized = FloatArray(3_500_000)
        stubStream(oversized)

        val (result, filePath) = run()

        assertTrue(result.isSuccess)
        assertEquals(0, wholeFileCalls)
        assertEquals(listOf(filePath to oversized.size.toLong()), windowedCalls)
        assertEquals(listOf(1), deliveredCues.map { it.speaker })
    }

    @Test
    fun `the retention guard releases the chunks it retained before the trip`() = runTest {
        // No readable duration: chunks 0 and 1 (4 MB together) fit the
        // ~13 MB unreadable-memory budget, chunk 2 (12 MB) trips the guard.
        // Once chunk 2 has moved on as the previous neighbor, nothing may
        // still pin chunk 1. Chunk 0 stays a small sacrifice: the first
        // chunk's processing suspends, and the frame that resumes it stays
        // on the stack for the rest of the stream, pinning that chunk.
        every { audioPreprocessor.getAudioDuration(any()) } throws IllegalStateException("no metadata")
        var tracked: WeakReference<FloatArray>? = null
        var releasedAfterTrip = false
        every {
            audioPreprocessor.prepareAudioStream(
                inputPath = any(), maxChunkDurationSeconds = any(), context = any(),
                enableVad = any(), availableRamBytes = any(), maxHeapBytes = any())
        } returns flow {
            emit(AudioPreprocessor.StreamEvent.Header(
                AudioPreprocessor.StreamHeader(
                    sampleRate = 16000, totalDurationSeconds = 0.0, expectedChunkCount = 4)))
            emit(chunk(FloatArray(16000), index = 0, isLast = false))
            emit(chunk(trackedSamples(1_000_000) { tracked = it }, index = 1, isLast = false))
            emit(chunk(FloatArray(3_000_000), index = 2, isLast = false))
            // The backend mock records its sample arguments; only the
            // orchestrator's own references are under test.
            clearMocks(backend, answers = false, recordedCalls = true, childMocks = false,
                verificationMarks = false, exclusionRules = false)
            releasedAfterTrip = collected(checkNotNull(tracked))
            emit(chunk(FloatArray(16000), index = 3, isLast = true))
        }

        val (result, _) = run()

        assertTrue(result.isSuccess)
        assertEquals(0, wholeFileCalls)
        assertEquals(1, windowedCalls.size)
        assertTrue("the tripped guard must release the retained chunks", releasedAfterTrip)
    }

    private fun chunk(samples: FloatArray, index: Int, isLast: Boolean) =
        AudioPreprocessor.StreamEvent.Chunk(
            AudioPreprocessor.StreamChunk(
                samples = samples, sampleRate = 16000, chunkIndex = index, isLast = isLast))

    /** Allocates outside the caller's frame so the weak reference is the only test-side handle. */
    private fun trackedSamples(size: Int, track: (WeakReference<FloatArray>) -> Unit) =
        FloatArray(size).also { track(WeakReference(it)) }

    private fun collected(ref: WeakReference<*>): Boolean {
        repeat(20) {
            if (ref.get() == null) return true
            System.gc()
            Thread.sleep(10)
        }
        return ref.get() == null
    }

    @Test
    fun `a failed windowed pass delivers the unlabeled transcript`() = runTest {
        every { audioPreprocessor.getAudioDuration(any()) } returns 2 * 3600.0
        stubStream(FloatArray(16000))
        orchestrator.windowedDiarization = { _, _, _, _, _ -> throw IllegalStateException("native failure") }

        val (result, _) = run()

        assertEquals("Hello world today", result.getOrNull())
        assertTrue(deliveredCues.isNotEmpty())
        assertTrue(deliveredCues.all { it.speaker == null })
    }

    @Test
    fun `the shared tail splits a straddling cue at the word boundary and names its clusters`() {
        val tokens = listOf(
            TimedToken("▁Hello", 0, 300),
            TimedToken("▁world", 350, 600),
            TimedToken("▁good", 650, 900),
            TimedToken("▁day", 950, 1400),
        )
        val transcription = TranscriptionResult(
            text = "Hello world good day",
            segments = listOf(TimedSegment(0, 1400, "Hello world good day", tokens = tokens)),
            processing = ProcessingContext(decodePath = "pipeline"),
        )
        // The second voice takes over at 0.8 s, nearest the "good"/"day" gap.
        val segments = listOf(DiarizedSegment(0f, 0.8f, 0, null), DiarizedSegment(0.8f, 1.4f, 1, null))

        val labeled = SpeakerLabelTail.withSpeakerLabels(transcription, segments, mapOf(1 to "Bob"))

        assertEquals(listOf("Hello world good", "day"), labeled.segments.map { it.text })
        assertEquals(listOf(0, 1), labeled.segments.map { it.speaker })
        assertEquals(listOf(null, "Bob"), labeled.segments.map { it.speakerName })
        assertTrue(labeled.segments.all { it.tokens.isEmpty() })
        assertEquals("word_boundary=1", labeled.processing?.speakerResplit)
    }
}
