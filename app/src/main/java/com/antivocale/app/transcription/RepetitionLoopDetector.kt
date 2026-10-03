package com.antivocale.app.transcription

import java.io.ByteArrayOutputStream
import java.util.Locale
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
 * covered; the 24-39-token band scans a 24/12 window instead, see
 * TASK-585 gap 1 below). The 2026-09-20 review measured that a whole-text compression
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
 * Condensing outputs (summaries, short answers) never fire: neither arm
 * measures length against the audio. TASK-585 gap 1 closed the floor:
 * texts of 24-39 tokens (which form no 40-token window) now scan with
 * the measured 24-token window; the thresholds are UNCHANGED (the
 * 2026-10-03 sweep: COMPRESSION separates at window 24, clean max
 * 1.2887 < 2.4 <= loop min 2.4259; the ngram arm separates nowhere in
 * the loop corpus and is clean-side-safe only). The
 * evaluated-and-rejected third arm: a words-per-second ceiling anchored on
 * audio duration; the greedy token budget itself caps loops at ~6 words/s,
 * too close to real fast speech to separate.
 */
object RepetitionLoopDetector {

    /**
     * TASK-581 (review F6): phase 2 completed non-blank but COLLAPSED over a
     * good first pass ("Si." over a 200-word transcript). detect() cannot see
     * it (no loop). The bar for the first pass mirrors [MIN_TOKENS] (a short
     * first pass has nothing to protect); the refined text must be under a
     * quarter of it. Only consulted for PLAIN transcription prompts: a
     * condensing prompt legitimately produces a short phase 2 (the lesson of
     * the reverted 7112ca0d guard, AC3).
     */
    fun shortCollapseOverGoodFirstPass(firstPassText: String, refinedText: String): Boolean {
        // [tokenize] on BOTH sides (symmetric): for unspaced scripts the
        // whitespace count would be ~1 word and the floor below would silence
        // the guard exactly where a one-word collapse is worst; tokenize's
        // CJK codepoint retokenization applies to both texts alike, so the
        // ratio stays comparable for every script mix.
        val firstPassWords = tokenize(firstPassText)
        if (firstPassWords.size < MIN_TOKENS) return false
        val refinedWords = tokenize(refinedText)
        if (refinedWords.isEmpty()) return false // blank has its own arm
        return refinedWords.size * COLLAPSE_FRACTION < firstPassWords.size
    }

    /** Stable reason tokens persisted in ProcessingContext. */
    const val REASON_COMPRESSION = "compression"
    const val REASON_NGRAM = "ngram"

    private const val COMPRESSION_THRESHOLD = 2.4f
    private const val NGRAM_DOMINANCE = 0.4f
    private const val WINDOW_TOKENS = 40
    private const val WINDOW_STEP = 20
    private const val MIN_TOKENS = 24

    /**
     * TASK-585 gap 1: the sub-40 window. Texts of 24-39 tokens form no
     * 40-token window and passed undetected (the 4-word-phrase x9 = 36
     * tokens case). The 2026-10-03 sweep (eval/loop_threshold_sweep.py,
     * clean corpus = the reference transcripts + the 280-word real ASR
     * sample; loop corpus = 456 synthesized incident-class texts)
     * measured that a 24-token window separates on the COMPRESSION arm
     * at the SHIPPED threshold: clean max 1.2887 < 2.4 <= loop min
     * 2.4259 (the phrase-7 x4 boundary, a 1.1 percent margin - the
     * tightest fixture in the corpus; pinned by a test). The ngram arm
     * does NOT separate in this band (loop min 0.1364, far under 0.4):
     * clean-side-safe but no detection power here. So the short band
     * scans with this window; both thresholds are unchanged. The clean
     * side is thin IN the band (one real transcript, 37 tokens, plus
     * sub-windows of longer texts): a real short note with mild
     * repetition above 2.4 would fire - watch the clean-maxima
     * telemetry as this band fills.
     */
    private const val SHORT_WINDOW_TOKENS = 24
    private const val SHORT_WINDOW_STEP = 12

