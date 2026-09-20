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
 * Both arms are WINDOWED over the token list (40 tokens, step 20, plus a
 * final window anchored at the tail so the last few tokens are always
 * covered). The 2026-09-20 review measured that a whole-text compression
 * ratio grows without bound on clean prose (2.4 crossed near 1950 Italian
 * words), which would make long clean transcripts false-positive; a
 * 40-token window of clean prose stays near 1.5-2.0 while any window of a
 * budget-filling loop measures 2.6-47, the separation the corpus measured.
 *  - compression: zlib ratio >= 2.4 over a window's bytes (OpenAI's
 *    long-form threshold, arXiv 2212.04356, applied per window).
 *  - n-gram: one token trigram covering >= 40 percent of a window.
 *
 * Tokens are whitespace words. Whitespace-free scripts (Japanese, Chinese)
 * would collapse to a single token and bypass both arms, so when the text
 * is substantial (over 500 bytes) but yields too few words it is
 * re-tokenized into codepoints; thresholds are unchanged, with the honest
 * caveat that they were measured on spaced scripts, not on CJK prose.
 *
 * Condensing outputs (summaries, short answers) never fire: the token
 * floor is 24 and neither arm measures length against the audio. The
 * evaluated-and-rejected third arm: a words-per-second ceiling anchored on
 * audio duration; the greedy token budget itself caps loops at ~6 words/s,
 * too close to real fast speech to separate.
 */
object RepetitionLoopDetector {

    /** Stable reason tokens persisted in ProcessingContext. */
    const val REASON_COMPRESSION = "compression"
    const val REASON_NGRAM = "ngram"

    private const val COMPRESSION_THRESHOLD = 2.4f
    private const val NGRAM_DOMINANCE = 0.4f
    private const val WINDOW_TOKENS = 40
    private const val WINDOW_STEP = 20
    private const val MIN_TOKENS = 24
    private const val CJK_RETOKENIZE_BYTES = 500
    private val WHITESPACE = Regex("\\s+")
    private val CJK = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]")

    /**
     * @return the reason token when [text] is a runaway repetition loop,
     *   null when the text is acceptable (including every short or
     *   condensed text, by construction).
     */
    fun detect(text: String): String? {
        val tokens = tokenize(text)
        if (tokens.size < MIN_TOKENS) return null
        var start = 0
        val lastStart = tokens.size - WINDOW_TOKENS
        while (start <= lastStart) {
            val window = tokens.subList(start, start + WINDOW_TOKENS).joinToString(" ")
            if (compressionRatio(window) >= COMPRESSION_THRESHOLD) return REASON_COMPRESSION
            if (topTrigramDominance(tokens, start, start + WINDOW_TOKENS) >= NGRAM_DOMINANCE) {
                return REASON_NGRAM
            }
            if (start == lastStart) break
            start = minOf(start + WINDOW_STEP, lastStart)
        }
        return null
    }

    /**
     * Whitespace words; a substantial text with too few of them (CJK) is
     * re-tokenized into codepoints so windows and trigrams still see it.
     */
    private fun tokenize(text: String): List<String> {
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        val raw = text.toByteArray(Charsets.UTF_8)
        if (words.size < MIN_TOKENS && raw.size > CJK_RETOKENIZE_BYTES && CJK.containsMatchIn(text)) {
            return text.codePoints().toArray().map { it.toChar().toString() }
        }
        return words
    }

    private fun compressionRatio(window: String): Float {
        val raw = window.toByteArray(Charsets.UTF_8)
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
    private fun topTrigramDominance(tokens: List<String>, from: Int, until: Int): Float {
        val positions = until - from - 2
        if (positions <= 0) return 0f
        val counts = HashMap<String, Int>(positions)
        var top = 0
        for (i in from + 2 until until) {
            val key = tokens[i - 2] + ' ' + tokens[i - 1] + ' ' + tokens[i]
            val count = (counts[key] ?: 0) + 1
            counts[key] = count
            if (count > top) top = count
        }
        return top.toFloat() / positions
    }
}
