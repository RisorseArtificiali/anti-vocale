package com.antivocale.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.FakeExternalRecordsProvider
import com.antivocale.app.data.ExternalModelImporter
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.PreferencesManagerImpl
import com.antivocale.app.transcription.SherpaModelManager
import com.antivocale.app.transcription.SilentModelDemoter
import com.antivocale.app.transcription.staticRegistry
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import com.antivocale.app.testing.TempDataStoreRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-675: the Model-tab half of silent-model demotion. Auto-selection (the
 * first-run pickup after a download) must skip a demoted model, and the
 * user's manual pick must clear the demotion. Construction follows
 * ModelViewModelUseModelPersistenceTest (real temp-file DataStore, so the
 * clear and the skip are proven against the persisted state the app ships).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModelViewModelDemotionTest {

    private val testDispatcher = StandardTestDispatcher()
    private val backendKey = stringPreferencesKey("transcription_backend")
    private val demotedKey = stringSetPreferencesKey("demoted_backends")

    @get:Rule
    val ds = TempDataStoreRule("prefs-demote")
    private val context: Context get() = ds.context
    private val dataStore: DataStore<Preferences> get() = ds.dataStore
    private val prefs: PreferencesManagerImpl get() = ds.prefs
    private lateinit var demoter: SilentModelDemoter
    private lateinit var viewModel: ModelViewModel

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(testDispatcher)
        demoter = SilentModelDemoter(prefs)

        val asset = File("src/main/assets/models_catalog.json")
            .takeIf { it.exists() } ?: File("app/src/main/assets/models_catalog.json")
        val assetManager = mockk<android.content.res.AssetManager>(relaxed = true)
        every { assetManager.open(any()) } answers {
            ByteArrayInputStream(asset.readText().toByteArray(Charsets.UTF_8))
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
            activeModelRepository = ActiveModelRepository(prefs, mockContext, staticRegistry(), FakeExternalRecordsProvider()),
            tokenManager = mockk(relaxed = true),
            backendManager = mockk(relaxed = true),
            llmManager = mockk(relaxed = true),
            shareTargetManager = mockk(relaxed = true),
            shareShortcutManager = mockk(relaxed = true),
            ctx = mockContext,
            backendRegistry = staticRegistry(),
            externalModelStore = ExternalModelStore(prefs),
            externalModelImporter = ExternalModelImporter(
                store = ExternalModelStore(prefs),
                filesRoot = { Files.createTempDirectory("demote-ext").toFile() },
            ),
            litertLmUrlImporter = mockk(relaxed = true),
            externalCatalogRepository = mockk(relaxed = true),
            applicationScope = kotlinx.coroutines.CoroutineScope(SupervisorJob()),
            silentModelDemoter = demoter,
            modelActivator = com.antivocale.app.transcription.ModelActivator(
                prefs,
                demoter,
                externalModelStore = com.antivocale.app.data.ExternalModelStore(prefs),
                backendRegistry = staticRegistry(),
            ),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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

    /** Polls (real time) until the DataStore's own IO scope lands the expected backend. */
    private fun awaitBackend(expected: String?) = runTest {
        val deadline = System.currentTimeMillis() + 5_000
        while (dataStore.data.first()[backendKey] != expected &&
            System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            runCurrent()
        }
        assertEquals(expected, dataStore.data.first()[backendKey])
    }

    /**
     * Polls until the demoted set matches: useModel writes the backend FIRST
     * and clears the demotion after it, so the clear can still be in flight
     * when the backend write has landed.
     */
    private fun awaitDemotedContains(expected: Boolean) = runTest {
        val deadline = System.currentTimeMillis() + 5_000
        while (prefs.demotedBackends.first().contains("whisper") != expected &&
            System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            runCurrent()
        }
        assertEquals(expected, prefs.demotedBackends.first().contains("whisper"))
    }

    @Test
    fun `auto-selection skips a demoted model`() = runTest {
        whisperSmallDir()
        // Seed a non-whisper selection so "the skip left it alone" is a raw-key
        // fact, not the absence-of-write ambiguity of an empty store.
        dataStore.edit { it[backendKey] = "external:gone-id" }
        prefs.markBackendDemoted("whisper")
        runCurrent()

        // The first-run auto-selection arm (what a completed download takes).
        viewModel.autoSelectIfNotDemoted("whisper") { viewModel.useModel("whisper", "small") }

        // The selection must NOT land: the previous backend stays active.
        awaitBackend("external:gone-id")
        assertTrue(prefs.demotedBackends.first().contains("whisper"))
    }

    @Test
    fun `auto-selection picks an eligible model`() = runTest {
        whisperSmallDir()
        dataStore.edit { it[backendKey] = "external:gone-id" }
        runCurrent()

        viewModel.autoSelectIfNotDemoted("whisper") { viewModel.useModel("whisper", "small") }
        awaitBackend("whisper")
        assertFalse(prefs.demotedBackends.first().contains("whisper"))
    }

    @Test
    fun `manual selection clears the demotion`() = runTest {
        whisperSmallDir()
        prefs.markBackendDemoted("whisper")
        runCurrent()

        val events = mutableListOf<ModelViewModel.SnackbarEvent>()
        val collector = launch { viewModel.snackbarEvent.collect { events.add(it) } }
        viewModel.useModel("whisper", "small")

        awaitBackend("whisper")
        // The pick is the give-it-another-chance path: the persisted demotion
        // entry is gone (both via the flow and in the raw store).
        awaitDemotedContains(expected = false)
        assertEquals(null, dataStore.data.first()[demotedKey])
        collector.cancel()
    }
}
