package com.antivocale.app.transcription

import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.FilePin
import com.antivocale.app.data.ModelFamily
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

    /** Fake store backed by FakePreferencesManager so add()/byId() work in tests. */
    protected val fakeStore: ExternalModelStore = ExternalModelStore(
        FakePreferencesManager(),
        dirExists = { true },
    )

    /** Builds a minimal TRANSDUCER record with a nemo_transducer modelType. */
    protected fun externalRecord(id: String, dir: String): ExternalModelRecord = ExternalModelRecord(
        id = id,
        displayName = "Test external $id",
        dir = dir,
        family = ModelFamily.TRANSDUCER,
        modelType = "nemo_transducer",
        languages = listOf("en"),
        source = ExternalModelSource.LOCAL,
        sourceUrl = null,
        files = mapOf("encoder.onnx" to FilePin("a".repeat(64), verified = true)),
        sizeBytes = 1L,
        importedAt = System.currentTimeMillis(),
    )

    @Before
    open fun baseSetUp() {
        // Managers/downloaders resolve model metadata through BundledCatalog; seed it
        // from the real asset (read from disk, same probing as BundledModelCatalogTest)
        // so no Android assets are needed in these non-Robolectric tests.
        val moduleRelative = java.io.File("src/main/assets/models_catalog.json")
        val rootRelative = java.io.File("app/src/main/assets/models_catalog.json")
        val asset = when {
            moduleRelative.exists() -> moduleRelative
            rootRelative.exists() -> rootRelative
            else -> throw IllegalStateException(
                "Cannot locate models_catalog.json from ${java.io.File(".").absolutePath}")
        }
        com.antivocale.app.data.catalog.BundledCatalog.seed(
            com.antivocale.app.data.catalog.ModelCatalogJson.parseCatalog(asset.readText()))

        preferencesManager = mockk(relaxed = true)
        logDao = mockk(relaxed = true) {
            coEvery { getByTaskId(any()) } returns null
        }
        transcriptionCalibrator = mockk(relaxed = true)
        backendManager = mockk(relaxed = true)
        audioPreprocessor = mockk(relaxed = true)
        listener = mockk(relaxed = true)

        orchestrator = TranscriptionOrchestrator(
            preferencesManager, logDao, transcriptionCalibrator, backendManager, audioPreprocessor,
            staticRegistry(),
            fakeStore,
        )

        // Default the OOM pre-flight to off in tests so it does not interfere with orchestrator
        // behaviour assertions. (The memory check itself is fail-open on a mock Context anyway,
        // but stubbing the preference keeps the intent explicit.)
        every { preferencesManager.forceModelLoad } returns flowOf(false)
        // GH #45: the model-name write reads the LLM model path before deriving the
        // display name; a relaxed mock Flow explodes on first().
        every { preferencesManager.modelPath } returns flowOf("/models/gemma")
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
        every { preferencesManager.progressiveTranscription } returns flowOf(false)
        // TASK-276: OFF by default in the fixture so existing tests never take
        // the punctuation pass; the dedicated pass test overrides these.
        every { preferencesManager.punctuationMode } returns flowOf("off")
        every { preferencesManager.punctuationPrompt } returns flowOf("")
        every { preferencesManager.sherpaModelPath("whisper") } returns flowOf("/models/whisper")
    }

    protected fun stubPreprocessing(
        chunks: List<FloatArray>,
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
                vadProvider = any(),
                availableRamBytes = any(),
                maxHeapBytes = any())
        } returns AudioPreprocessor.PreprocessingResult(
            chunks = chunks,
            sampleRate = 16000,
            totalDurationSeconds = totalDurationSeconds,
            chunkCount = chunks.size,
            isVadSegmented = isVadSegmented
        )
    }
}
