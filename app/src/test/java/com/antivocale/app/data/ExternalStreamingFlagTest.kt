package com.antivocale.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * TASK-368: streaming-transducer support in the external platform. The entry-JSON
 * parser is the single choke point for the streaming flag; these tests pin the
 * parse, the default, and the family restriction.
 */
class ExternalStreamingFlagTest {

    private fun entryJson(family: String?, streaming: Boolean?, supplements: String? = null): String {
        val f = family?.let { """"family":"$it",""" } ?: ""
        val s = streaming?.let { """"streaming":$it,""" } ?: ""
        val supp = supplements?.let { """"supplements":$it,""" } ?: ""
        return """{"name":"kroko","modelType":"",""" + f + s + supp + """"languages":["es"],
            "files":[{"name":"encoder.onnx","url":"https://x/e.onnx","sha256":"${"a".repeat(64)}","size":1},
                     {"name":"decoder.onnx","url":"https://x/d.onnx","sha256":"${"b".repeat(64)}","size":1},
                     {"name":"joiner.onnx","url":"https://x/j.onnx","sha256":"${"c".repeat(64)}","size":1},
                     {"name":"tokens.txt","url":"https://x/t.txt","sha256":"${"d".repeat(64)}","size":1}]}"""
    }

    @Test
    fun `streaming flag parses for transducer entries`() {
        val entry = ExternalModelEntryJson.parse(entryJson(family = "TRANSDUCER", streaming = true))
        assertTrue(entry.streaming)
    }

    @Test
    fun `streaming defaults to false when absent`() {
        val entry = ExternalModelEntryJson.parse(entryJson(family = "TRANSDUCER", streaming = null))
        assertFalse(entry.streaming)
    }

    @Test
    fun `streaming is rejected for non-transducer families`() {
        val e = runCatching {
            ExternalModelEntryJson.parse(entryJson(family = "WHISPER", streaming = true))
        }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $e", e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("TRANSDUCER"))
    }

    @Test
    fun `record round-trips the streaming flag`() {
        val record = ExternalModelRecord(
            id = "id", displayName = "n", dir = "/d", family = ModelFamily.TRANSDUCER,
            modelType = "", languages = listOf("es"), source = ExternalModelSource.URL,
            sourceUrl = null, files = emptyMap(), sizeBytes = 1, importedAt = 1,
            streaming = true,
        )
        val json = record.toJson()
        assertTrue(json.getBoolean("streaming"))
        val back = ExternalModelRecord.fromJson(json)!!
        assertTrue(back.streaming)

        // Legacy record without the field still parses (default false)
        val legacy = JSONObject(json.toString().replace("\"streaming\":true,", ""))
        assertFalse(ExternalModelRecord.fromJson(legacy)!!.streaming)
    }

    @Test
    fun `supplements parse with explicit url and sha pin`() {
        val json = entryJson(
            family = "TRANSDUCER", streaming = null,
            supplements = """[{"name":"bpe.vocab","url":"https://mirror/bpe.vocab","sha256":"${"e".repeat(64)}"}]""")
        val entry = ExternalModelEntryJson.parse(json)
        val s = entry.supplements.single()
        assertEquals("bpe.vocab", s.name)
        assertEquals("https://mirror/bpe.vocab", s.url)
        assertEquals("e".repeat(64), s.sha256)
    }

    @Test
    fun `entries without supplements parse to none`() {
        assertTrue(ExternalModelEntryJson.parse(entryJson(family = "TRANSDUCER", streaming = null)).supplements.isEmpty())
    }

    @Test
    fun `a malformed supplement is skipped, not import-blocking`() {
        val json = entryJson(
            family = "TRANSDUCER", streaming = null,
            supplements = """[{"name":"bpe.vocab","url":"https://mirror/bpe.vocab","sha256":"${"e".repeat(64)}"},
                             {"name":"broken"}]""")
        val entry = ExternalModelEntryJson.parse(json)
        // the model imports; only the well-formed supplement rides
        assertEquals(1, entry.supplements.size)
        assertEquals("bpe.vocab", entry.supplements.single().name)
    }
}
