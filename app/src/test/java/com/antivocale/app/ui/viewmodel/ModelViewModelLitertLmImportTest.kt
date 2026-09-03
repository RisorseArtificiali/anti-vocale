package com.antivocale.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.ExternalModelImporter
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.LitertLmFile
import com.antivocale.app.data.LitertLmUrlImporter
import com.antivocale.app.data.PreferencesManagerImpl
import com.antivocale.app.transcription.staticRegistry
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-373 (device-free): the litert-lm URL import must persist model_path and
 * backend "llm" into the REAL DataStore file (same guarantee class as the
 * USE-button persistence test for GH #23 route 3), and a listing failure must
 * leave preferences untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModelViewModelLitertLmImportTest {

    private val testDispatcher = StandardTestDispatcher()
    private val backendKey = stringPreferencesKey("transcription_backend")
    private val modelPathKey = stringPreferencesKey("model_path")

    private lateinit var context: Context
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var prefs: PreferencesManagerImpl
    private lateinit var viewModel: ModelViewModel
    private lateinit var file: File
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val importer = mockk<LitertLmUrlImporter>()

    private fun catalogJson(): String {
        val asset = File("src/main/assets/models_catalog.json")
            .takeIf { it.exists() } ?: File("app/src/main/assets/models_catalog.json")
        return asset.readText()
    }

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        file = File.createTempFile("prefs-litertlm-${System.nanoTime()}", ".preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        prefs = PreferencesManagerImpl(context, dataStore).apply { initialize() }

        val assetManager = mockk<android.content.res.AssetManager>(relaxed = true)
        every { assetManager.open(any()) } answers {
            ByteArrayInputStream(catalogJson().toByteArray(Charsets.UTF_8))
        }
        val mockContext = mockk<Context>(relaxed = true) {
            every { filesDir } returns context.filesDir
            every { assets } returns assetManager
            every { getString(any()) } answers { "str:${args[0]}" }
            every { getString(any(), *anyVararg()) } answers {
                val formatArgs = (args.getOrNull(1) as? Array<*>)?.joinToString(",") ?: ""
                "str:${args[0]}:$formatArgs"
            }
        }
        every { mockContext.applicationContext } returns mockContext
        com.antivocale.app.data.catalog.BundledCatalog.attach(mockContext)
        viewModel = ModelViewModel(
            preferencesManager = prefs,
            activeModelRepository = ActiveModelRepository(prefs, mockContext, staticRegistry()),
            tokenManager = mockk(relaxed = true),
            benchmarkManager = mockk(relaxed = true),
            backendManager = mockk(relaxed = true),
            llmManager = mockk(relaxed = true),
            shareTargetManager = mockk(relaxed = true),
            ctx = mockContext,
            backendRegistry = staticRegistry(),
            externalModelStore = ExternalModelStore(prefs),
            externalModelImporter = ExternalModelImporter(
                store = ExternalModelStore(prefs),
                filesRoot = { Files.createTempDirectory("litertlm-ext").toFile() },
            ),
            litertLmUrlImporter = importer,
            externalCatalogRepository = mockk(relaxed = true),
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        scope.cancel()
        file.delete()
    }

    @Test
    fun `import persists model path and llm backend into the real DataStore`() = runTest {
        val downloaded = File.createTempFile("imported-model", ".litertlm").apply { writeBytes(ByteArray(8)) }
        every {
            importer.importFromUrl(any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(downloaded)

        viewModel.importLitertLmFile(
            "https://huggingface.co/o/r", LitertLmFile("imported.litertlm", 8L))
        runCurrent()

        // importLitertLmFile runs on the real Dispatchers.IO; poll until the
        // persistence tail lands (bounded, fails loudly on timeout).
        val deadline = System.currentTimeMillis() + 5_000
        while (dataStore.data.first()[backendKey] == null &&
               System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        assertEquals("llm", dataStore.data.first()[backendKey])
        assertEquals(downloaded.absolutePath, dataStore.data.first()[modelPathKey])
        // Let the activeModelFlow collector (test dispatcher) settle before
        // asserting the UI mirror of the persisted state. The collector's name
        // is the llm descriptor's fixed localized label (mocked as "str:<id>"),
        // mirroring what happens for every backend after a selection.
        runCurrent()
        assertEquals(downloaded.absolutePath, viewModel.uiState.value.modelPath)
        assertTrue(viewModel.uiState.value.modelName.isNotBlank())
    }

    @Test
    fun `list failure leaves preferences untouched and clears importing state`() = runTest {
        every { importer.listModels(any()) } throws IllegalArgumentException("unsupported URL")

        viewModel.listLitertLmModels("https://example.com/nope")
        runCurrent()

        assertEquals(null, dataStore.data.first()[backendKey])
        assertEquals(null, dataStore.data.first()[modelPathKey])
        assertTrue(!viewModel.uiState.value.litertLmImporting)
    }

    @Test
    fun `import start clears candidates atomically - the auto-import effect cannot refire`() = runTest {
        // TASK-390 (analysis recommendation): candidates are cleared in the SAME
        // uiState.update that sets the busy flag, before any suspension, so the
        // LaunchedEffect keyed on candidates.size sees 0 and never re-triggers on
        // recomposition mid-download (the 2026-08-23 double-start bug class).
        val downloaded = File.createTempFile("imported-model", ".litertlm").apply { writeBytes(ByteArray(8)) }
        every { importer.listModels(any()) } returns listOf(
            LitertLmFile("one.litertlm", 1L), LitertLmFile("two.litertlm", 2L))
        viewModel.listLitertLmModels("https://huggingface.co/o/r")
        // Poll: candidates land (dispatchers settle)
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.uiState.value.litertLmCandidates.isEmpty() &&
               System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(2, viewModel.uiState.value.litertLmCandidates.size)

        every { importer.importFromUrl(any(), any(), any(), any(), any(), any(), any()) } answers {
            // Simulate the download suspension: during it, the effect must see
            // zero candidates and a busy flag.
            runBlocking { delay(300) }
            Result.success(downloaded)
        }
        viewModel.importLitertLmFile("https://huggingface.co/o/r",
            viewModel.uiState.value.litertLmCandidates.first())
        // The start update runs on Dispatchers.IO: poll for the busy flag (the
        // download is delayed 300ms inside the mock, so this is well before any
        // completion), then assert the atomic clear.
        val deadline2 = System.currentTimeMillis() + 5_000
        while (!viewModel.uiState.value.litertLmImporting &&
               System.currentTimeMillis() < deadline2) Thread.sleep(20)
        assertTrue(viewModel.uiState.value.litertLmImporting)
        assertEquals(0, viewModel.uiState.value.litertLmCandidates.size)
    }
}
