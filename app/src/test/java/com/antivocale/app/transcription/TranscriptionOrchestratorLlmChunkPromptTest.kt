package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.audio.AudioPreprocessor
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TASK-370 acceptance #2/#4: multi-chunk LLM audio never applies the user's
 * generative prompt per chunk; chunks are transcribed plain and the custom
 * prompt runs exactly once as a final text-only pass over the concatenation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorLlmChunkPromptTest : TranscriptionOrchestratorTestBase() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val customPrompt = "Riassumi e riscrivi in forma formale questo vocale."
    private val expectedTranscript = "chunk-1 chunk-2 chunk-3 chunk-4 chunk-5 chunk-6 chunk-7 chunk-8"

    private lateinit var backend: FakeLlmBackend

    private class FakeLlmBackend : TranscriptionBackend {
        val chunkPrompts = mutableListOf<String>()
        val generateTextPrompts = mutableListOf<String>()
        var generateTextResult: Result<String> = Result.success("GENERATIVE(1)")
        override val id = LlmTranscriptionBackend.BACKEND_ID
        override val displayName = "Gemma (LiteRT-LM)"
        override val supportsAudio = true
        override val supportsText = true
        override val maxChunkDurationSeconds: Int = 30
        // TASK-408 moved TASK-370's forced-VAD flag from an orchestrator id check
        // onto the backend interface; the fake mirrors the production override.
        override val requiresVadAlignedChunking: Boolean = true
        override suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String) =
            Result.success(TranscriptionResult(text = "chunk-${chunkPrompts.size + 1}"))
                .also { chunkPrompts.add(prompt) }
        override suspend fun generateText(prompt: String): Result<String> =
            generateTextResult.also { generateTextPrompts.add(prompt) }
        override suspend fun initialize(context: Context, config: BackendConfig) = Result.success(Unit)
        override fun isReady() = true
        override fun isAudioSupported() = true
        override fun unload() {}
        override fun setKeepAliveTimeout(minutes: Int) {}
        override fun getModelPath(): String? = null
    }

    override fun baseSetUp() {
        super.baseSetUp()
        backend = FakeLlmBackend()
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns backend
        every { preferencesManager.transcriptionBackend } returns flowOf("llm")
        every { preferencesManager.vadEnabled } returns flowOf(false)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.defaultPrompt } returns flowOf(customPrompt)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { preferencesManager.modelPath } returns flowOf("/models/m.litertlm")
        every { preferencesManager.inferenceProvider } returns flowOf("auto")
        every { preferencesManager.progressiveTranscription } returns flowOf(false)
        coEvery { transcriptionCalibrator.getEstimate(any(), any()) } returns null
    }

    private fun stubEightChunks() {
        val chunks = (1..8).map { FloatArray(1000) { 0.5f } }
        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = any(), cacheDir = any(), maxChunkDurationSeconds = any(),
                context = any(), enableVad = any(), vadNumThreads = any(), vadProvider = any())
        } returns AudioPreprocessor.PreprocessingResult(
            chunks = chunks, sampleRate = 16000,
            totalDurationSeconds = 240.0, chunkCount = 8, isVadSegmented = false)

        // The pipelined path (!vad && chunkDuration != null) streams via
        // prepareAudioStream; stub it too (same as the ParallelTest helper).
        val streamEvents = buildList {
            add(AudioPreprocessor.StreamEvent.Header(
                AudioPreprocessor.StreamHeader(
                    sampleRate = 16000,
                    totalDurationSeconds = 240.0,
                    expectedChunkCount = 8)))
            chunks.forEachIndexed { index, samples ->
                add(AudioPreprocessor.StreamEvent.Chunk(
                    AudioPreprocessor.StreamChunk(
                        samples = samples, sampleRate = 16000,
                        chunkIndex = index, isLast = index == chunks.lastIndex)))
            }
        }
        every {
            audioPreprocessor.prepareAudioStream(
                inputPath = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any()
            )
        } returns kotlinx.coroutines.flow.flow {
            streamEvents.forEach { emit(it) }
        }
    }

    private suspend fun CoroutineScope.runAudioRequest(prompt: String = ""): Result<String> {
        val audioFile = File(temporaryFolder.root, "audio.wav")
        audioFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        return orchestrator.processRequest(
            taskId = "llm-chunks", requestType = "audio", prompt = prompt,
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk<Context>(relaxed = true),
            cacheDir = temporaryFolder.root, listener = listener, coroutineScope = this)
    }

    @Test
    fun `eight chunks all get the plain prompt and the custom prompt runs once at the end`() = runTest {
        stubEightChunks()
        val result = runAudioRequest()

        assertTrue("expected success, got: ${result.exceptionOrNull()}; chunkPrompts=${backend.chunkPrompts}", result.isSuccess)
        assertEquals(8, backend.chunkPrompts.size)
        backend.chunkPrompts.forEach {
            assertEquals(ChunkPromptPolicy.PLAIN_TRANSCRIPTION_PROMPT, it)
        }
        assertEquals(1, backend.generateTextPrompts.size)
        val finalPrompt = backend.generateTextPrompts.single()
        assertTrue(finalPrompt.startsWith(customPrompt))
        assertTrue(finalPrompt.contains(expectedTranscript))
        assertEquals("GENERATIVE(1)", result.getOrNull())
    }

    @Test
    fun `default prompt - no final pass, plain transcript returned`() = runTest {
        every { preferencesManager.defaultPrompt } returns flowOf("")
        stubEightChunks()
        val result = runAudioRequest()

        assertTrue(result.isSuccess)
        assertEquals(0, backend.generateTextPrompts.size)
        assertEquals(expectedTranscript, result.getOrNull())
    }

    @Test
    fun `final pass failure falls back to the raw transcript instead of failing the task`() = runTest {
        stubEightChunks()
        backend.generateTextResult = Result.failure(IllegalStateException("boom"))
        val result = runAudioRequest()

        assertTrue(result.isSuccess)
        assertEquals(expectedTranscript, result.getOrNull())
    }

    @Test
    fun `llm backend forces VAD-aligned chunking even when the user toggle is off`() = runTest {
        // TASK-370 analysis outcome: fixed 30s cuts land mid-word; the LLM audio
        // encoder is far more boundary-sensitive than Whisper. VAD segmentation
        // is forced for the llm backend regardless of the user toggle.
        every { preferencesManager.vadEnabled } returns flowOf(false)
        stubEightChunks()
        val result = runAudioRequest()
        assertTrue(result.isSuccess)
        io.mockk.verify(atLeast = 1) {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = any(), cacheDir = any(), maxChunkDurationSeconds = any(),
                context = any(), enableVad = true, vadNumThreads = any(), vadProvider = any())
        }
    }
}
