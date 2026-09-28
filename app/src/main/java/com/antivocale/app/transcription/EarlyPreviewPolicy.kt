package com.antivocale.app.transcription

/**
 * TASK-186: decides whether a pipelined run opens with an early preview.
 *
 * On a multi-chunk pipeline run the first full cap-sized chunk takes the
 * longest to decode (decode of chunk 1 already overlaps it). Before that,
 * transcribing a short HEAD of chunk 0 surfaces rough text seconds earlier.
 * The preview is interim-only: the caller surfaces it through the interim
 * row and [com.antivocale.app.service.TranscriptionListener.onPreviewResult],
 * records nothing anywhere, and the real chunk 0 result replaces it on every
 * surface.
 *
 * Two guards do the exclusion work. The header's chunk count must say the
 * run is multi-chunk (>= 2): only the header can prove more audio follows,
 * and a metadata-less or over-reporting file within the cap boundary emits
 * one exactly-cap chunk 0 whose full result is one decode away (the empty
 * tail is never sent, so the chunk itself cannot prove anything). The
 * full-cap-sized guard on chunk 0 excludes everything else: a VAD-sized
 * first chunk, a whole-file run, and a short file all arrive as a chunk 0
 * shorter than the cap, where previewing would preview most of the clip at
 * full accuracy anyway.
 */
object EarlyPreviewPolicy {

    /** The preview window never exceeds 10s of audio. */
    private const val MAX_PREVIEW_SECONDS = 10

    /**
     * Slack on the full-cap check. The chunker emits full chunks at exactly
     * the cap × rate product, so this is a drift guard, not rounding
     * correction; with the count guard requiring >= 2 a near-cap chunk 0
     * previews legitimately anyway.
     */
    private const val EPSILON_MS = 100L

    /**
     * The preview window in seconds, or null when this run gets no preview:
     * disabled, a header that does not promise at least one more chunk
     * (count < 2, including unknown 0), or a chunk 0 that is not full
     * cap-sized.
     */
    fun previewSeconds(
        enabled: Boolean,
        pipelineChunkSeconds: Int,
        expectedChunkCount: Int,
        chunk0Samples: Int,
        chunk0SampleRate: Int,
    ): Int? {
        if (!enabled) return null
        if (expectedChunkCount < 2) return null
        val fullCapSamples = chunk0SampleRate.toLong() * pipelineChunkSeconds
        val minSamples = fullCapSamples - chunk0SampleRate * EPSILON_MS / 1000
        if (chunk0Samples < minSamples) return null
        return minOf(MAX_PREVIEW_SECONDS, pipelineChunkSeconds / 2)
    }
}
