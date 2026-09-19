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
        vadRequested = true,
    )

    @Test
    fun `round trip preserves every field`() {
        assertEquals(full, ProcessingContextConverter.fromJson(ProcessingContextConverter.toJson(full)))
    }

    @Test
    fun `render is one line with the observability keys`() {
        assertEquals(
            "pipeline chunks=157 (failed 3) decoded=4620.0s cap=60s ram=5531MB vad=on",
            ProcessingContextConverter.render(full))
    }

    @Test
    fun `dual-model fields round-trip and render on the metadata line`() {
        // GH #43 slice 1: the row's context is phase 2's; the fast pass
        // nests with its own backend id; the skip token rides along.
        val dual = ProcessingContext(
            decodePath = "pipeline",
            backendId = "parakeet",
            refinementPhase = ProcessingContext(
                decodePath = "whole_file", backendId = "nemotron-streaming"),
            refinementSkipReason = null,
        )
        assertEquals(dual, ProcessingContextConverter.fromJson(ProcessingContextConverter.toJson(dual)))
        assertEquals(
            "pipeline refined=nemotron-streaming",
            ProcessingContextConverter.render(dual))

        val skipped = ProcessingContext(
            decodePath = "whole_file",
            backendId = "parakeet",
            refinementSkipReason = "fast_load_failed",
        )
        assertEquals(
            "whole_file skip=fast_load_failed",
            ProcessingContextConverter.render(skipped))
        // Old JSON (pre-dual) parses with the new fields null.
        assertEquals(
            ProcessingContext(decodePath = "pipeline", totalChunks = 157, failedChunks = 3,
                transcribedSeconds = 4620.0, chunkCapSeconds = 60,
                availableRamBytes = 5802934272L, vadRequested = true),
            ProcessingContextConverter.fromJson(
                """{"decodePath":"pipeline","totalChunks":157,"failedChunks":3,"transcribedSeconds":4620.0,"chunkCapSeconds":60,"availableRamBytes":5802934272,"vadRequested":true}"""))
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
