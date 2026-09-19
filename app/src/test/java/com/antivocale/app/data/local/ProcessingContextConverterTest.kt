package com.antivocale.app.data.local

import com.antivocale.app.transcription.ProcessingContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TASK-512: the processing-context column codec and its one-line rendering. */
class ProcessingContextConverterTest {

    private val full = ProcessingContext(
        decodePath = "pipeline",
        totalChunks = 157,
        failedChunks = 3,
        transcribedSeconds = 4620.0,
        chunkCapSeconds = 60,
        availableRamBytes = 5_800_000_000L,
    )

    @Test
    fun `round trip preserves every field`() {
        assertEquals(full, ProcessingContextConverter.fromJson(ProcessingContextConverter.toJson(full)))
    }

    @Test
    fun `render is one line with the observability keys`() {
        assertEquals(
            "pipeline chunks=157 (failed 3) decoded=4620.0s cap=60s ram=5531MB",
            ProcessingContextConverter.render(full))
    }

    @Test
    fun `null blank and corrupt input degrade to null and clean shapes render bare`() {
        assertNull(ProcessingContextConverter.toJson(null))
        assertNull(ProcessingContextConverter.fromJson(null))
        assertNull(ProcessingContextConverter.fromJson("not json"))
        assertEquals(
            "vad_chunked chunks=10",
            ProcessingContextConverter.render(ProcessingContext(decodePath = "vad_chunked", totalChunks = 10)))
    }
}
