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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TASK-276: the punctuation pass at the transcribeAudio funnel. The decision
 * matrix itself is pinned by PunctuationPolicyTest; these tests pin the
 * wiring: the pass fires for a non-punctuating model in AUTO mode, swaps to
 * the LLM backend, and either replaces the text or degrades to the raw
 * transcript on failure.
 */
class TranscriptionOrchestratorPunctuationPassTest : TranscriptionOrchestratorTestBase() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var gigaamBackend: TranscriptionBackend
    private lateinit var llmBackend: TranscriptionBackend

    private val rawTranscript = "привет как дела сегодня мы обсудим новый проект по распознаванию речи"
    private val punctuatedTranscript =
        "Привет, как дела? Сегодня мы обсудим новый проект по распознаванию речи."

    @Before
    fun setUpGigaam() {
        // Non-punctuating model (the real registry flags gigaam false) with no
        // chunk cap: VAD-off still decodes whole-file, so the simple
        // prepareAudioForMediaPipe stub drives the request.
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
        // Common preference stubs (defaultPrompt, threadCount, provider, ...)
        // BEFORE the gigaam overrides: the AudioTest shape, minus the whisper bits.
        stubDefaultWhisperPreferences()
        every { backendManager.hasActiveBackend() } returns true
        every { preferencesManager.transcriptionBackend } returns flowOf("gigaam")
        every { preferencesManager.vadEnabled } returns flowOf(false)
        every { preferencesManager.sherpaModelPath("gigaam") } returns flowOf("/models/gigaam")
        every { preferencesManager.punctuationPrompt } returns flowOf("")
        // modelPath (the Gemma file) is already flowOf("/models/gemma") in the base.
    }

    private fun stubWholeFileRequest(@Suppress("UNUSED_PARAMETER") audioFile: java.io.File) {
        // TASK-682: the shared whole-file stub (the stub-BOTH gotcha lives
        // in the base now).
        stubWholeFileDecode(gigaamBackend, rawTranscript)
    }

    private fun stubSwapToLlm() {
        stubBackendSwapToLlm(gigaamBackend, llmBackend)
    }

    @Test
    fun `always mode punctuates an unpunctuated transcript via the llm backend`() = runTest {
        // logSuccess needs an existing row to update (the base stubs null).
        coEvery { logDao.getByTaskId(any()) } returns LogEntity(
            id = "punct-1", timestamp = 1, taskId = "punct-1",
            type = "AUDIO", status = "PROCESSING", prompt = "", result = "")
        // 2026-09-06 correction: gigaam punctuates natively, so AUTO no longer
        // selects it (flag true for every backend); ALWAYS + an unpunctuated
        // transcript is the shipping path this test pins.
        every { preferencesManager.punctuationMode } returns flowOf("always")
        stubSwapToLlm()
        coEvery { llmBackend.generateText(any()) } returns Result.success(punctuatedTranscript)
        val audioFile = temporaryFolder.newFile("audio.ogg")
        stubWholeFileRequest(audioFile)

        val result = orchestrator.processRequest(
            taskId = "punct-1", requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

        assertTrue("request failed: ${result.exceptionOrNull()}", result.isSuccess)
        // Gate inputs (registry flags gigaam non-punctuating; AUTO + unpunctuated
        // Russian fires) are pinned by BackendRegistryTest and PunctuationPolicyTest.
        coVerify(exactly = 1) { backendManager.setActiveBackend(eq(LlmTranscriptionBackend.BACKEND_ID), any(), any()) }
        coVerify(exactly = 1) { llmBackend.generateText(any()) }
        assertEquals(punctuatedTranscript, result.getOrNull())
        // AC3: the row's original is the pre-polish ASR text
        coVerify {
            logDao.update(match {
                it.result == punctuatedTranscript && it.rawTranscript == rawTranscript
            })
        }
        // the pass fed the curated default prompt + the raw transcript
        coVerify {
            llmBackend.generateText(match { it.contains(rawTranscript) && it.isNotBlank() })
        }
        verify { listener.onSuccess(eq("punct-1"), eq(punctuatedTranscript), any(), any(), any()) }
    }

    @Test
    fun `generation failure degrades to the raw transcript`() = runTest {
        every { preferencesManager.punctuationMode } returns flowOf("always")
        stubSwapToLlm()
        coEvery { llmBackend.generateText(any()) } returns
            Result.failure(IllegalStateException("liteRT exploded"))
        val audioFile = temporaryFolder.newFile("audio.ogg")
        stubWholeFileRequest(audioFile)

        val result = orchestrator.processRequest(
            taskId = "punct-2", requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

        assertTrue(result.isSuccess)
        assertEquals(rawTranscript, result.getOrNull())
    }

    @Test
    fun `off mode never touches the llm backend`() = runTest {
        every { preferencesManager.punctuationMode } returns flowOf("off")
        every { backendManager.getActiveBackend() } returns gigaamBackend
        val audioFile = temporaryFolder.newFile("audio.ogg")
        stubWholeFileRequest(audioFile)

        val result = orchestrator.processRequest(
            taskId = "punct-3", requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

        assertTrue(result.isSuccess)
        assertEquals(rawTranscript, result.getOrNull())
        coVerify(exactly = 0) {
            backendManager.setActiveBackend(eq(LlmTranscriptionBackend.BACKEND_ID), any(), any())
        }
    }

    // ---- TASK-666: the conservative cleanup contract at the funnel ----

    @Test
    fun `conservative mode cleans and preserves the word sequence`() = runTest {
        coEvery { logDao.getByTaskId(any()) } returns LogEntity(
            id = "punct-cc1", timestamp = 1, taskId = "punct-cc1",
            type = "AUDIO", status = "PROCESSING", prompt = "", result = "")
        every { preferencesManager.punctuationMode } returns flowOf("conservative")
        // A user prompt override must NOT reach the conservative pass.
        every { preferencesManager.punctuationPrompt } returns flowOf("translate this to english")
        stubSwapToLlm()
        coEvery { llmBackend.generateText(any()) } returns Result.success(punctuatedTranscript)
        val audioFile = temporaryFolder.newFile("audio.ogg")
        stubWholeFileRequest(audioFile)

        val result = orchestrator.processRequest(
            taskId = "punct-cc1", requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

        assertTrue("request failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(punctuatedTranscript, result.getOrNull())
        // the conservative fences: the override never reached the engine
        coVerify { llmBackend.generateText(match { !it.contains("translate this to english") }) }
        coVerify {
            logDao.update(match {
                it.result == punctuatedTranscript && it.rawTranscript == rawTranscript
            })
        }
    }

    @Test
    fun `conservative output that alters the word sequence is discarded whole`() = runTest {
        coEvery { logDao.getByTaskId(any()) } returns LogEntity(
            id = "punct-cc2", timestamp = 1, taskId = "punct-cc2",
            type = "AUDIO", status = "PROCESSING", prompt = "", result = "")
        every { preferencesManager.punctuationMode } returns flowOf("conservative")
        stubSwapToLlm()
        // one added word ("очень") and the whole cleanup must be dropped
        coEvery { llmBackend.generateText(any()) } returns
            Result.success("Привет, как дела? Сегодня мы очень обсудим новый проект по распознаванию речи.")
        val audioFile = temporaryFolder.newFile("audio.ogg")
        stubWholeFileRequest(audioFile)

        val result = orchestrator.processRequest(
            taskId = "punct-cc2", requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

        assertTrue(result.isSuccess)
        assertEquals(rawTranscript, result.getOrNull())
        coVerify { logDao.update(match { it.rawTranscript == null }) }
    }

    // ---- TASK-666.2: the chunked pass over the Gemma context guard ----

    /** A sentence-rich Russian wall of text that exceeds MAX_TRANSCRIPT_CHARS. */
    private fun longTranscript(sentences: Int): String =
        "Привет как дела сегодня мы обсудим новый проект по распознаванию речи. "
            .repeat(sentences)
            .trim()

    @Test
    fun `over the context guard the conservative pass paragraphs chunk by chunk`() = runTest {
        coEvery { logDao.getByTaskId(any()) } returns LogEntity(
            id = "chunk-1", timestamp = 1, taskId = "chunk-1",
            type = "AUDIO", status = "PROCESSING", prompt = "", result = "")
        every { preferencesManager.punctuationMode } returns flowOf("conservative")
        stubSwapToLlm()
        val raw = longTranscript(sentences = 400) // 71 chars/sentence -> 28.4k chars -> 3 chunks
        val chunks = ContextChunker.split(raw)
        assertTrue("fixture must exceed the guard in >1 chunk", chunks.size > 1)
        // Echo polish: each generation returns its chunk verbatim (passes the
        // collapse guard and the conservative fence trivially); the visible
        // effect under test is the CHUNKING and the paragraph join.
        coEvery { llmBackend.generateText(any()) } returnsMany chunks.map { Result.success(it) }
        val audioFile = temporaryFolder.newFile("long.ogg")
        stubWholeFileDecode(gigaamBackend, raw)

        val result = orchestrator.processRequest(
            taskId = "chunk-1", requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

        assertTrue("request failed: ${result.exceptionOrNull()}", result.isSuccess)
        coVerify(exactly = chunks.size) { llmBackend.generateText(any()) }
        // Fenced mode joins chunks with a paragraph break: the pre-666.2
        // behavior here was NO pass at all (the guard declined outright).
        assertEquals(chunks.joinToString("\n\n"), result.getOrNull())
    }

    @Test
    fun `an engine wedge timeout stops the pass after one chunk`() = runTest {
        coEvery { logDao.getByTaskId(any()) } returns LogEntity(
            id = "chunk-3", timestamp = 1, taskId = "chunk-3",
            type = "AUDIO", status = "PROCESSING", prompt = "", result = "")
        every { preferencesManager.punctuationMode } returns flowOf("conservative")
        stubSwapToLlm()
        val raw = longTranscript(sentences = 400)
        val chunks = ContextChunker.split(raw)
        // Chunk 1 wedges the engine; every later chunk must keep its
        // original WITHOUT another engine entry (the TASK-594 breaker).
        coEvery { llmBackend.generateText(any()) } returns
            Result.failure(com.antivocale.app.manager.EngineWedgeTimeoutException("wedge"))
        val audioFile = temporaryFolder.newFile("wedge.ogg")
        stubWholeFileDecode(gigaamBackend, raw)

        val result = orchestrator.processRequest(
            taskId = "chunk-3", requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

        assertTrue("request failed: ${result.exceptionOrNull()}", result.isSuccess)
        // One engine entry TOTAL (the wedge), then the breaker skips the rest.
        coVerify(exactly = 1) { llmBackend.generateText(any()) }
        // All chunks failed -> the original ships verbatim, no joiner splice.
        assertEquals(raw, result.getOrNull())
    }

    @Test
    fun `always mode joins polished chunks with a plain space`() = runTest {
        coEvery { logDao.getByTaskId(any()) } returns LogEntity(
            id = "chunk-4", timestamp = 1, taskId = "chunk-4",
            type = "AUDIO", status = "PROCESSING", prompt = "", result = "")
        every { preferencesManager.punctuationMode } returns flowOf("always")
        stubSwapToLlm()
        val raw = longTranscript(sentences = 400)
        val chunks = ContextChunker.split(raw)
        assertTrue(chunks.size > 1)
        coEvery { llmBackend.generateText(any()) } returnsMany chunks.map { Result.success(it) }
        val audioFile = temporaryFolder.newFile("long3.ogg")
        stubWholeFileDecode(gigaamBackend, raw)

        val result = orchestrator.processRequest(
            taskId = "chunk-4", requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

        assertTrue("request failed: ${result.exceptionOrNull()}", result.isSuccess)
        // Unfenced polish restores flow: chunks join with a space, no
        // invented paragraph breaks.
        assertEquals(chunks.joinToString(" "), result.getOrNull())
    }

    @Test
    fun `a chunk that violates the conservative fence keeps its original text`() = runTest {
        coEvery { logDao.getByTaskId(any()) } returns LogEntity(
            id = "chunk-2", timestamp = 1, taskId = "chunk-2",
            type = "AUDIO", status = "PROCESSING", prompt = "", result = "")
        every { preferencesManager.punctuationMode } returns flowOf("conservative")
        stubSwapToLlm()
        val raw = longTranscript(sentences = 400)
        val chunks = ContextChunker.split(raw)
        // Chunk 2 answers with an ALTERED token sequence (a whole new word):
        // the conservative fence rejects it; chunks 1..n echo-clean.
        val answers = chunks.mapIndexed { i, c ->
            if (i == 1) Result.success(c + " лишнее слово") else Result.success(c)
        }
        coEvery { llmBackend.generateText(any()) } returnsMany answers
        val audioFile = temporaryFolder.newFile("long2.ogg")
        stubWholeFileDecode(gigaamBackend, raw)

        val result = orchestrator.processRequest(
            taskId = "chunk-2", requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

        assertTrue("request failed: ${result.exceptionOrNull()}", result.isSuccess)
        // The misbehaving chunk contributes its ORIGINAL text; the others'
        // polish survives. One bad chunk costs its own paragraphing, never
        // the whole transcript (the pre-666.2 whole-pass fence discarded
        // EVERYTHING on a single violation below the guard).
        // Chunks 1..n echoed verbatim, so the expectation is the plain
        // join; the misbehaving chunk's ORIGINAL is also its echo form.
        val expected = chunks.joinToString("\n\n")
        assertEquals(expected, result.getOrNull())
    }

    @Test
    fun `degenerate polished output is rejected in favor of the original`() = runTest {
        every { preferencesManager.punctuationMode } returns flowOf("always")
        stubSwapToLlm()
        coEvery { llmBackend.generateText(any()) } returns Result.success("Да.")
        val audioFile = temporaryFolder.newFile("audio.ogg")
        stubWholeFileRequest(audioFile)

        val result = orchestrator.processRequest(
            taskId = "punct-4", requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

        assertTrue("request failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(rawTranscript, result.getOrNull())
    }
}
