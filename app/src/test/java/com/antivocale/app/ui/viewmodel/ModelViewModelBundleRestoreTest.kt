package com.antivocale.app.ui.viewmodel

import com.antivocale.app.testing.RepoFiles
import android.app.Application
import android.content.Context
import android.net.Uri
import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.ExternalModelImporter
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.FakeExternalRecordsProvider
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.ModelBundleCodec
import com.antivocale.app.data.ModelFamily
import com.antivocale.app.transcription.SherpaModelDownloader
import com.antivocale.app.transcription.SherpaModelManager
import com.antivocale.app.transcription.staticRegistry
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * TASK-924 (GH #124 reopen): the builtin-restore routing of the bundle
 * import. A bundle whose bytes are this device's catalog variant's bytes
 * lands on the built-in card (dir at the downloader path, NO external
 * record); a builtin-origin bundle the catalog cannot resolve falls back
 * to the external arm when the modelType has a slot, and fails with the
 * honest error when it has none (a newer app version's model must not
 * become a broken external twin).
 *
 * Construction follows ModelViewModelBundleLinkTest (real bundled catalog
 * via a mocked AssetManager, fixture bytes re-pinned into the served
 * catalog; preferences ride the fake).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ModelViewModelBundleRestoreTest : ViewModelTeardownTest() {

    private val testDispatcher = StandardTestDispatcher()

    // A field (not inline in setup) so the harness cancels it.
    private val applicationScope = register(CoroutineScope(SupervisorJob()))
    private lateinit var fakePrefs: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var viewModel: ModelViewModel
    private lateinit var mockContext: Context
    private lateinit var importerRoot: File
    private lateinit var appFilesDir: File
    private lateinit var appCacheDir: File
    /** Every fixture temp dir created this test method; reclaimed in afterTeardown. */
    private val scratch = mutableListOf<File>()

    private fun newTempDir(name: String): File =
        Files.createTempDirectory(name).toFile().also { scratch += it }

    /** Served by the mocked ContentResolver for the next bundle import. */
    private var nextBundle: ByteArray = ByteArray(0)

    /** Served by the mocked AssetManager; per-test override re-pins the catalog. */
    private var nextCatalogJson: String = ""

    /** The qwen3-shaped fixture the restore test pins the catalog onto. */
    private lateinit var qwen3SourceDir: File

    private fun hashFile(f: File) = BundleImportTestSupport.hashFile(f)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakePrefs = FakePreferencesManager()
        store = ExternalModelStore(fakePrefs)
        importerRoot = newTempDir("bundle-restore-ext")
        appFilesDir = newTempDir("bundle-restore-files")
        appCacheDir = newTempDir("bundle-restore-cache")

        // The served catalog re-pins the qwen3-asr AND nemotron-streaming
        // files onto the fixture bytes (the real pins hash the real weights
        // no test can ship); pin names are the catalog's relative paths
        // (tokenizer/ tree). BOTH entries patch here because the catalog
        // singleton loads once per class: a per-test override would load
        // after the first attach and never take effect.
        qwen3SourceDir = qwen3SourceDir()
        val realCatalogJson = RepoFiles.readText("src/main/assets/models_catalog.json")
        nextCatalogJson = patchCatalogPins(realCatalogJson, "qwen3-asr", pinsOf(qwen3SourceDir))
        nextCatalogJson = patchCatalogPins(
            nextCatalogJson, "nemotron-streaming", pinsOf(nemotronStreamingSourceDir()))
        val assetManager = mockk<android.content.res.AssetManager>(relaxed = true)
        every { assetManager.open(any()) } answers {
            ByteArrayInputStream(nextCatalogJson.toByteArray(Charsets.UTF_8))
        }
        val resolver = mockk<android.content.ContentResolver>(relaxed = true)
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream(nextBundle) }
        mockContext = mockk<Context>(relaxed = true) {
            every { filesDir } returns appFilesDir
            every { cacheDir } returns appCacheDir
            every { assets } returns assetManager
            every { contentResolver } returns resolver
            every { getString(any()) } answers { "str:${args[0]}" }
            every { getString(any(), *anyVararg()) } answers {
                val formatArgs = (args.getOrNull(1) as? Array<*>)?.joinToString(",") ?: ""
                "str:${args[0]}:$formatArgs"
            }
        }
        every { mockContext.applicationContext } returns mockContext
        com.antivocale.app.data.catalog.BundledCatalog.attach(mockContext)

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
            externalModelStore = store,
            recordsProvider = com.antivocale.app.data.DefaultExternalModelRecordsProvider(
                store = store,
                // Deliberately NOT registered with the teardown harness: the
                // provider's collectors observe the store; cancelling the
                // scope at teardown races the dir-missing records refresh
                // and, on CI timing, crashes pending jobs into the NEXT
                // class's Main (the 783de4fa red run). The scope is
                // test-JVM-lifetime bounded either way.
                scope = CoroutineScope(SupervisorJob()),
            ),
            externalModelImporter = ExternalModelImporter(store = store, filesRoot = { importerRoot }),
            litertLmUrlImporter = mockk(relaxed = true),
            externalCatalogRepository = mockk(relaxed = true),
            applicationScope = applicationScope,
            silentModelDemoter = sharedDemoter,
            modelActivator = com.antivocale.app.transcription.ModelActivator(
                fakePrefs, sharedDemoter, store, staticRegistry()),
        ))
        // LAST: the registry helpers above seed the catalog singleton with
        // the REAL entries; re-seed with the patched ones so the identity
        // guard sees the fixture pins (BundledCatalog caches per process).
        com.antivocale.app.data.catalog.BundledCatalog.seed(
            com.antivocale.app.data.catalog.ModelCatalogJson.parseCatalog(nextCatalogJson))
    }

    override fun afterTeardown() {
        // The patched seed must not leak into later classes sharing this
        // worker JVM (the catalog singleton caches per process).
        com.antivocale.app.data.catalog.BundledCatalog.seed(
            com.antivocale.app.transcription.bundledCatalogEntriesForTest())
        // The temp roots the suites scratch into (review round: they were
        // never reclaimed; the hook exists exactly for mock-free file I/O).
        if (::importerRoot.isInitialized) importerRoot.deleteRecursively()
        if (::appFilesDir.isInitialized) appFilesDir.deleteRecursively()
        if (::appCacheDir.isInitialized) appCacheDir.deleteRecursively()
        scratch.forEach { it.deleteRecursively() }
    }

    /** The real download path's qwen3 directory shape: files + tokenizer/ tree. */
    private fun qwen3SourceDir(): File {
        val dir = newTempDir("qwen3-src")
        File(dir, "conv_frontend.onnx").writeBytes(ByteArray(2048) { if (it == 0) 8 else 1 })
        File(dir, "encoder.int4.onnx").writeBytes(ByteArray(2048) { if (it == 0) 8 else 2 })
        File(dir, "encoder.int4.weights").writeBytes(ByteArray(2048) { if (it == 0) 8 else 4 })
        File(dir, "decoder.int4.onnx").writeBytes(ByteArray(2048) { if (it == 0) 8 else 3 })
        File(dir, "decoder.int4.weights").writeBytes(ByteArray(2048) { if (it == 0) 8 else 5 })
        File(dir, "tokenizer").mkdirs()
        File(dir, "tokenizer/vocab.json").writeText("""{"t":1}""")
        File(dir, "tokenizer/merges.txt").writeText((0..99).joinToString("\n") { "m$it" })
        File(dir, "tokenizer/tokenizer_config.json").writeText("""{"m":1}""")
        return dir
    }

    /** relative-path -> sha256 over the fixture tree (catalog pin vocabulary). */
    private fun pinsOf(dir: File): Map<String, String> = buildMap {
        dir.walkTopDown().filter { it.isFile }.forEach {
            put(it.relativeTo(dir).path, hashFile(it))
        }
    }

    /** Re-pins every file of [entryId]'s variants onto [pins] (fixture bytes). */
    private fun patchCatalogPins(catalogJson: String, entryId: String, pins: Map<String, String>): String {
        val catalog = org.json.JSONObject(catalogJson)
        val entry = catalog.getJSONArray("models")
            .let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            .first { it.optString("id") == entryId }
        entry.getJSONArray("variants").let { variants ->
            (0 until variants.length()).forEach { v ->
                val files = variants.getJSONObject(v).getJSONArray("files")
                (0 until files.length()).forEach { i ->
                    val file = files.optJSONObject(i)
                    val name = file?.optString("name") ?: files.optString(i)
                    pins[name]?.let { pin ->
                        if (file != null) file.put("sha256", pin)
                        else files.put(i, org.json.JSONObject().put("name", name).put("sha256", pin))
                    }
                }
            }
        }
        return catalog.toString()
    }

    /** A bundle zip of [dir], family-less (the TASK-924 builtin shape). */
    private fun bundleFrom(
        dir: File,
        modelType: String,
        entryId: String?,
        variantDir: String?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ModelBundleCodec.export(
            dir, out,
            ModelBundleCodec.BundleMetadata(
                displayName = "Qwen3 0.6B", family = null, modelType = modelType,
                languages = listOf("en"), streaming = false, options = emptyMap(),
                appVersion = "test", catalogEntryId = entryId, catalogVariantDir = variantDir))
        return out.toByteArray()
    }

    private fun importBundle(bundle: ByteArray) {
        nextBundle = bundle
        viewModel.importExternalFromBundle(mockContext, Uri.parse("content://bundle/test"))
    }

    /** The raw record list from the preference, without suspension (poll predicates). */
    private fun storedRecords() =
        BundleImportTestSupport.storedRecords(fakePrefs._externalModelsJson.value)

    private fun restoredDir(): File =
        File(SherpaModelManager.of("qwen3-asr").getModelStorageDir(mockContext),
            "sherpa-onnx-qwen3-asr-0.6b-int4")

    private fun qwen3Present(): Boolean =
        viewModel.catalogStates.value["qwen3-asr"]?.downloadedVariants?.contains("0.6b-int4") == true

    // ---- (a) the restore arm ----

    @Test
    fun `a catalog-matching bundle restores onto the built-in dir with no record`() = runTest {
        importBundle(bundleFrom(
            qwen3SourceDir, "qwen3_asr",
            entryId = "qwen3-asr", variantDir = "sherpa-onnx-qwen3-asr-0.6b-int4"))
        await("restore finished") {
            viewModel.externalImportState.value == ModelViewModel.ExternalImportState.Idle &&
                restoredDir().isDirectory
        }

        // No external twin: the store stays empty.
        await("no record written") { storedRecords().isEmpty() }

        // The tree is complete at the downloader path, tokenizer/ included,
        // and passes the manager's validator (the tokenizer/ subdir rule).
        assertTrue(File(restoredDir(), "encoder.int4.onnx").isFile)
        assertTrue(File(restoredDir(), "tokenizer/vocab.json").isFile)
        assertTrue(SherpaModelManager.of("qwen3-asr").isValidModelDir(restoredDir()))

        // The card flips to present through the disk-truth computation, and
        // the loader's own path predicate resolves it.
        await("qwen3 card present via the restored dir") { qwen3Present() }
        assertEquals(
            restoredDir().absolutePath,
            SherpaModelDownloader.of("qwen3-asr").getModelPath(mockContext, "0.6b-int4"))

        // The restore never touches preferences (the TASK-797 alias stays dead).
        assertEquals(null, fakePrefs._sherpaModelPath("qwen3-asr").value)
    }

    // ---- (a2) the blank-modelType entry (review R1) ----

    @Test
    fun `a blank-modelType builtin bundle restores onto the built-in dir`() = runTest {
        // nemotron-streaming's catalog modelType is "", which recordParams
        // parses to null; the identity pass must fold the blank and null
        // spellings or the entry the export-gate removal newly enabled
        // could restore nowhere (the fallback would then fail it with the
        // factually-wrong newer-app-version string).
        val src = nemotronStreamingSourceDir()
        importBundle(bundleFrom(src, "",
            entryId = "nemotron-streaming",
            variantDir = "nemotron-3.5-asr-streaming-0.6b-1120ms-int8"))
        val restored = File(
            File(appFilesDir, "nemotron"), "nemotron-3.5-asr-streaming-0.6b-1120ms-int8")
        await("streaming dir restored") { restored.isDirectory }
        await("no record written") { storedRecords().isEmpty() }
        assertTrue(File(restored, "encoder.int8.onnx").isFile)
    }

    /** The streaming Nemotron fixture: the catalog variant's four file
     *  names, no metadata theater (the identity pass never loads it). */
    private fun nemotronStreamingSourceDir(): File {
        val dir = newTempDir("bundle-src-nemotron")
        File(dir, "encoder.int8.onnx").writeBytes(ByteArray(2048) { if (it == 0) 8 else 1 })
        File(dir, "decoder.int8.onnx").writeBytes(ByteArray(2048) { if (it == 0) 8 else 2 })
        File(dir, "joiner.int8.onnx").writeBytes(ByteArray(2048) { if (it == 0) 8 else 3 })
        File(dir, "tokens.txt").writeText((0..99).joinToString("\n") { "tok$it $it" })
        return dir
    }

    // ---- (a3) the TASK-933 ordering invariant ----

    @Test
    fun `an in-flight download refuses the restore through the localized error`() = runTest {
        // Download first: the registration flips isDownloading before any
        // service work, which is the state the restore claim reads.
        viewModel.startDownload("qwen3-asr", "0.6b-int4")
        importBundle(bundleFrom(
            qwen3SourceDir, "qwen3_asr",
            entryId = "qwen3-asr", variantDir = "sherpa-onnx-qwen3-asr-0.6b-int4"))
        await("restore refused as download-active") {
            viewModel.externalImportState.value is ModelViewModel.ExternalImportState.Error
        }
        // Nothing landed, nothing recorded, and the refusal surfaces the
        // localized reason at the import failure tail (not the raw
        // exception text).
        assertFalse(restoredDir().exists())
        assertTrue(storedRecords().isEmpty())
        assertEquals(
            "str:${com.antivocale.app.R.string.bundle_restore_download_active}",
            (viewModel.externalImportState.value
                as ModelViewModel.ExternalImportState.Error).message)
        // The refused restore leaves no marker and no scar on the download.
        assertEquals(emptySet<String>(), viewModel.catalogStates.value["qwen3-asr"]?.restoringVariants)
        assertEquals(
            true,
            viewModel.catalogStates.value["qwen3-asr"]?.variantDownloadStates
                ?.get("0.6b-int4")?.isDownloading)
    }

    @Test
    fun `a restore in progress refuses the download start until it releases`() = runTest {
        // The claim seam is the only way to hold the marker mid-restore
        // (the real landing is a sub-second rename with no pause point);
        // everything under test here is the download start's behavior
        // against that marker.
        val claim = viewModel.claimRestoreVariant("qwen3-asr", "0.6b-int4")
        try {
            viewModel.startDownload("qwen3-asr", "0.6b-int4")
            val state = viewModel.catalogStates.value["qwen3-asr"]
            assertEquals(false, state?.variantDownloadStates?.get("0.6b-int4")?.isDownloading)
            assertEquals(
                "str:${com.antivocale.app.R.string.model_download_restore_active}",
                state?.variantDownloadStates?.get("0.6b-int4")?.errorMessage)
            assertEquals(setOf("0.6b-int4"), state?.restoringVariants)
        } finally {
            claim.close()
        }
        // Released: the same start now registers the download and clears
        // the refused attempt's error.
        viewModel.startDownload("qwen3-asr", "0.6b-int4")
        val state = viewModel.catalogStates.value["qwen3-asr"]
        assertEquals(true, state?.variantDownloadStates?.get("0.6b-int4")?.isDownloading)
        assertEquals(null, state?.variantDownloadStates?.get("0.6b-int4")?.errorMessage)
        assertEquals(emptySet<String>(), state?.restoringVariants)
    }

    // ---- (b) the external fallback (unresolvable origin, resolvable type) ----
    @Test
    fun `a builtin-origin bundle with no resolvable entry falls back to the external import`() = runTest {
        // The transducer source rides a fake entry id: the restore arm
        // cannot resolve it, but nemo_transducer has an import family, so
        // the external arm lands the record exactly as before TASK-924.
        val gigaam = transducerSourceDir()
        importBundle(bundleFrom(gigaam, "nemo_transducer",
            entryId = "no-such-entry", variantDir = null))
        await("bundle import registered") { storedRecords().isNotEmpty() }
        val record = store.records().single()
        // The family was re-derived from the modelType (the manifest is
        // family-less), and nothing links to a nonexistent entry.
        assertEquals(ModelFamily.TRANSDUCER, record.family)
        assertEquals(null, record.linkedCatalog)
    }

    // ---- (c) the hard failure (unresolvable origin AND type) ----

    @Test
    fun `a restore-less bundle with no import slot fails instead of becoming a twin`() = runTest {
        // The qwen3 bytes ride an entry id this catalog does not know: the
        // restore cannot resolve it, qwen3_asr has no import family, so the
        // import must FAIL (the newer-app-version situation), not write a
        // broken external twin or a half-restored dir.
        importBundle(bundleFrom(qwen3SourceDir, "qwen3_asr",
            entryId = "no-such-entry", variantDir = null))
        await("import failed") {
            viewModel.externalImportState.value is ModelViewModel.ExternalImportState.Error
        }
        assertTrue(storedRecords().isEmpty())
        assertFalse(restoredDir().exists())
    }

    /** The gigaam-shaped transducer source the importer's validation accepts
     *  (the ModelViewModelBundleLinkTest fixture shape). */
    private fun transducerSourceDir(): File {
        val dir = newTempDir("bundle-src")
        val metadataProp = { key: String, value: String ->
            key.toByteArray() + byteArrayOf(0x12, value.length.toByte()) + value.toByteArray()
        }
        File(dir, "gigaam_v3_e2e_rnnt_encoder_int8.onnx").writeBytes(
            ByteArray(2048) { if (it == 0) 8 else 1 } + "vocab_size=1024 subsampling_factor=8 ".toByteArray() +
                metadataProp("model_type", "nemo_transducer"))
        File(dir, "gigaam_v3_e2e_rnnt_decoder.onnx").writeBytes(ByteArray(2048) { if (it == 0) 8 else 2 })
        File(dir, "gigaam_v3_e2e_rnnt_joint.onnx").writeBytes(ByteArray(2048) { if (it == 0) 8 else 3 })
        File(dir, "gigaam_v3_e2e_rnnt_tokens.txt").writeText((0..99).joinToString("\n") { "tok$it $it" })
        return dir
    }
}
