package com.antivocale.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Local-entry importer tests (plan v2a, Task 7): role-based copy plan, id-fragment
 * directory uniqueness, streaming pins, metadata validation before persisting,
 * missing-role failure, same-hash dedupe. The SAF entry (importFromTreeUri) is a
 * thin DocumentFile port of the same core and is device-verified (Task 12).
 */
class ExternalModelImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fake: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var filesRoot: File
    private lateinit var importer: ExternalModelImporter

    @Before
    fun setUp() {
        fake = FakePreferencesManager()
        store = ExternalModelStore(fake)
        filesRoot = tmp.newFolder("external-root")
        importer = ExternalModelImporter(
            store = store,
            filesRoot = { filesRoot },
            uuid = { "fedcba9876543210" },
        )
    }

    /** Protobuf-framed metadata prop (key + 0x12 tag + varint length + value), as onnxMetadataValue expects. */
    private fun metadataProp(key: String, value: String): ByteArray =
        key.toByteArray() + byteArrayOf(0x12, value.length.toByte()) + value.toByteArray()

    /** Source dir with the four role files; the encoder carries the metadata keys in its tail. */
    private fun sourceDir(name: String = "gigaam-v3"): File {
        val dir = tmp.newFolder(name)
        File(dir, "some_encoder_int8.onnx").writeBytes(
            ByteArray(64) { 1 } + "vocab_size=1024 subsampling_factor=8 ".toByteArray() +
                metadataProp("model_type", "nemo_transducer"))
        File(dir, "some_decoder.onnx").writeBytes(ByteArray(16) { 2 })
        File(dir, "some_joiner.onnx").writeBytes(ByteArray(16) { 3 })
        File(dir, "tokens.txt").writeText("<unk> 0\n. 1\n")
        return dir
    }

    @Test
    fun `local import copies to id-fragment dir, records pins, registers the record`() = runTest {
        val src = sourceDir()
        val record = importer.importFromDirectory(src)

        assertEquals(4, record.files.size)
        assertTrue(record.files.values.all { it.verified })
        assertTrue(File(record.dir).isDirectory)
        assertEquals(4, File(record.dir).listFiles()!!.size)
        assertTrue("dir must embed the id fragment", record.dir.endsWith(record.id.take(6)))
        assertEquals(listOf(record), store.records())
    }

    @Test
    fun `buildCopyPlan maps roles by keyword and rejects missing roles`() {
        val plan = importer.buildCopyPlan(listOf("gigaam_encoder_int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"))
        assertNotNull(plan)
        assertEquals(4, plan!!.size)
        assertEquals("gigaam_encoder_int8.onnx", plan["encoder.int8.onnx"])
        assertEquals("decoder.onnx", plan["decoder.int8.onnx"])
        assertEquals("joiner.onnx", plan["joiner.int8.onnx"])
        assertEquals("tokens.txt", plan["tokens.txt"])

        assertEquals(null, importer.buildCopyPlan(listOf("tokens.txt")))
        assertEquals(null, importer.buildCopyPlan(emptyList()))
    }

    @Test
    fun `buildCopyPlan accepts gigaam joint naming and vocab tokens`() {
        // The real GigaAM v3 mirror: joint.onnx (not joiner) and tokens.txt.
        val gigaam = importer.buildCopyPlan(listOf(
            "gigaam_v3_e2e_rnnt_encoder_int8.onnx",
            "gigaam_v3_e2e_rnnt_decoder.onnx",
            "gigaam_v3_e2e_rnnt_joint.onnx",
            "gigaam_v3_e2e_rnnt_tokens.txt",
        ))
        assertEquals("gigaam_v3_e2e_rnnt_joint.onnx", gigaam!!["joiner.int8.onnx"])

        // istupakov's export naming: vocab.txt as the tokens file.
        val istupakov = importer.buildCopyPlan(listOf(
            "v3_e2e_rnnt_encoder.int8.onnx",
            "v3_e2e_rnnt_decoder.int8.onnx",
            "v3_e2e_rnnt_joint.int8.onnx",
            "v3_e2e_rnnt_vocab.txt",
        ))
        assertEquals("v3_e2e_rnnt_vocab.txt", istupakov!!["tokens.txt"])
    }

    @Test
    fun `missing role fails with a clean error and registers nothing`() = runTest {
        val src = tmp.newFolder("incomplete")
        File(src, "tokens.txt").writeText("x")

        val result = runCatching { importer.importFromDirectory(src) }

        assertTrue(result.isFailure)
        assertEquals(0, store.records().size)
        assertEquals(0, filesRoot.listFiles()!!.size)
    }

    @Test
    fun `missing transducer metadata error carries the export guidance`() = runTest {
        val src = tmp.newFolder("nometa")
        File(src, "encoder.onnx").writeBytes(ByteArray(32) { 7 })
        File(src, "decoder.onnx").writeBytes(ByteArray(8) { 2 })
        File(src, "joiner.onnx").writeBytes(ByteArray(8) { 3 })
        File(src, "tokens.txt").writeText("x")

        val result = runCatching { importer.importFromDirectory(src) }

        assertTrue(result.isFailure)
        assertTrue(
            "import-time error names the known-good sources (TASK-481): ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message!!.contains("Parakeet"))
    }

    @Test
    fun `implausible vocab_size fails import with the guidance`() = runTest {
        // TASK-481: the hand-patched-encoder class. Key present, value junk.
        val dir = tmp.newFolder("fakevocab")
        File(dir, "encoder_a.onnx").writeBytes(
            ByteArray(64) { 1 } +
                metadataProp("vocab_size", "0") +
                metadataProp("subsampling_factor", "8") +
                metadataProp("model_type", "nemo_transducer"))
        File(dir, "decoder_b.onnx").writeBytes(ByteArray(16) { 2 })
        File(dir, "joiner_c.onnx").writeBytes(ByteArray(16) { 3 })
        File(dir, "tokens.txt").writeText("<unk> 0\n. 1\n")

        val result = runCatching { importer.importFromDirectory(dir) }

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("names the fake value: $msg", msg.contains("vocab_size"))
        assertTrue("carries the cure: $msg", msg.contains("Parakeet"))
        assertEquals(0, store.records().size)
    }

    @Test
    fun `encoder without metadata fails import cleanly`() = runTest {
        val src = tmp.newFolder("badmeta")
        File(src, "encoder.onnx").writeBytes(ByteArray(32) { 7 })
        File(src, "decoder.onnx").writeBytes(ByteArray(8) { 2 })
        File(src, "joiner.onnx").writeBytes(ByteArray(8) { 3 })
        File(src, "tokens.txt").writeText("x")

        val result = runCatching { importer.importFromDirectory(src) }

        assertTrue(result.isFailure)
        assertEquals(0, store.records().size)
    }

    @Test
    fun `same-hash reimport offers no second directory`() = runTest {
        val src = sourceDir()
        val first = importer.importFromDirectory(src)
        val second = importer.importFromDirectory(src)
        assertEquals(first.id, second.id)
        assertEquals(1, store.records().size)
        assertEquals(1, filesRoot.listFiles()!!.size)
    }

    // ---- family-aware imports (TASK-331) ----

    /** Whisper source dir: encoder + decoder + tokens; the encoder tail names its model_type. */
    private fun whisperSourceDir(name: String = "whisper-tiny"): File {
        val dir = tmp.newFolder(name)
        File(dir, "whisper_encoder.onnx").writeBytes(
            ByteArray(32) { 1 } + metadataProp("model_type", "whisper-tiny"))
        File(dir, "whisper_decoder.onnx").writeBytes(ByteArray(16) { 2 })
        File(dir, "tokens.txt").writeText("<unk> 0\n")
        return dir
    }

    /** SenseVoice source dir: the single acoustic model plus tokens. */
    private fun senseVoiceSourceDir(name: String = "sense-voice"): File {
        val dir = tmp.newFolder(name)
        File(dir, "model.int8.onnx").writeBytes(ByteArray(32) { 5 })
        File(dir, "tokens.txt").writeText("<unk> 0\n")
        return dir
    }

    @Test
    fun `whisper family import records family, canonical pins, and empty modelType`() = runTest {
        val record = importer.importFromDirectory(whisperSourceDir(), modelType = "", family = ModelFamily.WHISPER)

        assertEquals(ModelFamily.WHISPER, record.family)
        assertEquals("", record.modelType)
        assertEquals(setOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"), record.files.keys)
        assertTrue(File(record.dir, "encoder.int8.onnx").exists())
        assertEquals(3, File(record.dir).listFiles()!!.size)
    }

    @Test
    fun `whisper file set imported as TRANSDUCER fails naming transducer expectations`() = runTest {
        val result = runCatching { importer.importFromDirectory(whisperSourceDir()) }

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message ?: ""
        assertTrue("error must name the family: $message", message.contains("TRANSDUCER"))
        assertEquals(0, store.records().size)
        assertEquals(0, filesRoot.listFiles()!!.size)
    }

    @Test
    fun `transducer set imported as WHISPER is rejected by the metadata value check`() = runTest {
        // The generic-name hole: the encoder carries a model_type KEY (key-presence
        // passes) but its VALUE is nemo_transducer, so only the value-aware
        // validateImportedModel call catches it.
        val result = runCatching { importer.importFromDirectory(sourceDir(), modelType = "", family = ModelFamily.WHISPER) }

        assertTrue(result.isFailure)
        assertTrue(
            "error must point at the transducer family: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("TRANSDUCER") == true)
        assertEquals(0, store.records().size)
    }

    @Test
    fun `sense voice family import records the single-model role set`() = runTest {
        val record = importer.importFromDirectory(senseVoiceSourceDir(), modelType = "", family = ModelFamily.SENSE_VOICE)

        assertEquals(ModelFamily.SENSE_VOICE, record.family)
        assertEquals(setOf("model.int8.onnx", "tokens.txt"), record.files.keys)
        assertTrue(File(record.dir, "model.int8.onnx").exists())
    }

    @Test
    fun `sense voice import of a split model set fails naming sense voice expectations`() = runTest {
        // An encoder/decoder set has no model-role candidate: the error must name
        // SENSE_VOICE (metadataFileRole dispatch means validation happens on the
        // model file, which no plan can produce here).
        val result = runCatching {
            importer.importFromDirectory(sourceDir(), modelType = "", family = ModelFamily.SENSE_VOICE)
        }

        assertTrue(result.isFailure)
        assertTrue(
            "error must name the family: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("SENSE_VOICE") == true)
        assertEquals(0, store.records().size)
    }

    // ---- ONNX split-file sidecars (TASK-331) ----

    @Test
    fun `onnx sidecar is planned as an extra entry under its source base name`() = runTest {
        val dir = tmp.newFolder("whisper-split")
        File(dir, "whisper_encoder.int8.onnx").writeBytes(ByteArray(32) { 1 } + metadataProp("model_type", "whisper-tiny"))
        File(dir, "whisper_encoder.int8.onnx.data").writeBytes(ByteArray(24) { 9 })
        File(dir, "whisper_decoder.onnx").writeBytes(ByteArray(16) { 2 })
        File(dir, "tokens.txt").writeText("<unk> 0\n")

        val record = importer.importFromDirectory(dir, modelType = "", family = ModelFamily.WHISPER)

        // Copied and pinned, keeping its SOURCE base name (sherpa resolves external
        // data by co-location with the referenced file name), never renamed to a role.
        assertTrue(File(record.dir, "whisper_encoder.int8.onnx.data").exists())
        assertTrue(record.files.containsKey("whisper_encoder.int8.onnx.data"))
        assertEquals(4, File(record.dir).listFiles()!!.size)
        // The sidecar is NOT a role: the required role set is unchanged.
        assertEquals(setOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"),
            record.files.keys - "whisper_encoder.int8.onnx.data")
    }

    /**
     * A sparse length that exceeds the volume's usable space (so the disk pre-flight
     * must trip) while staying under any filesystem's max file size: CI runners
     * rejected setLength(Long.MAX_VALUE / 2) with IOException (beyond s_maxbytes),
     * which broke the unit-test job and, downstream, the release signing chain.
     */
    private fun sparseLengthBiggerThanFreeSpace(anchor: File): Long =
        anchor.usableSpace + (1L shl 30)

    /**
     * Creates a sparse file of [length] bytes. Java's RandomAccessFile.setLength does
     * not create sparse files on Windows (it zero-fills, so a length beyond the free
     * space throws IOException); there the pre-flight tests are skipped.
     */
    private fun createSparse(file: File, length: Long) {
        try {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(length) }
        } catch (e: java.io.IOException) {
            org.junit.Assume.assumeTrue("sparse files unsupported on this platform", false)
        }
    }

    @Test
    fun `disk pre-flight totals include the sidecar size`() = runTest {
        val smallRoot = tmp.newFolder("tiny-root-sidecar")
        val tightImporter = ExternalModelImporter(store, filesRoot = { smallRoot }, uuid = { "0123456789abcdef" })
        val src = sourceDir("huge-sidecar")
        // A sparse sidecar whose length alone exceeds the free space: the
        // pre-flight must count it, otherwise the import would proceed and then
        // explode while copying gigabytes that were never accounted for.
        createSparse(File(src, "some_encoder_int8.onnx.data"), sparseLengthBiggerThanFreeSpace(src))

        val result = runCatching { tightImporter.importFromDirectory(src) }

        assertTrue(result.isFailure)
        assertTrue(
            "error must be the disk pre-flight: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("disk space") == true)
        assertEquals(0, store.records().size)
    }

    @Test
    fun `same-hash reimport refreshes family, options and languages`() = runTest {
        val src = whisperSourceDir()
        val first = importer.importFromDirectory(
            src, modelType = "", family = ModelFamily.WHISPER,
            options = mapOf("whisper.language" to "en"), languages = listOf("en"))

        val second = importer.importFromDirectory(
            src, modelType = "", family = ModelFamily.WHISPER,
            options = mapOf("whisper.language" to "ar", "whisper.task" to "translate"),
            languages = listOf("ar"))

        assertEquals(first.id, second.id)
        assertEquals(first.dir, second.dir)
        assertEquals(1, store.records().size)
        assertEquals(ModelFamily.WHISPER, second.family)
        assertEquals(mapOf("whisper.language" to "ar", "whisper.task" to "translate"), second.options)
        assertEquals(listOf("ar"), second.languages)
    }

    @Test
    fun `modelType default is family-aware`() = runTest {
        // No explicit modelType: WHISPER/SENSE_VOICE persist "" (never a transducer
        // modelType on a non-transducer record), TRANSDUCER keeps "nemo_transducer".
        val whisper = importer.importFromDirectory(whisperSourceDir(), family = ModelFamily.WHISPER)
        assertEquals("", whisper.modelType)
        assertEquals(ModelFamily.WHISPER, whisper.family)

        val senseVoice = importer.importFromDirectory(senseVoiceSourceDir(), family = ModelFamily.SENSE_VOICE)
        assertEquals("", senseVoice.modelType)

        val transducer = importer.importFromDirectory(sourceDir())
        assertEquals("nemo_transducer", transducer.modelType)
    }

    @Test
    fun `family CTC without an explicit modelType is rejected`() = runTest {
        val dir = tmp.newFolder("ctc")
        File(dir, "v3_ctc.int8.onnx").writeBytes(ByteArray(16) { 4 })
        File(dir, "tokens.txt").writeText("<unk> 0\n")

        val result = runCatching {
            importer.importFromDirectory(dir, family = ModelFamily.CTC)
        }

        assertTrue(result.isFailure)
        assertTrue(
            "error must mirror the entry-JSON rule: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("CTC family requires an explicit modelType") == true)
        assertEquals(0, store.records().size)
    }

    @Test
    fun `disk pre-flight blocks imports larger than available space`() = runTest {
        val smallRoot = tmp.newFolder("tiny-root")
        val tightImporter = ExternalModelImporter(store, filesRoot = { smallRoot }, uuid = { "0123456789abcdef" })
        // TemporaryFolder cannot shrink usableSpace; instead verify the guard fires by
        // faking a huge source: a sparse file whose length exceeds the free space heuristic.
        val src = tmp.newFolder("huge")
        val huge = File(src, "encoder.onnx")
        // Length only, no allocation: the pre-flight reads lengths, RandomAccessFile sets them sparsely.
        createSparse(huge, sparseLengthBiggerThanFreeSpace(src))
        File(src, "decoder.onnx").writeBytes(ByteArray(4))
        File(src, "joiner.onnx").writeBytes(ByteArray(4))
        File(src, "tokens.txt").writeText("x")

        val result = runCatching { tightImporter.importFromDirectory(src) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `local import missing the onnx sidecar its encoder references fails loudly`() = runTest {
        // Split encoder whose protobuf references an external-data sidecar that is
        // NOT in the folder: the import must fail here naming the sidecar, not at
        // engine load time.
        val dir = tmp.newFolder("split-missing-sidecar")
        File(dir, "some_encoder_int8.onnx").writeBytes(
            ByteArray(64) { 1 } + "vocab_size=1024 subsampling_factor=8 ".toByteArray() +
                metadataProp("model_type", "nemo_transducer") +
                "some_encoder_int8.onnx.data".toByteArray())
        File(dir, "some_decoder.onnx").writeBytes(ByteArray(16) { 2 })
        File(dir, "some_joiner.onnx").writeBytes(ByteArray(16) { 3 })
        File(dir, "tokens.txt").writeText("<unk> 0\n. 1\n")

        val result = runCatching { importer.importFromDirectory(dir) }

        assertTrue(result.isFailure)
        assertTrue(
            "error must name the missing sidecar: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("some_encoder_int8.onnx.data") == true)
        assertEquals(0, store.records().size)
    }

    @Test
    fun `url import of a stray non-HF non-json url fails naming the accepted forms`() = runTest {
        val result = runCatching {
            importer.importFromUrl("https://example.com/downloads/model.zip")
        }

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message
        assertTrue("error must name the url: $message", message?.contains("https://example.com/downloads/model.zip") == true)
        assertTrue("error must name the HF repo form: $message", message?.contains("HuggingFace repository") == true)
        assertTrue("error must name the entry JSON form: $message", message?.contains(".json") == true)
        assertEquals(0, store.records().size)
    }
}
