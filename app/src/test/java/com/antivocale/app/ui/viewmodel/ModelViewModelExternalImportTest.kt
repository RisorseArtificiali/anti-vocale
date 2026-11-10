package com.antivocale.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.FakeExternalRecordsProvider
import com.antivocale.app.data.ExternalModelImportOperations
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.ModelFamily
import com.antivocale.app.transcription.staticRegistry
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Unit tests proving the ModelViewModel import wrappers forward family, options and
 * languages to the importer, and that the positional modelType argument is gone:
 * non-CTC families pass modelType = null so the importer's family-aware
 * resolveModelType governs (TASK-331 Task 12 amendment).
 *
 * Construction follows ModelViewModelActiveModelTest. There is no fake-importer
 * seam on ExternalModelImporter, so the test introduces the minimal
 * [ExternalModelImportOperations] interface the ViewModel now depends on and drives
 * it with a recording fake (the interface choice is documented in the commit).
 * Robolectric is needed only for Uri.parse (framework class).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ModelViewModelExternalImportTest : ViewModelTeardownTest() {

    private val testDispatcher = StandardTestDispatcher()

    // A field (not inline in setup) so the harness cancels it.
    private val applicationScope = register(CoroutineScope(SupervisorJob()))
    private lateinit var fakePrefs: FakePreferencesManager
    private lateinit var viewModel: ModelViewModel
    private lateinit var fakeImporter: RecordingImporter

    /** One captured importer invocation. */
    data class ImportCall(
        val url: String?,
        val modelType: String?,
        val family: ModelFamily,
        val options: Map<String, String>,
        val languages: List<String>,
    )

    /** Records every call and returns a valid record; the latch lets the test wait
     *  for the real Dispatchers.IO coroutine runExternalImport launches. The latch
     *  counts down at the END of each import method, after every state capture:
     *  counting at entry (as this class once did) let the assertion race the IO
     *  coroutine's appends and flake IndexOutOfBoundsException on slow runners. */
    private class RecordingImporter(var latch: CountDownLatch) : ExternalModelImportOperations {

        /** TASK-969: multi-round tests (fail, then retry) re-arm the one
         *  countdown per round; awaitImporter reads the field at call time. */
        fun rearmLatch() {
            latch = CountDownLatch(1)
        }
        val calls = mutableListOf<ImportCall>()
        var treeUri: Uri? = null
        var stateAtCallback: (() -> ModelViewModel.ExternalImportState)? = null

        /** TASK-969: when set, the next importFromUrl throws this and clears
         *  it (one scripted failure for the retry-affordance round trip). */
        var nextFailure: Exception? = null

        /** TASK-979: when set, importFromUrl counts its latch and suspends
         *  until cancelled (the in-flight shape the cancel button targets). */
        var blockUntilCancelled = false

        /** TASK-979: the same knob for the folder path (no retry closure:
         *  its cancel must land in Idle, not a cancelled-message Error). */
        var blockTreeUntilCancelled = false

        private fun record(url: String?, modelType: String?, family: ModelFamily,
                           options: Map<String, String>, languages: List<String>) {
            calls.add(ImportCall(url, modelType, family, options, languages))
        }

        override suspend fun importFromDirectory(
            src: java.io.File,
            modelType: String?,
            family: ModelFamily,
            options: Map<String, String>,
            languages: List<String>,
            streaming: Boolean,
            displayName: String?,
            linkedCatalog: com.antivocale.app.data.LinkedCatalogRef?,
            licence: String?,
        ): ExternalModelRecord = throw UnsupportedOperationException("not used in this test")

        override suspend fun importFromTreeUri(
            context: Context, treeUri: Uri, modelType: String?, family: ModelFamily,
            options: Map<String, String>, languages: List<String>, streaming: Boolean,
        ): ExternalModelRecord {
            this.treeUri = treeUri
            record(null, modelType, family, options, languages)
            if (blockTreeUntilCancelled) {
                latch.countDown()
                kotlinx.coroutines.awaitCancellation()
            }
            latch.countDown()
            return sampleRecord()
        }

        var statesAtCallbacks: List<ModelViewModel.ExternalImportState> = emptyList()
        var onProgressObserved: com.antivocale.app.data.ExternalImportProgress = { _, _, _, _, _ -> }

        /** TASK-925: the canned .zip scan zipChildren forwards (the input
         *  the Unknown-arm branch decides on). */
        var nextZipChildren: List<Uri> = emptyList()

        override suspend fun bundleZipChildren(context: Context, treeUri: Uri): List<Uri> =
            nextZipChildren

        override suspend fun importFromUrl(
            url: String, modelType: String?, family: ModelFamily,
            options: Map<String, String>, languages: List<String>, streaming: Boolean,
            onProgress: com.antivocale.app.data.ExternalImportProgress,
        ): ExternalModelRecord {
            record(url, modelType, family, options, languages)
            if (blockUntilCancelled) {
                latch.countDown()
                kotlinx.coroutines.awaitCancellation()
            }
            // The latch counts down FIRST on the scripted-failure path too:
            // the class contract is countdown at end-of-method, and a throw
            // before it stalled awaitImporter for the full 5s timeout in
            // every run (review finding: a flat +5s on the whole suite).
            nextFailure?.let {
                latch.countDown()
                nextFailure = null
                throw it
            }
            onProgressObserved = onProgress
            onProgress(0, 2, "encoder.int8.onnx", 100L, 200L)
            statesAtCallbacks += stateAtCallback?.invoke() ?: ModelViewModel.ExternalImportState.Idle
            onProgress(1, 2, "tokens.txt", 50L, 50L)
            statesAtCallbacks += stateAtCallback?.invoke() ?: ModelViewModel.ExternalImportState.Idle
            latch.countDown()
            return sampleRecord()
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakePrefs = FakePreferencesManager()
        fakeImporter = RecordingImporter(CountDownLatch(1))
        val filesRoot = Files.createTempDirectory("mvm-ext").toFile()
        val mockContext: Context = mockk<Context>(relaxed = true) {
            every { filesDir } returns filesRoot
            every { getString(any()) } answers { "str:${args[0]}" }
            every { getString(any(), *anyVararg()) } answers {
                val formatArgs = (args.getOrNull(1) as? Array<*>)?.joinToString(",") ?: ""
                "str:${args[0]}:$formatArgs"
            }
        }
        val sharedDemoter = com.antivocale.app.transcription.SilentModelDemoter(fakePrefs)
        viewModel = register(ModelViewModel(
            preferencesManager = fakePrefs,
            activeModelRepository = ActiveModelRepository(fakePrefs, mockContext, staticRegistry(), FakeExternalRecordsProvider()),
            tokenManager = mockk(relaxed = true),
            backendManager = mockk(relaxed = true),
            llmManager = mockk(relaxed = true),
            shareTargetManager = mockk(relaxed = true),
            shareShortcutManager = mockk(relaxed = true),
            ctx = mockContext,
            backendRegistry = staticRegistry(),
            externalModelStore = com.antivocale.app.data.ExternalModelStore(fakePrefs),
            recordsProvider = com.antivocale.app.data.FakeExternalRecordsProvider(),
            externalModelImporter = fakeImporter,
            litertLmUrlImporter = io.mockk.mockk(relaxed = true),
            externalCatalogRepository = io.mockk.mockk(relaxed = true),
            applicationScope = applicationScope,
            // TASK-675: real demoter over the same preferences.
            silentModelDemoter = sharedDemoter,
            modelActivator = com.antivocale.app.transcription.ModelActivator(
                fakePrefs,
                sharedDemoter,
                externalModelStore = com.antivocale.app.data.ExternalModelStore(fakePrefs),
                backendRegistry = staticRegistry(),
                ),
        ))

    }

    @Test
    fun `wiped ids ride the same emission as the records`() = runTest {
        // TASK-965: the combined state derives both from ONE records
        // emission (no two-chain skew) and routes through isLoadable, the
        // single loadability predicate (dir gone, NOT quarantined).
        val root = java.nio.file.Files.createTempDirectory("wiped-ids")
        fun rec(id: String, present: Boolean, quarantined: Boolean = false): ExternalModelRecord {
            val dir = root.resolve(id).toFile()
            if (present) dir.mkdirs()
            return sampleRecord().copy(
                id = id, displayName = "Model $id", dir = dir.absolutePath,
                quarantined = quarantined)
        }
        val store = com.antivocale.app.data.ExternalModelStore(fakePrefs)
        store.add(rec("present-one", present = true))
        store.add(rec("gone-one", present = false))
        store.add(rec("quar-one", present = false, quarantined = true))
        // WhileSubscribed arms on the first collector; the map runs on
        // Dispatchers.Default (flowOn), so await the real emission instead
        // of runCurrent-ing the test scheduler.
        val state = viewModel.externalModelState.first { it.records.isNotEmpty() }
        val wiped = state.wipedIds
        assertTrue(wiped.toString(), "gone-one" in wiped)
        assertTrue(wiped.toString(), "present-one" !in wiped)
        assertTrue(wiped.toString(), "quar-one" !in wiped)
    }

    @Test
    fun `zipChildren forwards the importer's scan for every list size (TASK-925)`() = runTest {
        val ctx = mockk<Context>(relaxed = true)
        val tree = Uri.parse("content://tree/xyz")
        fakeImporter.nextZipChildren = emptyList()
        assertEquals(emptyList<Uri>(), viewModel.zipChildren(ctx, tree))
        val one = listOf(Uri.parse("content://tree/xyz/doc/a.zip"))
        fakeImporter.nextZipChildren = one
        assertEquals(one, viewModel.zipChildren(ctx, tree))
        val two = one + Uri.parse("content://tree/xyz/doc/b.zip")
        fakeImporter.nextZipChildren = two
        assertEquals(two, viewModel.zipChildren(ctx, tree))
    }

    @Test
    fun `folder import forwards family options languages with null modelType for whisper`() = runTest {
        val uri = Uri.parse("content://tree/xyz")
        viewModel.importExternalFromFolder(
            context = mockk(relaxed = true), treeUri = uri,
            family = ModelFamily.WHISPER,
            options = mapOf("whisper.language" to "ar"),
            languages = listOf("ar"),
        )
        awaitImporter()
        assertEquals(uri, fakeImporter.treeUri)
        val call = fakeImporter.calls.single()
        assertNull(call.url)
        assertNull(call.modelType)
        assertEquals(ModelFamily.WHISPER, call.family)
        assertEquals(mapOf("whisper.language" to "ar"), call.options)
        assertEquals(listOf("ar"), call.languages)
    }

    @Test
    fun `url import forwards family options languages`() = runTest {
        viewModel.importExternalFromUrl(
            url = "https://example.com/model.json",
            family = ModelFamily.SENSE_VOICE,
            options = mapOf("sensevoice.itn" to "true"),
            languages = listOf("en"),
        )
        awaitImporter()
        val call = fakeImporter.calls.single()
        assertEquals("https://example.com/model.json", call.url)
        assertNull(call.modelType)
        assertEquals(ModelFamily.SENSE_VOICE, call.family)
        assertEquals(mapOf("sensevoice.itn" to "true"), call.options)
        assertEquals(listOf("en"), call.languages)
    }

    @Test
    fun `url import streams per-file progress into the state`() = runTest {
        fakeImporter.stateAtCallback = { viewModel.externalImportState.value }
        viewModel.importExternalFromUrl(
            url = "https://example.com/model.json",
            family = ModelFamily.WHISPER,
        )
        awaitImporter()
        val first = fakeImporter.statesAtCallbacks[0]
        val second = fakeImporter.statesAtCallbacks[1]
        org.junit.Assert.assertTrue(first is ModelViewModel.ExternalImportState.Importing)
        first as ModelViewModel.ExternalImportState.Importing
        second as ModelViewModel.ExternalImportState.Importing
        assertEquals("encoder.int8.onnx", first.fileName)
        assertEquals(0, first.fileIndex)
        assertEquals(2, first.fileCount)
        assertEquals(0.5f, first.progress)
        assertEquals("tokens.txt", second.fileName)
        assertEquals(1f, second.progress)
        // completion resets to Idle
        assertEquals(
            ModelViewModel.ExternalImportState.Idle,
            viewModel.externalImportState.value)
    }

    @Test
    fun `a failed url import exposes a Retry that re-dispatches the same params (TASK-969)`() = runTest {
        fakeImporter.nextFailure = java.io.IOException("connection reset by the ladder's end")
        viewModel.importExternalFromUrl(
            url = "https://example.com/models/it.json",
            family = ModelFamily.SENSE_VOICE,
            options = mapOf("sensevoice.itn" to "true"),
            languages = listOf("it"),
            displayName = "Italian SenseVoice",
            licence = "CC-BY-4.0")
        awaitImporter()
        val error = viewModel.externalImportState.value
        assertTrue(error is ModelViewModel.ExternalImportState.Error)
        error as ModelViewModel.ExternalImportState.Error
        assertTrue("the URL import's retry closure rides the Error state", error.retry != null)

        // Retry re-dispatches the SAME params without a dialog round trip
        // (the closure is captured at dispatch time, so a dropped param is
        // a compile error, not a silent default).
        fakeImporter.rearmLatch()
        error.retry!!.invoke()
        awaitImporter()
        assertEquals(ModelViewModel.ExternalImportState.Idle,
            viewModel.externalImportState.value)
        val retry = fakeImporter.calls.last()
        assertEquals("https://example.com/models/it.json", retry.url)
        assertEquals(ModelFamily.SENSE_VOICE, retry.family)
        assertEquals(listOf("it"), retry.languages)
        assertEquals("the user-set option reaches the retried import",
            mapOf("sensevoice.itn" to "true"), retry.options)
    }

    @Test
    fun `a retried CTC import keeps the picked subtype instead of the default (TASK-969)`() = runTest {
        // The hazard the closure design exists for: the manual path's CTC
        // subtype dropdown is user-set (zipformer_ctc here, not the NeMo
        // default), and a mirrored-params retry once dropped it.
        fakeImporter.nextFailure = java.io.IOException("stalled")
        viewModel.importExternalFromUrl(
            url = "https://example.com/models/ctc.json",
            family = ModelFamily.CTC,
            ctcModelType = "zipformer_ctc")
        awaitImporter()
        val error = viewModel.externalImportState.value as ModelViewModel.ExternalImportState.Error

        fakeImporter.rearmLatch()
        error.retry!!.invoke()
        awaitImporter()
        val retry = fakeImporter.calls.last()
        assertEquals("zipformer_ctc", retry.modelType)
        assertEquals(ModelFamily.CTC, retry.family)
    }

    @Test
    fun `cancelling a url import offers the cancelled state with the retry (TASK-979)`() = runTest {
        fakeImporter.blockUntilCancelled = true
        viewModel.importExternalFromUrl(
            url = "https://example.com/models/big.json",
            family = ModelFamily.WHISPER)
        // The blocked phase waits on the LATCH, not awaitImporter: the
        // import stays Importing by design until cancelled, and the poll
        // loop would spin its full 5s deadline every run (review finding).
        assertTrue(fakeImporter.latch.await(5, TimeUnit.SECONDS))
        assertTrue(viewModel.externalImportState.value is ModelViewModel.ExternalImportState.Importing)

        viewModel.cancelExternalImport()
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.externalImportState.value !is ModelViewModel.ExternalImportState.Cancelled &&
            System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        val state = viewModel.externalImportState.value
        assertTrue("cancel lands in the dedicated Cancelled state that carries retry",
            state is ModelViewModel.ExternalImportState.Cancelled)
        state as ModelViewModel.ExternalImportState.Cancelled
        // The mock context renders getString as "str:<id>": asserting that
        // prefix proves the message came from the localized cancelled
        // string, not from the exception's own text (the real wording is
        // pinned by the i18n gates, not here).
        assertTrue("the localized cancelled string, not an exception text",
            state.message.startsWith("str:"))
        assertTrue("resume is the same affordance", state.retry != null)

        // The retry re-dispatches and (knob off) completes to Idle.
        fakeImporter.blockUntilCancelled = false
        fakeImporter.rearmLatch()
        state.retry!!.invoke()
        awaitImporter()
        assertEquals(ModelViewModel.ExternalImportState.Idle,
            viewModel.externalImportState.value)
        assertEquals(2, fakeImporter.calls.size)
    }

    @Test
    fun `cancelling a folder import returns to Idle with no error line (TASK-979)`() = runTest {
        fakeImporter.blockTreeUntilCancelled = true
        viewModel.importExternalFromFolder(
            context = mockk(relaxed = true), treeUri = Uri.parse("content://tree/xyz"),
            family = ModelFamily.WHISPER)
        assertTrue(fakeImporter.latch.await(5, TimeUnit.SECONDS))
        assertTrue(viewModel.externalImportState.value is ModelViewModel.ExternalImportState.Importing)

        viewModel.cancelExternalImport()
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.externalImportState.value is ModelViewModel.ExternalImportState.Importing &&
            System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals("a folder cancel has no retry closure: silent Idle",
            ModelViewModel.ExternalImportState.Idle, viewModel.externalImportState.value)
    }

    @Test
    fun `ctc import passes the explicit subtype as modelType`() = runTest {
        viewModel.importExternalFromUrl(
            url = "https://huggingface.co/istupakov/gigaam-v3-onnx",
            family = ModelFamily.CTC,
            ctcModelType = "zipformer_ctc",
        )
        awaitImporter()
        val call = fakeImporter.calls.single()
        assertEquals("zipformer_ctc", call.modelType)
        assertEquals(ModelFamily.CTC, call.family)
        assertEquals(emptyMap<String, String>(), call.options)
        assertEquals(emptyList<String>(), call.languages)
    }

    @Test
    fun `deleting the active external model resets the backend preference to default`() = runTest {
        // Same store instance the ViewModel was built with in setup? It was built with
        // its own ExternalModelStore(fakePrefs) in setup; replicate that here for seeding.
        val store = com.antivocale.app.data.ExternalModelStore(fakePrefs)
        val dir = Files.createTempDirectory("ext-delete").toFile()
        val record = sampleRecord().copy(id = "active-ext", dir = dir.absolutePath)
        store.add(record)

        viewModel.useExternalModel(record)
        runCurrent()
        assertEquals(record.backendId, fakePrefs._transcriptionBackend.value)

        viewModel.deleteExternalModel(record)
        // deleteExternalModel launches on Dispatchers.IO (real dispatcher): poll the
        // preference until the reset lands or the deadline expires.
        val deadline = System.currentTimeMillis() + 5_000
        while (fakePrefs._transcriptionBackend.value == record.backendId &&
            System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND, fakePrefs._transcriptionBackend.value)
        dir.deleteRecursively()
    }

    @Test
    fun `selecting a canary model enables VAD, other families leave the toggle alone (TASK-408)`() = runTest {
        // canary decodes empty on mid-speech chunk cuts, so silence-aligned
        // segmentation is part of the selection: the preference flips on,
        // visibly in Settings rather than as a silent override.
        viewModel.useExternalModel(sampleRecord().copy(family = ModelFamily.CANARY))
        runCurrent()
        assertTrue(fakePrefs._vadEnabled.value)

        // switching to a non-canary model must NOT flip it back off (the user
        // stays in control of disabling it)
        fakePrefs._vadEnabled.value = true
        viewModel.useExternalModel(sampleRecord().copy(id = "whisp", family = ModelFamily.WHISPER))
        runCurrent()
        assertTrue(fakePrefs._vadEnabled.value)
    }

    /** runExternalImport launches on Dispatchers.IO (a real dispatcher in JVM tests):
     *  block until the fake was reached, then drain the Main dispatcher.
     *  The latch fires at the END of the fake's importFromUrl, but the fold
     *  that sets the terminal state (Idle, or Error) still runs on the IO
     *  coroutine afterwards; under a loaded suite that fold can lose the race
     *  with the assertion (seen 3x in full-suite runs, green in isolation).
     *  So also poll, bounded, for ANY terminal state; the specific terminal
     *  value stays each test's own assertion. */
    private fun kotlinx.coroutines.test.TestScope.awaitImporter() {
        fakeImporter.latch.await(5, TimeUnit.SECONDS)
        runCurrent()
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.externalImportState.value is ModelViewModel.ExternalImportState.Importing &&
            System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            runCurrent()
        }
    }
}

/** Minimal valid record the fake importer returns. */
internal fun sampleRecord(): ExternalModelRecord = ExternalModelRecord(
    id = "testid", displayName = "Test Model", dir = "/tmp/test-model",
    family = ModelFamily.TRANSDUCER, modelType = "nemo_transducer",
    languages = emptyList(), source = ExternalModelSource.LOCAL, sourceUrl = null,
    files = emptyMap(), sizeBytes = 0L, importedAt = 0L,
)
