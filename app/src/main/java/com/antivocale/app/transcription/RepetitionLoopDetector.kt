package com.antivocale.app.transcription

import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

/**
 * TASK-579: detects the runaway repetition-loop class in a transcript
 * ("phrase x N until the decoder budget fills"; the Whisper-small
 * "Muy bien!" x20 incident and the crash-report loop corpus). Replaces
 * the reverted length-ratio guard (7112ca0d): a ratio between two
 * loop-prone texts cannot see either direction, while these anchors
 * judge each text on its own.
 *
 * Two independent arms, both validated on the loop corpus and the real
 * transcripts of the 2026-09-20 spike (claudedocs; loop ratios 2.6-47,
 * prose 1.7-1.9, condensations 0.7-1.4):
 *  - compression: raw/zlib-deflated UTF-8 ratio >= 2.4 (OpenAI's
 *    long-form threshold, arXiv 2212.04356). Above the 24-word floor it
 *    fires from ~5 repeats of a five-word phrase and ~3 of a full
 *    sentence; the two-word "Muy bien!" loop needs twelve.
 *  - n-gram, windowed: one word trigram covering >= 40 percent of a
 *    40-word window (step 20). Windows catch the buried-loop shape a
 *    global ratio dilutes: a clean chunk followed by a looping chunk,
 *    exactly what multi-chunk assembly produces.
 *
 * Condensing outputs (summaries, short answers) never fire: both arms
 * need a minimum word count, and neither measures length against the
 * audio. The evaluated-and-rejected third arm: a words-per-second
 * ceiling anchored on audio duration; the greedy token budget itself
 * caps loops at ~6 words/s, too close to real fast speech to separate.
 */
object RepetitionLoopDetector {

    /** Stable reason tokens persisted in ProcessingContext. */
    const val REASON_COMPRESSION = "compression"
    const val REASON_NGRAM = "ngram"

    private const val COMPRESSION_THRESHOLD = 2.4f
    private const val COMPRESSION_MIN_WORDS = 24
    private const val NGRAM_DOMINANCE = 0.4f
    private const val NGRAM_WINDOW_WORDS = 40
    private const val NGRAM_WINDOW_STEP = 20

    private val WHITESPACE = Regex("\\s+")

    /**
     * @return the reason token when [text] is a runaway repetition loop,
     *   null when the text is acceptable (including every short or
     *   condensed text, by construction).
     */
    fun detect(text: String): String? {
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size < COMPRESSION_MIN_WORDS) return null
        if (compressionRatio(text) >= COMPRESSION_THRESHOLD) return REASON_COMPRESSION
        var start = 0
        while (start + NGRAM_WINDOW_WORDS <= words.size) {
            if (topTrigramDominance(words, start, start + NGRAM_WINDOW_WORDS) >= NGRAM_DOMINANCE) {
                return REASON_NGRAM
            }
            start += NGRAM_WINDOW_STEP
        }
        return null
    }

    private fun compressionRatio(text: String): Float {
        val raw = text.toByteArray(Charsets.UTF_8)
        val deflated = ByteArrayOutputStream().use { out ->
            // No explicit Deflater: the default level suffices (the consumer
            // is a ratio against 2.4; loops measure 2.6-47), and only the
            // implicitly-created deflater is ended on close; an explicit one
            // would leak native zlib state per call.
            DeflaterOutputStream(out).use { it.write(raw) }
            out.size()
        }
        if (deflated <= 0) return 1f
        return raw.size.toFloat() / deflated
    }

    /** Top trigram count over positions in [from, until), divided by them. */
    private fun topTrigramDominance(words: List<String>, from: Int, until: Int): Float {
        val positions = until - from - 2
        if (positions <= 0) return 0f
        val counts = HashMap<String, Int>(positions)
        var top = 0
        for (i in from + 2 until until) {
            val key = words[i - 2] + ' ' + words[i - 1] + ' ' + words[i]
            val count = (counts[key] ?: 0) + 1
            counts[key] = count
            if (count > top) top = count
        }
        return top.toFloat() / positions
    }
}
