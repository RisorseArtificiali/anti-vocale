package com.antivocale.app.transcription

import com.antivocale.app.data.local.LogEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TASK-672 (GH #72): the repetition collapse at the transcribeAudio
 * funnel, first in the polish chain, before the punctuation pass. The tiers themselves are
 * pinned by RepetitionCollapseTest; these tests pin the wiring: the
 * collapsed text is delivered while the row keeps the pre-collapse
 * transcript as its raw original, and the dual-model loop detection
 * still judges the UNcollapsed phase text.
 */
class TranscriptionOrchestratorRepetitionCollapseTest : TranscriptionOrchestratorTestBase() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var gigaamBackend: TranscriptionBackend
    private lateinit var llmBackend: TranscriptionBackend

    /** A 7-token url run buried in prose: collapses to the prose around it. */
    private val loopTranscript =
        "senti un attimo url url url url url url url ecco fatto"
    private val collapsedTranscript = "senti un attimo url ecco fatto"

    private val cleanTranscript =
        "una trascrizione del tutto normale senza nessun loop"

    @Before
    fun setUpGigaam() {
        // The PunctuationPassTest fixture: a catalog id with no chunk cap,
        // so VAD-off decodes whole-file through the simple
        // prepareAudioForMediaPipe stub.
        gigaamBackend = mockk(relaxed = true) {
            every { id } returns "gigaam"
            every { isReady() } returns true
            every { isAudioSupported() } returns true
            every { supportsAudio } returns true
            every { maxChunkDurationSeconds } returns null
            every { displayName } returns "GigaAM v3"
        }
        llmBackend = mockk(relaxed = true) {
            every { id } returns LlmTranscriptionBackend.BACKEND_ID
            every { isReady() } returns true
        }
        stubDefaultWhisperPreferences()
        setUpGigaamWholeFileFixture(gigaamBackend)
    }

    private fun stubWholeFileRequestWithSegments(text: String, segments: List<com.antivocale.app.transcription.TimedSegment>) {
        stubWholeFileDecode(gigaamBackend, text, segments)
    }

    private fun stubWholeFileRequest(transcript: String) {
        stubWholeFileDecode(gigaamBackend, transcript)
    }

    private fun stubSwapToLlm() {
        stubBackendSwapToLlm(gigaamBackend, llmBackend)
    }

    /** logSuccess needs an existing row to update (the base stubs null). */
    private fun stubExistingRow(taskId: String) {
        coEvery { logDao.getByTaskId(any()) } returns LogEntity(
            id = taskId, timestamp = 1, taskId = taskId,
            type = "AUDIO", status = "PROCESSING", prompt = "", result = "")
    }

    @Test
    fun `a looping transcript delivers the collapsed text and preserves the raw`() = runTest {
        stubExistingRow("collapse-1")
        val audioFile = temporaryFolder.newFile("audio.ogg")
        stubWholeFileRequest(loopTranscript)

        val result = orchestrator.processRequest(
            taskId = "collapse-1", requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

        assertTrue("request failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(collapsedTranscript, result.getOrNull())
        // AC4: the row's original is the pre-collapse transcript, the raw
        // the TASK-583 warning and the annotated lineage must keep seeing.
        coVerify {
            logDao.update(match {
                it.result == collapsedTranscript && it.rawTranscript == loopTranscript
            })
        }
        verify { listener.onSuccess(eq("collapse-1"), eq(collapsedTranscript), any(), any(), any()) }
    }

    @Test
    fun `a clean transcript passes through with no raw row`() = runTest {
        stubExistingRow("collapse-2")
        val audioFile = temporaryFolder.newFile("audio.ogg")
        stubWholeFileRequest(cleanTranscript)

        val result = orchestrator.processRequest(
            taskId = "collapse-2", requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

        assertTrue(result.isSuccess)
        assertEquals(cleanTranscript, result.getOrNull())
        coVerify {
            logDao.update(match { it.result == cleanTranscript && it.rawTranscript == null })
        }
    }

    @Test
    fun `after the punctuation pass the raw stays the pre-polish transcript`() = runTest {
        // Review F3: the collapse runs BEFORE the polish (the polish's
        // ", " separators broke the 6-copy backref on multi-word loops),
        // so Gemma receives the already-collapsed text here; the row's raw
        // must remain the pre-polish ASR original (the deepest text).
        stubExistingRow("collapse-3")
        every { preferencesManager.punctuationMode } returns flowOf("always")
        stubSwapToLlm()
        val rawUnpunctuated =
            "guarda questo url url url url url url url e dimmi"
        val punctuatedCollapsed =
            "Guarda questo url e dimmi."
        coEvery { llmBackend.generateText(any()) } returns Result.success(punctuatedCollapsed)
        val audioFile = temporaryFolder.newFile("audio.ogg")
        stubWholeFileRequest(rawUnpunctuated)

        val result = orchestrator.processRequest(
            taskId = "collapse-3", requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

        assertTrue("request failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("Guarda questo url e dimmi.", result.getOrNull())
        coVerify {
            logDao.update(match {
                it.result == "Guarda questo url e dimmi." && it.rawTranscript == rawUnpunctuated
            })
        }
    }

    @Test
    fun `the dual-model loop detector still judges the uncollapsed refinement`() {
        // Constraint pin: the collapse leaves at most one copy of the loop
        // unit, and the detector goes silent on that remnant (asserted
        // below), so the loop arm can only fire if the detector read the
        // UNcollapsed refined text. Reordering the collapse before the
        // dual-model arms would flip this to a "clean" refinement
        // delivering the remnant.
        val loopText = (1..40).joinToString(" ") { "¡Muy bien!" }
        assertNull(RepetitionLoopDetector.detect(RepetitionCollapse.collapse(loopText)))

        val firstPass = FirstPassOutcome(
            text = "prima passata completa e pulita",
            processing = ProcessingContext(
                decodePath = "whole_file", backendId = "nemotron-streaming"),
        )
        val delivered = orchestrator.refinementFoldSuccess(
            firstPass, TranscriptionResult(text = loopText)).getOrThrow()

        assertEquals("prima passata completa e pulita", delivered.text)
        assertEquals(
            DualRefinementPolicy.SKIP_REFINE_LOOP,
            delivered.firstPass?.skipOutcome?.token)
    }

    @Test
    fun `cue texts collapse with the delivered text so exports stay clean`() = runTest {
        stubExistingRow("collapse-2")
        val audioFile = temporaryFolder.newFile("audio2.ogg")
        // A loop inside a cue-bearing transcript: the delivered text and the
        // cue texts must both lose the loop (Simplify F1: exports render
        // segment.text verbatim).
        stubWholeFileRequestWithSegments(
            text = "prologue url url url url url url url url",
            segments = listOf(
                com.antivocale.app.transcription.TimedSegment(0, 1_000, "prologue"),
                com.antivocale.app.transcription.TimedSegment(1_000, 2_000, "url url url url url url url url"),
            ),
        )

        val result = orchestrator.processRequest(
            taskId = "collapse-2", requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

        assertTrue("request failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("prologue url", result.getOrNull()?.trim())
        coVerify {
            logDao.update(match {
                it.result == "prologue url" &&
                    it.rawTranscript == "prologue url url url url url url url url"
            })
        }
    }

    // ---- TASK-581 (review F6): the short-collapse fold arm ----

    @Test
    fun `a plain-prompt short collapse delivers the first pass with the collapse token`() = runTest {
        stubExistingRow("collapse-fold-1")
        val goodFirstPass = (1..60).joinToString(" ") { "parola$it" }
        val firstPass = FirstPassOutcome(
            text = goodFirstPass,
            processing = ProcessingContext(decodePath = "whole_file", backendId = "nemotron-streaming"),
        )

        val delivered = orchestrator.refinementFoldSuccess(
            firstPass, TranscriptionResult(text = "Si.")).getOrThrow()

        assertEquals(goodFirstPass, delivered.text)
        assertEquals(
            DualRefinementPolicy.SKIP_REFINE_COLLAPSED,
            delivered.firstPass?.skipOutcome?.token)
    }

    @Test
    fun `a final-pass result may return short text`() = runTest {
        stubExistingRow("collapse-fold-2")
        val firstPass = FirstPassOutcome(
            text = (1..60).joinToString(" ") { "parola$it" },
            processing = ProcessingContext(decodePath = "whole_file", backendId = "nemotron-streaming"),
        )

        val delivered = orchestrator.refinementFoldSuccess(
            firstPass, TranscriptionResult(text = "Punti chiave: uno, due.", finalPassApplied = true)).getOrThrow()

        // The condensed text IS the deliverable for a condensing prompt.
        assertEquals("Punti chiave: uno, due.", delivered.text)
    }
}
