package com.antivocale.app.ui.viewmodel

import com.antivocale.app.testing.RepoFiles
import com.antivocale.app.ui.tabs.hasCleanupWork
import android.content.Context
import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.FakeExternalRecordsProvider
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TASK-871's investigation closed as a MISDIAGNOSIS (2026-10-23): the
 * instrumented device run proved both sweeps mark the orphan correctly and
 * the full-build Idle/Download rendering is the DESIGNED behavior (the
 * Orphaned state is stub-build-only by TASK-870's narrowing; a full build
 * intentionally re-offers the download, which is loadable there). The
 * original scare came from two of my own errors: reading the curated
 * section (which never lists whistle) instead of the browse-all list, and
 * misreading the intended full-build behavior as a bug.
 *
 * What remains valuable is pinned here, against regression:
 *  1. the sweep marks a junk dir under a current variant dirName orphaned
 *     (the input the 866/870 cleanup surfaces depend on);
 *  2. a records emission after construction (the device's real
 *     Turkish/pt-BR trigger) re-runs the sweep without wiping the marking;
 *  3. an orphaned variant counts as cleanup work, so the stub-build render
 *     gate cannot hide a card that has on-disk work to surface.
 *
 * Construction follows ModelViewModelActiveModelTest (the house pattern):
 * real temp filesDir, the REAL bundled catalog through the mocked asset
 * manager, real prefs/repository, relaxed mocks elsewhere; the init block
 * runs both sweeps on real IO dispatchers, so the tests poll real time the
 * way ModelViewModelBundleLinkTest.await does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelViewModelOrphanSweepTest {

    private val testDispatcher = StandardTestDispatcher()
    private val recordsProvider = FakeExternalRecordsProvider()
    private lateinit var viewModel: ModelViewModel

    /** Real temp dir backing the mocked Context's filesDir. */
    private val filesRoot = Files.createTempDirectory("mvm-orphan").toFile()

    private fun catalogJson(): String =
        RepoFiles.readText("src/main/assets/models_catalog.json")

    private val mockContext: Context = mockk<Context>(relaxed = true) {
        every { filesDir } returns filesRoot
        every { getString(any()) } answers { "str:${args[0]}" }
        every { getString(any(), *anyVararg()) } answers {
            val formatArgs = (args.getOrNull(1) as? Array<*>)?.joinToString(",") ?: ""
            "str:${args[0]}:$formatArgs"
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // The device shape: files/whistle/whistle-v1/ containing a file that
        // is NOT the variant's expected whistle.cact (a corrupted or
        // half-extracted leftover under the current dir name).
        val junkDir = File(filesRoot, "whistle/whistle-v1")
        junkDir.mkdirs()
        File(junkDir, "whistle-encoder.onnx").writeBytes(ByteArray(0))

        val assetManager = mockk<android.content.res.AssetManager>(relaxed = true)
        every { assetManager.open(any()) } answers {
            ByteArrayInputStream(catalogJson().toByteArray(Charsets.UTF_8))
        }
        every { mockContext.assets } returns assetManager
        every { mockContext.applicationContext } returns mockContext
        com.antivocale.app.data.catalog.BundledCatalog.attach(mockContext)

        val fakePrefs = FakePreferencesManager()
        viewModel = ModelViewModel(
            preferencesManager = fakePrefs,
            activeModelRepository = ActiveModelRepository(fakePrefs, mockContext, staticRegistry(), recordsProvider),
            tokenManager = mockk(relaxed = true),
            backendManager = mockk(relaxed = true),
            llmManager = mockk(relaxed = true),
            shareTargetManager = mockk(relaxed = true),
            shareShortcutManager = mockk(relaxed = true),
            ctx = mockContext,
            backendRegistry = staticRegistry(),
            externalModelStore = mockk(relaxed = true),
            recordsProvider = recordsProvider,
            externalModelImporter = mockk(relaxed = true),
            litertLmUrlImporter = mockk(relaxed = true),
            externalCatalogRepository = mockk(relaxed = true),
            silentModelDemoter = mockk(relaxed = true),
            modelActivator = mockk(relaxed = true),
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        filesRoot.deleteRecursively()
    }

    /** Real-time poll (the sweeps run on real IO dispatchers); FAILS on
     *  timeout so a broken sweep cannot pass by waiting out the clock. */
    private fun TestScope.await(what: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            runCurrent()
        }
        assertTrue("timed out waiting for: $what", predicate())
    }

    @Test
    fun `a junk dir under a current variant dirName marks the variant orphaned`() = runTest {
        await("the whistle entry state to land") {
            viewModel.catalogStates.value.containsKey("whistle")
        }
        val state = viewModel.catalogStates.value["whistle"]!!
        assertEquals(setOf("v1"), state.orphanedVariants)
        assertTrue("junk dir must not count as downloaded", state.downloadedVariants.isEmpty())
        // The 866/870 render gate's input: an orphaned variant is cleanup
        // work, so a stub build cannot hide a card with on-disk work.
        assertTrue("an orphaned variant is cleanup work", state.hasCleanupWork())
    }

    @Test
    fun `a records emission after construction does not wipe the orphan state`() = runTest {
        await("the orphan variant marked") {
            viewModel.catalogStates.value["whistle"]?.orphanedVariants?.contains("v1") == true
        }
        // The device carries linked external records (Turkish, pt-BR); each
        // records change re-triggers refreshAllCatalogEntries inline in the
        // collector. Emitting a record here replays that second pass.
        val recordDir = Files.createTempDirectory("ext-record").toFile()
        val record = com.antivocale.app.data.ExternalModelRecord(
            id = "rec1", displayName = "Turkish via bundle",
            dir = recordDir.absolutePath,
            family = com.antivocale.app.data.ModelFamily.WHISPER,
            modelType = "whisper",
            languages = listOf("tr"),
            source = com.antivocale.app.data.ExternalModelSource.LOCAL,
            sourceUrl = null,
            files = emptyMap(), sizeBytes = 0L, importedAt = 0L,
        )
        (recordsProvider.records as kotlinx.coroutines.flow.MutableStateFlow).value = listOf(record)
        await("the records-driven second sweep to land") {
            viewModel.catalogStates.value["whisper"] != null
        }
        Thread.sleep(300) // let any racing second pass settle
        runCurrent()
        val state = viewModel.catalogStates.value["whistle"]!!
        assertEquals("the second sweep must keep the orphan marking",
            setOf("v1"), state.orphanedVariants)
    }
}
