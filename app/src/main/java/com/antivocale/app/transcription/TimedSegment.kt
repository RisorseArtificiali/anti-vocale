package com.antivocale.app.transcription

/**
 * GH #92: one timestamped cue of a transcript. Times are durations from the
 * audio's start in milliseconds (never wall clock). Granularity depends on what
 * the backend supplied: sentence cues (several per chunk, text re-joined from
 * normalized tokens by SentenceCueBuilder) when token timestamps exist, else
 * the chunk's own decoded output as one positional cue. Either way the text is
 * captured before the punctuation pass.
 */
data class TimedSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** GH #83: dense speaker id from the diarization pass, null on unlabeled
     *  cues and on every row written before the feature existed. */
    val speaker: Int? = null,
)

/**
 * GH #92: one sherpa-decoded token with its timing. Times are CHUNK-RELATIVE
 * milliseconds (offsets from the chunk the recognizer decoded; the orchestrator
 * shifts them by the chunk offset when building cues). The text is raw sherpa
 * output: the U+2581 word-start marker survives here and is normalized only in
 * [SentenceCueBuilder].
 */
data class TimedToken(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * GH #92: maps sherpa's [com.k2fsa.sherpa.onnx.OfflineRecognizerResult] arrays
 * into [TimedToken]s. One owner so the built-in and external sherpa engines
 * produce identical token timing. Cue data is optional and fails closed:
 * empty token arrays, a tokens/timestamps size mismatch, or non-finite or
 * negative timestamps yield an empty list instead of failing the
 * transcription. An
 * unusable durations array (size mismatch, non-finite, negative, or zeros) only
 * disables the duration path; ends are then inferred from the successor
 * token's start.
 */
internal object TimedTokens {

    /** Last token has no duration and no successor; a short tail is the honest guess. */
    private const val LAST_TOKEN_END_PAD_MS = 400L

    fun fromRecognizer(
        tokens: Array<String>,
        timestamps: FloatArray,
        durations: FloatArray,
    ): List<TimedToken> {
        if (tokens.isEmpty() || timestamps.size != tokens.size) return emptyList()
        // Non-finite or negative seconds would poison every downstream offset
        // (+Infinity survives an isNaN check yet toLong() saturates): reject
        // the whole set.
        if (timestamps.any { !it.isFinite() || it < 0f }) return emptyList()
        val durationsUsable = durations.size == timestamps.size &&
            durations.all { it.isFinite() && it >= 0f }
        return List(tokens.size) { i ->
            val startMs = (timestamps[i] * 1000).toLong()
            val durationMs = if (durationsUsable) (durations[i] * 1000).toLong() else 0L
            val endMs = when {
                // TDT models report a per-token duration; it carries the real
                // inter-token silences the pause split depends on.
                durationMs > 0 -> startMs + durationMs
                i + 1 < tokens.size -> (timestamps[i + 1] * 1000).toLong()
                else -> startMs + LAST_TOKEN_END_PAD_MS
            }
            TimedToken(text = tokens[i], startMs = startMs, endMs = endMs)
        }
    }
}
