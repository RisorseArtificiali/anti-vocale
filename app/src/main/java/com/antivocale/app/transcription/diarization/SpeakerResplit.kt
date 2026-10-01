package com.antivocale.app.transcription.diarization

import com.antivocale.app.transcription.TimedSegment
import com.antivocale.app.transcription.TimedToken
import kotlin.math.abs
import kotlin.math.min

/**
 * TASK-678 (GH #83): a cue that straddles two speakers never ships as one
 * voice. This pass re-splits such cues with a tiered, honesty-first chain:
 *
 * 1. [Tier.WORD_BOUNDARY] the cue carries token timestamps: the split lands
 *    at the WORD nearest the speaker boundary (words are timed by grouping
 *    tokens on the U+2581 word-start marker, the same normalization
 *    [com.antivocale.app.transcription.SentenceCueBuilder] applies);
 * 2. [Tier.SILENCE_GAP] no tokens: words are timed proportionally across the
 *    cue and the split lands at the word nearest the boundary;
 * 3. [Tier.MIXED_LABEL] no split is possible (a single word, a half too
 *    short, or the token words and the cue text disagree): the cue stays
 *    whole and is marked [TimedSegment.mixedSpeakers] so no surface
 *    attributes it to one voice.
 *
 * Word conservation is BY CONSTRUCTION in tiers 1 and 2: both halves are cut
 * from the SAME whitespace word list as the original text, so a re-split can
 * never drop or duplicate a word (the TASK-598 per-cue lineage's invariant).
 * A wrong split is worse than an honest mixed cue: when the token grouping
 * and the cue text disagree, the chain degrades instead of splitting blind.
 */
object SpeakerResplit {

    /** Which tier produced a cue's outcome; null when no action was needed. */
    enum class Tier { WORD_BOUNDARY, SILENCE_GAP, MIXED_LABEL }

    data class Outcome(
        /** The cue(s) replacing the input cue, in time order. */
        val cues: List<TimedSegment>,
        /** The tier that acted, null when the cue was not straddling. */
        val tier: Tier?,
    )

    /**
     * A second voice must hold this much of the cue before the cue counts as
     * straddling (short overlaps are diarization jitter, one speaker's cue).
     */
    private const val MIN_SECOND_VOICE_MS = 300L

    /** Both halves of a split must keep at least this duration. */
    private const val MIN_HALF_MS = 400L

    /** sherpa's word-start marker (LOWER ONE EIGHTH BLOCK). */
    private const val WORD_START = "▁"

    fun resplit(
        cue: TimedSegment,
        cueTokens: List<TimedToken>,
        segments: List<DiarizedSegment>,
        /** TASK-678: the labeler's id for a cue this pass leaves unsplit. */
        fallbackSpeaker: Int? = null,
    ): Outcome {
        val votes = SpeakerLabeler.overlapVotesMs(cue.startMs, cue.endMs, segments)
        // Review: the same tie-break winnerMs applies (lower speaker id on
        // equal overlap), so the "second voice" is deterministic.
        val ranked = votes.entries.sortedWith(
            compareByDescending<Map.Entry<Int, Long>> { it.value }.thenBy { it.key })
        if (ranked.size < 2 || ranked[1].value < MIN_SECOND_VOICE_MS) {
            return Outcome(
                listOf(cue.copy(speaker = fallbackSpeaker, tokens = emptyList())), null)
        }
        val boundaryMs = speakerBoundaryMs(cue, segments, ranked[1].key)
            ?: return mixed(cue)

        // Words come from the TEXT (conservation by construction); the token
        // groups only TIME them. A count disagreement degrades the tier.
        val words = cue.text.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size < 2) return mixed(cue)

        val tokenSpans = wordSpansFromTokens(cueTokens)
        if (tokenSpans != null && tokenSpans.size == words.size) {
            val index = nearestWordBoundary(tokenSpans, boundaryMs, cue)
                ?.takeIf { bothHalvesViable(tokenSpans, it) }
            return if (index != null) splitAt(cue, words, tokenSpans, index, Tier.WORD_BOUNDARY, segments)
            else mixed(cue)
        }

