package com.antivocale.app.ui.viewmodel

import com.antivocale.app.testing.RepoFiles
import android.app.Application
import android.content.Context
import android.net.Uri
import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.ExternalModelImporter
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.FakeExternalRecordsProvider
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.LinkedCatalogRef
import com.antivocale.app.data.ModelBundleCodec
import com.antivocale.app.data.ModelFamily
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
 * TASK-801 (GH #128): the catalog entity link, end to end through the real
 * bundle import. A bundle exported from a built-in entry and imported here
 * must link the resulting external record to that entry's card (presence
 * derived from the link, Use dispatching to the record) WITHOUT writing the
 * entry's saved model path (the TASK-797 alias that died in ModelLoadError).
 *
 * Construction follows ModelViewModelUseModelPersistenceTest (real bundled
 * catalog via a mocked AssetManager) but preferences ride the fake, which is
 * enough for the dispatch contract under test and keeps the suite fast.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModelViewModelBundleLinkTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePrefs: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var viewModel: ModelViewModel
    private lateinit var mockContext: Context
    private lateinit var importerRoot: File

    /** Served by the mocked ContentResolver for the next bundle import. */
    private var nextBundle: ByteArray = ByteArray(0)

    /** Served by the mocked AssetManager; per-test override re-pins the catalog. */
    private var nextCatalogJson: String = ""

    /** The fixture-bytes source the served gigaam pins are hashed from. */
    private lateinit var gigaamSourceDir: File

    private fun hashFile(f: File): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakePrefs = FakePreferencesManager()
        store = ExternalModelStore(fakePrefs)
        importerRoot = Files.createTempDirectory("bundle-link-ext").toFile()
        val appFilesDir = Files.createTempDirectory("bundle-link-files").toFile()
        val appCacheDir = Files.createTempDirectory("bundle-link-cache").toFile()

        val realCatalogJson = RepoFiles.readText("src/main/assets/models_catalog.json")
        // The served catalog re-pins the gigaam files onto the fixture bytes:
        // the import's identity guard links only byte-identical bundles, and
        // the real pins hash the real 326MB weights no test can ship. The
        // pin-mismatch test re-attaches the unpatched catalog instead.
        gigaamSourceDir = transducerSourceDir()
        val catalogJson = run {
            val catalog = org.json.JSONObject(realCatalogJson)
            val entry = catalog.getJSONArray("models")
                .let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
                .first { it.optString("id") == "gigaam" }
            val pins = gigaamSourceDir.listFiles().associate { it.name to hashFile(it) }
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
            catalog.toString()
        }
        nextCatalogJson = catalogJson
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
        viewModel = ModelViewModel(
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
            // The production provider over the real store: the record-driven
            // refreshes this file pins ride the same hot seam the app uses.
            recordsProvider = com.antivocale.app.data.DefaultExternalModelRecordsProvider(
                store = store,
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            ),
            externalModelImporter = ExternalModelImporter(store = store, filesRoot = { importerRoot }),
            litertLmUrlImporter = mockk(relaxed = true),
            externalCatalogRepository = mockk(relaxed = true),
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            silentModelDemoter = sharedDemoter,
            modelActivator = com.antivocale.app.transcription.ModelActivator(
                fakePrefs, sharedDemoter, store, staticRegistry()),
        )
        // LAST: the registry helpers above seed the catalog singleton with
        // the REAL entries; re-seed with the patched ones so the identity
        // guard sees the fixture pins (BundledCatalog caches per process).
        com.antivocale.app.data.catalog.BundledCatalog.seed(
            com.antivocale.app.data.catalog.ModelCatalogJson.parseCatalog(catalogJson))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // The patched seed must not leak into later classes sharing this
        // worker JVM (the catalog singleton caches per process).
        com.antivocale.app.data.catalog.BundledCatalog.seed(
            com.antivocale.app.transcription.bundledCatalogEntriesForTest())
    }

    /** Protobuf-framed metadata prop, as ExternalModelImporterTest's sourceDir builds. */
    private fun metadataProp(key: String, value: String): ByteArray =
        key.toByteArray() + byteArrayOf(0x12, value.length.toByte()) + value.toByteArray()

    /** The gigaam-shaped transducer source the importer's validation accepts. */
    private fun transducerSourceDir(): File {
        val dir = Files.createTempDirectory("bundle-src").toFile()
        File(dir, "gigaam_v3_e2e_rnnt_encoder_int8.onnx").writeBytes(
            ByteArray(2048) { if (it == 0) 8 else 1 } + "vocab_size=1024 subsampling_factor=8 ".toByteArray() +
                metadataProp("model_type", "nemo_transducer"))
        File(dir, "gigaam_v3_e2e_rnnt_decoder.onnx").writeBytes(ByteArray(2048) { if (it == 0) 8 else 2 })
        File(dir, "gigaam_v3_e2e_rnnt_joint.onnx").writeBytes(ByteArray(2048) { if (it == 0) 8 else 3 })
        File(dir, "gigaam_v3_e2e_rnnt_tokens.txt").writeText((0..99).joinToString("\n") { "tok$it $it" })
        return dir
    }

    /** A bundle zip of the transducer source, stamped with the given catalog identity. */
    private fun gigaamBundle(entryId: String?, variantDir: String?): ByteArray =
        bundleFrom(gigaamSourceDir, entryId, variantDir)

    private fun bundleFrom(dir: File, entryId: String?, variantDir: String?): ByteArray {
        val out = ByteArrayOutputStream()
        ModelBundleCodec.export(
            dir, out,
            ModelBundleCodec.BundleMetadata(
                displayName = "GigaAM v3", family = "TRANSDUCER", modelType = "nemo_transducer",
                languages = listOf("ru"), streaming = false, options = emptyMap(),
                appVersion = "test", catalogEntryId = entryId, catalogVariantDir = variantDir))
        return out.toByteArray()
    }

    private fun importBundle(entryId: String?, variantDir: String?) {
        nextBundle = gigaamBundle(entryId, variantDir)
        viewModel.importExternalFromBundle(mockContext, Uri.parse("content://bundle/test"))
    }

    /** A whisper-family record linked to the whisper "small" variant card. */
    private fun linkedWhisperRecord(dir: File, id: String = "linked1") = ExternalModelRecord(
        id = id, displayName = "Whisper via bundle", dir = dir.absolutePath,
        family = ModelFamily.WHISPER, modelType = "whisper",
        languages = listOf("en"), source = ExternalModelSource.LOCAL, sourceUrl = null,
        files = emptyMap(), sizeBytes = 0L, importedAt = 0L,
        linkedCatalog = LinkedCatalogRef("whisper", "sherpa-onnx-whisper-small"),
    )

    /** The real download path's whisper "small" directory shape. */
    private fun whisperSmallDir(): File {
        val dir = File(SherpaModelManager.of("whisper").getModelStorageDir(mockContext), "sherpa-onnx-whisper-small")
        dir.mkdirs()
        listOf("small-encoder.int8.onnx", "small-decoder.int8.onnx", "small-tokens.txt")
            .forEach { File(dir, it).writeText("placeholder") }
        return dir
    }

    /** The raw record list from the preference, without suspension (poll predicates). */
    private fun storedRecords(): List<ExternalModelRecord> =
        com.antivocale.app.data.ExternalModelListJson.decode(fakePrefs._externalModelsJson.value)

    /** Polls [predicate] on real time (the import and the catalog refreshes
     *  run on real IO dispatchers); FAILS the test on timeout so a broken
     *  derivation cannot pass by silently waiting out the clock. */
    private fun kotlinx.coroutines.test.TestScope.await(what: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            runCurrent()
        }
        assertTrue("timed out waiting for: $what", predicate())
    }

    private fun whisperSmallPresent(): Boolean =
        viewModel.catalogStates.value["whisper"]?.downloadedVariants?.contains("small") == true

    // ---- (a) the import pin ----

    @Test
    fun `bundle import from a built-in entry links the record and writes no sherpa model path`() = runTest {
        importBundle(entryId = "gigaam", variantDir = "gigaam-v3")
        await("bundle import registered") { storedRecords().isNotEmpty() }
        val record = store.records().single()
        assertEquals(LinkedCatalogRef("gigaam", "gigaam-v3"), record.linkedCatalog)
        // The TASK-797 alias stays reverted: the built-in loader must keep
        // resolving nothing for this entry.
        assertEquals(null, fakePrefs._sherpaModelPath("gigaam").value)
        // The linked card derives presence with no download on disk.
        await("gigaam card derives v3 present via the link") {
            viewModel.catalogStates.value["gigaam"]?.downloadedVariants?.contains("v3") == true
        }
    }

    // ---- (b) card derivation ----

    @Test
    fun `linked record marks the variant present, quarantined or dir-missing record does not`() = runTest {
        val dir = Files.createTempDirectory("linked-rec").toFile()
        val record = linkedWhisperRecord(dir)
        store.add(record)
        await("linked record marks whisper small present") { whisperSmallPresent() }

        // Quarantine (the load killed the process): the card must revert.
        store.update(record.copy(quarantined = true))
        await("quarantined record reverts the card") { !whisperSmallPresent() }

        // Un-quarantined: present again (the flow re-derives both ways).
        store.update(record.copy(quarantined = false))
        await("un-quarantined record restores the card") { whisperSmallPresent() }

        // Valid flags but a deleted dir: the store's loadability predicate
        // still excludes the record, so the card must stay reverted.
        store.update(record.copy(dir = "/nonexistent/model-dir"))
        await("dir-missing record reverts the card") { !whisperSmallPresent() }
    }

    @Test
    fun `deleting the linked record reverts the card with no per-site delete code`() = runTest {
        val dir = Files.createTempDirectory("linked-del").toFile()
        val record = linkedWhisperRecord(dir)
        store.add(record)
        await("linked record marks whisper small present") { whisperSmallPresent() }

        // deleteExternalModel knows nothing about links; the records flow
        // re-derives the catalog state (by construction, TASK-801).
        viewModel.deleteExternalModel(record)
        await("delete reverts the card") { !whisperSmallPresent() }
        await("record deleted") { storedRecords().isEmpty() }
    }

    // ---- (c) Use dispatch ----

    @Test
    fun `use on a linked-only variant activates the external record`() = runTest {
        val dir = Files.createTempDirectory("linked-use").toFile()
        val record = linkedWhisperRecord(dir)
        store.add(record)
        await("linked record marks whisper small present") { whisperSmallPresent() }

        viewModel.useModel("whisper", "small")
        await("backend switched to the linked record") {
            fakePrefs._transcriptionBackend.value == record.backendId
        }
        assertEquals(record.backendId, fakePrefs._transcriptionBackend.value)
        // The catalog ladder never ran: no saved path was written.
        assertEquals(null, fakePrefs._sherpaModelPath("whisper").value)
    }

    @Test
    fun `a disk copy keeps precedence over the linked record for use`() = runTest {
        val dir = whisperSmallDir()
        val record = linkedWhisperRecord(Files.createTempDirectory("linked-shadowed").toFile())
        store.add(record)
        await("linked record marks whisper small present") { whisperSmallPresent() }

        viewModel.useModel("whisper", "small")
        await("disk copy wins the catalog ladder") {
            fakePrefs._transcriptionBackend.value == "whisper"
        }
        assertEquals("whisper", fakePrefs._transcriptionBackend.value)
        assertEquals(dir.absolutePath, fakePrefs._sherpaModelPath("whisper").value)
    }

    // ---- (e) legacy manifests without variant identity ----

    @Test
    fun `legacy manifest without variant links a single-variant entry`() = runTest {
        importBundle(entryId = "gigaam", variantDir = null)
        await("bundle import registered") { storedRecords().isNotEmpty() }
        assertEquals(LinkedCatalogRef("gigaam", "gigaam-v3"), store.records().single().linkedCatalog)
    }

    @Test
    fun `legacy manifest without variant does not link a multi-variant entry`() = runTest {
        // Parakeet has two variants and the same modelType as the bundle; with
        // no variant identity there is nothing sound to link to.
        importBundle(entryId = "sherpa-onnx", variantDir = null)
        await("bundle import registered") { storedRecords().isNotEmpty() }
        assertEquals(null, store.records().single().linkedCatalog)
    }

    // ---- (g) guard rejections ----

    @Test
    fun `modelType mismatch imports plain without a link`() = runTest {
        // The manifest claims nemo_transducer; the named entry is whisper.
        importBundle(entryId = "whisper", variantDir = "sherpa-onnx-whisper-small")
        await("bundle import registered") { storedRecords().isNotEmpty() }
        assertEquals(null, store.records().single().linkedCatalog)
    }

    @Test
    fun `a bundle riding extra canonical-named files imports plain without a link`() = runTest {
        // Every catalog pin correct, plus one extra file under a canonical
        // import name: key-set equality must refuse the link, or the importer's
        // role matching could load the substitute while the card vouches for
        // the catalog bytes.
        val src = Files.createTempDirectory("bundle-extra").toFile()
        gigaamSourceDir.listFiles().forEach { it.copyTo(File(src, it.name)) }
        // A loadable substitute: the real encoder bytes under the canonical
        // name (garbage bytes would fail the importer's metadata gate before
        // the link decision, which is not what this test pins).
        File(src, "encoder.int8.onnx").writeBytes(
            File(gigaamSourceDir, "gigaam_v3_e2e_rnnt_encoder_int8.onnx").readBytes())
        nextBundle = bundleFrom(src, "gigaam", "gigaam-v3")
        viewModel.importExternalFromBundle(mockContext, Uri.parse("content://bundle/extra"))
        await("bundle import registered") { storedRecords().isNotEmpty() }
        assertEquals(null, store.records().single().linkedCatalog)
    }

    @Test
    fun `a bundle whose pins differ from the catalog variant imports plain without a link`() = runTest {
        // The REAL catalog pins the real GigaAM weights; the fixture bytes
        // hash differently, so the identity guard must refuse the card.
        com.antivocale.app.data.catalog.BundledCatalog.seed(
            com.antivocale.app.transcription.bundledCatalogEntriesForTest())
        importBundle(entryId = "gigaam", variantDir = "gigaam-v3")
        await("bundle import registered") { storedRecords().isNotEmpty() }
        val record = store.records().single()
        assertEquals(null, record.linkedCatalog)
        assertEquals(null, fakePrefs._sherpaModelPath("gigaam").value)
    }

    @Test
    fun `unknown entry id imports plain without a link`() = runTest {
        importBundle(entryId = "no-such-entry", variantDir = "whatever")
        await("bundle import registered") { storedRecords().isNotEmpty() }
        assertEquals(null, store.records().single().linkedCatalog)
    }
}
