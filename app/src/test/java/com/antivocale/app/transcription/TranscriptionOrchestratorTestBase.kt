package com.antivocale.app.transcription

import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.TranscriptionCalibrator
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.service.TranscriptionListener
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Before

abstract class TranscriptionOrchestratorTestBase {

    protected lateinit var preferencesManager: PreferencesManager
    protected lateinit var logDao: LogDao
    protected lateinit var transcriptionCalibrator: TranscriptionCalibrator
    protected lateinit var backendManager: TranscriptionBackendManager
    protected lateinit var audioPreprocessor: AudioPreprocessor
    protected lateinit var listener: TranscriptionListener
    protected lateinit var orchestrator: TranscriptionOrchestrator

    @Before
    open fun baseSetUp() {
        preferencesManager = mockk(relaxed = true)
        logDao = mockk(relaxed = true) {
            coEvery { getByTaskId(any()) } returns null
        }
        transcriptionCalibrator = mockk(relaxed = true)
        backendManager = mockk(relaxed = true)
        audioPreprocessor = mockk(relaxed = true)
        listener = mockk(relaxed = true)

        orchestrator = TranscriptionOrchestrator(
            preferencesManager, logDao, transcriptionCalibrator, backendManager, audioPreprocessor
        )
    }

    protected fun stubWhisperBackend(): TranscriptionBackend =
        mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns "whisper"
            every { isReady() } returns true
            every { isAudioSupported() } returns true
            every { supportsAudio } returns true
            every { maxChunkDurationSeconds } returns 30
            every { displayName } returns "Whisper"
        }.also { backend ->
            every { backendManager.hasActiveBackend() } returns true
            every { backendManager.getActiveBackend() } returns backend
        }

    protected fun stubDefaultWhisperPreferences() {
        every { preferencesManager.transcriptionBackend } returns flowOf("whisper")
        every { preferencesManager.vadEnabled } returns flowOf(false)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.inferenceProvider } returns flowOf("auto")
        every { preferencesManager.defaultPrompt } returns flowOf("")
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { preferencesManager.whisperModelPath } returns flowOf("/models/whisper")
    }

    protected fun stubPreprocessing(
        chunks: List<ByteArray>,
        totalDurationSeconds: Double = 30.0,
        isVadSegmented: Boolean = false
    ) {
        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = any(),
                cacheDir = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                vadNumThreads = any(),
                vadProvider = any()
            )
        } returns AudioPreprocessor.PreprocessingResult(
            chunks = chunks,
            totalDurationSeconds = totalDurationSeconds,
            chunkCount = chunks.size,
            isVadSegmented = isVadSegmented
        )
    }
}