        // No usable tokens: proportional word timing, the word nearest the
        // boundary (tier 2); still not viable lands on the honest mix.
        val spans = proportionalSpans(cue, words.size)
        val index = nearestWordBoundary(spans, boundaryMs, cue)
            ?.takeIf { bothHalvesViable(spans, it) }
        return if (index != null) splitAt(cue, words, spans, index, Tier.SILENCE_GAP, segments)
        else mixed(cue)
    }

    private fun mixed(cue: TimedSegment) = Outcome(
        listOf(cue.copy(mixedSpeakers = true, speaker = null, speakerName = null, tokens = emptyList())),
        Tier.MIXED_LABEL,
    )

    private fun splitAt(
        cue: TimedSegment,
        words: List<String>,
        spans: List<LongArray>,
        splitIndex: Int,
        tier: Tier,
        segments: List<DiarizedSegment>,
    ): Outcome {
        val boundary = ((spans[splitIndex - 1][1] + spans[splitIndex][0]) / 2)
            .coerceIn(cue.startMs + 1, cue.endMs - 1)
        // Each half is re-voted against the diarized segments for ITS OWN
        // span (the straddle means the halves belong to different voices);
        // naming rides the orchestrator's later id->name pass, so the
        // halves carry ids only.
        fun half(startMs: Long, endMs: Long, text: String) =
            TimedSegment(startMs = startMs, endMs = endMs, text = text).copy(
                speaker = SpeakerLabeler.winnerMs(
                    SpeakerLabeler.overlapVotesMs(startMs, endMs, segments)))
        return Outcome(
            listOf(
                half(cue.startMs, boundary, words.subList(0, splitIndex).joinToString(" ")),
                half(boundary, cue.endMs, words.subList(splitIndex, words.size).joinToString(" ")),
            ),
            tier,
        )
    }

    private fun nearestWordBoundary(spans: List<LongArray>, boundaryMs: Long, cue: TimedSegment): Int? {
        var best: Int? = null
        var bestDistance = Long.MAX_VALUE
        for (i in 1 until spans.size) {
            val gap = (spans[i - 1][1] + spans[i][0]) / 2
            if (gap <= cue.startMs || gap >= cue.endMs) continue
            val distance = abs(gap - boundaryMs)
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    private fun bothHalvesViable(spans: List<LongArray>, splitIndex: Int): Boolean {
        val firstDuration = spans[splitIndex - 1][1] - spans[0][0]
        val secondDuration = spans.last()[1] - spans[splitIndex][0]
        return firstDuration >= MIN_HALF_MS && secondDuration >= MIN_HALF_MS
    }

    /**
     * Times each whitespace word via the cue's tokens: a token carrying the
     * U+2581 marker opens the next word, continuation tokens extend it.
     * Null when no tokens. The CALLER compares the word count with the
     * text's; a disagreement must degrade, never split blind.
     */
    private fun wordSpansFromTokens(tokens: List<TimedToken>): List<LongArray>? {
        if (tokens.isEmpty()) return null
        val spans = mutableListOf<LongArray>()
        var start = tokens.first().startMs
        var end = tokens.first().endMs
        for (token in tokens.drop(1)) {
            if (token.text.startsWith(WORD_START)) {
                spans.add(longArrayOf(start, end))
                start = token.startMs
                end = token.endMs
            } else {
                end = maxOf(end, token.endMs)
            }
        }
        spans.add(longArrayOf(start, end))
        return spans
    }

    /** No tokens: words spread evenly across the cue (the honest estimate). */
    private fun proportionalSpans(cue: TimedSegment, wordCount: Int): List<LongArray> {
        val duration = (cue.endMs - cue.startMs).coerceAtLeast(0)
        val step = if (wordCount > 0) duration / wordCount else duration
        return List(wordCount) { i -> longArrayOf(cue.startMs + step * i, cue.startMs + step * (i + 1)) }
    }

    /**
     * The interior time where the second voice takes over: its segment's
     * clipped start when it begins inside the cue, its clipped end when it
     * ends inside. Null when neither is interior (the overlap surrounds the
     * cue: no boundary to split at, the honest mix).
     */
    private fun speakerBoundaryMs(
        cue: TimedSegment,
        segments: List<DiarizedSegment>,
        secondVoice: Int,
    ): Long? {
        // Review: the boundary is the START of the second voice's LONGEST
        // interior overlap (the takeover moment). Returning the first
        // interior edge in list order let a brief interjection beat the
        // sustained run and split at the wrong word.
        var bestStart: Long? = null
        var bestDuration = 0L
        for (segment in segments.filter { it.speaker == secondVoice }) {
            val startMs = (segment.startSec * 1000).toLong()
            val endMs = (segment.endSec * 1000).toLong()
            val interiorStart = maxOf(startMs, cue.startMs)
            val interiorEnd = minOf(endMs, cue.endMs)
            if (interiorEnd <= interiorStart) continue
            val duration = interiorEnd - interiorStart
            if (duration > bestDuration) {
                bestDuration = duration
                bestStart = interiorStart
            }
        }
        return bestStart
    }

    private val WHITESPACE = Regex("\\s+")
}
