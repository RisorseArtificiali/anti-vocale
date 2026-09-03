package com.antivocale.app.ui.viewmodel

import android.content.Context
import com.antivocale.app.R
import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.transcription.staticRegistry
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests proving that a preference change propagates reactively into
 * [ModelViewModel.uiState] through the REAL [ActiveModelRepository]
 * (TASK-258 acceptance #4).
 *
 * Construction follows the house LogsViewModel pattern: a
 * [StandardTestDispatcher] is installed as Main in [setup] and reset in
 * [tearDown]. [FakePreferencesManager] and [ActiveModelRepository] are real;
 * every other constructor dependency (token/benchmark/backend/llm/share-target
 * managers) is a relaxed mockk, and the @ApplicationContext Context is a
 * mockk with filesDir pointed at a real empty temp directory so the init-time
 * download-state scans touch real (nonexistent) paths instead of mocked Files.
 *
 * ModelViewModel's init block calls loadSavedModelPath() itself, so the
 * collector is already running once construction returns; the tests only
 * drain the dispatcher with runCurrent() around preference mutations.
 *
 * getString is stubbed to "str:<resId>[:<args>]" so statusMessage assertions
 * can distinguish the "model ready" resource from the "model not found" one
 * without Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelViewModelActiveModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePrefs: FakePreferencesManager
    private lateinit var viewModel: ModelViewModel

    /** Real empty dir backing the mocked Context's filesDir. */
    private val filesRoot = Files.createTempDirectory("mvm-files").toFile()

    private fun catalogJson(): String {
        val moduleRelative = File("src/main/assets/models_catalog.json")
        val rootRelative = File("app/src/main/assets/models_catalog.json")
        val asset = when {
            moduleRelative.exists() -> moduleRelative
            rootRelative.exists() -> rootRelative
            else -> throw IllegalStateException(
                "Cannot locate models_catalog.json from ${File(".").absolutePath}")
        }
        return asset.readText()
    }

    private val mockContext: Context = mockk<Context>(relaxed = true) {
        every { filesDir } returns filesRoot
        every { getString(any()) } answers { "str:${args[0]}" }
        // The vararg arrives in args[1] as a single Array; render its elements.
        every { getString(any(), *anyVararg()) } answers {
            val formatArgs = (args.getOrNull(1) as? Array<*>)?.joinToString(",") ?: ""
            "str:${args[0]}:$formatArgs"
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakePrefs = FakePreferencesManager()
        // The catalog-driven downloaders resolve through BundledCatalog, so the
        // mocked Context must serve the real bundled asset (read from disk, same
        // probing as BundledModelCatalogTest) and round-trip as its own
        // applicationContext.
        val assetManager = mockk<android.content.res.AssetManager>(relaxed = true)
        every { assetManager.open(any()) } answers {
            ByteArrayInputStream(catalogJson().toByteArray(Charsets.UTF_8))
        }
        every { mockContext.assets } returns assetManager
        every { mockContext.applicationContext } returns mockContext
        com.antivocale.app.data.catalog.BundledCatalog.attach(mockContext)
        viewModel = ModelViewModel(
            preferencesManager = fakePrefs,
            activeModelRepository = ActiveModelRepository(fakePrefs, mockContext, staticRegistry()),
            tokenManager = mockk(relaxed = true),
            benchmarkManager = mockk(relaxed = true),
            backendManager = mockk(relaxed = true),
            llmManager = mockk(relaxed = true),
            shareTargetManager = mockk(relaxed = true),
            ctx = mockContext,
            backendRegistry = staticRegistry(),
            externalModelStore = com.antivocale.app.data.ExternalModelStore(fakePrefs),
            externalModelImporter = com.antivocale.app.data.ExternalModelImporter(
                store = com.antivocale.app.data.ExternalModelStore(fakePrefs),
                filesRoot = { filesRoot },
            ),
            litertLmUrlImporter = io.mockk.mockk(relaxed = true),
            externalCatalogRepository = io.mockk.mockk(relaxed = true),
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
        )

    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        filesRoot.deleteRecursively()
    }

    /**
     * Mechanical wiring proof: the collector started by init{} first sees the
     * default backend with no saved path (UiState cleared), then driving the
     * fake preferences to whisper-with-a-real-directory updates modelPath,
     * modelName and statusMessage through the real repository.
     *
     * The directory is real but contains no tokens.txt, so whisper validity
     * only requires exists()+isDirectory, hence the "ready" status message.
     * Whisper has a fixed catalog display name (whisper_title), so modelName is
     * the localized title rather than the directory name, and the ready message
     * embeds that title.
     */
    @Test
    fun `saved whisper model preference propagates into uiState through real repository`() = runTest {
        // init{} already launched loadSavedModelPath(); drain the default-backend emission
        runCurrent()
        assertEquals("", viewModel.uiState.value.modelPath)

        val modelDir = Files.createTempDirectory("whisper-test").toFile()
        fakePrefs._sherpaModelPath("whisper").value = modelDir.absolutePath
        fakePrefs._transcriptionBackend.value = "whisper"
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(modelDir.absolutePath, state.modelPath)
        assertEquals("str:${R.string.whisper_title}", state.modelName)
        assertEquals(
            "str:${R.string.backend_model_ready}:str:${R.string.whisper_title}",
            state.statusMessage
        )
    }

    @Test
    fun `backend switch mid-collection updates state reactively`() = runTest {
        // Collector running on the initial backend with a saved model path that
        // points at a real directory (whisper: valid -> "ready" status message).
        val whisperDir = Files.createTempDirectory("whisper-initial").toFile()
        fakePrefs._transcriptionBackend.value = "whisper"
        fakePrefs._sherpaModelPath("whisper").value = whisperDir.absolutePath
        runCurrent()

        // Switch to a second backend that has a DIFFERENT saved model path.
        // The .gguf file does not exist, so the gemma4_gguf validity check fails.
        fakePrefs._ggufModelPath.value = "/models/gemma-4-e2b-it.gguf"
        fakePrefs._transcriptionBackend.value = "gemma4_gguf"
        runCurrent()

        // Assertions (profile: model fields + statusMessage; the stale-statusMessage
        // class is exactly what this task fixes, so the flip to "not found" is
        // asserted, not just the name/path pair).
        val state = viewModel.uiState.value
        assertEquals("/models/gemma-4-e2b-it.gguf", state.modelPath)
        assertEquals("gemma-4-e2b-it.gguf", state.modelName)
        assertEquals(
            "str:${R.string.backend_model_not_found}:gemma-4-e2b-it.gguf",
            state.statusMessage
        )
    }
}
