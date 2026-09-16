package com.antivocale.app.transcription

/**
 * GH #92: one timestamped cue of a transcript. Times are durations from the
 * audio's start in milliseconds (never wall clock), at chunk granularity; the
 * text is the chunk's own decoded output, captured before the punctuation pass.
 */
data class TimedSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
