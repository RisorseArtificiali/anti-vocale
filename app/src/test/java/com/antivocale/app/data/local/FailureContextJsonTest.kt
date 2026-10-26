package com.antivocale.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK-570: the failure-diagnostics column codec and its one-line rendering. */
class FailureContextJsonTest {

    private val full = FailureContext(
        errorClass = "PipelineFailure",
        backendId = "external:abc123",
        provider = "cpu",
        appVersion = "1.13.0-SNAPSHOT",
        processedChunks = 12,
        failedChunks = 3,
        // TASK-664: the ladder count must survive the column round trip.
        retriedChunks = 2,
        metadataSeconds = 4620.0,
        decodedSeconds = 1380.0,
    )

    @Test
    fun `round trip preserves every field`() {
        val back = FailureContextJson.fromJson(FailureContextJson.toJson(full))
        assertEquals(full, back)
    }

    @Test
    fun `render is one line with the diagnostic keys`() {
        val rendered = FailureContextJson.render(full)!!
        assertEquals(
            "PipelineFailure backend=external:abc123 provider=cpu " +
                "v1.13.0-SNAPSHOT chunks=12 (failed 3) retried=2 total=4620.0s decoded=1380.0s",
            rendered)
    }

    @Test
    fun `null and blank and corrupt input degrade to null`() {
        assertNull(FailureContextJson.toJson(null))
        assertNull(FailureContextJson.fromJson(null))
        assertNull(FailureContextJson.fromJson(""))
        assertNull(FailureContextJson.fromJson("not json"))
        assertNull(FailureContextJson.render(null))
    }

    @Test
    fun `unknown future keys and absent optionals are tolerated`() {
        val json = """{"errorClass":"OutOfMemoryError","backendId":"whisper","futureKey":1}"""
        val back = FailureContextJson.fromJson(json)!!
        assertEquals("OutOfMemoryError", back.errorClass)
        assertEquals("whisper", back.backendId)
        assertNull(back.provider)
        assertNull(back.processedChunks)
        // Absent optionals do not appear in the rendering.
        assertEquals("OutOfMemoryError backend=whisper", FailureContextJson.render(back))
    }

    @Test
    fun `suspension context round trips and renders the lower bound`() {
        // TASK-684: the freezer-suspension row's typed marker.
        val ctx = FailureContext(errorClass = "SystemSuspended", suspendedMs = 300_000L)
        val back = FailureContextJson.fromJson(FailureContextJson.toJson(ctx))!!
        assertEquals(ctx, back)
        assertEquals("SystemSuspended suspended>=300s", FailureContextJson.render(back))
    }

    @Test
    fun `old rows without suspendedMs read as null`() {
        val json = """{"errorClass":"PipelineFailure"}"""
        assertNull(FailureContextJson.fromJson(json)!!.suspendedMs)
    }

    @Test
    fun `sibling-heal flag round trips and renders the marker`() {
        // TASK-904: the switched-variant heal is distinguishable from a
        // re-download heal in the report line, and the JSON stays absent
        // for ordinary failures (old rows and zero-flag runs unchanged).
        val ctx = FailureContext(errorClass = "CorruptModelFiles", siblingHealed = true)
        val raw = FailureContextJson.toJson(ctx)!!
        assertTrue(raw.contains("\"siblingHealed\":true"))
        val back = FailureContextJson.fromJson(raw)!!
        assertEquals(ctx, back)
        assertEquals("CorruptModelFiles sibling", FailureContextJson.render(back))

        // The default (false) leaves the JSON untouched, exactly like the
        // pre-TASK-904 shape, and the plain render carries NO marker token
        // (the History view parses the line positionally).
        val plainBack = FailureContextJson.fromJson(
            FailureContextJson.toJson(FailureContext(errorClass = "CorruptModelFiles")))!!
        assertFalse(plainBack.siblingHealed)
        assertEquals("CorruptModelFiles", FailureContextJson.render(plainBack))

        // The missing verdict's sibling row renders the same marker.
        val missing = FailureContext(errorClass = "MissingModelFiles", siblingHealed = true)
        assertEquals("MissingModelFiles sibling",
            FailureContextJson.render(FailureContextJson.fromJson(
                FailureContextJson.toJson(missing))!!))
    }
}