    /** TASK-581: refined under this fraction of a good first pass is a collapse. */
    private const val COLLAPSE_FRACTION = 4
    private const val CJK_RETOKENIZE_BYTES = 500
    private val WHITESPACE = Regex("\\s+")
    private val CJK = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]")

    /** The one formatter for the persisted compact form (see [Detection.metrics]). */
    private fun formatMetrics(compression: Float, dominance: Float): String =
        "compression=%.4f ngram=%.4f".format(Locale.US, compression, dominance)

    /**
     * TASK-582: a fired detection with the measured signal maxima for
     * threshold tuning. Once a window fires the scan CONTINUES to the end
     * so the maxima cover every window (a review measured prefix-truncated
     * maxima biasing field rows low, e.g. firing at 2.47 with later
     * windows at 7.48); the reason is the FIRST arm that fired, the
     * verdict, which cannot change after the fact.
     */
    data class Detection(
        val reason: String,
        val maxCompressionRatio: Float,
        val maxTrigramDominance: Float,
    ) {
        /** The compact persisted form, Locale-stable: "compression=2.3951
         *  ngram=0.1053". Four decimals: two decimals rendered 2.3951 and
         *  2.4049 identically, losing the tuning signal at 2.4. */
        fun metrics(): String = formatMetrics(maxCompressionRatio, maxTrigramDominance)
    }

    /**
     * TASK-585: the scan result either way: a FIRED [Detection] or the
     * acceptable-text maxima (the one-sided-tuning gap: clean maxima were
     * computed then discarded, so a future false-positive report arrived
     * with no data on the acceptable distribution to move a threshold
     * against). `cleanMaxima` is null on a fired detection AND on short
     * texts (the same floor where detect() returns null).
     */
    data class Scan(
        val detection: Detection?,
        /** "compression=X ngram=Y" of the acceptable text; null on a fire
         *  or on a short text (the same floor where detection is null). */
        val cleanMaxima: String?,
    )

    /** The [Scan] payload before its packaging: maxima either way, the
     *  first fired arm's reason when one fired. Null = short text. */
    private data class WalkResult(
        val firedReason: String?,
        val maxCompression: Float,
        val maxDominance: Float,
    )

    /**
     * THE one window walk: measures every window's compression and trigram
     * dominance, records the first arm that fires, and returns the maxima
     * either way so the fired and clean distributions stay comparable
     * (simplify round: a second walk re-walking the same windows had made
     * the two dialects drift-able exactly where the task's own next step,
     * the Gap-1 window re-measurement, must edit them).
     */
    private fun walk(text: String): WalkResult? {
        val tokens = tokenize(text)
        if (tokens.size < MIN_TOKENS) return null
        // TASK-585 gap 1: the sub-40 band scans with the measured short
        // window (24) and its half-step; 40+ keeps the original window.
        // One walk, two window sizes: the tail anchor and the first-fire
        // semantics are identical in both bands, so the persisted maxima
        // stay comparable across the floor.
        val windowTokens = if (tokens.size < WINDOW_TOKENS) SHORT_WINDOW_TOKENS else WINDOW_TOKENS
        val windowStep = if (tokens.size < WINDOW_TOKENS) SHORT_WINDOW_STEP else WINDOW_STEP
        var start = 0
        val lastStart = tokens.size - windowTokens
        var maxCompression = 0f
        var maxDominance = 0f
        var firedReason: String? = null
        while (start <= lastStart) {
            val window = tokens.subList(start, start + windowTokens).joinToString(" ")
            val compression = compressionRatio(window)
            maxCompression = maxOf(maxCompression, compression)
            val dominance = topTrigramDominance(tokens, start, start + windowTokens)
            maxDominance = maxOf(maxDominance, dominance)
            // First fire fixes the reason and the verdict; the scan still
            // continues so later windows update the tuning maxima.
            if (firedReason == null) {
                firedReason = when {
                    compression >= COMPRESSION_THRESHOLD -> REASON_COMPRESSION
                    dominance >= NGRAM_DOMINANCE -> REASON_NGRAM
                    else -> null
                }
            }
            if (start == lastStart) break
            start = minOf(start + windowStep, lastStart)
        }
        return WalkResult(firedReason, maxCompression, maxDominance)
    }

    fun scan(text: String): Scan = when (val w = walk(text)) {
        null -> Scan(null, null)
        else -> w.firedReason
            ?.let { Scan(Detection(it, w.maxCompression, w.maxDominance), null) }
            ?: Scan(null, formatMetrics(w.maxCompression, w.maxDominance))
    }

    /**
     * @return the detection (reason plus measured values) when [text] is a
     *   runaway repetition loop, null when the text is acceptable
     *   (including every short or condensed text, by construction).
     */
    fun detect(text: String): Detection? = walk(text)?.let { w ->
        w.firedReason?.let { Detection(it, w.maxCompression, w.maxDominance) }
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
