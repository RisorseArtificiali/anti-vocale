package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.audio.AudioPreprocessor.PreprocessingError
import com.antivocale.app.data.TranscriptionCalibrator
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

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorAudioTest : TranscriptionOrchestratorTestBase() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var backend: TranscriptionBackend

    override fun baseSetUp() {
        super.baseSetUp()
        backend = stubWhisperBackend()
        stubDefaultWhisperPreferences()
    }

    // ---- Helper ----

    private fun singleChunkResult(audioSamples: FloatArray = FloatArray(3) { it.toFloat() }) =
        AudioPreprocessor.PreprocessingResult(
            chunks = listOf(audioSamples),
            sampleRate = 16000,
            totalDurationSeconds = 5.0,
            chunkCount = 1
        )

    private fun stubSingleChunkStream(audioSamples: FloatArray = FloatArray(3) { it.toFloat() }) {
        every {
            audioPreprocessor.prepareAudioStream(
                inputPath = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any()
            )
        } returns flow {
            emit(AudioPreprocessor.StreamEvent.Header(
                AudioPreprocessor.StreamHeader(
                    sampleRate = 16000,
                    totalDurationSeconds = 5.0,
                    expectedChunkCount = 1
                )
            ))
            emit(AudioPreprocessor.StreamEvent.Chunk(
                AudioPreprocessor.StreamChunk(
                    samples = audioSamples,
                    sampleRate = 16000,
                    chunkIndex = 0,
                    isLast = true
                )
            ))
        }
    }

    private suspend fun CoroutineScope.callProcessRequest(
        taskId: String = "test-1",
        filePath: String? = null,
        prompt: String = "",
        source: String? = null,
        sourcePackage: String? = null
    ): Result<String> {
        val file = filePath ?: temporaryFolder.newFile("audio.ogg").absolutePath
        return orchestrator.processRequest(
            taskId = taskId,
            requestType = "audio",
            prompt = prompt,
            filePath = file,
            source = source,
            sourcePackage = sourcePackage,
            queuePosition = 1,
            queueTotal = 1,
            context = mockk(relaxed = true),
            cacheDir = temporaryFolder.root,
            listener = listener,
            coroutineScope = this@callProcessRequest
        )
    }

    // ---- Tests ----

    @Test
    fun `single chunk success - returns trimmed transcription`() = runTest {
        val audioFile = temporaryFolder.newFile("audio.ogg")
        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = audioFile.absolutePath,
                cacheDir = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                vadNumThreads = any(),
                vadProvider = any()
            )
        } returns singleChunkResult()
        stubSingleChunkStream()

        coEvery { backend.transcribeAudio(any(), any(), any()) } returns Result.success("  Hello world  ")

        val result = callProcessRequest(filePath = audioFile.absolutePath)

        assertTrue(result.isSuccess)
        assertEquals("Hello world", result.getOrNull())
        verify {
            listener.onSuccess(eq("test-1"), eq("Hello world"), eq(false), isNull(), any())
        }
    }

    @Test
    fun `single chunk with blank transcription - returns failure`() = runTest {
        val audioFile = temporaryFolder.newFile("audio.ogg")
        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = audioFile.absolutePath,
                cacheDir = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                vadNumThreads = any(),
                vadProvider = any()
            )
        } returns singleChunkResult()
        stubSingleChunkStream()

        coEvery { backend.transcribeAudio(any(), any(), any()) } returns Result.success("   ")

        val result = callProcessRequest(filePath = audioFile.absolutePath)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("No transcription produced", error?.message)
        verify {
            listener.onError(eq("test-1"), eq("INFERENCE_ERROR"), any(), eq(false), eq(false), any())
        }
    }

    @Test
    fun `single chunk with custom prompt - passes prompt to backend`() = runTest {
        val audioFile = temporaryFolder.newFile("audio.ogg")
        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = audioFile.absolutePath,
                cacheDir = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                vadNumThreads = any(),
                vadProvider = any()
            )
        } returns singleChunkResult()
        stubSingleChunkStream()

        coEvery { backend.transcribeAudio(any(), any(), any()) } returns Result.success("Transcribed text")

        val result = callProcessRequest(
            filePath = audioFile.absolutePath,
            prompt = "Translate to Italian"
        )

        assertTrue(result.isSuccess)
        coVerify { backend.transcribeAudio(any(), any(), eq("Translate to Italian")) }
    }

    @Test
    fun `single chunk falls back to default prompt from preferences`() = runTest {
        val audioFile = temporaryFolder.newFile("audio.ogg")
        every { preferencesManager.defaultPrompt } returns flowOf("Custom prompt")

        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = audioFile.absolutePath,
                cacheDir = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                vadNumThreads = any(),
                vadProvider = any()
            )
        } returns singleChunkResult()
        stubSingleChunkStream()

        coEvery { backend.transcribeAudio(any(), any(), any()) } returns Result.success("Transcribed text")

        val result = callProcessRequest(
            filePath = audioFile.absolutePath,
            prompt = ""
        )

        assertTrue(result.isSuccess)
        coVerify { backend.transcribeAudio(any(), any(), eq("Custom prompt")) }
    }

    @Test
    fun `single chunk with PreprocessingError FileNotFound - returns failure`() = runTest {
        val audioFile = temporaryFolder.newFile("audio.ogg")
        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = audioFile.absolutePath,
                cacheDir = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                vadNumThreads = any(),
                vadProvider = any()
            )
        } throws PreprocessingError.FileNotFound
        every {
            audioPreprocessor.prepareAudioStream(
                inputPath = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any()
            )
        } throws PreprocessingError.FileNotFound

        val result = callProcessRequest(filePath = audioFile.absolutePath)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PreprocessingError.FileNotFound)
        verify {
            listener.onError(eq("test-1"), eq("INFERENCE_ERROR"), any(), eq(false), eq(false), any())
        }
    }

    @Test
    fun `single chunk with generic preprocessing error - returns failure`() = runTest {
        val audioFile = temporaryFolder.newFile("audio.ogg")
        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = audioFile.absolutePath,
                cacheDir = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                vadNumThreads = any(),
                vadProvider = any()
            )
        } throws RuntimeException("Codec exploded")
        every {
            audioPreprocessor.prepareAudioStream(
                inputPath = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any()
            )
        } throws RuntimeException("Codec exploded")

        val result = callProcessRequest(filePath = audioFile.absolutePath)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message?.contains("Pipeline failed") == true)
        verify {
            listener.onError(eq("test-1"), eq("INFERENCE_ERROR"), any(), eq(false), eq(false), any())
        }
    }

    @Test
    fun `null file path - returns failure with IllegalArgumentException`() = runTest {
        val result = orchestrator.processRequest(
            taskId = "test-1",
            requestType = "audio",
            prompt = "",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = mockk(relaxed = true),
            cacheDir = temporaryFolder.root,
            listener = listener,
            coroutineScope = this
        )

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("No file path provided", error?.message)
        verify {
            listener.onError(eq("test-1"), eq("INFERENCE_ERROR"), any(), eq(false), eq(false), any())
        }
    }

    @Test
    fun `file does not exist - returns failure with FileNotFound`() = runTest {
        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = "/nonexistent/path/audio.ogg",
                cacheDir = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                vadNumThreads = any(),
                vadProvider = any()
            )
        } throws PreprocessingError.FileNotFound
        every {
            audioPreprocessor.prepareAudioStream(
                inputPath = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any()
            )
        } throws PreprocessingError.FileNotFound

        val result = callProcessRequest(filePath = "/nonexistent/path/audio.ogg")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is PreprocessingError.FileNotFound)
        verify {
            listener.onError(eq("test-1"), eq("INFERENCE_ERROR"), any(), eq(false), eq(false), any())
        }
    }

    @Test
    fun `backend does not support audio - returns failure`() = runTest {
        every { backend.isAudioSupported() } returns false

        val audioFile = temporaryFolder.newFile("audio.ogg")
        val result = callProcessRequest(filePath = audioFile.absolutePath)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message?.contains("does not support audio transcription") == true)
        verify {
            listener.onError(eq("test-1"), eq("INFERENCE_ERROR"), any(), eq(false), eq(false), any())
        }
    }
}
