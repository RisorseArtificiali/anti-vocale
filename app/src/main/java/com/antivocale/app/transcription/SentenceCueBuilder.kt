package com.antivocale.app.transcription

/**
 * GH #92: sentence-level subtitle cues from sherpa's per-token timestamps.
 *
 * Chunk-level cues (one [TimedSegment] per decoded chunk) are too coarse for
 * subtitles: a 5:29 voice note in 60s chunks renders six one-minute cues. This
 * builder regroups [TimedToken]s (chunk-relative, as produced by TimedTokens)
 * into sentence cues. The caller passes the chunk's own range on the audio
 * timeline; returned cues are absolute (chunk offset applied) and clamped to
 * that range.
 *
 * Split rules, first match wins:
 * 1. Sentence end: after a token ending with . ! or ? (trailing quotes or
 *    parentheses do not block the split).
 * 2. Pause: a silence gap between consecutive tokens longer than
 *    [PAUSE_SPLIT_MS]. Covers models whose raw output carries no punctuation:
 *    inter-word gaps stay well under the threshold, sentence pauses exceed it.
 *    (Only duration-reporting models such as TDT produce nonzero gaps.)
 * 3. Reading-speed cap: when a cue would exceed [MAX_CUE_MS], it closes at the
 *    token boundary closest to [TARGET_CUE_MS] into the cue. 15-17 chars/s is
 *    the comfortable subtitle reading speed; 7s is the ceiling a cue may reach
 *    and 5s keeps cap-driven splits near the middle instead of piling at the
 *    ceiling. A comma-terminated token in the cue's second half wins over the
 *    closest boundary: a clause break reads better than an arbitrary cut.
 *
 * Normalization: sherpa marks word starts with U+2581 (LOWER ONE EIGHTH
 * BLOCK); it becomes a space. A token starting with closing punctuation
 * (. , ! ? ; : )) glues to the previous word without a preceding space; runs
 * of spaces collapse; cues whose text is blank drop. Cues never overlap.
 */
object SentenceCueBuilder {

    /** Silence between two tokens that ends the current cue. */
    internal const val PAUSE_SPLIT_MS = 700L

    /** A cue longer than this is split (subtitle reading speed). */
    internal const val MAX_CUE_MS = 7000L

    /** Where a cap split aims, so cap-driven cues do not all pile at the ceiling. */
    internal const val TARGET_CUE_MS = 5000L

    private const val WORD_START_MARKER = '▁'
    private val CLOSING_PUNCTUATION = charArrayOf('.', ',', '!', '?', ';', ':', ')')
    private val SENTENCE_ENDERS = charArrayOf('.', '!', '?')
    private val TRAILING_CLOSERS = "\"'”’)]}"
    private val MULTIPLE_SPACES = Regex(" {2,}")

    /**
     * Builds the sentence cues of one chunk. [chunkStartMs]/[chunkEndMs] are the
     * chunk's range on the audio's timeline; token times are relative to the
     * chunk.
     */
    fun build(tokens: List<TimedToken>, chunkStartMs: Long, chunkEndMs: Long): List<TimedSegment> {
        if (tokens.isEmpty()) return emptyList()
        val lo = minOf(chunkStartMs, chunkEndMs)
        val hi = maxOf(chunkStartMs, chunkEndMs)
        val cues = mutableListOf<TimedSegment>()
        for ((first, last) in cueBounds(tokens)) {
            val text = joinText(tokens, first, last)
            if (text.isBlank()) continue
            val start = (chunkStartMs + tokens[first].startMs).coerceIn(lo, hi)
            val end = (chunkStartMs + tokens[last].endMs).coerceIn(lo, hi).coerceAtLeast(start)
            // Trim a previous cue that would reach past this start, keeping its
            // duration non-negative. Full non-overlap holds for the monotonic
            // token starts every wired sherpa family produces; badly inverted
            // starts can still leave neighbors touching out of order.
            cues.lastOrNull()?.let { previous ->
                if (previous.endMs > start) {
                    cues[cues.size - 1] = previous.copy(endMs = maxOf(start, previous.startMs))
                }
            }
            cues.add(TimedSegment(start, end, text))
        }
        return cues
    }

    /**
     * Cue token ranges (inclusive first..last). The loop walks boundaries: the
     * open cue is tokens[cueFirst..i-1] and the decision is whether tokens[i]
     * joins it. An empty cue (cueFirst == i) always takes the next token.
     */
    private fun cueBounds(tokens: List<TimedToken>): List<Pair<Int, Int>> {
        val bounds = mutableListOf<Pair<Int, Int>>()
        var cueFirst = 0
        var i = 1
        while (i < tokens.size) {
            if (cueFirst == i) {
                i++
                continue
            }
            val prev = tokens[i - 1]
            val next = tokens[i]
            when {
                // Rules 1 and 2 share the cut; when branches are checked in order.
                endsSentence(prev.text) || next.startMs - prev.endMs > PAUSE_SPLIT_MS -> {
                    bounds += cueFirst to i - 1
                    cueFirst = i
                    i++
                }
                next.endMs - tokens[cueFirst].startMs > MAX_CUE_MS -> {
                    val closeAfter = capSplitIndex(tokens, cueFirst, i)
                    bounds += cueFirst to closeAfter
                    cueFirst = closeAfter + 1
                    // Same i: the shortened cue may still overflow, and the other
                    // rules must be re-evaluated against its new start.
                }
                else -> i++
            }
        }
        bounds += cueFirst to tokens.lastIndex
        return bounds
    }

    /**
     * The token index the capped cue closes after: the boundary whose end time
     * is closest to [TARGET_CUE_MS] into the cue, preferring a comma-terminated
     * token in the cue's second half (a clause break over the closest cut).
     * [overflowAt] is the first token NOT yet in the cue.
     */
    private fun capSplitIndex(tokens: List<TimedToken>, cueFirst: Int, overflowAt: Int): Int {
        val target = tokens[cueFirst].startMs + TARGET_CUE_MS
        val secondHalfStart = cueFirst + (overflowAt - cueFirst) / 2
        val commaCandidates = (secondHalfStart until overflowAt)
            .filter { normalized(tokens[it].text).trim().endsWith(',') }
        return (commaCandidates.ifEmpty { (cueFirst until overflowAt).toList() })
            .minByOrNull { kotlin.math.abs(tokens[it].endMs - target) }
            ?: cueFirst
    }

    private fun endsSentence(raw: String): Boolean {
        val text = normalized(raw).trimEnd()
        var i = text.length - 1
        while (i >= 0 && text[i] in TRAILING_CLOSERS) i--
        return i >= 0 && text[i] in SENTENCE_ENDERS
    }

    private fun joinText(tokens: List<TimedToken>, first: Int, last: Int): String {
        val sb = StringBuilder()
        for (i in first..last) appendNormalized(sb, normalized(tokens[i].text))
        return MULTIPLE_SPACES.replace(sb.toString(), " ").trim()
    }

    /** Joins one normalized token, gluing closing punctuation to the previous word. */
    private fun appendNormalized(sb: StringBuilder, normalized: String) {
        val glued = normalized.trimStart()
        if (sb.isNotEmpty() && glued.isNotEmpty() && glued[0] in CLOSING_PUNCTUATION) {
            sb.append(glued)
            return
        }
        if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
        sb.append(normalized)
    }

    private fun normalized(raw: String): String = raw.replace(WORD_START_MARKER, ' ')
}
