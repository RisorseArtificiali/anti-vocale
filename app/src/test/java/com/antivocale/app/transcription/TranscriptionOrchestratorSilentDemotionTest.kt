package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.audio.AudioPreprocessor
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TASK-675: the orchestrator's detection hook, end to end through
 * processRequest with a fake backend. Pins the two-signal discipline at the
 * raise sites: the run must decode blank (signal 1) AND carry a
 * speech-presence signal (signal 2, per path) before the demoter's N=2
 * session counter ever advances toward a persisted demotion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorSilentDemotionTest : TranscriptionOrchestratorTestBase() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var backend: TranscriptionBackend
    private lateinit var audioFile: File

    override fun baseSetUp() {
        super.baseSetUp()
        backend = stubWhisperBackend()
        stubDefaultWhisperPreferences()
        // Silence the two-pass first pass: phase 1 must not deliver text that
        // would rescue the run from the blank-decode failure under test.
        every { preferencesManager.refinementEnabled } returns flowOf(false)
        audioFile = temporaryFolder.newFile("silent.ogg")
        audioFile.writeBytes(ByteArray(1024))
        // The blank decode: every chunk succeeds with empty text.
        coEvery { backend.transcribeAudio(any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = ""))
        coEvery { backend.transcribeAudioStreaming(any(), any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = ""))
    }

    private suspend fun CoroutineScope.runBlankRequest(taskId: String): Result<String> =
        orchestrator.processRequest(
            taskId = taskId,
            requestType = "audio",
            prompt = "",
            filePath = audioFile.absolutePath,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = mockk(relaxed = true),
            cacheDir = temporaryFolder.root,
            listener = listener,
            coroutineScope = this,
        )

    /** Single decoded chunk on the pipeline path (VAD off). */
    private fun stubPipelineStream(samples: FloatArray) {
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
                    sampleRate = 16000,
                    totalDurationSeconds = samples.size / 16000.0,
                    expectedChunkCount = 1)))
            emit(AudioPreprocessor.StreamEvent.Chunk(
                AudioPreprocessor.StreamChunk(
                    samples = samples,
                    sampleRate = 16000,
                    chunkIndex = 0,
                    isLast = true)))
        }
    }

    // ---- Pipeline path (VAD never runs; signal 2 = decoded seconds) ----

    @Test
    fun `pipeline blank decode never demotes even with decoded audio`() = runTest {
        // Review F1 (TASK-675): the pipeline path never runs VAD, so decoded
        // seconds prove PCM reached the model, not that speech was in it;
        // the strong-signal rule wins over coverage: NO demotion here, on
        // any run count. This test pins the NEW contract (the old
        // demotes-on-second-run expectation was the pre-F1 behavior).
        stubPipelineStream(FloatArray(16000) { 0.5f })
        runBlankRequest("silent-1").let { assertTrue(it.isFailure) }
        runBlankRequest("silent-2").let { assertTrue(it.isFailure) }
        runBlankRequest("silent-3").let { assertTrue(it.isFailure) }
        assertFalse(silentModelDemoter.isDemoted("whisper"))
    }

    @Test
    fun `pipeline blank decode with no decoded audio never demotes`() = runTest {
        // Zero samples reached the model: no speech-presence signal, so the
        // empty transcript is a correct empty, not a model failure (AC #4).
        stubPipelineStream(FloatArray(0))
        runBlankRequest("empty-1").let { assertTrue(it.isFailure) }
        runBlankRequest("empty-2").let { assertTrue(it.isFailure) }
        assertFalse(silentModelDemoter.isDemoted("whisper"))
    }

    // ---- Whole-file path (VAD on; signal 2 = VAD-kept speech duration) ----

    @Test
    fun `whole-file blank decode with vad-kept speech demotes on the second run`() = runTest {
        every { preferencesManager.vadEnabled } returns flowOf(true)
        stubPreprocessing(
            chunks = listOf(FloatArray(16000) { 0.5f }),
            totalDurationSeconds = 12.0)
        runBlankRequest("wf-1").let { assertTrue(it.isFailure) }
        assertFalse(silentModelDemoter.isDemoted("whisper"))
        runBlankRequest("wf-2").let { assertTrue(it.isFailure) }
        assertTrue(silentModelDemoter.isDemoted("whisper"))
    }

    @Test
    fun `whole-file blank decode with zero vad-kept duration never demotes`() = runTest {
        every { preferencesManager.vadEnabled } returns flowOf(true)
        // The VAD stripped to nothing: it heard no speech, so an empty
        // transcript is correct and must not demote the model.
        stubPreprocessing(chunks = listOf(FloatArray(0)), totalDurationSeconds = 0.0)
        runBlankRequest("nospeech-1").let { assertTrue(it.isFailure) }
        runBlankRequest("nospeech-2").let { assertTrue(it.isFailure) }
        assertFalse(silentModelDemoter.isDemoted("whisper"))
    }

    // ---- Progressive path (VAD segments; signal 2 = post-VAD speech total) ----

    @Test
    fun `progressive all-blank segments with speech demote on the second run`() = runTest {
        every { preferencesManager.vadEnabled } returns flowOf(true)
        every { preferencesManager.progressiveTranscription } returns flowOf(true)
        stubPreprocessing(
            chunks = listOf(FloatArray(16000), FloatArray(16000)),
            totalDurationSeconds = 20.0,
            isVadSegmented = true)
        runBlankRequest("prog-1").let { assertTrue(it.isFailure) }
        assertFalse(silentModelDemoter.isDemoted("whisper"))
        runBlankRequest("prog-2").let { assertTrue(it.isFailure) }
        assertTrue(silentModelDemoter.isDemoted("whisper"))
    }

    // ---- Parallel path (VAD segments, progressive off; signal 2 = post-VAD speech total) ----

    @Test
    fun `parallel all-blank chunks with speech demote on the second run`() = runTest {
        every { preferencesManager.vadEnabled } returns flowOf(true)
        every { preferencesManager.progressiveTranscription } returns flowOf(false)
        stubPreprocessing(
            chunks = listOf(FloatArray(16000), FloatArray(16000)),
            totalDurationSeconds = 20.0,
            isVadSegmented = true)
        runBlankRequest("par-1").let { assertTrue(it.isFailure) }
        assertFalse(silentModelDemoter.isDemoted("whisper"))
        runBlankRequest("par-2").let { assertTrue(it.isFailure) }
        assertTrue(silentModelDemoter.isDemoted("whisper"))
    }

    // ---- Not the demotion class: partial text, decode errors ----

    @Test
    fun `a run that delivers text anywhere never counts as silent`() = runTest {
        // Two chunks per run: the first decodes text, the second blank. A
        // working model with one quiet chunk is the opposite of the demotion
        // class; the aggregate is whole-clip-empty only. Chunk-discriminated
        // stub (not returnsMany) so every run sees the same shape.
        val speechChunk = FloatArray(16000) { 0.1f }
        val quietChunk = FloatArray(16000) { 0.2f }
        coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            if (firstArg<FloatArray>()[0] == 0.1f)
                Result.success(TranscriptionResult(text = "hello"))
            else
                Result.success(TranscriptionResult(text = ""))
        }
        every { preferencesManager.vadEnabled } returns flowOf(true)
        every { preferencesManager.progressiveTranscription } returns flowOf(false)
        stubPreprocessing(
            chunks = listOf(speechChunk, quietChunk),
            totalDurationSeconds = 20.0,
            isVadSegmented = true)
        repeat(3) { i ->
            runBlankRequest("partial-$i").let { assertTrue(it.isSuccess) }
        }
        assertFalse(silentModelDemoter.isDemoted("whisper"))
    }


    @Test
    fun `all-failed chunks with speech never demote`() = runTest {
        // Every chunk FAILS (the external backend's own raise surfaces this
        // way): the aggregate is blank but blankChunks == 0, which is a
        // broken-decode error, not a model that decodes empty. Demotion is
        // for the silent class only.
        coEvery { backend.transcribeAudio(any(), any(), any()) } returns
            Result.failure(TranscriptionException.NoTranscriptionProduced())
        stubPipelineStream(FloatArray(16000) { 0.5f })
        runBlankRequest("failed-1").let { assertTrue(it.isFailure) }
        runBlankRequest("failed-2").let { assertTrue(it.isFailure) }
        assertFalse(silentModelDemoter.isDemoted("whisper"))
    }

    // ---- Backend eligibility at the orchestrator call site ----

    @Test
    fun `the llm backend is never demoted by a blank decode`() = runTest {
        // Same shape as TranscriptionOrchestratorLlmChunkPromptTest's fake:
        // the LLM forces VAD-aligned chunking, so its blank decodes reach the
        // whole-file hook with a positive speech signal; the eligibility
        // guard must drop them (empty output is a legitimate LLM answer).
        val llmBackend = object : TranscriptionBackend {
            override val id = LlmTranscriptionBackend.BACKEND_ID
            override val displayName = "Gemma (LiteRT-LM)"
            override val supportsAudio = true
            override val supportsText = true
            override val maxChunkDurationSeconds: Int = 30
            override val requiresVadAlignedChunking: Boolean = true
            override suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String) =
                Result.success(TranscriptionResult(text = ""))
            override suspend fun generateText(prompt: String) = Result.success("")
            override suspend fun initialize(context: Context, config: BackendConfig) = Result.success(Unit)
            override fun isReady() = true
            override fun isAudioSupported() = true
            override fun unload() {}
            override fun setKeepAliveTimeout(minutes: Int) {}
            override fun getModelPath(): String? = null
        }
        every { backendManager.getActiveBackend() } returns llmBackend
        every { preferencesManager.transcriptionBackend } returns flowOf(LlmTranscriptionBackend.BACKEND_ID)
        every { preferencesManager.modelPath } returns flowOf("/models/m.litertlm")
        stubPreprocessing(
            chunks = listOf(FloatArray(16000) { 0.5f }),
            totalDurationSeconds = 12.0)
        runBlankRequest("llm-1").let { assertTrue(it.isFailure) }
        runBlankRequest("llm-2").let { assertTrue(it.isFailure) }
        assertFalse(silentModelDemoter.isDemoted(LlmTranscriptionBackend.BACKEND_ID))
    }
}
