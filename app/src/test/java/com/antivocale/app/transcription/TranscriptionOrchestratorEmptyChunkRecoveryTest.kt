package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.data.local.LogEntity
import io.mockk.*
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
import java.io.File

/**
 * TASK-664 (GH #119): the empty-chunk recovery ladder at the orchestrator's
 * per-chunk decode sites. AC5's observable contract: a chunk that decodes
 * empty is re-fed (bounded, with neighbor overlap) before it lands in the
 * blank accounting; a non-empty first pass takes zero extra decodes; the
 * ladder-off seam keeps today's behavior at zero extra decodes.
 *
 * Feeds are told apart by SIZE: chunks are 1s (16000 samples), a one-sided
 * overlap re-feed is 2s, a both-sided one 3s, and the single-chunk padding
 * re-feed is chunk plus half a second of silence either side (2s here).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorEmptyChunkRecoveryTest : TranscriptionOrchestratorTestBase() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var backend: TranscriptionBackend
    private lateinit var audioFile: File
    private val feedSizes = mutableListOf<Int>()

    override fun baseSetUp() {
        super.baseSetUp()
        backend = stubWhisperBackend()
        stubDefaultWhisperPreferences()
        audioFile = temporaryFolder.newFile("ladder.ogg")
        audioFile.writeBytes(ByteArray(1024))
    }

    private fun oneSecondChunk(value: Float) = FloatArray(16_000) { value }

    private suspend fun CoroutineScope.runRequest(taskId: String): Result<String> =
        orchestrator.processRequest(
            taskId = taskId, requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this,
        )

    /** VAD-segmented multi-chunk stub: the parallel path (progressive off). */
    private fun stubParallel(vararg chunks: FloatArray) {
        every { preferencesManager.vadEnabled } returns flowOf(true)
        every { preferencesManager.progressiveTranscription } returns flowOf(false)
        stubPreprocessing(chunks = chunks.toList(), totalDurationSeconds = chunks.size.toDouble(), isVadSegmented = true)
    }

    /** VAD-off stream stub: the pipeline path. */
    private fun stubPipeline(vararg chunks: FloatArray) {
        val events = buildList {
            add(AudioPreprocessor.StreamEvent.Header(
                AudioPreprocessor.StreamHeader(16_000, chunks.size.toDouble(), chunks.size)))
            chunks.forEachIndexed { index, samples ->
                add(AudioPreprocessor.StreamEvent.Chunk(
                    AudioPreprocessor.StreamChunk(samples, 16_000, index, index == chunks.lastIndex)))
            }
        }
        every {
            audioPreprocessor.prepareAudioStream(
                inputPath = any(), maxChunkDurationSeconds = any(), context = any(), enableVad = any(),
                availableRamBytes = any(), maxHeapBytes = any())
        } returns flow { events.forEach { emit(it) } }
    }

    private fun text(s: String) = Result.success(TranscriptionResult(text = s))

    /**
     * An overlap-rung answer: text with one timestamped token per word, all
     * inside the chunk window (1000..2000ms for a 1s chunk after a 1s tail),
     * so the ladder adopts the whole text after trimming.
     */
    private fun overlapText(s: String) = Result.success(TranscriptionResult(
        text = s,
        tokens = s.split(" ").mapIndexed { i, word -> TimedToken("▁$word", 1200L + 200L * i, 1400L + 200L * i) }))

    /** Content-keyed stub (the base helper) that also records every feed size. */
    private fun stubFeeds(answer: (feedSize: Int, firstSample: Float) -> Result<TranscriptionResult>) {
        backend.stubContentKeyedDecodes { size, first ->
            feedSizes.add(size)
            answer(size, first)
        }
    }

    /** Captures the row writes; call the getter after the run for the SUCCESS row's context JSON. */
    private fun stubRowCapture(): () -> String? {
        val captured = mutableListOf<LogEntity>()
        coEvery { logDao.getByTaskId(any()) } returns
            LogEntity(id = "row", timestamp = 0L, taskId = "row", type = "AUDIO", status = "PROCESSING")
        coEvery { logDao.update(capture(captured)) } returns Unit
        return { captured.lastOrNull { it.status == "SUCCESS" }?.processingContext }
    }

    /** Captures the failure-context writes; call the getter after the run for the ERROR row's JSON. */
    private fun stubFailureContextCapture(): () -> String? {
        val captured = mutableListOf<String>()
        coEvery { logDao.updateFailureContext(any(), capture(captured)) } returns Unit
        return { captured.lastOrNull() }
    }

    // ---- Parallel path ----

    @Test
    fun `parallel empty chunk recovers on the overlap re-feed and no longer counts as blank`() = runTest {
        val successContext = stubRowCapture()
        stubParallel(oneSecondChunk(1f), oneSecondChunk(2f), oneSecondChunk(3f))
        stubFeeds { size, first ->
            when {
                // Chunks 0 and 2 decode once, non-empty: zero extra work.
                size == 16_000 && first == 1f -> text("alpha")
                size == 16_000 && first == 3f -> text("gamma")
                // Chunk 1: blank first pass, blank retry rung, overlap recovers.
                size == 16_000 && first == 2f -> text("")
                size == 48_000 -> overlapText("beta from overlap")
                else -> text("unexpected feed $size $first")
            }
        }

        val result = runRequest("recover")

        assertTrue("expected success: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("alpha beta from overlap gamma", result.getOrNull())
        // Three first passes plus chunk 1's two bounded rungs, nothing more.
        assertEquals(listOf(16_000, 16_000, 16_000, 48_000, 16_000), feedSizes)
        verify {
            listener.onSuccess(
                eq("recover"), any(), any(), any(), any(),
                confidence = any(), detectedLanguage = any(),
                isPartial = false, failedChunkCount = 0, segments = any())
        }
        val context = successContext()
        assertTrue("retriedChunks missing from the persisted context: $context",
            context != null && context.contains("\"retriedChunks\":1"))
    }

    @Test
    fun `parallel chunk still empty after the ladder is one honest blank`() = runTest {
        val successContext = stubRowCapture()
        stubParallel(oneSecondChunk(1f), oneSecondChunk(2f), oneSecondChunk(3f))
        stubFeeds { size, first ->
            when {
                size == 16_000 && first == 1f -> text("alpha")
                size == 16_000 && first == 3f -> text("gamma")
                else -> text("")
            }
        }

        val result = runRequest("stillblank")

        assertTrue(result.isSuccess)
        assertEquals("alpha gamma", result.getOrNull())
        // First pass plus the two bounded rungs for chunk 1, then the ladder stops.
        assertEquals(5, feedSizes.size)
        verify {
            listener.onSuccess(
                eq("stillblank"), any(), any(), any(), any(),
                confidence = any(), detectedLanguage = any(),
                isPartial = false, failedChunkCount = 0, segments = any())
        }
        // Post-ladder accounting: 1 blank, 1 retried, nothing failed.
        val context = successContext()
        assertTrue("blank/retried missing from the persisted context: $context",
            context != null && context.contains("\"blankChunks\":1") && context.contains("\"retriedChunks\":1"))
    }

    @Test
    fun `parallel ladder disabled keeps the blank at zero extra decodes`() = runTest {
        orchestrator.emptyChunkRecoveryEnabled = false
        stubParallel(oneSecondChunk(1f), oneSecondChunk(2f))
        stubFeeds { _, first -> if (first == 2f) text("") else text("alpha") }

        val result = runRequest("off")

        assertTrue(result.isSuccess)
        assertEquals("alpha", result.getOrNull())
        // Exactly one decode per chunk: the pre-ladder contract.
        assertEquals(listOf(16_000, 16_000), feedSizes)
    }

    // ---- Pipeline path (the held chunk waits for its next neighbor) ----

    @Test
    fun `pipeline empty chunk is held and recovered once the next head exists`() = runTest {
        stubPipeline(oneSecondChunk(1f), oneSecondChunk(2f), oneSecondChunk(3f))
        stubFeeds { size, first ->
            when {
                size == 16_000 && first == 1f -> text("alpha")
                size == 16_000 && first == 3f -> text("gamma")
                // The held chunk: blank retry rung, then the overlap re-feed
                // (previous tail plus the head of the chunk that just arrived).
                size == 16_000 && first == 2f -> text("")
                size == 48_000 -> overlapText("beta from overlap")
                else -> text("unexpected feed $size $first")
            }
        }

        val result = runRequest("held")

        assertTrue("expected success: ${result.exceptionOrNull()}", result.isSuccess)
        // Order survives: the held chunk's text lands before chunk 2's own.
        assertEquals("alpha beta from overlap gamma", result.getOrNull())
    }

    @Test
    fun `pipeline trailing empty chunk stays one honest blank at stream end`() = runTest {
        stubPipeline(oneSecondChunk(1f), oneSecondChunk(2f))
        stubFeeds { size, first ->
            when {
                size == 16_000 && first == 1f -> text("alpha")
                // The last chunk: blank everywhere, resolved after the stream
                // with the previous tail only.
                else -> text("")
            }
        }

        val result = runRequest("tail")

        assertTrue(result.isSuccess)
        assertEquals("alpha", result.getOrNull())
        // Chunk 0 once; chunk 1 first pass, retry rung, one-sided overlap.
        assertEquals(listOf(16_000, 16_000, 16_000, 32_000), feedSizes)
    }

    // ---- Single-chunk arm (whole-file path; no neighbors, padding variant) ----

    @Test
    fun `single chunk note that decodes empty is recovered by the padding re-feed`() = runTest {
        val successContext = stubRowCapture()
        every { preferencesManager.vadEnabled } returns flowOf(true)
        stubPreprocessing(chunks = listOf(oneSecondChunk(1f)), totalDurationSeconds = 1.0)
        stubFeeds { size, _ ->
            // Same-samples rung stays blank; the padded re-feed (chunk plus
            // silence either side) recovers the note.
            if (size > 16_000) text("rescued note") else text("")
        }

        val result = runRequest("single")

        assertTrue("expected success: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("rescued note", result.getOrNull())
        // First pass, retry rung, padding rung: bounded at two extras.
        assertEquals(3, feedSizes.size)
        // The ladder ENTRY lands on the success context (TASK-664: whole_file
        // records entry like every other arm, recovered or not).
        val context = successContext()
        assertTrue("retriedChunks missing from the persisted context: $context",
            context != null && context.contains("\"retriedChunks\":1"))
    }

    @Test
    fun `single chunk note still empty after the ladder fails honestly`() = runTest {
        val failureContext = stubFailureContextCapture()
        every { preferencesManager.vadEnabled } returns flowOf(true)
        stubPreprocessing(chunks = listOf(oneSecondChunk(1f)), totalDurationSeconds = 1.0)
        stubFeeds { _, _ -> text("") }

        val result = runRequest("single-blank")

        assertTrue(result.isFailure)
        assertEquals(3, feedSizes.size)
        // TASK-664: the all-blank ERROR row says the ladder already ran (the
        // count rides NoTranscriptionProduced to the failure context).
        val context = failureContext()
        assertTrue("retriedChunks missing from the failure context: $context",
            context != null && context.contains("\"retriedChunks\":1"))
    }

    @Test
    fun `single chunk wedge inside the ladder persists retriedChunks on the abort transport`() = runTest {
        val failureContext = stubFailureContextCapture()
        every { preferencesManager.vadEnabled } returns flowOf(true)
        stubPreprocessing(chunks = listOf(oneSecondChunk(1f)), totalDurationSeconds = 1.0)
        stubFeeds { size, _ ->
            // Blank first pass enters the ladder; the PADDING rung (the
            // second: the same-samples retry stays blank at 16_000) wedges.
            if (size > 16_000) Result.failure(
                com.antivocale.app.manager.EngineWedgeTimeoutException("LiteRT audio generation timed out after 300s"))
            else text("")
        }

        val result = runRequest("single-wedge")

        // TASK-691: this arm wraps the wedge for the abort transport (like
        // the segment and parallel arms), and the ladder entry rides it.
        val wedge = result.exceptionOrNull() as? TranscriptionOrchestrator.WedgeAbortException
        assertTrue("expected WedgeAbortException, got ${result.exceptionOrNull()}",
            wedge != null)
        assertEquals("the ladder entry rides the abort transport", 1, wedge?.retriedChunks)
        // The same wedge-in-ladder scenario now records retried=1 on the
        // ERROR row like the pipeline arm always did (GH #96/#2 reads it).
        val context = failureContext()
        assertTrue("retriedChunks missing from the failure context: $context",
            context != null && context.contains("\"retriedChunks\":1"))
    }
}
