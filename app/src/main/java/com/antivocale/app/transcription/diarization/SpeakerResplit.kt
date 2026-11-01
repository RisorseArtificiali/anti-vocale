package com.antivocale.app.transcription.diarization

import com.antivocale.app.transcription.TimedSegment
import com.antivocale.app.transcription.TimedToken
import com.antivocale.app.transcription.TimedWord
import com.antivocale.app.transcription.TimedWords
import kotlin.math.abs
import kotlin.math.min

/**
 * TASK-678 (GH #83): a cue that straddles two speakers never ships as one
 * voice. This pass re-splits such cues with a tiered, honesty-first chain:
 *
 * 1. [Tier.WORD_BOUNDARY] the cue carries token timestamps: the split lands
 *    at the WORD nearest the speaker boundary (words are timed by
 *    [com.antivocale.app.transcription.TimedWords], the one token-to-word
 *    derivation the cues serializer's plain path shares);
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
                listOf(cue.untimedCopy(cueTokens, speaker = fallbackSpeaker)), null)
        }
        val boundaryMs = speakerBoundaryMs(cue, segments, ranked[1].key)
            ?: return mixed(cue, cueTokens)

        // Words come from the TEXT (conservation by construction); the token
        // groups only TIME them. A count disagreement degrades the tier.
        val words = cue.text.split(WHITESPACE).filter { it.isNotBlank() }
        if (words.size < 2) return mixed(cue, cueTokens)

        val tokenSpans = TimedWords.ofCue(cue.text, cueTokens)
        if (tokenSpans != null) {
            val index = nearestWordBoundary(tokenSpans, boundaryMs, cue)
                ?.takeIf { bothHalvesViable(tokenSpans, it) }
            return if (index != null) splitAt(cue, words, tokenSpans, index, Tier.WORD_BOUNDARY, segments)
            else mixed(cue, cueTokens)
        }

        // No usable tokens: proportional word timing, the word nearest the
        // boundary (tier 2); still not viable lands on the honest mix.
        val spans = proportionalSpans(cue, words.size)
        val index = nearestWordBoundary(spans, boundaryMs, cue)
            ?.takeIf { bothHalvesViable(spans, it) }
        return if (index != null) splitAt(cue, words, spans, index, Tier.SILENCE_GAP, segments)
        else mixed(cue, cueTokens)
    }

    /**
     * TASK-928: the cue leaves this pass as shipped (speaker id applied, the
     * transient tokens dropped), keeping its real word timing when the
     * tokens backed every word. A proportional estimate is never persisted:
     * no tokens means no [TimedWord]s.
     */
    private fun TimedSegment.untimedCopy(
        cueTokens: List<TimedToken>,
        speaker: Int?,
        mixed: Boolean = false,
    ): TimedSegment = copy(
        speaker = speaker,
        mixedSpeakers = mixed,
        speakerName = if (mixed) null else speakerName,
        tokens = emptyList(),
        words = TimedWords.ofCue(text, cueTokens).orEmpty(),
    )

    private fun mixed(cue: TimedSegment, cueTokens: List<TimedToken>) = Outcome(
        listOf(cue.untimedCopy(cueTokens, speaker = null, mixed = true)),
        Tier.MIXED_LABEL,
    )

    private fun splitAt(
        cue: TimedSegment,
        words: List<String>,
        spans: List<TimedWord>,
        splitIndex: Int,
        tier: Tier,
        segments: List<DiarizedSegment>,
    ): Outcome {
        val boundary = ((spans[splitIndex - 1].endMs + spans[splitIndex].startMs) / 2)
            .coerceIn(cue.startMs + 1, cue.endMs - 1)
        // Each half is re-voted against the diarized segments for ITS OWN
        // span (the straddle means the halves belong to different voices);
        // naming rides the orchestrator's later id->name pass, so the
        // halves carry ids only. WORD_BOUNDARY halves slice the exact token
        // spans; SILENCE_GAP halves are proportional estimates, and a
        // proportional estimate never persists (TASK-928), so those halves
        // carry no word timing.
        fun half(startMs: Long, endMs: Long, text: String, words: List<TimedWord>) =
            TimedSegment(
                startMs = startMs, endMs = endMs, text = text,
                speaker = SpeakerLabeler.winnerMs(
                    SpeakerLabeler.overlapVotesMs(startMs, endMs, segments)),
                words = words,
            )
        val halfWords = when (tier) {
            Tier.WORD_BOUNDARY -> spans.subList(0, splitIndex) to spans.subList(splitIndex, spans.size)
            else -> emptyList<TimedWord>() to emptyList()
        }
        return Outcome(
            listOf(
                half(cue.startMs, boundary, words.subList(0, splitIndex).joinToString(" "), halfWords.first),
                half(boundary, cue.endMs, words.subList(splitIndex, words.size).joinToString(" "), halfWords.second),
            ),
            tier,
        )
    }

    private fun nearestWordBoundary(spans: List<TimedWord>, boundaryMs: Long, cue: TimedSegment): Int? {
        var best: Int? = null
        var bestDistance = Long.MAX_VALUE
        for (i in 1 until spans.size) {
            val gap = (spans[i - 1].endMs + spans[i].startMs) / 2
            if (gap <= cue.startMs || gap >= cue.endMs) continue
            val distance = abs(gap - boundaryMs)
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    private fun bothHalvesViable(spans: List<TimedWord>, splitIndex: Int): Boolean {
        val firstDuration = spans[splitIndex - 1].endMs - spans[0].startMs
        val secondDuration = spans.last().endMs - spans[splitIndex].startMs
        return firstDuration >= MIN_HALF_MS && secondDuration >= MIN_HALF_MS
    }

    /** No tokens: words spread evenly across the cue (the honest estimate). */
    private fun proportionalSpans(cue: TimedSegment, wordCount: Int): List<TimedWord> {
        val duration = (cue.endMs - cue.startMs).coerceAtLeast(0)
        val step = if (wordCount > 0) duration / wordCount else duration
        return List(wordCount) { i -> TimedWord(cue.startMs + step * i, cue.startMs + step * (i + 1)) }
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
