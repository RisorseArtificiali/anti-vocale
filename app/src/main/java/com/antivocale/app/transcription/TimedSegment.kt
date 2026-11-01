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
    /** TASK-670 (GH #83): the enrolled person's name when the cluster above
     *  matched an enrolled voiceprint at or above the similarity threshold;
     *  null keeps the generic SPEAKER N prefix. Rides the same cue, so every
     *  rendering surface picks it up without forking. */
    val speakerName: String? = null,
    /** TASK-678 (GH #83): this cue straddles two speakers and could not be
     *  honestly split; renderers must not attribute it to one voice. */
    val mixedSpeakers: Boolean = false,
    /** TASK-678: the cue's own tokens, ABSOLUTE ms from the audio start
     *  (the builder shifts chunk-relative times when attaching), transient
     *  by design: never serialized, consumed by the speaker re-split in the
     *  same pass that labels the cue, then irrelevant. */
    val tokens: List<TimedToken> = emptyList(),
    /** TASK-928: one [TimedWord] per whitespace word of [text], ABSOLUTE ms,
     *  index-aligned with the text's words (count-validated at derivation).
     *  Empty on every row written before the feature, on models without
     *  token timestamps, and on re-split halves whose only timing would be
     *  a proportional estimate (bogus timing never persists). */
    val words: List<TimedWord> = emptyList(),
    /** TASK-928: [words] is the cue-envelope estimate ([TimedWords
     *  .proportionalEstimate]) for a marker-less token stream, not measured
     *  grouping. Both endpoints stay measured; the interior placement is
     *  interpolated. Consumers that need measured boundaries only (the
     *  karaoke export) refuse estimated words; tap-to-seek accepts them.
     *  False on every measured-words row and on rows before the feature. */
    val wordsEstimated: Boolean = false,
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
 * TASK-928: one word of a cue's text with its timing. Times are ABSOLUTE ms
 * from the audio start, like the cue's own. The text is not carried: the
 * list is index-aligned with the cue text's whitespace words, and a stale
 * text copy would drift from the rendered words.
 */
data class TimedWord(
    val startMs: Long,
    val endMs: Long,
)

/**
 * TASK-928: the ONE derivation of per-word timing from a cue's tokens, shared
 * by the speaker re-split (which must attach it before dropping the transient
 * tokens) and the cues serializer (plain path: tokens still ride the cue at
 * write time). A token carrying the U+2581 word-start marker opens the next
 * word, continuation tokens extend it, the same grouping SpeakerResplit's
 * split vote has always used. Whisper's base64 tokens.txt decodes to tokens
 * whose word starts are a literal leading space instead of the marker
 * (symbol-table.cc only rewrites U+2581 for non-byte-BPE tables), so a
 * whitespace-led token opens a word too. Null when the tokens cannot back
 * every whitespace word of [cueText]: the count equality is what makes index
 * alignment to the rendered words sound, and a mismatch degrades instead of
 * timing words it cannot see. Tokens that carry no character (whisper's
 * bare-space piece, a lone U+2581) group nothing: they detokenize to no
 * text, so counting them as word starts (or letting their spans stretch a
 * group) invents a phantom word and fails the gate.
 */
internal object TimedWords {

    private val WHITESPACE = Regex("\\s+")

    /** Same normalization the builder and the re-split apply. */
    private const val WORD_START = "▁"

    fun ofCue(cueText: String, tokens: List<TimedToken>): List<TimedWord>? {
        val worded = tokens.filter { hasWordText(it.text) }
        if (worded.isEmpty()) return null
        val spans = mutableListOf<TimedWord>()
        var start = worded.first().startMs
        var end = worded.first().endMs
        for (token in worded.drop(1)) {
            if (opensWord(token.text)) {
                spans.add(TimedWord(start, end))
                start = token.startMs
                end = token.endMs
            } else {
                end = maxOf(end, token.endMs)
            }
        }
        spans.add(TimedWord(start, end))
        val wordCount = cueText.split(WHITESPACE).count { it.isNotBlank() }
        return spans.takeIf { it.size == wordCount }
    }

    /**
     * The cue-envelope estimate for a token stream whose words cannot be
     * grouped (a subword tokenizer with no word-start markers, Parakeet's
     * BPE): every whitespace word spreads evenly over the cue's span. Both
     * endpoints are measured token/cue boundaries; only the interior
     * placement is interpolated, so the caller must persist the result under
     * [TimedSegment.wordsEstimated]. Null when there is nothing to distribute
     * or the span is not positive: never a fabricated word count.
     */
    fun proportionalEstimate(cueText: String, startMs: Long, endMs: Long): List<TimedWord>? {
        val wordCount = cueText.split(WHITESPACE).count { it.isNotBlank() }
        if (wordCount == 0 || endMs <= startMs) return null
        val step = (endMs - startMs) / wordCount
        return List(wordCount) { i ->
            // The last word closes at the cue's measured end, not at a
            // rounding remainder of the even step.
            val end = if (i == wordCount - 1) endMs else startMs + step * (i + 1)
            TimedWord(startMs + step * i, end)
        }
    }

    /** U+2581 for sentencepiece vocabs, a leading space for whisper's BPE. */
    private fun opensWord(text: String): Boolean =
        text.startsWith(WORD_START) || text[0].isWhitespace()

    /** A pure separator (only whitespace or markers) is not a word and has no span a group may inherit. */
    private fun hasWordText(text: String): Boolean =
        text.any { !it.isWhitespace() && it != WORD_START.single() }
}

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
