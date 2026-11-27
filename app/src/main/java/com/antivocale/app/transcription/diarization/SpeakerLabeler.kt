package com.antivocale.app.transcription.diarization

import com.antivocale.app.transcription.TimedSegment

/**
 * GH #83 (prototype 2026-09-20, claudedocs/diarization-prototype/): one
 * diarized speech span on the same timeline as the transcript's cues.
 * Times are seconds from the audio's start; [speaker] is a DENSE id
 * already renumbered from sherpa's sparse cluster ids; [confidence] is
 * the per-segment silhouette when sherpa computed it, null when it
 * reports its -2.0 "unavailable" marker.
 */
data class DiarizedSegment(
    val startSec: Float,
    val endSec: Float,
    val speaker: Int,
    val confidence: Float?,
)

/**
 * GH #83: the cue-level join. Each transcript cue gets the speaker whose
 * diarized segments overlap it the most (speech-time voting, not nearest
 * boundary): the prototype measured 11/11 cues attributed correctly this
 * way on ground truth, while whole-chunk labeling measured 63 percent and
 * is wrong by design for any conversation. Cues with no overlapping speech
 * (punctuation-only or silence artifacts) get no speaker, and so (TASK-1060.9,
 * windowed arm) do cues whose unlabeled-voice overlap beats the winner's.
 */
object SpeakerLabeler {

    /**
     * TASK-678: the cue/diarization overlap vote in MILLISECONDS, shared by
     * the labeler and the re-split (one join owner; ties break toward the
     * LOWER speaker id exactly as [label] does).
     */
    fun overlapVotesMs(
        startMs: Long,
        endMs: Long,
        segments: List<DiarizedSegment>,
    ): Map<Int, Long> {
        val votes = HashMap<Int, Long>()
        for (segment in segments) {
            val overlap = kotlin.math.min(endMs, (segment.endSec * 1000).toLong()) -
                maxOf(startMs, (segment.startSec * 1000).toLong())
            if (overlap > 0) votes[segment.speaker] = (votes[segment.speaker] ?: 0L) + overlap
        }
        return votes
    }

    /**
     * The vote winner with the labeler's tie-break, or null on no overlap.
     * TASK-1060.9: null too when [unknownMs] of unlabeled voice exceeds the
     * winner's overlap, the rule [label] applies.
     */
    fun winnerMs(votes: Map<Int, Long>, unknownMs: Long = 0L): Int? =
        votes.entries.sortedWith(compareByDescending<Map.Entry<Int, Long>> { it.value }
            .thenBy { it.key }).firstOrNull()?.takeIf { it.value >= unknownMs }?.key

    /** TASK-1060.9: how much of [startMs, endMs) the [unknown] spans cover. */
    fun unknownOverlapMs(startMs: Long, endMs: Long, unknown: List<TimeSpan>): Long =
        unknown.sumOf { span ->
            (minOf(endMs, (span.endSec * 1000).toLong()) -
                maxOf(startMs, (span.startSec * 1000).toLong())).coerceAtLeast(0L)
        }

    /**
     * @param unknown TASK-1060.9: speech the windowed pass left without a
     *  speaker (dropped by the absorption gate or unlabeled by the subsample
     *  fallback), sorted and non-overlapping (SpeakerLabelTail merges it).
     *  Its overlap votes for no speaker: a cue whose unknown overlap exceeds
     *  the winner's gets null, so a cue that is mostly an unlabeled voice
     *  never takes a neighbor's sliver. Non-empty input decides in the same
     *  milliseconds as the re-split ([overlapVotesMs], [winnerMs]), so a cue
     *  and its split halves cannot disagree on a near-tie. Empty (the
     *  whole-file arm) leaves the original vote byte-for-byte unchanged.
     * @return the dense speaker id for each cue index, null where the cue
     *  overlaps no speech or its unknown overlap beats the winner's; empty
     *  when there is nothing to join.
     */
    fun label(
        cues: List<TimedSegment>,
        segments: List<DiarizedSegment>,
        unknown: List<TimeSpan> = emptyList(),
    ): List<Int?> {
        if (cues.isEmpty() || segments.isEmpty()) return List(cues.size) { null }
        if (unknown.isNotEmpty()) {
            return cues.map { cue ->
                winnerMs(
                    overlapVotesMs(cue.startMs, cue.endMs, segments),
                    unknownOverlapMs(cue.startMs, cue.endMs, unknown))
            }
        }
        return cues.map { cue ->
            val cueStartSec = cue.startMs / 1000f
            val cueEndSec = cue.endMs / 1000f
            var bestSpeaker: Int? = null
            var bestOverlap = 0f
            val votes = HashMap<Int, Float>()
            for (segment in segments) {
                val overlap = overlapSeconds(cueStartSec, cueEndSec, segment)
                if (overlap > 0f) {
                    votes[segment.speaker] = (votes[segment.speaker] ?: 0f) + overlap
                }
            }
            for ((speaker, overlap) in votes) {
                if (overlap > bestOverlap ||
                    (overlap == bestOverlap && speaker < (bestSpeaker ?: Int.MAX_VALUE))) {
                    bestOverlap = overlap
                    bestSpeaker = speaker
                }
            }
            bestSpeaker
        }
    }

    /**
     * Renumbers sherpa's sparse cluster ids ({0,1,2,5,7}) to dense
     * first-appearance order; the count is what matters, the values are
     * opaque. Also maps the -2.0 confidence marker to null.
     */
    fun denseSegments(
        segments: List<DiarizedSegment>,
    ): List<DiarizedSegment> {
        val mapping = HashMap<Int, Int>()
        return segments.map { segment ->
            val dense = mapping.getOrPut(segment.speaker) { mapping.size }
            segment.copy(
                speaker = dense,
                confidence = segment.confidence?.takeIf { it >= 0f },
            )
        }
    }

    private fun overlapSeconds(
        cueStartSec: Float,
        cueEndSec: Float,
        segment: DiarizedSegment,
    ): Float {
        val overlap = minOf(cueEndSec, segment.endSec) - maxOf(cueStartSec, segment.startSec)
        return overlap.coerceAtLeast(0f)
    }
}
