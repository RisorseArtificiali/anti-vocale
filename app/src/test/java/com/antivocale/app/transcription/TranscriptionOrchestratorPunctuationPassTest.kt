package com.antivocale.app.transcription

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
        // Base fixture stub (any() inputPath, non-VAD): identical to the old
        // 17-line local block.
        stubPreprocessing(listOf(FloatArray(3) { it.toFloat() }), totalDurationSeconds = 5.0)
        // The single-chunk whole-file path calls transcribeAudioStreaming (the
        // interface default forwards to transcribeAudio, but on a mock the
        // relaxed stub would fabricate Result<Object>: stub BOTH).
        coEvery { gigaamBackend.transcribeAudio(any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = rawTranscript))
        coEvery { gigaamBackend.transcribeAudioStreaming(any(), any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = rawTranscript))
    }

    /** The backend swap flips which backend getActiveBackend answers with. */
    private fun stubSwapToLlm() {
        val swapped = AtomicBoolean(false)
        every { backendManager.getActiveBackend() } answers {
            if (swapped.get()) llmBackend else gigaamBackend
        }
        coEvery {
            backendManager.setActiveBackend(eq(LlmTranscriptionBackend.BACKEND_ID), any(), any())
        } coAnswers {
            swapped.set(true)
            Result.success(Unit)
        }
    }

    @Test
    fun `auto mode punctuates a non-punctuating model via the llm backend`() = runTest {
        every { preferencesManager.punctuationMode } returns flowOf("auto")
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

    @Test
    fun `degenerate polished output is rejected in favor of the original`() = runTest {
        every { preferencesManager.punctuationMode } returns flowOf("auto")
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
