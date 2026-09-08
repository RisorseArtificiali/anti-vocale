package com.antivocale.app.transcription

import com.antivocale.app.data.local.LogEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TASK-121.4: the summary pass at the transcribeAudio funnel, chained after
 * the punctuation pass. The gate matrix itself is pinned by SummaryPolicyTest;
 * these tests pin the wiring: the opt-in toggle fires the pass for a long
 * transcript, swaps to the LLM backend, attaches the summary as metadata
 * (the delivered text stays the transcript), and every skip/degrade path
 * (toggle off, short transcript, no Gemma configured, generation failure,
 * collapse guard) delivers the transcript with no summary.
 */
class TranscriptionOrchestratorSummaryPassTest : TranscriptionOrchestratorTestBase() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var whisperBackend: TranscriptionBackend
    private lateinit var llmBackend: TranscriptionBackend

    /** Comfortably over SummaryPolicy.MIN_TRANSCRIPT_CHARS so the pass fires. */
    private val longTranscript = buildString {
        while (length <= SummaryPolicy.MIN_TRANSCRIPT_CHARS) {
            append("la riunione di oggi ha coperto il budget, le scadenze e i nomi dei responsabili, ")
        }
    }.trim()

    private val summary = "Riunione su budget, scadenze e responsabili del progetto vocale."

    @Before
    fun setUpWhisper() {
        // Punctuating catalog model with no chunk cap: VAD-off still decodes
        // whole-file, so the simple prepareAudioForMediaPipe stub drives the
        // request, and the punctuation pass (OFF here) never interferes.
        whisperBackend = mockk(relaxed = true) {
            every { id } returns "whisper"
            every { isReady() } returns true
            every { isAudioSupported() } returns true
            every { supportsAudio } returns true
            every { maxChunkDurationSeconds } returns null
            every { displayName } returns "Whisper"
        }
        llmBackend = mockk(relaxed = true) {
            every { id } returns LlmTranscriptionBackend.BACKEND_ID
            every { isReady() } returns true
        }
        stubDefaultWhisperPreferences()
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns whisperBackend
        // logSuccess needs an existing row to update (the base stubs null).
        coEvery { logDao.getByTaskId(any()) } returns LogEntity(
            id = "summ-0", timestamp = 1, taskId = "summ-0",
            type = "AUDIO", status = "PROCESSING", prompt = "", result = "")
    }

    private fun stubWholeFileRequest() {
        stubPreprocessing(listOf(FloatArray(3) { it.toFloat() }), totalDurationSeconds = 5.0)
        // The single-chunk whole-file path calls transcribeAudioStreaming (the
        // interface default forwards to transcribeAudio, but on a mock the
        // relaxed stub would fabricate Result<Object>: stub BOTH).
        coEvery { whisperBackend.transcribeAudio(any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = longTranscript))
        coEvery { whisperBackend.transcribeAudioStreaming(any(), any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = longTranscript))
    }

    /** The backend swap flips which backend getActiveBackend answers with. */
    private fun stubSwapToLlm() {
        val swapped = AtomicBoolean(false)
        every { backendManager.getActiveBackend() } answers {
            if (swapped.get()) llmBackend else whisperBackend
        }
        coEvery {
            backendManager.setActiveBackend(eq(LlmTranscriptionBackend.BACKEND_ID), any(), any())
        } coAnswers {
            swapped.set(true)
            Result.success(Unit)
        }
    }

    /** Drives one whole-file audio request inside the caller's runTest scope. */
    private suspend fun CoroutineScope.runAudioRequest(taskId: String) =
        orchestrator.processRequest(
            taskId = taskId, requestType = "audio", prompt = "",
            filePath = temporaryFolder.newFile("audio.ogg").absolutePath,
            source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

    @Test
    fun `enabled toggle summarizes a long transcript and keeps the delivered text`() = runTest {
        every { preferencesManager.summarizeEnabled } returns flowOf(true)
        stubSwapToLlm()
        coEvery { llmBackend.generateText(any()) } returns Result.success(summary)
        stubWholeFileRequest()

        val result = runAudioRequest("summ-1")

        assertTrue("request failed: ${result.exceptionOrNull()}", result.isSuccess)
        // Content preservation: the delivered value is the transcript, NOT the summary.
        assertEquals(longTranscript, result.getOrNull())
        coVerify(exactly = 1) { backendManager.setActiveBackend(eq(LlmTranscriptionBackend.BACKEND_ID), any(), any()) }
        coVerify(exactly = 1) { llmBackend.generateText(any()) }
        // the pass fed the curated default prompt + the transcript
        coVerify {
            llmBackend.generateText(match { it.contains(longTranscript) && it.isNotBlank() })
        }
        // the summary lands on the log row as metadata next to the unchanged result
        coVerify {
            logDao.update(match {
                it.result == longTranscript && it.summary == summary && it.rawTranscript == null
            })
        }
        // the notification/reply value is the transcript (onSuccess carries text, not summary)
        coVerify { listener.onSuccess(eq("summ-1"), eq(longTranscript), any(), any(), any()) }
    }

    @Test
    fun `disabled toggle never touches the llm backend`() = runTest {
        every { preferencesManager.summarizeEnabled } returns flowOf(false)
        stubWholeFileRequest()

        val result = runAudioRequest("summ-2")

        assertTrue(result.isSuccess)
        assertEquals(longTranscript, result.getOrNull())
        coVerify(exactly = 0) {
            backendManager.setActiveBackend(eq(LlmTranscriptionBackend.BACKEND_ID), any(), any())
        }
        coVerify(exactly = 0) { llmBackend.generateText(any()) }
    }

    @Test
    fun `a short transcript is never summarized`() = runTest {
        every { preferencesManager.summarizeEnabled } returns flowOf(true)
        stubSwapToLlm()
        val short = "ciao, ti chiamo dopo due minuti"
        stubPreprocessing(listOf(FloatArray(3) { it.toFloat() }), totalDurationSeconds = 5.0)
        coEvery { whisperBackend.transcribeAudio(any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = short))
        coEvery { whisperBackend.transcribeAudioStreaming(any(), any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = short))

        val result = runAudioRequest("summ-3")

        assertTrue(result.isSuccess)
        assertEquals(short, result.getOrNull())
        coVerify(exactly = 0) { llmBackend.generateText(any()) }
    }

    @Test
    fun `no gemma model configured skips silently`() = runTest {
        every { preferencesManager.summarizeEnabled } returns flowOf(true)
        // Override the base's "/models/gemma": no Gemma path configured.
        every { preferencesManager.modelPath } returns flowOf(null)
        stubSwapToLlm()
        stubWholeFileRequest()

        val result = runAudioRequest("summ-4")

        assertTrue(result.isSuccess)
        assertEquals(longTranscript, result.getOrNull())
        coVerify(exactly = 0) {
            backendManager.setActiveBackend(eq(LlmTranscriptionBackend.BACKEND_ID), any(), any())
        }
        coVerify(exactly = 0) { llmBackend.generateText(any()) }
    }

    @Test
    fun `generation failure degrades to no summary`() = runTest {
        every { preferencesManager.summarizeEnabled } returns flowOf(true)
        stubSwapToLlm()
        coEvery { llmBackend.generateText(any()) } returns
            Result.failure(IllegalStateException("liteRT exploded"))
        stubWholeFileRequest()

        val result = runAudioRequest("summ-5")

        assertTrue(result.isSuccess)
        assertEquals(longTranscript, result.getOrNull())
        coVerify {
            logDao.update(match { it.result == longTranscript && it.summary == null })
        }
    }

    @Test
    fun `a summary that outgrew the transcript is dropped`() = runTest {
        every { preferencesManager.summarizeEnabled } returns flowOf(true)
        stubSwapToLlm()
        // Longer than transcript * 1.2: a rewrite, not a summary.
        val rewrite = "x".repeat(longTranscript.length * 2)
        coEvery { llmBackend.generateText(any()) } returns Result.success(rewrite)
        stubWholeFileRequest()

        val result = runAudioRequest("summ-6")

        assertTrue(result.isSuccess)
        assertEquals(longTranscript, result.getOrNull())
        coVerify {
            logDao.update(match { it.result == longTranscript && it.summary == null })
        }
    }
}
