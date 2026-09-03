package com.antivocale.app.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * URL-import tests (plan v2a, Task 8): repo-id parsing, HF tree listing (LFS oid vs
 * plain file), catalog-entry JSON parsing with mandatory hashes, and the end-to-end
 * importer path against a MockWebServer (canonical landing, verified vs TOFU pins,
 * hashless-entry rejection).
 */
class HuggingFaceRepoListingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var listing: HuggingFaceRepoListing
    private lateinit var fake: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var filesRoot: File
    private lateinit var importer: ExternalModelImporter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        listing = HuggingFaceRepoListing(apiBase = server.url("/").toString().trimEnd('/'))
        fake = FakePreferencesManager()
        store = ExternalModelStore(fake)
        filesRoot = tmp.newFolder("external-root")
        importer = ExternalModelImporter(
            store = store,
            filesRoot = { filesRoot },
            uuid = { "fedcba9876543210" },
            repoListing = listing,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- parsing ----

    @Test
    fun `parseRepoId accepts repo urls and rejects others`() {
        assertEquals("pantinor/gigaam-v3", HuggingFaceRepoListing.parseRepoId("https://huggingface.co/pantinor/gigaam-v3"))
        assertEquals("pantinor/gigaam-v3", HuggingFaceRepoListing.parseRepoId("https://huggingface.co/pantinor/gigaam-v3/tree/main"))
        assertEquals("istupakov/gigaam-v3-onnx", HuggingFaceRepoListing.parseRepoId("istupakov/gigaam-v3-onnx"))
        assertNull(HuggingFaceRepoListing.parseRepoId("https://huggingface.co/only-owner"))
        assertNull(HuggingFaceRepoListing.parseRepoId("https://example.com/a/b"))
    }

    @Test
    fun `tree listing maps lfs and plain files`() = runTest {
        server.enqueue(MockResponse().setBody("""
            [
              {"type":"file","path":"gigaam_v3_e2e_rnnt_encoder_int8.onnx","lfs":{"oid":"${"a".repeat(64)}","size":318995997},"size":318995997},
              {"type":"file","path":"tokens.txt","size":13353},
              {"type":"directory","path":"subdir"}
            ]
        """.trimIndent()))

        val files = listing.listFiles("pantinor/gigaam-v3")

        assertEquals(2, files.size)
        val lfs = files[0] as HuggingFaceRepoListing.HfFile.Lfs
        assertEquals("gigaam_v3_e2e_rnnt_encoder_int8.onnx", lfs.name)
        assertEquals("a".repeat(64), lfs.sha256)
        assertEquals(318995997L, lfs.size)
        assertTrue(files[1] is HuggingFaceRepoListing.HfFile.Plain)
        assertEquals("tokens.txt", (files[1] as HuggingFaceRepoListing.HfFile.Plain).name)
    }

    @Test
    fun `entry json parses and demands hashes`() {
        val entry = ExternalModelEntryJson.parse("""
            {"name":"GigaAM v3","modelType":"nemo_transducer","languages":["ru"],
             "files":[{"name":"some_encoder.onnx","url":"https://x/e.onnx","sha256":"${"b".repeat(64)}","size":100},
                      {"name":"decoder.onnx","url":"https://x/d.onnx","sha256":"${"c".repeat(64)}","size":50},
                      {"name":"joiner.onnx","url":"https://x/j.onnx","sha256":"${"d".repeat(64)}","size":50},
                      {"name":"tokens.txt","url":"https://x/t.txt","sha256":"${"e".repeat(64)}","size":10}]}
        """.trimIndent())
        assertEquals("GigaAM v3", entry.name)
        assertEquals("nemo_transducer", entry.modelType)
        assertEquals(4, entry.files.size)

        val hashless = runCatching {
            ExternalModelEntryJson.parse("""{"name":"x","files":[{"name":"a.onnx","url":"https://x/a"}]}""")
        }
        assertTrue(hashless.isFailure)

        // Sizes are mandatory too: they feed the unconditional disk pre-flight.
        val sizeless = runCatching {
            ExternalModelEntryJson.parse(
                """{"name":"x","files":[{"name":"a.onnx","url":"https://x/a","sha256":"${"f".repeat(64)}"}]}""")
        }
        assertTrue(sizeless.isFailure)
    }

    @Test
    fun `entry without family parses as TRANSDUCER`() {
        val entry = ExternalModelEntryJson.parse("""
            {"name":"Old Model","languages":["en"],"modelType":"nemo_transducer",
             "files":[{"name":"encoder.onnx","url":"https://x/e.onnx","sha256":"${"a".repeat(64)}","size":100}]}
        """.trimIndent())
        assertEquals(ModelFamily.TRANSDUCER, entry.family)
    }

    @Test
    fun `entry with WHISPER family parses correctly`() {
        val entry = ExternalModelEntryJson.parse("""
            {"name":"Arabic Whisper","family":"WHISPER","languages":["ar"],
             "files":[{"name":"encoder.onnx","url":"https://x/e.onnx","sha256":"${"a".repeat(64)}","size":100}]}
        """.trimIndent())
        assertEquals(ModelFamily.WHISPER, entry.family)
        assertEquals("", entry.modelType)
    }

    @Test
    fun `entry with unknown family throws IllegalArgumentException`() {
        val error = runCatching {
            ExternalModelEntryJson.parse("""
                {"name":"Bad","family":"UNKNOWN_FAMILY","languages":["en"],
                 "files":[{"name":"encoder.onnx","url":"https://x/e.onnx","sha256":"${"a".repeat(64)}","size":100}]}
            """.trimIndent())
        }
        assertTrue(error.isFailure)
        assertTrue(error.exceptionOrNull()?.message?.contains("UNKNOWN_FAMILY") == true)
        assertTrue(
            "error must be the named unknown-family message, not a bare enum error: ${error.exceptionOrNull()?.message}",
            error.exceptionOrNull()?.message?.contains("unknown family") == true)
    }

    @Test
    fun `entry with options parses correctly`() {
        val entry = ExternalModelEntryJson.parse("""
            {"name":"Whisper Arabic","family":"WHISPER","languages":["ar"],
             "options":{"whisper.language":"ar","whisper.task":"transcribe"},
             "files":[{"name":"encoder.onnx","url":"https://x/e.onnx","sha256":"${"a".repeat(64)}","size":100}]}
        """.trimIndent())
        assertEquals(2, entry.options.size)
        assertEquals("ar", entry.options["whisper.language"])
        assertEquals("transcribe", entry.options["whisper.task"])
    }

    @Test
    fun `WHISPER entry without modelType defaults to empty`() {
        val entry = ExternalModelEntryJson.parse("""
            {"name":"Whisper","family":"WHISPER","languages":["en"],
             "files":[{"name":"encoder.onnx","url":"https://x/e.onnx","sha256":"${"a".repeat(64)}","size":100}]}
        """.trimIndent())
        assertEquals("", entry.modelType)
    }

    @Test
    fun `TRANSDUCER entry without modelType defaults to nemo_transducer`() {
        val entry = ExternalModelEntryJson.parse("""
            {"name":"Transducer","family":"TRANSDUCER","languages":["en"],
             "files":[{"name":"encoder.onnx","url":"https://x/e.onnx","sha256":"${"a".repeat(64)}","size":100}]}
        """.trimIndent())
        assertEquals("nemo_transducer", entry.modelType)
    }

    @Test
    fun `entry with family but no languages is rejected`() {
        val error = runCatching {
            ExternalModelEntryJson.parse("""
                {"name":"Bad","family":"WHISPER",
                 "files":[{"name":"encoder.onnx","url":"https://x/e.onnx","sha256":"${"a".repeat(64)}","size":100}]}
            """.trimIndent())
        }
        assertTrue(error.isFailure)
        assertTrue(error.exceptionOrNull()?.message?.contains("languages") == true)
    }

    @Test
    fun `WHISPER entry with non-empty transducer modelType is rejected`() {
        val error = runCatching {
            ExternalModelEntryJson.parse("""
                {"name":"Bad","family":"WHISPER","modelType":"nemo_transducer","languages":["en"],
                 "files":[{"name":"encoder.onnx","url":"https://x/e.onnx","sha256":"${"a".repeat(64)}","size":100}]}
            """.trimIndent())
        }
        assertTrue(error.isFailure)
        assertTrue(error.exceptionOrNull()?.message?.contains("modelType") == true)
    }

    @Test
    fun `CTC entry with valid modelType parses correctly`() {
        val entry = ExternalModelEntryJson.parse("""
            {"name":"CTC Model","family":"CTC","modelType":"nemo_ctc","languages":["en"],
             "files":[{"name":"encoder.onnx","url":"https://x/e.onnx","sha256":"${"a".repeat(64)}","size":100}]}
        """.trimIndent())
        assertEquals("nemo_ctc", entry.modelType)
    }

    @Test
    fun `CTC entry without modelType is rejected with clear error`() {
        val error = runCatching {
            ExternalModelEntryJson.parse("""
                {"name":"CTC No Type","family":"CTC","languages":["en"],
                 "files":[{"name":"encoder.onnx","url":"https://x/e.onnx","sha256":"${"a".repeat(64)}","size":100}]}
            """.trimIndent())
        }
        assertTrue(error.isFailure)
        assertTrue(error.exceptionOrNull()?.message?.contains("CTC family requires an explicit modelType") == true)
    }

    @Test
    fun `CTC entry with invalid modelType is rejected`() {
        val error = runCatching {
            ExternalModelEntryJson.parse("""
                {"name":"Bad","family":"CTC","modelType":"bad_type","languages":["en"],
                 "files":[{"name":"encoder.onnx","url":"https://x/e.onnx","sha256":"${"a".repeat(64)}","size":100}]}
            """.trimIndent())
        }
        assertTrue(error.isFailure)
        assertTrue(error.exceptionOrNull()?.message?.contains("modelType") == true)
    }

    @Test
    fun `TRANSDUCER entry with conformer_transducer parses correctly`() {
        val entry = ExternalModelEntryJson.parse("""
            {"name":"Conformer","family":"TRANSDUCER","modelType":"conformer_transducer","languages":["en"],
             "files":[{"name":"encoder.onnx","url":"https://x/e.onnx","sha256":"${"a".repeat(64)}","size":100}]}
        """.trimIndent())
        assertEquals("conformer_transducer", entry.modelType)
    }

    @Test
    fun `TRANSDUCER entry with invalid modelType is rejected`() {
        val error = runCatching {
            ExternalModelEntryJson.parse("""
                {"name":"Bad","family":"TRANSDUCER","modelType":"whisper","languages":["en"],
                 "files":[{"name":"encoder.onnx","url":"https://x/e.onnx","sha256":"${"a".repeat(64)}","size":100}]}
            """.trimIndent())
        }
        assertTrue(error.isFailure)
        assertTrue(error.exceptionOrNull()?.message?.contains("modelType") == true)
    }

    @Test
    fun `legacy entry without family or languages defaults correctly`() {
        val entry = ExternalModelEntryJson.parse("""
            {"name":"legacy","files":[{"name":"a.onnx","url":"https://x/a","sha256":"${"a".repeat(64)}","size":1}]}
        """.trimIndent())
        assertEquals(ModelFamily.TRANSDUCER, entry.family)
        assertEquals(emptyList<String>(), entry.languages)
    }

    // ---- end-to-end against the mock server ----

    private val encoderBytes = ByteArray(64) { 1 } + "vocab_size=1024 subsampling_factor=8 model_type=nemo_transducer".toByteArray()
    private val decoderBytes = ByteArray(16) { 2 }
    private val joinerBytes = ByteArray(16) { 3 }
    private val tokensBytes = "<unk> 0\n. 1\n".toByteArray()

    @Test
    fun `importFromHuggingFaceRepo downloads under canonical names with TOFU for non-LFS`() = runTest {
        server.enqueue(MockResponse().setBody("""
            [
              {"type":"file","path":"gigaam_encoder_int8.onnx","lfs":{"oid":"${sha256(encoderBytes)}","size":${encoderBytes.size}},"size":${encoderBytes.size}},
              {"type":"file","path":"gigaam_decoder.onnx","lfs":{"oid":"${sha256(decoderBytes)}","size":${decoderBytes.size}},"size":${decoderBytes.size}},
              {"type":"file","path":"gigaam_joiner.onnx","lfs":{"oid":"${sha256(joinerBytes)}","size":${joinerBytes.size}},"size":${joinerBytes.size}},
              {"type":"file","path":"tokens.txt","size":${tokensBytes.size}}
            ]
        """.trimIndent()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(encoderBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(decoderBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(joinerBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(tokensBytes)))

        val record = importer.importFromHuggingFaceRepo("https://huggingface.co/pantinor/gigaam-v3")

        assertEquals(ExternalModelSource.URL, record.source)
        assertEquals(4, record.files.size)
        assertTrue(File(record.dir, "encoder.int8.onnx").exists())
        assertTrue(File(record.dir, "decoder.int8.onnx").exists())
        assertTrue(File(record.dir, "joiner.int8.onnx").exists())
        assertTrue(File(record.dir, "tokens.txt").exists())
        assertTrue("LFS pins verified", record.files["encoder.int8.onnx"]!!.verified)
        assertTrue("non-LFS pin computed (TOFU)", !record.files["tokens.txt"]!!.verified)
        assertEquals(sha256(tokensBytes), record.files["tokens.txt"]!!.sha256)
    }

    @Test
    fun `importFromEntryJson downloads all files and verifies their hashes`() = runTest {
        val base = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse().setBody("""
            {"name":"GigaAM v3","modelType":"nemo_transducer",
             "files":[{"name":"my_encoder.onnx","url":"$base/e","sha256":"${sha256(encoderBytes)}","size":${encoderBytes.size}},
                      {"name":"decoder.onnx","url":"$base/d","sha256":"${sha256(decoderBytes)}","size":${decoderBytes.size}},
                      {"name":"joiner.onnx","url":"$base/j","sha256":"${sha256(joinerBytes)}","size":${joinerBytes.size}},
                      {"name":"tokens.txt","url":"$base/t","sha256":"${sha256(tokensBytes)}","size":${tokensBytes.size}}]}
        """.trimIndent()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(encoderBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(decoderBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(joinerBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(tokensBytes)))

        val record = importer.importFromEntryJson("$base/entry.json")

        assertEquals("GigaAM v3", record.displayName)
        assertTrue(record.files.values.all { it.verified })
        assertTrue(File(record.dir, "encoder.int8.onnx").exists())
        assertEquals(ModelFamily.TRANSDUCER, record.family)
    }

    @Test
    fun `hf repo import downloads the onnx sidecar as an extra LFS-pinned triple`() = runTest {
        val sidecarBytes = ByteArray(24) { 9 }
        server.enqueue(MockResponse().setBody("""
            [
              {"type":"file","path":"gigaam_encoder_int8.onnx","lfs":{"oid":"${sha256(encoderBytes)}","size":${encoderBytes.size}},"size":${encoderBytes.size}},
              {"type":"file","path":"gigaam_encoder_int8.onnx.data","lfs":{"oid":"${sha256(sidecarBytes)}","size":${sidecarBytes.size}},"size":${sidecarBytes.size}},
              {"type":"file","path":"gigaam_decoder.onnx","lfs":{"oid":"${sha256(decoderBytes)}","size":${decoderBytes.size}},"size":${decoderBytes.size}},
              {"type":"file","path":"gigaam_joiner.onnx","lfs":{"oid":"${sha256(joinerBytes)}","size":${joinerBytes.size}},"size":${joinerBytes.size}},
              {"type":"file","path":"tokens.txt","size":${tokensBytes.size}}
            ]
        """.trimIndent()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(encoderBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(decoderBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(joinerBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(tokensBytes)))
        // Sidecars are appended after the roles in the download plan.
        server.enqueue(MockResponse().setBody(okio.Buffer().write(sidecarBytes)))

        val record = importer.importFromHuggingFaceRepo("https://huggingface.co/pantinor/gigaam-v3")
        assertTrue(File(record.dir, "gigaam_encoder_int8.onnx.data").exists())
        assertEquals(sha256(sidecarBytes), record.files["gigaam_encoder_int8.onnx.data"]!!.sha256)
        assertTrue("LFS sidecar pin verified", record.files["gigaam_encoder_int8.onnx.data"]!!.verified)
    }

    @Test
    fun `entry missing the onnx sidecar its encoder references fails loudly`() = runTest {
        val base = server.url("/").toString().trimEnd('/')
        // Split encoder: the protobuf references its external-data file by name.
        val splitEncoder = ByteArray(32) { 1 } +
            "vocab_size=1024 subsampling_factor=8 model_type=nemo_transducer some_encoder.onnx.data".toByteArray()
        server.enqueue(MockResponse().setBody("""
            {"name":"GigaAM split","modelType":"nemo_transducer",
             "files":[{"name":"some_encoder.onnx","url":"$base/e","sha256":"${sha256(splitEncoder)}","size":${splitEncoder.size}},
                      {"name":"decoder.onnx","url":"$base/d","sha256":"${sha256(decoderBytes)}","size":${decoderBytes.size}},
                      {"name":"joiner.onnx","url":"$base/j","sha256":"${sha256(joinerBytes)}","size":${joinerBytes.size}},
                      {"name":"tokens.txt","url":"$base/t","sha256":"${sha256(tokensBytes)}","size":${tokensBytes.size}}]}
        """.trimIndent()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(splitEncoder)))

        val result = runCatching { importer.importFromEntryJson("$base/entry.json") }

        assertTrue(result.isFailure)
        assertTrue(
            "error must name the missing sidecar: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("some_encoder.onnx.data") == true)
        assertEquals(0, store.records().size)
    }

    @Test
    fun `importFromEntryJson drives family, options and languages from the entry`() = runTest {
        val base = server.url("/").toString().trimEnd('/')
        // Protobuf-framed metadata prop so the value-aware whisper validation reads it.
        val whisperEncoder = ByteArray(32) { 1 } +
            "model_type".toByteArray() + byteArrayOf(0x12, 0x0B) + "whisper-tiny".toByteArray()
        val whisperDecoder = ByteArray(16) { 2 }
        val whisperTokens = "<unk> 0\n".toByteArray()
        server.enqueue(MockResponse().setBody("""
            {"name":"Arabic Whisper","family":"WHISPER","languages":["ar"],
             "options":{"whisper.language":"ar","whisper.task":"transcribe"},
             "files":[{"name":"w_encoder.onnx","url":"$base/we","sha256":"${sha256(whisperEncoder)}","size":${whisperEncoder.size}},
                      {"name":"w_decoder.onnx","url":"$base/wd","sha256":"${sha256(whisperDecoder)}","size":${whisperDecoder.size}},
                      {"name":"tokens.txt","url":"$base/t","sha256":"${sha256(whisperTokens)}","size":${whisperTokens.size}}]}
        """.trimIndent()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(whisperEncoder)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(whisperDecoder)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(whisperTokens)))

        val record = importer.importFromEntryJson("$base/entry.json")

        assertEquals(ModelFamily.WHISPER, record.family)
        assertEquals("", record.modelType)
        assertEquals(listOf("ar"), record.languages)
        assertEquals("ar", record.options["whisper.language"])
        assertEquals("transcribe", record.options["whisper.task"])
    }

    @Test
    fun `same-hash entry reimport dedupes onto the record and refreshes its sourceUrl`() = runTest {
        val base = server.url("/").toString().trimEnd('/')
        fun enqueueEntryPlusBodies() {
            server.enqueue(MockResponse().setBody("""
                {"name":"GigaAM v3","modelType":"nemo_transducer",
                 "files":[{"name":"my_encoder.onnx","url":"$base/e","sha256":"${sha256(encoderBytes)}","size":${encoderBytes.size}},
                          {"name":"decoder.onnx","url":"$base/d","sha256":"${sha256(decoderBytes)}","size":${decoderBytes.size}},
                          {"name":"joiner.onnx","url":"$base/j","sha256":"${sha256(joinerBytes)}","size":${joinerBytes.size}},
                          {"name":"tokens.txt","url":"$base/t","sha256":"${sha256(tokensBytes)}","size":${tokensBytes.size}}]}
            """.trimIndent()))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(encoderBytes)))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(decoderBytes)))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(joinerBytes)))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(tokensBytes)))
        }

        enqueueEntryPlusBodies()
        val first = importer.importFromEntryJson("$base/entry.json")
        assertEquals("$base/entry.json", first.sourceUrl)

        // Same file hashes (same pins) from a different entry URL: dedupe must land
        // on the existing record WITH the fresh entry url, not keep the stale one.
        enqueueEntryPlusBodies()
        val second = importer.importFromEntryJson("$base/entry-v2.json")

        assertEquals(first.id, second.id)
        assertEquals(1, store.records().size)
        assertEquals("$base/entry-v2.json", second.sourceUrl)
        assertEquals("$base/entry-v2.json", store.records().single().sourceUrl)
    }

    @Test
    fun `folder reimport over a URL record keeps its provenance`() = runTest {
        // Code review 2026-09-03: importFromDirectory registers with
        // sourceUrl=null; the dedup copy must not erase a URL-imported
        // record's provenance while leaving source=URL behind.
        val base = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse().setBody("""
            {"name":"GigaAM v3","modelType":"nemo_transducer",
             "files":[{"name":"my_encoder.onnx","url":"$base/e","sha256":"${sha256(encoderBytes)}","size":${encoderBytes.size}},
                      {"name":"decoder.onnx","url":"$base/d","sha256":"${sha256(decoderBytes)}","size":${decoderBytes.size}},
                      {"name":"joiner.onnx","url":"$base/j","sha256":"${sha256(joinerBytes)}","size":${joinerBytes.size}},
                      {"name":"tokens.txt","url":"$base/t","sha256":"${sha256(tokensBytes)}","size":${tokensBytes.size}}]}
        """.trimIndent()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(encoderBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(decoderBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(joinerBytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(tokensBytes)))
        val urlRecord = importer.importFromEntryJson("$base/entry.json")
        assertEquals("$base/entry.json", urlRecord.sourceUrl)

        // A FOLDER reimport of the identical files (same pins): dedupe must
        // keep the URL, not overwrite it with null.
        val dir = tmp.newFolder("mirror")
        java.io.File(dir, "my_encoder.onnx").writeBytes(encoderBytes)
        java.io.File(dir, "decoder.onnx").writeBytes(decoderBytes)
        java.io.File(dir, "joiner.onnx").writeBytes(joinerBytes)
        java.io.File(dir, "tokens.txt").writeBytes(tokensBytes)
        val folderRecord = importer.importFromDirectory(dir)
        assertEquals(urlRecord.id, folderRecord.id)
        assertEquals(1, store.records().size)
        assertEquals("$base/entry.json", store.records().single().sourceUrl)
    }
}
