package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.transcription.diarization.DiarizedSegment
import com.antivocale.app.transcription.diarization.WindowedDiarization
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
            WindowedDiarization(listOf(DiarizedSegment(0f, 1f, 1, null)), emptyMap())
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

        val labeled = TranscriptionOrchestrator.withSpeakerLabels(transcription, segments, mapOf(1 to "Bob"))

        assertEquals(listOf("Hello world good", "day"), labeled.segments.map { it.text })
        assertEquals(listOf(0, 1), labeled.segments.map { it.speaker })
        assertEquals(listOf(null, "Bob"), labeled.segments.map { it.speakerName })
        assertTrue(labeled.segments.all { it.tokens.isEmpty() })
        assertEquals("word_boundary=1", labeled.processing?.speakerResplit)
    }
}
