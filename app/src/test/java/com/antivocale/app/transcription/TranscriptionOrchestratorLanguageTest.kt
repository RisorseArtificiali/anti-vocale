package com.antivocale.app.transcription

import android.app.ActivityManager
import android.content.Context
import com.antivocale.app.data.catalog.BundledCatalog
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The wiring of the transcription-language mapping through
 * [TranscriptionOrchestrator.loadCatalogBackend] against the REAL bundled
 * catalog (seeded by the test base): the sentinels keep model-side detection
 * (TASK-457 removed the app-locale pinning the "system" default used to
 * carry), pinned codes pass through, and single-language forcing in
 * SherpaBackend still wins. The pure mapping matrix lives in
 * [TranscriptionLanguagePolicyTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorLanguageTest : TranscriptionOrchestratorTestBase() {

    private val tempDirs = mutableListOf<File>()

    override fun baseSetUp() {
        super.baseSetUp()
        every { preferencesManager.inferenceProvider } returns flowOf("auto")
        every { preferencesManager.transcriptionLanguage } returns flowOf("auto")
    }

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    /**
     * Creates a model dir whose NAME is the catalog variant dir-name (how the
     * orchestrator and SherpaBackend detect the installed variant) carrying the
     * variant's catalog files (.onnx non-empty: the completeness check rejects
     * zero-length files).
     */
    private fun createVariantDir(variantName: String): File {
        val entry = BundledCatalog.byId(BuiltInBackendIds.WHISPER)!!
        val variant = entry.variant(variantName)!!
        val parent = File(System.getProperty("java.io.tmpdir"), "whisper-lang-${System.nanoTime()}")
        parent.mkdirs()
        tempDirs.add(parent)
        val variantDir = File(parent, variant.dirName)
        variantDir.mkdirs()
        variant.files.forEach { f -> File(variantDir, f.name).writeBytes(byteArrayOf(1)) }
        return variantDir
    }

    /** OOM pre-flight fails open on a mock Context (see TranscriptionOrchestratorBackendOverrideTest). */
    private fun createMockContext(): Context =
        mockk<Context>(relaxed = true) {
            every { getSystemService(ActivityManager::class.java) } returns null
            every { filesDir } returns File(System.getProperty("java.io.tmpdir"), "ctx-${System.nanoTime()}").apply {
                mkdirs()
                tempDirs.add(this)
            }
        }

    private fun assertLoadedLanguage(
        dir: File,
        preference: String,
        expected: String,
        /** TASK-546 AC3: the per-request override the request carries (null = none). */
        override: String? = null,
    ) = runTest {
        every { preferencesManager.transcriptionBackend } returns flowOf(BuiltInBackendIds.WHISPER)
        every { preferencesManager.sherpaModelPath(BuiltInBackendIds.WHISPER) } returns flowOf(dir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.transcriptionLanguage } returns flowOf(preference)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        orchestrator.processRequest(
            taskId = "test-language-$preference-${dir.name}",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            languageOverride = override,
            queuePosition = 1,
            queueTotal = 1,
            context = createMockContext(),
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        coVerify {
            backendManager.setActiveBackend(
                backendId = BuiltInBackendIds.WHISPER,
                context = any(),
                config = match {
                    it is BackendConfig.SherpaOnnxConfig && it.language == expected
                }
            )
        }
    }

    @Test
    fun `untouched default keeps model-side detection on the small variant`() {
        // TASK-457: the "system" default no longer follows the app locale
        // (the silent pin translated wrong-language audio, GH #84).
        assertLoadedLanguage(
            createVariantDir("small"),
            preference = TranscriptionLanguagePolicy.PREF_SYSTEM,
            expected = "",
        )
    }

    @Test
    fun `untouched default keeps model-side detection on every whisper variant`() {
        assertLoadedLanguage(
            createVariantDir("turbo"),
            preference = TranscriptionLanguagePolicy.PREF_SYSTEM,
            expected = "",
        )
    }

    @Test
    fun `explicit auto keeps model-side detection on the small variant`() {
        assertLoadedLanguage(
            createVariantDir("small"),
            preference = TranscriptionLanguagePolicy.PREF_AUTO,
            expected = "",
        )
    }

    @Test
    fun `pinned language passes through on the small variant`() {
        assertLoadedLanguage(createVariantDir("small"), preference = "it", expected = "it")
    }

    // ---- TASK-546 AC3: the per-request language override (the chip's re-run arm) ----

    @Test
    fun `language override forces its code over the persisted preference`() {
        // The re-run rides a transient override; the persisted preference
        // ("auto" here) is never consulted for THIS request.
        assertLoadedLanguage(
            createVariantDir("small"),
            preference = TranscriptionLanguagePolicy.PREF_AUTO,
            override = "de",
            expected = "de",
        )
    }

    @Test
    fun `language override auto keeps model-side detection`() {
        // The override speaks the preference vocabulary: "auto" detects even
        // when the persisted preference pins a concrete code.
        assertLoadedLanguage(
            createVariantDir("small"),
            preference = "it",
            override = TranscriptionLanguagePolicy.PREF_AUTO,
            expected = "",
        )
    }

    /**
     * The warm-backend residency matrix. The configured language is part of
     * the recognizer's identity (offline backends bake it into the config,
     * streaming reads the configured field per stream): a DIFFERENT desired
     * language must force the reconfigure path, the SAME desired language
     * must stay warm (a batch of same-language re-runs reloads once, not per
     * row), and an override language left resident must be recovered on the
     * next ordinary request (the preference wins again).
     */
    private fun warmBackend(dir: File, residentLanguage: String): TranscriptionBackend =
        mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns BuiltInBackendIds.WHISPER
            every { isReady() } returns true
            every { getModelPath() } returns dir.absolutePath
            every { getConfiguredLanguage() } returns residentLanguage
        }

    private fun stubWarmWhisper(dir: File, residentLanguage: String) {
        every { preferencesManager.transcriptionBackend } returns flowOf(BuiltInBackendIds.WHISPER)
        every { preferencesManager.sherpaModelPath(BuiltInBackendIds.WHISPER) } returns flowOf(dir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.transcriptionLanguage } returns flowOf(TranscriptionLanguagePolicy.PREF_AUTO)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns warmBackend(dir, residentLanguage)
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)
    }

    @Test
    fun `language override reconfigures when the warm language differs`() = runTest {
        val dir = createVariantDir("small")
        // Warm under auto-detect (the backend stores the blank resolution as "auto").
        stubWarmWhisper(dir, residentLanguage = "auto")

        orchestrator.processRequest(
            taskId = "test-language-warm-differs",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            languageOverride = "de",
            queuePosition = 1,
            queueTotal = 1,
            context = createMockContext(),
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        verify(atLeast = 1) { backendManager.unloadActiveBackend() }
        coVerify(atLeast = 1) {
            backendManager.setActiveBackend(
                backendId = BuiltInBackendIds.WHISPER,
                context = any(),
                config = match { it is BackendConfig.SherpaOnnxConfig && it.language == "de" }
            )
        }
    }

    @Test
    fun `language override equal to the warm language stays warm`() = runTest {
        val dir = createVariantDir("small")
        // A batch of wrong-language messages: rows 2..N re-run under the same
        // language the engine already holds. Reloading would re-map hundreds
        // of MB of weights per row for an identical configuration.
        stubWarmWhisper(dir, residentLanguage = "de")

        orchestrator.processRequest(
            taskId = "test-language-warm-equal",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            languageOverride = "de",
            queuePosition = 1,
            queueTotal = 1,
            context = createMockContext(),
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        verify(exactly = 0) { backendManager.unloadActiveBackend() }
        coVerify(exactly = 0) { backendManager.setActiveBackend(any(), any(), any()) }
    }

    @Test
    fun `an override language left warm is recovered on the next ordinary request`() = runTest {
        val dir = createVariantDir("small")
        // After an override run the engine holds "de" while the preference
        // still says auto: the next ordinary request must reconfigure back,
        // not serve the stale override while pinning the preference.
        stubWarmWhisper(dir, residentLanguage = "de")

        orchestrator.processRequest(
            taskId = "test-language-warm-recover",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            languageOverride = null,
            queuePosition = 1,
            queueTotal = 1,
            context = createMockContext(),
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        verify(atLeast = 1) { backendManager.unloadActiveBackend() }
        coVerify(atLeast = 1) {
            backendManager.setActiveBackend(
                backendId = BuiltInBackendIds.WHISPER,
                context = any(),
                // The auto preference resolves to blank on a passLanguage entry.
                config = match { it is BackendConfig.SherpaOnnxConfig && it.language == "" }
            )
        }
    }

    /**
     * The override must land on the ROW too: languagePin records what the
     * request actually ran under, not the persisted preference (the TASK-545
     * report-time lesson). Warm-backend audio run, mirroring the
     * single-chunk success fixture of TranscriptionOrchestratorAudioTest
     * (the streaming pipeline path: maxChunkDuration != null routes there).
     */
    @Test
    fun `language override pins the row language as what actually ran`() = runTest {
        val backend = stubWhisperBackend()
        stubDefaultWhisperPreferences()
        // The override forces the reconfigure path (see the warm test below),
        // so this run must survive a REAL catalog load: give the entry a
        // variant dir the loader can resolve.
        val variantDir = createVariantDir("small")
        every { preferencesManager.sherpaModelPath(BuiltInBackendIds.WHISPER) } returns
            flowOf(variantDir.absolutePath)
        every { preferencesManager.transcriptionLanguage } returns flowOf("it")
        // The forced reload (below) reaches the real catalog load; its
        // setActiveBackend must answer like the assertLoadedLanguage fixture.
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)
        val audioFile = File(
            File(System.getProperty("java.io.tmpdir"), "lang-override-${System.nanoTime()}")
                .apply { mkdirs(); tempDirs.add(this) },
            "audio.ogg").apply { writeBytes(byteArrayOf(1)) }
        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = audioFile.absolutePath,
                cacheDir = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                vadNumThreads = any(),
                vadProvider = any(),
                availableRamBytes = any(),
                maxHeapBytes = any())
        } returns com.antivocale.app.audio.AudioPreprocessor.PreprocessingResult(
            chunks = listOf(FloatArray(3) { it.toFloat() }),
            sampleRate = 16000,
            totalDurationSeconds = 5.0,
            chunkCount = 1,
            isVadSegmented = false,
        )
        every {
            audioPreprocessor.prepareAudioStream(
                inputPath = any(),
                maxChunkDurationSeconds = any(),
                context = any(),
                enableVad = any(),
                availableRamBytes = any(),
                maxHeapBytes = any())
        } returns kotlinx.coroutines.flow.flow {
            emit(com.antivocale.app.audio.AudioPreprocessor.StreamEvent.Header(
                com.antivocale.app.audio.AudioPreprocessor.StreamHeader(
                    sampleRate = 16000,
                    totalDurationSeconds = 5.0,
                    expectedChunkCount = 1
                )
            ))
            emit(com.antivocale.app.audio.AudioPreprocessor.StreamEvent.Chunk(
                com.antivocale.app.audio.AudioPreprocessor.StreamChunk(
                    samples = FloatArray(3) { it.toFloat() },
                    sampleRate = 16000,
                    chunkIndex = 0,
                    isLast = true
                )
            ))
        }
        coEvery { backend.transcribeAudio(any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = "Hallo Welt"))
        coEvery { logDao.getByTaskId("lang-override-row") } returns
            com.antivocale.app.data.local.LogEntity(
                id = "1", timestamp = 0L, taskId = "lang-override-row",
                type = "AUDIO", status = "PROCESSING", prompt = "")

        orchestrator.processRequest(
            taskId = "lang-override-row",
            requestType = "audio",
            prompt = "",
            filePath = audioFile.absolutePath,
            source = null,
            sourcePackage = null,
            languageOverride = "de",
            queuePosition = 1,
            queueTotal = 1,
            context = createMockContext(),
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        // The override pins as itself; the persisted "it" never ran.
        coVerify(atLeast = 1) { logDao.update(match { it.languagePin == "de" }) }
    }

    /**
     * Matrix 4: forcedLanguage (SherpaBackend, untouched) wins over anything
     * the policy resolves for the single-language Distil-IT variant.
     */
    @Test
    fun `single-language distil variant forces italian regardless of the resolved language`() {
        val entry = BundledCatalog.byId(BuiltInBackendIds.WHISPER)!!
        val distil = entry.variant("distil-large-v3-it")!!
        val backend = SherpaBackend(BuiltInBackendIds.WHISPER)
        // Whatever the orchestrator resolved ("" on the system default, a code
        // when pinned): the distil load always forces "it".
        assertEquals("it", backend.forcedLanguage(entry, distil, ""))
        assertEquals("it", backend.forcedLanguage(entry, distil, "de"))
        assertEquals("it", backend.forcedLanguage(entry, distil, "it"))
    }
}
