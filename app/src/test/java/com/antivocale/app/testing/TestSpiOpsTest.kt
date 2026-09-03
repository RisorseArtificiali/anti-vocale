package com.antivocale.app.testing

import com.antivocale.app.data.ExternalModelListJson
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.FilePin
import com.antivocale.app.data.ModelFamily
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.optStringList
import com.antivocale.app.transcription.BuiltInBackendIds
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests the pure op handling of the debug SPI (TASK-409) against the shared
 * fakes; the thin Hilt receiver (debug source set) is not unit-testable
 * without the DI graph, which is why the ops live in [TestSpiOps].
 */
class TestSpiOpsTest {

    private lateinit var fake: FakePreferencesManager
    private lateinit var ops: TestSpiOps

    @Before
    fun setUp() {
        fake = FakePreferencesManager()
        ops = TestSpiOps(fake, ExternalModelStore(fake))
    }

    private fun record(id: String = "a1b2c3d4e5f6") = ExternalModelRecord(
        id = id,
        displayName = "GigaAM v3",
        dir = "/data/user/0/com.antivocale.app/files/models/external/gigaam-v3-$id",
        family = ModelFamily.TRANSDUCER,
        modelType = "nemo_transducer",
        languages = listOf("ru"),
        source = ExternalModelSource.LOCAL,
        sourceUrl = null,
        files = mapOf(
            "encoder.int8.onnx" to FilePin("aa", verified = true),
            "tokens.txt" to FilePin("bb", verified = false),
        ),
        sizeBytes = 326_322_304L,
        importedAt = 1_755_000_000_000L,
    )

    @Test
    fun `get returns the full state shape with per-backend saved paths`() = runTest {
        fake._vadEnabled.value = true
        fake._threadCount.value = 6
        fake._inferenceProvider.value = "cpu"
        fake._transcriptionLanguage.value = "it"
        fake._transcriptionBackend.value = BuiltInBackendIds.WHISPER
        fake._sherpaModelPath(BuiltInBackendIds.WHISPER).value = "/m/whisper"

        val raw = ops.handle(TestSpiOps.OP_GET)
        assertFalse("responses must stay single-line for logcat grep", raw.contains('\n'))
        val json = JSONObject(raw)
        assertTrue(json.getBoolean("vadEnabled"))
        assertEquals(6, json.getInt("threadCount"))
        assertEquals("cpu", json.getString("inferenceProvider"))
        assertEquals("it", json.getString("transcriptionLanguage"))
        assertEquals(BuiltInBackendIds.WHISPER, json.getString("transcriptionBackend"))
        assertEquals("/m/whisper", json.getString("activeModelPath"))
        val paths = json.getJSONObject("paths")
        assertEquals("/m/whisper", paths.getString(BuiltInBackendIds.WHISPER))
        for (id in BuiltInBackendIds.ALL + "llm") {
            assertTrue("every static backend appears in paths", paths.has(id))
        }
        assertTrue("unset paths serialize as null", paths.isNull(BuiltInBackendIds.PARAKEET))
    }

    @Test
    fun `get resolves the llm backend to the generic model path`() = runTest {
        fake._transcriptionBackend.value = "llm"
        fake._modelPath.value = "/m/gemma.taskml"

        val json = JSONObject(ops.handle(TestSpiOps.OP_GET))
        assertEquals("/m/gemma.taskml", json.getString("activeModelPath"))
        assertEquals("/m/gemma.taskml", json.getJSONObject("paths").getString("llm"))
    }

    @Test
    fun `get resolves an external backend to the record dir`() = runTest {
        val rec = record()
        fake._externalModelsJson.value = ExternalModelListJson.encode(listOf(rec))
        fake._transcriptionBackend.value = rec.backendId

        val json = JSONObject(ops.handle(TestSpiOps.OP_GET))
        assertEquals(rec.dir, json.getString("activeModelPath"))
    }

    @Test
    fun `set vad writes through and parses booleans strictly`() = runTest {
        val json = JSONObject(ops.handle(TestSpiOps.OP_SET, key = "vad", value = "true"))
        assertEquals(TestSpiOps.OP_SET, json.getString("op"))
        assertEquals("vad", json.getString("key"))
        assertTrue(fake._vadEnabled.value)

        assertTrue(
            "non-boolean value is rejected, not silently coerced",
            JSONObject(ops.handle(TestSpiOps.OP_SET, key = "vad", value = "yes"))
                .getString("error").contains("vad"))
    }

    @Test
    fun `set threads writes through and parses integers`() = runTest {
        JSONObject(ops.handle(TestSpiOps.OP_SET, key = "threads", value = "4"))
        assertEquals(4, fake._threadCount.value)

        assertTrue(
            JSONObject(ops.handle(TestSpiOps.OP_SET, key = "threads", value = "four"))
                .getString("error").contains("threads"))
    }

    @Test
    fun `set provider writes through and rejects values the app would silently map to cpu`() = runTest {
        ops.handle(TestSpiOps.OP_SET, key = "provider", value = "nnapi")
        assertEquals("nnapi", fake._inferenceProvider.value)

        val error = JSONObject(ops.handle(TestSpiOps.OP_SET, key = "provider", value = "gpu"))
            .getString("error")
        assertTrue(error.contains("gpu"))
        assertEquals("nnapi", fake._inferenceProvider.value)
    }

