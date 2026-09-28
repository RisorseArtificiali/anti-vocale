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
import java.util.concurrent.atomic.AtomicBoolean

abstract class TranscriptionOrchestratorTestBase {

    protected lateinit var preferencesManager: PreferencesManager
    protected lateinit var logDao: LogDao
    protected lateinit var transcriptionCalibrator: TranscriptionCalibrator
    protected lateinit var backendManager: TranscriptionBackendManager
    protected lateinit var audioPreprocessor: AudioPreprocessor
    protected lateinit var listener: TranscriptionListener
    protected lateinit var llmManager: com.antivocale.app.manager.LlmManager
    protected lateinit var orchestrator: TranscriptionOrchestrator

    /** Fake store backed by FakePreferencesManager so add()/byId() work in tests. */
    protected val fakeStore: ExternalModelStore = ExternalModelStore(
        FakePreferencesManager(),
        dirExists = { true },
    )

    /**
     * TASK-675: a REAL demoter over its own fake preferences, so tests drive
     * the actual threshold/persist logic and assert through the same API the
     * app uses (a relaxed-mock Flow would explode on first()).
     */
    protected val silentModelDemoter: SilentModelDemoter =
        SilentModelDemoter(FakePreferencesManager())

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
        llmManager = mockk(relaxed = true)

        orchestrator = TranscriptionOrchestrator(
            preferencesManager, logDao, transcriptionCalibrator, backendManager, audioPreprocessor,
            staticRegistry(),
            // TASK-660 review F2: the heal's share-surface retirement hooks.
            mockk(relaxed = true),
            mockk(relaxed = true),
            fakeStore,
            silentModelDemoter,
            // TASK-679: the real recorder over the same mocks, so the
            // breadcrumb tests drive the shipped capture path.
            OomBreadcrumbRecorder(preferencesManager, backendManager, llmManager, staticRegistry()),
            // TASK-670 (GH #83): the real voiceprint store over a throwaway
            // dir; the naming pass never runs in these tests (the flag stays
            // off on the relaxed mock), an empty store is the floor.
            com.antivocale.app.transcription.diarization.SpeakerIdentityStore(
                java.io.File.createTempFile("speaker-ids", null).let { file ->
                    file.delete(); file.mkdirs(); file
                }),
        )

        // Default the opt-in memory protection to off in tests so it does not interfere with
        // orchestrator behaviour assertions. (The check itself is fail-open on a mock Context
        // anyway, but stubbing the preference keeps the intent explicit.)
        every { preferencesManager.memoryProtection } returns flowOf(false)
        // GH #45: the model-name write reads the LLM model path before deriving the
        // display name; a relaxed mock Flow explodes on first().
        every { preferencesManager.modelPath } returns flowOf("/models/gemma")
        // TASK-121.4: the summary toggle off at the BASE (unlike punctuationMode,
        // which is only stubbed in stubDefaultWhisperPreferences): the pass runs
        // after the punctuation pass for every audio test, so every one of them
        // needs the explicit off, not just the whisper-shaped ones.
        every { preferencesManager.summarizeEnabled } returns flowOf(false)
        // TASK-546: the success fold resolves the language pin for EVERY request
        // (text paths included), so this read is as unavoidable as modelPath above;
        // unstubbed it is a relaxed-mock Flow and first() explodes (NoSuchElementException),
        // which the generic catch turns into Result.failure. "" = the untouched
        // preference, which resolvedLanguagePin reports as "auto". Tests that pin
        // a language re-stub this after baseSetUp and win.
        every { preferencesManager.transcriptionLanguage } returns flowOf("")
        // TASK-186: the early-preview read sits on the audio path next to
        // progressiveTranscription; default OFF keeps every existing test on
        // the identity behavior (an unstubbed relaxed-mock Flow explodes on
        // first(), the modelPath trap above).
        every { preferencesManager.earlyPreviewEnabled } returns flowOf(false)
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

    /**
     * TASK-682: the shared gigaam whole-file funnel fixture (extracted from
     * the near-verbatim pair in RepetitionCollapseTest + PunctuationPassTest:
     * the stub-BOTH-transcribe-methods gotcha now lives in ONE place).
     */
    protected fun setUpGigaamWholeFileFixture(gigaamBackend: TranscriptionBackend) {
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns gigaamBackend
        every { preferencesManager.transcriptionBackend } returns flowOf("gigaam")
        every { preferencesManager.vadEnabled } returns flowOf(false)
        every { preferencesManager.sherpaModelPath("gigaam") } returns flowOf("/models/gigaam")
    }

    /**
     * The whole-file single-chunk stub: the path calls
     * transcribeAudioStreaming (the interface default forwards to
     * transcribeAudio, but on a mock the relaxed stub would fabricate
     * Result<Object>: stub BOTH).
     */
    protected fun stubWholeFileDecode(
        backend: TranscriptionBackend,
        text: String,
        segments: List<TimedSegment> = emptyList(),
    ) {
        stubPreprocessing(listOf(FloatArray(3) { it.toFloat() }), totalDurationSeconds = 5.0)
        coEvery { backend.transcribeAudio(any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = text, segments = segments))
        coEvery { backend.transcribeAudioStreaming(any(), any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = text, segments = segments))
    }

    /** The backend swap flips which backend getActiveBackend answers with. */
    protected fun stubBackendSwapToLlm(
        gigaamBackend: TranscriptionBackend,
        llmBackend: TranscriptionBackend,
    ) {
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

    /**
     * TASK-664: the content-keyed decode stub. The answer is keyed on the
     * FEED's size and first sample (the chunk's content), never on call
     * order, because the empty-chunk ladder adds re-feed calls whose feeds
     * are never chunk-shaped; an order-keyed stub misroutes once it runs.
     * Covers the streaming variant too (the single-chunk whole-file arm).
     */
    protected fun TranscriptionBackend.stubContentKeyedDecodes(
        answer: (feedSize: Int, firstSample: Float) -> Result<TranscriptionResult>,
    ) {
        coEvery { transcribeAudio(any(), any(), any()) } answers {
            val feed = firstArg<FloatArray>()
            answer(feed.size, feed[0])
        }
        coEvery { transcribeAudioStreaming(any(), any(), any(), any()) } answers {
            val feed = firstArg<FloatArray>()
            answer(feed.size, feed[0])
        }
    }
}
