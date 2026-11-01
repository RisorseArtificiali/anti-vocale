package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.data.local.TimedSegmentsConverter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TASK-928: the plain path (no diarization, no subtitles) must persist cues on
 * the row. The funnel tests below exercise the two arms a short plain clip can
 * take: the whole-file single-chunk arm (VAD on) and the pipeline arm (VAD off,
 * the most common voice note). A token-bearing decode must land sentence cues
 * carrying the per-word array; a timestamp-less decode lands the chunk's
 * positional cue with NO words (the honesty gate: features stay hidden). The
 * device regression behind this: whisper rows were positional-only because the
 * built-in engine ran without its token-timestamp flag (see SherpaBackend);
 * the funnel itself never dropped cues.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorCuePersistenceTest : TranscriptionOrchestratorTestBase() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var backend: TranscriptionBackend

    override fun baseSetUp() {
        super.baseSetUp()
        backend = stubWhisperBackend()
        stubDefaultWhisperPreferences()
        coEvery { logDao.getByTaskId("test-1") } returns LogEntity(
            id = "1", timestamp = 0L, taskId = "test-1",
            type = "AUDIO", status = "PROCESSING", prompt = "")
    }

    private val helloTokens = listOf(
        TimedToken("▁Hello", 0, 300),
        TimedToken("▁world", 350, 600),
        TimedToken("▁today", 650, 900),
    )

    private suspend fun CoroutineScope.processRequest(filePath: String): Result<String> =
        orchestrator.processRequest(
            taskId = "test-1", requestType = "audio", prompt = "",
            filePath = filePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk<Context>(relaxed = true),
            cacheDir = temporaryFolder.root,
            listener = listener,
            coroutineScope = this,
        )

    private fun lastPersistedRow(): LogEntity {
        val rows = mutableListOf<LogEntity>()
        coVerify { logDao.update(capture(rows)) }
        return rows.last()
    }

    private fun stubSingleChunkPreprocessing(vadOn: Boolean, filePath: String) {
        every { preferencesManager.vadEnabled } returns flowOf(vadOn)
        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = filePath,
                cacheDir = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                vadNumThreads = any(),
                vadProvider = any(),
                availableRamBytes = any(),
                maxHeapBytes = any())
        } returns AudioPreprocessor.PreprocessingResult(
            chunks = listOf(FloatArray(3) { it.toFloat() }),
            sampleRate = 16000,
            totalDurationSeconds = 8.0,
            chunkCount = 1,
            chunkRangesMs = listOf(0L to 8000L),
        )
    }

    private fun stubPipelineStream() {
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
                    sampleRate = 16000, totalDurationSeconds = 8.0, expectedChunkCount = 1)))
            emit(AudioPreprocessor.StreamEvent.Chunk(
                AudioPreprocessor.StreamChunk(
                    samples = FloatArray(3) { it.toFloat() },
                    sampleRate = 16000, chunkIndex = 0, isLast = true)))
        }
    }

    @Test
    fun `whole-file arm with token timestamps persists sentence cues carrying words`() = runTest {
        val audioFile = temporaryFolder.newFile("audio.ogg")
        stubSingleChunkPreprocessing(vadOn = true, filePath = audioFile.absolutePath)
        coEvery { backend.transcribeAudioStreaming(any(), any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = "Hello world today", tokens = helloTokens))

        val result = processRequest(audioFile.absolutePath)

        assertTrue(result.isSuccess)
        val cues = TimedSegmentsConverter.fromJson(lastPersistedRow().segments)
        assertTrue(cues.isNotEmpty())
        // The word array rides the same cues JSON: the plain path's write-time
        // derivation over the tokens the cue carries.
        assertTrue("expected per-word timing on the cue", cues[0].words.isNotEmpty())
        assertEquals("Hello world today", cues[0].text)
    }

    @Test
    fun `pipeline arm with token timestamps persists cues carrying words`() = runTest {
        val audioFile = temporaryFolder.newFile("audio.ogg")
        stubPipelineStream()
        coEvery { backend.transcribeAudio(any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = "Hello world today", tokens = helloTokens))

        val result = processRequest(audioFile.absolutePath)

        assertTrue(result.isSuccess)
        val cues = TimedSegmentsConverter.fromJson(lastPersistedRow().segments)
        assertTrue(cues.isNotEmpty())
        assertTrue("expected per-word timing on the cue", cues[0].words.isNotEmpty())
    }

    @Test
    fun `timestamp-less decode persists a positional cue without words`() = runTest {
        val audioFile = temporaryFolder.newFile("audio.ogg")
        stubSingleChunkPreprocessing(vadOn = true, filePath = audioFile.absolutePath)
        coEvery { backend.transcribeAudioStreaming(any(), any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = "Hello world today"))

        val result = processRequest(audioFile.absolutePath)

        assertTrue(result.isSuccess)
        val row = lastPersistedRow()
        val cues = TimedSegmentsConverter.fromJson(row.segments)
        // GH #92's chunk-level fallback stays: one positional cue, honestly
        // timed by the chunk's own range.
        assertEquals(1, cues.size)
        assertEquals(0L, cues[0].startMs)
        assertEquals(8000L, cues[0].endMs)
        // The honesty gate: no tokens, no word array, features stay hidden.
        assertTrue(cues[0].words.isEmpty())
        assertNotNull(row.segments)
    }

    @Test
    fun `separator-bounded bare-piece decode persists measured words without the estimate flag`() = runTest {
        // wordtap-probe-11's stream shape (Nemotron 3.5 streaming): word
        // pieces carry no opener and the standalone space piece sits at the
        // breaks. The whole-file arm must persist real word times, never the
        // cue-envelope estimate the old grouping fell to.
        val audioFile = temporaryFolder.newFile("audio.ogg")
        stubSingleChunkPreprocessing(vadOn = true, filePath = audioFile.absolutePath)
        coEvery { backend.transcribeAudioStreaming(any(), any(), any(), any()) } returns
            Result.success(TranscriptionResult(
                text = "alpha beta",
                tokens = listOf(
                    TimedToken(" ", 1199, 1199),
                    TimedToken("al", 1199, 1199),
                    TimedToken("pha", 1199, 1400),
                    TimedToken(" ", 1400, 1400),
                    TimedToken("be", 1400, 1400),
                    TimedToken("ta", 1400, 1600),
                ),
            ))

        val result = processRequest(audioFile.absolutePath)

        assertTrue(result.isSuccess)
        val row = lastPersistedRow()
        val cues = TimedSegmentsConverter.fromJson(row.segments)
        assertEquals(1, cues.size)
        assertEquals(
            listOf(TimedWord(1199, 1400), TimedWord(1400, 1600)),
            cues[0].words,
        )
        assertFalse("measured timing never carries the estimate flag",
            row.segments!!.contains("wordsEstimated"))
    }

    @Test
    fun `refinement fold delivers the refined result with its cues`() {
        val refined = TranscriptionResult(
            text = "Refined text here",
            segments = listOf(TimedSegment(0, 8000, "Refined text here")),
        )
        val fast = FirstPassOutcome(
            text = "fast preview",
            processing = ProcessingContext(decodePath = "whole_file"),
            segments = emptyList(),
            refinePin = null,
        )
        val folded = orchestrator.refinementFoldSuccess(fast, refined).getOrThrow()
        assertEquals(1, folded.segments.size)
        assertEquals("Refined text here", folded.segments[0].text)
    }
}
