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
 * (punctuation-only or silence artifacts) get no speaker.
 */
object SpeakerLabeler {

    /** @return the dense speaker id for each cue index, null where the cue
     *  overlaps no speech; empty when there is nothing to join. */
    fun label(
        cues: List<TimedSegment>,
        segments: List<DiarizedSegment>,
    ): List<Int?> {
        if (cues.isEmpty() || segments.isEmpty()) return List(cues.size) { null }
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