    @Test
    fun `set backend accepts catalog llm and external ids`() = runTest {
        ops.handle(TestSpiOps.OP_SET, key = "backend", value = BuiltInBackendIds.QWEN3_ASR)
        assertEquals(BuiltInBackendIds.QWEN3_ASR, fake._transcriptionBackend.value)

        ops.handle(TestSpiOps.OP_SET, key = "backend", value = "llm")
        assertEquals("llm", fake._transcriptionBackend.value)

        ops.handle(TestSpiOps.OP_SET, key = "backend", value = "external:a1b2c3d4e5f6")
        assertEquals("external:a1b2c3d4e5f6", fake._transcriptionBackend.value)
    }

    @Test
    fun `set backend rejects unknown ids without writing`() = runTest {
        val error = JSONObject(ops.handle(TestSpiOps.OP_SET, key = "backend", value = "no-such-backend"))
            .getString("error")
        assertTrue(error.contains("no-such-backend"))
        assertEquals(
            "a rejected backend must not overwrite the saved preference",
            PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND,
            fake._transcriptionBackend.value,
        )
    }

    @Test
    fun `set language writes through`() = runTest {
        ops.handle(TestSpiOps.OP_SET, key = "language", value = "de")
        assertEquals("de", fake._transcriptionLanguage.value)
    }

    @Test
    fun `set model_path writes the generic preference`() = runTest {
        ops.handle(TestSpiOps.OP_SET, key = "model_path", value = "/m/gemma.taskml")
        assertEquals("/m/gemma.taskml", fake._modelPath.value)
    }

    @Test
    fun `set sherpa_path writes the keyed preference for the entry id`() = runTest {
        val json = JSONObject(
            ops.handle(TestSpiOps.OP_SET, key = "sherpa_path", value = "/m/parakeet", entry = BuiltInBackendIds.PARAKEET))
        assertEquals("sherpa_path", json.getString("key"))
        assertEquals(BuiltInBackendIds.PARAKEET, json.getString("entry"))
        assertEquals("/m/parakeet", fake._sherpaModelPath(BuiltInBackendIds.PARAKEET).value)
    }

    @Test
    fun `set sherpa_path requires a valid catalog entry`() = runTest {
        val missing = JSONObject(ops.handle(TestSpiOps.OP_SET, key = "sherpa_path", value = "/m/x"))
            .getString("error")
        assertTrue(missing.contains("entry"))

        val unknown = JSONObject(
            ops.handle(TestSpiOps.OP_SET, key = "sherpa_path", value = "/m/x", entry = "not-a-catalog-id"))
            .getString("error")
        assertTrue(unknown.contains("not-a-catalog-id"))
    }

    @Test
    fun `set with an unknown key returns an error listing the supported keys`() = runTest {
        val json = JSONObject(ops.handle(TestSpiOps.OP_SET, key = "nope", value = "1"))
        assertTrue(json.getString("error").contains("nope"))
        assertEquals(TestSpiOps.SET_KEYS, json.getJSONArray("supportedKeys").optStringList())
    }

    @Test
    fun `set without key or value returns errors`() = runTest {
        assertTrue(ops.handle(TestSpiOps.OP_SET).contains("missing key"))
        assertTrue(ops.handle(TestSpiOps.OP_SET, key = "vad").contains("missing value"))
    }

    @Test
    fun `records lists every record with its derived backend id`() = runTest {
        val rec = record()
        fake._externalModelsJson.value = ExternalModelListJson.encode(listOf(rec))

        val json = JSONObject(ops.handle(TestSpiOps.OP_RECORDS))
        assertEquals(1, json.getInt("count"))
        val first = json.getJSONArray("records").getJSONObject(0)
        assertEquals(rec.id, first.getString("id"))
        assertEquals(rec.displayName, first.getString("displayName"))
        assertEquals(rec.family.name, first.getString("family"))
        assertEquals(rec.dir, first.getString("dir"))
        assertEquals(rec.backendId, first.getString("backendId"))
        assertEquals(rec.languages, first.getJSONArray("languages").optStringList())
    }

    @Test
    fun `help lists ops set keys and the PROCESS_REQUEST pointer`() = runTest {
        val json = JSONObject(ops.handle(TestSpiOps.OP_HELP))
        // help is a known op: it must NOT carry the unknown-op error (device
        // verification 2026-09-03 caught the dispatch bug this pins).
        assertFalse(json.has("error"))
        assertEquals(listOf("get", "set", "records", "help"), json.getJSONArray("ops").optStringList())
        assertEquals(TestSpiOps.SET_KEYS, json.getJSONArray("setKeys").optStringList())
        assertTrue(json.getString("usage").contains("com.antivocale.app.TEST_SPI"))
        assertTrue(json.getString("transcription").contains("com.antivocale.app.PROCESS_REQUEST"))
    }

    @Test
    fun `missing op returns help and unknown op returns help with an error`() = runTest {
        assertFalse(ops.handle(null).contains("error"))
        assertTrue(ops.handle("bogus").contains("unknown op 'bogus'"))
    }
}
