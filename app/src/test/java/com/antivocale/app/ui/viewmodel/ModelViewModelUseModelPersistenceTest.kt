package com.antivocale.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.R
import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.ExternalModelImporter
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.PreferencesManagerImpl
import com.antivocale.app.transcription.SherpaModelManager
import com.antivocale.app.transcription.staticRegistry
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * TASK-342 defect 1: the Model tab USE button must persist the backend selection
 * into the REAL DataStore file, not just flip the UI. The first test replays the
 * exact device scenario at unit level: the persisted backend is a dangling
 * external id and the user taps USE on a downloaded catalog variant; the write
 * must land in the DataStore (read back from the same store instance, byte for
 * byte the file the app ships). The second test pins the failure branch: when no
 * valid model directory resolves, the skip must be LOUD (a user-visible message),
 * not a silent no-op that leaves the previous (possibly dangling) backend active.
 *
 * Construction follows ModelViewModelActiveModelTest, but preferences go through
 * a real temp-file DataStore via [PreferencesManagerImpl]'s injected-store seam
 * (same pattern as PreferencesManagerMigrationTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModelViewModelUseModelPersistenceTest {

    private val testDispatcher = StandardTestDispatcher()
    private val backendKey = stringPreferencesKey("transcription_backend")
    private val sherpaWhisperKey = stringPreferencesKey("sherpa_model_path_whisper")

    private lateinit var context: Context
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var prefs: PreferencesManagerImpl
    private lateinit var viewModel: ModelViewModel
    private lateinit var file: File
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun catalogJson(): String {
        val asset = File("src/main/assets/models_catalog.json")
            .takeIf { it.exists() } ?: File("app/src/main/assets/models_catalog.json")
        return asset.readText()
    }

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        file = File.createTempFile("prefs-use-${System.nanoTime()}", ".preferences_pb")
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
                filesRoot = { Files.createTempDirectory("use-model-ext").toFile() },
            ),
            litertLmUrlImporter = io.mockk.mockk(relaxed = true),
            externalCatalogRepository = io.mockk.mockk(relaxed = true),
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
        )

    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        scope.cancel()
        file.delete()
    }

    /** Files of the whisper "small" variant, non-empty so the sidecar check passes. */
    private fun whisperSmallDir(): File {
        val dir = File(
            SherpaModelManager.of("whisper").getModelStorageDir(context),
            "sherpa-onnx-whisper-small",
        )
        dir.mkdirs()
        listOf(
            "small-encoder.int8.onnx",
            "small-decoder.int8.onnx",
            "small-tokens.txt",
        ).forEach { File(dir, it).writeText("placeholder") }
        return dir
    }

    @Test
    fun `useModel persists backend and model path into the real DataStore from a dangling external pref`() = runTest {
        // Device scenario: the persisted backend is a deleted external model.
        dataStore.edit { it[backendKey] = "external:gone-id" }
        prefs.initialize()
        runCurrent()

        val dir = whisperSmallDir()
        viewModel.useModel("whisper", "small")
        runCurrent()

        // The DataStore's own scope runs on REAL Dispatchers.IO, which the virtual-time
        // scheduler does not advance: poll (real time) until the write lands.
        val deadline = System.currentTimeMillis() + 5_000
        while (dataStore.data.first()[backendKey] != "whisper" &&
            System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            // Drain the Main queue so the coroutine resumes between the two edits.
            runCurrent()
        }
        val stored = dataStore.data.first()
        assertEquals("whisper", stored[backendKey])
        assertEquals(dir.absolutePath, stored[sherpaWhisperKey])
    }

    @Test
    fun `useModel with stale catalog modelPath whose dir vanished does not select a missing model`() = runTest {
        dataStore.edit { it[backendKey] = "external:gone-id" }
        prefs.initialize()
        runCurrent()

        // Seed a stale catalog state the way a completed download does: refresh the
        // entry while the dir exists, then delete the dir behind the ViewModel's back
        // (device scenario: files removed outside the app between scans).
        val dir = whisperSmallDir()
        viewModel.refreshCatalogEntries()
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.catalogStates.value["whisper"]?.modelPath == null &&
            System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            runCurrent()
        }
        assertEquals(dir.absolutePath, viewModel.catalogStates.value["whisper"]?.modelPath)
        dir.deleteRecursively()

        val events = mutableListOf<ModelViewModel.SnackbarEvent>()
        val collector = launch { viewModel.snackbarEvent.collect { events.add(it) } }
        viewModel.useModel("whisper", "small")
        runCurrent()

        assertTrue(
            "Expected a missing-files snackbar event, got: $events",
            events.any {
                it is ModelViewModel.SnackbarEvent.Message &&
                    it.text.startsWith("str:${R.string.model_use_missing_files}")
            }
        )
        // The stale path must not be persisted as the active selection.
        assertEquals("external:gone-id", dataStore.data.first()[backendKey])
        assertEquals(null, dataStore.data.first()[sherpaWhisperKey])
        collector.cancel()
    }

    @Test
    fun `useModel with no valid model directory is loud instead of a silent no-op`() = runTest {
        dataStore.edit { it[backendKey] = "external:gone-id" }
        prefs.initialize()
        runCurrent()

        // Directory exists but is INVALID (tokens file missing): no path resolves.
        val dir = File(
            SherpaModelManager.of("whisper").getModelStorageDir(context),
            "sherpa-onnx-whisper-small",
        )
        dir.mkdirs()
        File(dir, "small-encoder.int8.onnx").writeText("placeholder")

        val events = mutableListOf<ModelViewModel.SnackbarEvent>()
        val collector = launch { viewModel.snackbarEvent.collect { events.add(it) } }
        viewModel.useModel("whisper", "small")
        runCurrent()

        assertTrue(
            "Expected a missing-files snackbar event, got: $events",
            events.any {
                it is ModelViewModel.SnackbarEvent.Message &&
                    it.text.startsWith("str:${R.string.model_use_missing_files}")
            }
        )
        // The dangling external preference must NOT silently remain the active
        // choice the user believes was replaced... but with no valid model there is
        // nothing to switch to; the assertion is that the UI did not flip either.
        assertEquals("external:gone-id", dataStore.data.first()[backendKey])
        collector.cancel()
    }
}
