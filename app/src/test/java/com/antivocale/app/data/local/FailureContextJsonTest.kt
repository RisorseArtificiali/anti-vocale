package com.antivocale.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                "v1.13.0-SNAPSHOT chunks=12 (failed 3) total=4620.0s decoded=1380.0s",
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
}
