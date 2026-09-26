package com.antivocale.app.data.local

import com.antivocale.app.transcription.ProcessingContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK-512: the processing-context column codec and its one-line rendering. */
class ProcessingContextConverterTest {

    private val full = ProcessingContext(
        decodePath = "pipeline",
        totalChunks = 157,
        failedChunks = 3,
        blankChunks = 2,
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
        // TASK-622: blank chunks render beside failed ones (the two mean
        // opposite things and the pair is the diagnosis).
        assertEquals(
            "pipeline chunks=157 (failed 3) (blank 2) decoded=4620.0s cap=60s ram=5531MB vad=on",
            ProcessingContextConverter.render(full))
        assertEquals(
            "pipeline chunks=157 (failed 3) decoded=4620.0s cap=60s ram=5531MB vad=on",
            ProcessingContextConverter.render(full.copy(blankChunks = 0)))
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
        // F8: the common metrics-free skip render stays asserted too.
        assertEquals(
            "pipeline refined=nemotron-streaming skip=fast_blank",
            ProcessingContextConverter.render(dual.copy(refinementSkipReason = "fast_blank")))
        // TASK-582: the measured loop values survive the JSON round trip
        // (a key mismatch would silently drop them from every row).
        val looped = dual.copy(
            refinementSkipReason = "fast_loop_detected",
            refinementLoopMetrics = "compression=2.6100 ngram=0.4200")
        assertEquals(
            looped,
            ProcessingContextConverter.fromJson(ProcessingContextConverter.toJson(looped)))
        assertEquals(
            "pipeline refined=nemotron-streaming",
            ProcessingContextConverter.render(dual))

        val skipped = ProcessingContext(
            decodePath = "whole_file",
            backendId = "parakeet",
            refinementSkipReason = "fast_loop_detected",
            refinementLoopMetrics = "compression=2.6100 ngram=0.4200",
        )
        assertEquals(
            "whole_file skip=fast_loop_detected loop=compression=2.6100 ngram=0.4200",
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

    /**
     * TASK-677 (GH #92): the History card derives the subtitle-sourced label
     * from a raw substring check, so this pins the fragments to [toJson]'s
     * actual compact output: if the JSON shape drifts, this fails before any
     * row silently loses its honest labeling.
     */
    @Test
    fun `subtitle-sourced markers are detected on real toJson output`() {
        val imported = ProcessingContext(decodePath = ProcessingContext.DECODE_PATH_SUBTITLE_IMPORT)
        val seeded = ProcessingContext(decodePath = ProcessingContext.DECODE_PATH_SUBTITLE_TRACK)
        assertTrue(ProcessingContextConverter.isSubtitleSourced(ProcessingContextConverter.toJson(imported)))
        assertTrue(ProcessingContextConverter.isSubtitleSourced(ProcessingContextConverter.toJson(seeded)))

        // Every non-subtitle context, including ones that merely MENTION the
        // word elsewhere, stays undetected; null (pre-v10 rows) too.
        assertFalse(ProcessingContextConverter.isSubtitleSourced(ProcessingContextConverter.toJson(full)))
        assertFalse(ProcessingContextConverter.isSubtitleSourced(
            ProcessingContextConverter.toJson(full.copy(backendId = "subtitle_importer"))))
        assertFalse(ProcessingContextConverter.isSubtitleSourced(null))
    }
}
