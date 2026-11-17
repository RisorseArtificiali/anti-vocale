package com.antivocale.app.data.download

import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.data.catalog.ModelCatalogJson
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TASK-994.1: the supplement channel. The parser tests pin the schema
 * (supplements + the repo override); the pass tests pin the migration
 * contract: fetch ONLY for installed variants, ONLY with a dictionary,
 * ONLY what is missing, sha-verified, failure-deferring, and never
 * touching dir validity.
 */
class BiasingVocabSupplementTest {

    @get:Rule val tmp = TemporaryFolder()

    private val prefs = FakePreferencesManager()
    private val context = mockk<android.content.Context>()
    private val backendManager = mockk<com.antivocale.app.transcription.TranscriptionBackendManager>(relaxed = true)
    private lateinit var filesRoot: File
    private lateinit var modelDir: File

    private val vocabBytes = "piece\tscore\n".repeat(8).toByteArray()
    private val vocabSha = sha256(vocabBytes)

    private val fetched = mutableListOf<Triple<String, File, File>>()

    @Before
    fun setUp() {
        com.antivocale.app.service.InferenceService.alive = false
        every { backendManager.activeBackendId } returns
            kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        filesRoot = tmp.newFolder("files")
        every { context.filesDir } returns filesRoot
        every { context.cacheDir } returns tmp.newFolder("cache")
        modelDir = File(File(filesRoot, "supp-test"), "supp-v1")
        modelDir.mkdirs()
        File(modelDir, "tokens.txt").writeText("a\nb\n")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun seedCatalog(supplementRepo: String? = "acme/vocabs") {
        val supplement = buildString {
            append("""{"name": "bpe.vocab", "sha256": "$vocabSha"""")
            if (supplementRepo != null) append(""", "repo": "$supplementRepo"""")
            append("}")
        }
        val json = """
            {"schemaVersion": 1, "models": [
              {"id": "supp-test", "runtime": "offline", "modelType": "nemo_transducer",
               "family": "TRANSDUCER", "display": {"text": "Supp Test"},
               "variants": [
                 {"name": "v1", "dirName": "supp-v1", "estimatedSizeMB": 1,
                  "source": {"kind": "huggingface", "repo": "acme/models"},
                  "files": [{"name": "tokens.txt"}],
                  "supplements": [$supplement]}
               ]}
            ]}
        """.trimIndent()
        BundledCatalog.seed(ModelCatalogJson.parseCatalog(json))
    }

    private fun newPass(bytes: ByteArray = vocabBytes) =
        BiasingVocabSupplement(prefs, backendManager, com.antivocale.app.data.FakeExternalRecordsProvider()).apply {
        fetcher = { url, temp, target ->
            fetched.add(Triple(url, temp, target))
            runCatching { target.writeBytes(bytes); target }
        }
    }

    private fun newFailingPass() = BiasingVocabSupplement(prefs, backendManager, com.antivocale.app.data.FakeExternalRecordsProvider()).apply {
        fetcher = { _, _, _ -> Result.failure(IllegalStateException("offline")) }
    }

    @Test
    fun `supplements parse with the repo override and default to absent`() {
        seedCatalog(supplementRepo = "acme/vocabs")
        val variant = BundledCatalog.byId("supp-test")!!.variants.single()
        val supplement = variant.supplements.single()
        assertEquals("bpe.vocab", supplement.name)
        assertEquals(vocabSha, supplement.sha256)
        assertEquals("acme/vocabs", supplement.repo)
        assertEquals(listOf("tokens.txt"), variant.files.map { it.name })

        val withoutKey = ModelCatalogJson.parseCatalog(
            """{"schemaVersion": 1, "models": [
                 {"id": "bare", "runtime": "offline", "modelType": "nemo_transducer",
                  "family": "TRANSDUCER", "display": {"text": "Bare"},
                  "variants": [{"name": "v1", "dirName": "bare-v1", "estimatedSizeMB": 1,
                   "source": {"kind": "huggingface", "repo": "acme/models"},
                   "files": ["tokens.txt"]}]}
               ]}""")
        assertTrue(withoutKey.single().variants.single().supplements.isEmpty())
    }

    @Test
    fun `an empty dictionary fetches nothing`() = runTest {
        seedCatalog()
        prefs._keywordBiasing.value = ""
        newPass().run(context)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `an installed variant with a dictionary gains the pinned vocab`() = runTest {
        seedCatalog(supplementRepo = "acme/vocabs")
        prefs._keywordBiasing.value = "Boncompagni"
        newPass().run(context)

        assertEquals(1, fetched.size)
        assertEquals(
            "https://huggingface.co/acme/vocabs/resolve/main/bpe.vocab",
            fetched.single().first)
        val landed = File(modelDir, "bpe.vocab")
        assertTrue(landed.isFile)
        assertTrue(landed.readBytes().contentEquals(vocabBytes))
        // The resume temp lives OUTSIDE the model dir (bundles/validators
        // must never see a partial): its parent is the cache root.
        assertFalse(fetched.single().second.parentFile == modelDir)
    }

    @Test
    fun `without a repo override the vocab rides the variant source repo`() = runTest {
        seedCatalog(supplementRepo = null)
        prefs._keywordBiasing.value = "Boncompagni"
        newPass().run(context)
        assertEquals(
            "https://huggingface.co/acme/models/resolve/main/bpe.vocab",
            fetched.single().first)
    }

    @Test
    fun `a pin mismatch deletes the fetched file`() = runTest {
        seedCatalog()
        prefs._keywordBiasing.value = "Boncompagni"
        newPass(bytes = "tampered".toByteArray()).run(context)
        assertFalse(File(modelDir, "bpe.vocab").exists())
    }

    @Test
    fun `a variant dir missing its files is not an install and fetches nothing`() = runTest {
        seedCatalog()
        prefs._keywordBiasing.value = "Boncompagni"
        File(modelDir, "tokens.txt").delete()
        newPass().run(context)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `a rotted vocab on disk is refetched surgically`() = runTest {
        seedCatalog()
        prefs._keywordBiasing.value = "Boncompagni"
        File(modelDir, "bpe.vocab").writeBytes("rotted".toByteArray())
        newPass().run(context)
        // The corrupt file was replaced by the pinned bytes.
        assertEquals(1, fetched.size)
        val landed = File(modelDir, "bpe.vocab")
        assertTrue(landed.readBytes().contentEquals(vocabBytes))
    }

    @Test
    fun `a repo key on a files entry is rejected at parse`() {
        val ex = runCatching {
            ModelCatalogJson.parseCatalog(
                """{"schemaVersion": 1, "models": [
                     {"id": "bad", "runtime": "offline", "modelType": "nemo_transducer",
                      "family": "TRANSDUCER", "display": {"text": "Bad"},
                      "variants": [{"name": "v1", "dirName": "bad-v1", "estimatedSizeMB": 1,
                       "source": {"kind": "huggingface", "repo": "acme/models"},
                       "files": [{"name": "tokens.txt", "repo": "other/repo"}]}]}
                   ]}""")
        }.exceptionOrNull()
        assertTrue("expected the supplements-only repo guard to fire, got $ex",
            ex is IllegalArgumentException)
    }

    @Test
    fun `an already-present vocab is not re-fetched`() = runTest {
        seedCatalog()
        prefs._keywordBiasing.value = "Boncompagni"
        File(modelDir, "bpe.vocab").writeBytes(vocabBytes)
        newPass().run(context)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `a landing for the ACTIVE backend unloads it so the next request re-arms`() = runTest {
        seedCatalog()
        prefs._keywordBiasing.value = "Boncompagni"
        every { backendManager.activeBackendId } returns
            kotlinx.coroutines.flow.MutableStateFlow<String?>("supp-test")
        newPass().run(context)
        io.mockk.verify(exactly = 1) { backendManager.unloadActiveBackend() }
        assertTrue(File(modelDir, "bpe.vocab").isFile)
    }

    @Test
    fun `a landing for an INACTIVE backend never unloads`() = runTest {
        seedCatalog()
        prefs._keywordBiasing.value = "Boncompagni"
        every { backendManager.activeBackendId } returns
            kotlinx.coroutines.flow.MutableStateFlow<String?>("some-other-backend")
        newPass().run(context)
        io.mockk.verify(exactly = 0) { backendManager.unloadActiveBackend() }
    }

    @Test
    fun `a dictionary change unloads an ACTIVE supplement-bearing backend`() {
        every { backendManager.activeBackendId } returns
            kotlinx.coroutines.flow.MutableStateFlow<String?>("supp-test")
        newPass().onDictionaryChanged()
        io.mockk.verify(exactly = 1) { backendManager.unloadActiveBackend() }
    }

    @Test
    fun `a dictionary change is a no-op for non-supplement backends`() {
        every { backendManager.activeBackendId } returns
            kotlinx.coroutines.flow.MutableStateFlow<String?>("whisper")
        newPass().onDictionaryChanged()
        io.mockk.verify(exactly = 0) { backendManager.unloadActiveBackend() }
    }

    @Test
    fun `a dictionary change with no active backend is a no-op`() {
        every { backendManager.activeBackendId } returns
            kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        newPass().onDictionaryChanged()
        io.mockk.verify(exactly = 0) { backendManager.unloadActiveBackend() }
    }

    @Test
    fun `a failed fetch defers to the next start without crashing`() = runTest {
        seedCatalog()
        prefs._keywordBiasing.value = "Boncompagni"
        newFailingPass().run(context)
        assertFalse(File(modelDir, "bpe.vocab").exists())
        // The dir stays a valid install: completeness never saw the supplement.
        assertTrue(File(modelDir, "tokens.txt").isFile)
    }


    @Test
    fun `an external catalog record gains its entry-declared supplement and re-arms`() = runTest {
        seedCatalog()
        File(modelDir, "tokens.txt").delete() // the built-in lane stays not-installed
        prefs._keywordBiasing.value = "قنبری"
        val recordDir = seedExternalFarsi()
        val entryJson = farsiEntryJson()
        val provider = com.antivocale.app.data.FakeExternalRecordsProvider(listOf(farsiRecord()))
        every { backendManager.activeBackendId } returns
            kotlinx.coroutines.flow.MutableStateFlow("external:abc")
        val entryFetches = mutableListOf<String>()
        val pass = BiasingVocabSupplement(prefs, backendManager, provider).apply {
            entryTextFetcher = { entryFetches.add(it); entryJson }
            fetcher = { url, temp, target ->
                fetched.add(Triple(url, temp, target))
                runCatching { target.writeBytes(vocabBytes); target }
            }
        }
        pass.run(context)
        assertEquals(listOf("https://catalog/farsi.json"), entryFetches)

        val landed = File(recordDir, "bpe.vocab")
        assertTrue(landed.isFile)
        assertTrue(landed.readBytes().contentEquals(vocabBytes))
        // the entry JSON rides the text seam, then the supplement the binary one
        assertEquals(listOf("https://mirror/bpe.vocab"), fetched.map { it.first })
        io.mockk.verify(exactly = 1) { backendManager.unloadActiveBackend() }
    }

    @Test
    fun `an external record with an already-pinned supplement costs no network`() = runTest {
        seedCatalog()
        File(modelDir, "tokens.txt").delete() // the built-in lane stays not-installed
        prefs._keywordBiasing.value = "قنبری"
        val recordDir = seedExternalFarsi(supplementPresent = true)
        val provider = com.antivocale.app.data.FakeExternalRecordsProvider(listOf(farsiRecord()))
        val entryFetches = mutableListOf<String>()
        BiasingVocabSupplement(prefs, backendManager, provider).apply {
            entryTextFetcher = { entryFetches.add(it); farsiEntryJson() }
            fetcher = { url, _, _ ->
                fetched.add(Triple(url, File("/tmp"), File("/tmp")))
                Result.failure(IllegalStateException("offline after recording"))
            }
        }.run(context)
        // The entry JSON is the one unavoidable touch (the supplement names
        // live in it); the PRESENT supplement itself is never fetched.
        assertEquals(listOf("https://catalog/farsi.json"), entryFetches)
        assertTrue(fetched.isEmpty())
        io.mockk.verify(exactly = 0) { backendManager.unloadActiveBackend() }
    }

    @Test
    fun `onDictionaryChanged unloads immediately when no service is alive`() = runTest {
        seedCatalog()
        every { backendManager.activeBackendId } returns
            kotlinx.coroutines.flow.MutableStateFlow("supp-test")
        newPass().onDictionaryChanged()
        io.mockk.verify(exactly = 1) { backendManager.unloadActiveBackend() }
    }

    @Test
    fun `onDictionaryChanged defers to the batch drain when a service is alive`() = runTest {
        seedCatalog()
        every { backendManager.activeBackendId } returns
            kotlinx.coroutines.flow.MutableStateFlow("supp-test")
        com.antivocale.app.service.InferenceService.alive = true
        try {
            newPass().onDictionaryChanged()
            io.mockk.verify(exactly = 0) { backendManager.unloadActiveBackend() }
            // the deferred flag is armed; the service's drain consumes it
            assertTrue(com.antivocale.app.service.InferenceService.takePendingRearm())
            assertFalse(com.antivocale.app.service.InferenceService.takePendingRearm())
        } finally {
            com.antivocale.app.service.InferenceService.alive = false
        }
    }

    private fun farsiEntryJson() = """
        {"name": "Farsi Streaming", "family": "TRANSDUCER", "modelType": "",
         "languages": ["fa"], "streaming": true,
         "files": [{"name": "tokens.txt", "url": "https://x/tokens.txt",
                    "sha256": "${"a".repeat(64)}", "size": 2}],
         "supplements": [{"name": "bpe.vocab", "url": "https://mirror/bpe.vocab",
                          "sha256": "$vocabSha"}]}
    """.trimIndent()

    private fun farsiRecord() = com.antivocale.app.data.ExternalModelRecord(
        id = "abc", displayName = "Farsi Streaming",
        dir = File(filesRoot, "models/external/farsi-abc").absolutePath,
        family = com.antivocale.app.data.ModelFamily.TRANSDUCER,
        modelType = "", languages = listOf("fa"),
        source = com.antivocale.app.data.ExternalModelSource.URL,
        sourceUrl = "https://catalog/farsi.json",
        files = emptyMap(), sizeBytes = 1, importedAt = 0L,
        streaming = true)

    /** Seeds the Farsi record dir; returns it (supplement optionally pre-planted). */
    private fun seedExternalFarsi(supplementPresent: Boolean = false): File {
        val recordDir = File(File(filesRoot, "models/external"), "farsi-abc")
        recordDir.mkdirs()
        File(recordDir, "tokens.txt").writeText("x\n")
        if (supplementPresent) File(recordDir, "bpe.vocab").writeBytes(vocabBytes)
        return recordDir
    }
}
