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
 * The single full-cap-sized guard on chunk 0 does all the exclusion work: a
 * VAD-sized first chunk, a whole-file run, and a file shorter than the cap
 * all arrive as a chunk 0 shorter than the cap, where previewing would
 * either duplicate the imminent full result (single-chunk files, excluded
 * by their own rule) or preview most of the clip at full accuracy anyway.
 */
object EarlyPreviewPolicy {

    /** The preview window never exceeds 10s of audio. */
    private const val MAX_PREVIEW_SECONDS = 10

    /**
     * Slack on the full-cap check: a cap-sized chunk can come up a few
     * samples short of the exact cap × rate product (resampler rounding).
     * 100ms is far below any non-cap chunk shape the stream produces.
     */
    private const val EPSILON_MS = 100L

    /**
     * The preview window in seconds, or null when this run gets no preview:
     * disabled, a known single-chunk file, or a chunk 0 that is not full
     * cap-sized. [expectedChunkCount] 0 means unknown; a full cap-sized
     * chunk 0 still previews (more audio provably follows).
     */
    fun previewSeconds(
        enabled: Boolean,
        pipelineChunkSeconds: Int,
        expectedChunkCount: Int,
        chunk0Samples: Int,
        chunk0SampleRate: Int,
    ): Int? {
        if (!enabled) return null
        if (expectedChunkCount == 1) return null
        val fullCapSamples = chunk0SampleRate.toLong() * pipelineChunkSeconds
        val minSamples = fullCapSamples - chunk0SampleRate * EPSILON_MS / 1000
        if (chunk0Samples < minSamples) return null
        return minOf(MAX_PREVIEW_SECONDS, pipelineChunkSeconds / 2)
    }
}
