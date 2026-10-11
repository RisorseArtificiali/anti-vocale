package com.antivocale.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExternalModelStoreTest {

    private lateinit var fake: FakePreferencesManager
    private lateinit var store: ExternalModelStore

    @Before
    fun setUp() {
        fake = FakePreferencesManager()
        store = ExternalModelStore(fake)
    }

    private fun record(id: String = "a1b2c3d4e5f6", name: String = "GigaAM v3") = ExternalModelRecord(
        id = id,
        displayName = name,
        dir = "/data/user/0/com.antivocale.app/files/models/external/gigaam-v3-a1b2c3",
        family = ModelFamily.TRANSDUCER,
        modelType = "nemo_transducer",
        languages = listOf("ru"),
        source = ExternalModelSource.LOCAL,
        sourceUrl = null,
        files = mapOf(
            "encoder.int8.onnx" to FilePin("2cac62d0c270bd128f898f2be1a2d34780d524a6e9483888ebac7b00f97410f1", verified = true),
            "tokens.txt" to FilePin("7ddf22514c42c531358182c81446a8159771e9921019f09ae743ea622d40221d", verified = false),
        ),
        sizeBytes = 326_322_304L,
        importedAt = 1_755_000_000_000L,
    )

    @Test
    fun `empty store lists nothing and json round-trips through the preference`() = runTest {
        assertEquals(emptyList<ExternalModelRecord>(), store.records())
        val rec = record()
        store.add(rec)
        assertEquals(listOf(rec), store.records())
        assertEquals(listOf(rec), ExternalModelStore(fake).records())
    }

    @Test
    fun `update replaces by id and delete removes only the target`() = runTest {
        val a = record(id = "aaaaaaaaaaaa", name = "A")
        val b = record(id = "bbbbbbbbbbbb", name = "B")
        store.add(a); store.add(b)
        store.update(a.copy(displayName = "A2"))
        assertEquals("A2", store.records().first { it.id == "aaaaaaaaaaaa" }.displayName)
        store.delete("aaaaaaaaaaaa")
        assertEquals(listOf("bbbbbbbbbbbb"), store.records().map { it.id })
    }

    @Test
    fun `updateDir is a targeted read-modify-write that preserves concurrent edits`() = runTest {
        val rec = record()
        store.add(rec)
        // An unrelated edit lands first (e.g. the importer rewriting pins on re-import);
        // a whole-record writeback from the dir redirect must not revert it.
        store.update(rec.copy(displayName = "GigaAM v3 (reimported)"))
        store.updateDir(rec.id, "/new/dir")
        val landed = store.records().single()
        assertEquals("GigaAM v3 (reimported)", landed.displayName)
        assertEquals("/new/dir", landed.dir)
    }

    @Test
    fun `validity requires the directory to exist`() = runTest {
        val rec = record()
        store.add(rec)
        val validity = ExternalModelStore(fake) { false }
        // The record is still persisted; it is only filtered out of every valid view.
        assertEquals(listOf(rec.id), validity.records().map { it.id })
        assertEquals(emptyList<ExternalModelRecord>(), validity.validRecords())
        assertNull(validity.byId(rec.id))
    }

    @Test
    fun `validRecordsFlow filters records whose directory is missing`() = runTest {
        val rec = record()
        store.add(rec)
        val filtered = ExternalModelStore(fake) { false }
        assertEquals(emptyList<ExternalModelRecord>(), filtered.validRecordsFlow.first())
        assertEquals(listOf(rec), ExternalModelStore(fake) { true }.validRecordsFlow.first())
    }

    @Test
    fun `corrupt entry is dropped, valid records survive`() = runTest {
        val rec = record()
        val validJson = rec.toJson().toString()
        // Two entries: one valid, one with an unknown family enum. Element-granularity:
        // the corrupt entry is dropped, the valid one survives (whole-list rejection
        // combined with the store's read-modify-write would destroy it on the next write).
        val raw = """[$validJson,{"id":"x","displayName":"bad","dir":"/tmp","family":"CTC"}]"""
        val decoded = ExternalModelListJson.decode(raw)
        assertEquals(listOf(rec), decoded)
    }

    @Test
    fun `legacy record without options decodes with emptyMap`() = runTest {
        val raw = """[{"id":"aaa","displayName":"Old","dir":"/old","family":"TRANSDUCER","modelType":"nemo_transducer","languages":["en"],"source":"LOCAL","sourceUrl":null,"files":{"encoder.onnx":{"sha256":"abc1234567890123456789012345678901234567890123456789012345678901","verified":true}},"sizeBytes":100,"importedAt":1000}]"""
        val decoded = ExternalModelListJson.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(emptyMap<String, String>(), decoded.single().options)
    }

    @Test
    fun `record with explicit null options decodes with emptyMap`() = runTest {
        // Test null-tolerance: "options":null should decode to emptyMap, not throw
        val raw = """[{"id":"bbb","displayName":"Null Options","dir":"/null","family":"TRANSDUCER","modelType":"nemo_transducer","languages":["en"],"source":"LOCAL","sourceUrl":null,"options":null,"files":{"encoder.onnx":{"sha256":"def4567890123456789012345678901234567890123456789012345678901234","verified":true}},"sizeBytes":100,"importedAt":2000}]"""
        val decoded = ExternalModelListJson.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(emptyMap<String, String>(), decoded.single().options)
    }

    @Test
    fun `record with options round-trips encode and decode`() = runTest {
        val rec = record().copy(options = mapOf("whisper.language" to "ar"))
        val json = rec.toJson().toString()
        val decoded = ExternalModelRecord.fromJson(org.json.JSONObject(json))
        assertEquals(mapOf("whisper.language" to "ar"), decoded!!.options)
    }

    @Test
    fun `linked catalog ref round-trips and pre-801 json decodes with no link`() = runTest {
        // TASK-801: the entity link survives the preference round trip...
        val rec = record().copy(linkedCatalog = LinkedCatalogRef("gigaam", "gigaam-v3"))
        val decoded = ExternalModelRecord.fromJson(org.json.JSONObject(rec.toJson().toString()))!!
        assertEquals(LinkedCatalogRef("gigaam", "gigaam-v3"), decoded.linkedCatalog)
        // ...while records persisted before the field existed parse to null.
        val legacy = record().toJson().toString()
        assertNull(ExternalModelRecord.fromJson(org.json.JSONObject(legacy))!!.linkedCatalog)
    }

    @Test
    fun `a variant dir without its entry is dropped at parse`() = runTest {
        // The link is entry+variant or nothing: a half-written field pair must
        // not survive as an ambiguous whole-entry claim.
        val raw = record().toJson().put("linkedCatalogVariantDir", "gigaam-v3").toString()
        assertNull(ExternalModelRecord.fromJson(org.json.JSONObject(raw))!!.linkedCatalog)
    }

    @Test
    fun `typeLabel falls back to the family name when modelType is blank`() {
        // WHISPER/SENSE_VOICE records carry a blank modelType by design; the card
        // must not render them as "zipformer" (bug seen on device, TASK-331).
        val whisper = record().copy(family = ModelFamily.WHISPER, modelType = "", languages = emptyList())
        assertEquals("whisper", whisper.typeLabel)

        val senseVoice = record().copy(family = ModelFamily.SENSE_VOICE, modelType = "")
        assertEquals("sense_voice", senseVoice.typeLabel)

        // Explicit modelTypes always win.
        assertEquals("nemo_transducer", record().typeLabel)
        assertEquals("zipformer_ctc", record().copy(family = ModelFamily.CTC, modelType = "zipformer_ctc").typeLabel)
    }
}
