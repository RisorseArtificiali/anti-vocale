package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.R
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
    private suspend fun CoroutineScope.runAudioRequest(taskId: String, context: Context = mockk(relaxed = true)) =
        orchestrator.processRequest(
            taskId = taskId, requestType = "audio", prompt = "",
            filePath = temporaryFolder.newFile("audio.ogg").absolutePath,
            source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = context, cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

    @Test
    fun `enabled toggle summarizes a long transcript and keeps the delivered text`() = runTest {
        every { preferencesManager.summarizeEnabled } returns flowOf(true)
        every { preferencesManager.summaryPrompt } returns flowOf("")
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
        every { preferencesManager.summaryPrompt } returns flowOf("")
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
        every { preferencesManager.summaryPrompt } returns flowOf("")
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
        every { preferencesManager.summaryPrompt } returns flowOf("")
        stubSwapToLlm()
        coEvery { llmBackend.generateText(any()) } returns
            Result.failure(IllegalStateException("liteRT exploded"))
        stubWholeFileRequest()

        val result = runAudioRequest("summ-5")

        assertTrue(result.isSuccess)
        assertEquals(longTranscript, result.getOrNull())
        coVerify {
            logDao.update(match {
                it.result == longTranscript && it.summary == null &&
                    // An exception is a failed attempt, not a guard verdict.
                    it.summarySkipReason == SummaryPolicy.SKIP_REASON_FAILED
            })
        }
    }

    @Test
    fun `a summary that outgrew the transcript is dropped with the guard reason`() = runTest {
        every { preferencesManager.summarizeEnabled } returns flowOf(true)
        every { preferencesManager.summaryPrompt } returns flowOf("")
        stubSwapToLlm()
        // Longer than transcript * 1.2: a rewrite, not a summary.
        val rewrite = "x".repeat(longTranscript.length * 2)
        coEvery { llmBackend.generateText(any()) } returns Result.success(rewrite)
        stubWholeFileRequest()

        val result = runAudioRequest("summ-6")

        assertTrue(result.isSuccess)
        assertEquals(longTranscript, result.getOrNull())
        // TASK-494: the entry must NAME the cause, not swallow it.
        coVerify {
            logDao.update(match {
                it.result == longTranscript && it.summary == null &&
                    it.summarySkipReason == SummaryPolicy.SKIP_REASON_GUARDS
            })
        }
    }

    @Test
    fun `a stutter-short summary is dropped with the guard reason`() = runTest {
        every { preferencesManager.summarizeEnabled } returns flowOf(true)
        every { preferencesManager.summaryPrompt } returns flowOf("")
        stubSwapToLlm()
        // Under the 20-char floor: the classic one-word-title prompt output.
        coEvery { llmBackend.generateText(any()) } returns Result.success("Riassunto.")
        stubWholeFileRequest()

        val result = runAudioRequest("summ-7")

        assertTrue(result.isSuccess)
        assertEquals(longTranscript, result.getOrNull())
        coVerify {
            logDao.update(match {
                it.result == longTranscript && it.summary == null &&
                    it.summarySkipReason == SummaryPolicy.SKIP_REASON_GUARDS
            })
        }
    }

    @Test
    fun `saved override replaces the built-in instruction`() = runTest {
        // TASK-483: the user's prompt must reach generateText; the built-in
        // 2-3 sentence default only when the override is blank.
        every { preferencesManager.summarizeEnabled } returns flowOf(true)
        every { preferencesManager.summaryPrompt } returns flowOf("Give a one-line TL;DR in Italian.")
        stubSwapToLlm()
        coEvery { llmBackend.generateText(any()) } returns Result.success(summary)
        stubWholeFileRequest()

        val result = runAudioRequest("summ-custom")

        assertTrue(result.isSuccess)
        // TASK-483: the override is the instruction the model sees, not the built-in.
        coVerify {
            llmBackend.generateText(match { it.contains("TL;DR") && it.contains(longTranscript) })
        }
    }

    // TASK-498: a custom-prompt attempt that fails retries ONCE with the
    // built-in instruction on the same loaded backend (no second swap).

    @Test
    fun `custom prompt rejected by guards retries with built-in and delivers`() = runTest {
        every { preferencesManager.summarizeEnabled } returns flowOf(true)
        every { preferencesManager.summaryPrompt } returns flowOf("Reply with the single word: done.")
        stubSwapToLlm()
        // First call (custom prompt) stutters; retry (built-in) delivers.
        coEvery { llmBackend.generateText(any()) } returnsMany listOf(
            Result.success("done"),
            Result.success(summary),
        )
        stubWholeFileRequest()

        val result = runAudioRequest("summ-retry-1")

        assertTrue(result.isSuccess)
        assertEquals(longTranscript, result.getOrNull())
        coVerify(exactly = 2) { llmBackend.generateText(any()) }
        // The retry stays inside the SAME backend bracket: one swap, not two.
        coVerify(exactly = 1) { backendManager.setActiveBackend(eq(LlmTranscriptionBackend.BACKEND_ID), any(), any()) }
        coVerify {
            logDao.update(match { it.summary == summary && it.summarySkipReason == null })
        }
    }

    @Test
    fun `custom prompt generation failure also earns the built-in retry`() = runTest {
        every { preferencesManager.summarizeEnabled } returns flowOf(true)
        every { preferencesManager.summaryPrompt } returns flowOf("Summarize as a haiku.")
        stubSwapToLlm()
        coEvery { llmBackend.generateText(any()) } returnsMany listOf(
            Result.failure(IllegalStateException("first attempt exploded")),
            Result.success(summary),
        )
        stubWholeFileRequest()

        val result = runAudioRequest("summ-retry-2")

        assertTrue(result.isSuccess)
        coVerify(exactly = 2) { llmBackend.generateText(any()) }
        coVerify {
            logDao.update(match { it.summary == summary && it.summarySkipReason == null })
        }
    }

    @Test
    fun `both attempts rejected by guards records the guards reason`() = runTest {
        every { preferencesManager.summarizeEnabled } returns flowOf(true)
        every { preferencesManager.summaryPrompt } returns flowOf("Rewrite everything verbatim.")
        stubSwapToLlm()
        val rewrite = "x".repeat(longTranscript.length * 2)
        coEvery { llmBackend.generateText(any()) } returns Result.success(rewrite)
        stubWholeFileRequest()

        val result = runAudioRequest("summ-retry-3")

        assertTrue(result.isSuccess)
        assertEquals(longTranscript, result.getOrNull())
        coVerify(exactly = 2) { llmBackend.generateText(any()) }
        coVerify {
            logDao.update(match {
                it.summary == null && it.summarySkipReason == SummaryPolicy.SKIP_REASON_GUARDS
            })
        }
    }

    @Test
    fun `a whitespace-only custom prompt runs a single attempt`() = runTest {
        every { preferencesManager.summarizeEnabled } returns flowOf(true)
        every { preferencesManager.summaryPrompt } returns flowOf("   ")
        stubSwapToLlm()
        val rewrite = "x".repeat(longTranscript.length * 2)
        coEvery { llmBackend.generateText(any()) } returns Result.success(rewrite)
        stubWholeFileRequest()

        val result = runAudioRequest("summ-retry-5")

        assertTrue(result.isSuccess)
        // Trims to blank: the built-in IS the first attempt and there is
        // nothing to retry with (single generation).
        coVerify(exactly = 1) { llmBackend.generateText(any()) }
        coVerify {
            logDao.update(match { it.summarySkipReason == SummaryPolicy.SKIP_REASON_GUARDS })
        }
    }

    @Test
    fun `a saved prompt identical to the built-in text runs a single attempt`() = runTest {
        every { preferencesManager.summarizeEnabled } returns flowOf(true)
        // The dedup compares against the localized built-in, so the context
        // must answer a real string (the relaxed default is "").
        val builtIn = "Summarize the transcript in three sentences."
        every { preferencesManager.summaryPrompt } returns flowOf(builtIn)
        val promptContext = mockk<Context>(relaxed = true) {
            every { getString(R.string.summary_default_prompt) } returns builtIn
        }
        stubSwapToLlm()
        val rewrite = "x".repeat(longTranscript.length * 2)
        coEvery { llmBackend.generateText(any()) } returns Result.success(rewrite)
        stubWholeFileRequest()

        val result = runAudioRequest("summ-retry-6", context = promptContext)

        assertTrue(result.isSuccess)
        // Identical instructions must not run the same generation twice.
        coVerify(exactly = 1) { llmBackend.generateText(any()) }
        coVerify {
            logDao.update(match { it.summarySkipReason == SummaryPolicy.SKIP_REASON_GUARDS })
        }
    }

    @Test
    fun `retry generation failure after a rejected custom attempt degrades with failed reason`() = runTest {
        every { preferencesManager.summarizeEnabled } returns flowOf(true)
        every { preferencesManager.summaryPrompt } returns flowOf("One word: summary.")
        stubSwapToLlm()
        // First attempt stutters (rejected), the built-in retry THROWS: the
        // last attempt's nature decides the reason (generation = failed).
        coEvery { llmBackend.generateText(any()) } returnsMany listOf(
            Result.success("done"),
            Result.failure(IllegalStateException("retry exploded")),
        )
        stubWholeFileRequest()

        val result = runAudioRequest("summ-retry-4")

        assertTrue(result.isSuccess)
        assertEquals(longTranscript, result.getOrNull())
        coVerify {
            logDao.update(match {
                it.summary == null && it.summarySkipReason == SummaryPolicy.SKIP_REASON_FAILED
            })
        }
    }
}
