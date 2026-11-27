package com.antivocale.app.transcription.diarization

import com.antivocale.app.transcription.TranscriptionResult

/**
 * TASK-1060.6: the pure cue-labeling tail, moved out of the orchestrator
 * companion so History re-diarize can run it from outside the pipeline.
 */
internal object SpeakerLabelTail {

    /**
     * GH #83: the speaker-labeling tail both arms share. Each cue gets
     * the speaker whose segments overlap it most; TASK-678: a cue
     * straddling two voices is re-split at the word nearest the speaker
     * boundary (tier chain: tokens, proportional, honest mixed). Split
     * halves carry their OWN re-voted speakers; unsplit cues take the
     * labeler's label by ORIGINAL index (the split changes the cue
     * count, so the two lists must never be re-zipped). The transient
     * tokens are consumed here and dropped. TASK-670: [names] maps a
     * dense cluster id to its enrolled person's name. TASK-1060.9:
     * [unlabeledSpans] is the windowed arm's speech without a speaker,
     * counted as votes for no speaker; the whole-file arm passes none.
     */
    fun withSpeakerLabels(
        transcription: TranscriptionResult,
        segments: List<DiarizedSegment>,
        names: Map<Int, String>,
        unlabeledSpans: List<TimeSpan> = emptyList(),
    ): TranscriptionResult {
        // One merged, sorted copy: the labeler and the re-split both sum
        // overlaps, so overlapping spans would count the same speech twice.
        val unknown = PieceClustering.mergedSpans(unlabeledSpans)
        val labels = SpeakerLabeler.label(transcription.segments, segments, unknown)
        val outcomes = transcription.segments.mapIndexed { index, cue ->
            SpeakerResplit.resplit(cue, cue.tokens, segments, labels[index], unknown)
        }
        val tierCounts = outcomes.mapNotNull { it.tier }.groupingBy { it }.eachCount()
        val resplitSummary = if (tierCounts.isEmpty()) null else
            tierCounts.entries.joinToString(",") { (tier, count) ->
                "${tier.name.lowercase()}=$count"
            }
        // TASK-678 review: map the RESPLIT segments (outcomes carry the
        // labeled/re-split cues; mapping the original result discarded
        // them and every label with them).
        return transcription.copy(
            segments = outcomes.flatMap { it.cues }.map { cue ->
                cue.copy(speakerName = cue.speaker?.let(names::get))
            },
            processing = transcription.processing?.copy(speakerResplit = resplitSummary),
        )
    }
}
