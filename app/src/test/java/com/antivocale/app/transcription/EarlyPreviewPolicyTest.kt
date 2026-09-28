package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TASK-186: the early-preview gate is pure data in, seconds out. Every
 * null-returning guard fires BEFORE the window is derived, so no guard can
 * accidentally ship a preview the design excludes: a disabled preference
 * never previews; a header that does not promise a second chunk never
 * previews (single-chunk files and unknown counts alike: only the header
 * can prove more audio follows); a chunk 0 that is not full cap-sized
 * never previews (this guard excludes VAD-sized chunks, whole-file runs,
 * and short files, which all arrive as a chunk 0 shorter than the cap);
 * everything else previews min(10, cap/2) seconds.
 */
class EarlyPreviewPolicyTest {

    private val rate = 16_000

    private fun preview(
        enabled: Boolean = true,
        capSeconds: Int = 30,
        expectedChunkCount: Int = 4,
        chunk0Samples: Int = rate * capSeconds,
    ): Int? = EarlyPreviewPolicy.previewSeconds(
        enabled = enabled,
        pipelineChunkSeconds = capSeconds,
        expectedChunkCount = expectedChunkCount,
        chunk0Samples = chunk0Samples,
        chunk0SampleRate = rate,
    )

    @Test
    fun `disabled never previews`() {
        assertEquals(null, preview(enabled = false))
    }

    @Test
    fun `single-chunk file never previews`() {
        assertEquals(null, preview(expectedChunkCount = 1, chunk0Samples = rate * 10))
    }

    @Test
    fun `chunk shorter than the cap never previews`() {
        // A 12s first chunk under a 30s cap: short file or whole-file run.
        assertEquals(null, preview(chunk0Samples = rate * 12))
    }

    @Test
    fun `just under the cap but outside the resampling epsilon never previews`() {
        val threshold = rate * 30 - rate / 10
        assertEquals(null, preview(chunk0Samples = threshold - 1))
    }

    @Test
    fun `a cap-sized chunk shaved by the epsilon still previews`() {
        val threshold = rate * 30 - rate / 10
        assertEquals(10, preview(chunk0Samples = threshold))
    }

    @Test
    fun `full-cap 30s chunk previews 10s`() {
        assertEquals(10, preview(capSeconds = 30, chunk0Samples = rate * 30))
    }

    @Test
    fun `cap 12 previews half the chunk`() {
        assertEquals(6, preview(capSeconds = 12, chunk0Samples = rate * 12))
    }

    @Test
    fun `cap 10 previews half the chunk`() {
        assertEquals(5, preview(capSeconds = 10, chunk0Samples = rate * 10))
    }

    @Test
    fun `unknown chunk count never previews (the header is the only proof more audio follows)`() {
        // The code-review F1 class: a metadata-less or over-reporting file
        // within the cap boundary emits one exactly-cap chunk 0 whose full
        // result is one decode away; the empty tail is never sent, so the
        // chunk itself proves nothing.
        assertEquals(null, preview(expectedChunkCount = 0))
    }

    @Test
    fun `the smallest multi-chunk header previews`() {
        assertEquals(10, preview(expectedChunkCount = 2))
    }
}
