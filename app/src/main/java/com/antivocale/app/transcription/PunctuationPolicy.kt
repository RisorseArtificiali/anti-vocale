package com.antivocale.app.transcription

/**
 * TASK-276: the punctuation pass decision, single source. Pure Kotlin, no
 * Android imports, so the whole policy is JVM-testable.
 *
 * The pass chains Gemma after a non-punctuating ASR model (GigaAM today).
 * Every skip path exists to avoid a useless model load: the pass costs a
 * backend swap (unload ASR, load Gemma) plus generation time, so the cheap
 * checks below run BEFORE any of that.
 */
object PunctuationPolicy {

    enum class Mode { OFF, AUTO, ALWAYS }

    /**
     * Whether this transcript still needs the pass. False (skip) when it
     * already carries terminal punctuation at a plausible density, or is too
     * short to bother. The density floor is deliberately lenient: real
     * punctuated text averages a terminal mark every 8-15 words, so one per
     * [TERMINALS_PER_WORDS_FLOOR] words accepts stray marks (a lone trailing
     * dot from a Whisper chunk) while still skipping genuinely punctuated
     * transcripts.
     */
    fun needsPunctuation(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < MIN_LENGTH_CHARS) return false
        val words = WHITESPACE.split(trimmed).size
        if (words == 0) return false
        // OCCURRENCES, not distinct characters: Latin text uses almost
        // only '.', and a distinct-count would score one mark for a
        // five-sentence paragraph and re-polish it in ALWAYS mode.
        val terminals = trimmed.count { it in TERMINALS }
        return terminals * TERMINALS_PER_WORDS_FLOOR < words
    }

    /**
     * The whole gate in one place: the mode preference, the per-model
     * capability flag, and the transcript itself. AUTO trusts the flag but
     * still checks the text (a model flagged non-punctuating that emits
     * punctuation anyway must not pay the pass); ALWAYS runs for any model
     * (normalize Whisper/Parakeet output too) but still respects the text
     * check, so already-punctuated input never re-pays.
     */
    fun shouldRun(mode: Mode, modelPunctuates: Boolean, transcript: String): Boolean = when (mode) {
        Mode.OFF -> false
        Mode.AUTO -> !modelPunctuates && needsPunctuation(transcript)
        Mode.ALWAYS -> needsPunctuation(transcript)
    }

    /** Too-long transcripts cannot fit Gemma's context: skip rather than truncate. */
    fun withinContextLimit(transcript: String): Boolean = transcript.length <= MAX_TRANSCRIPT_CHARS

    /**
     * The effective prompt: the user's override, or the localized curated
     * default supplied by the caller (a string resource). The composition
     * with the transcript is [ChunkPromptPolicy.finalPrompt].
     */
    fun effectivePrompt(userPrompt: String, localizedDefault: String): String =
        userPrompt.trim().ifBlank { localizedDefault }

    /**
     * Preference parsing, lenient by design: the value comes from disk where
     * legacy or hand-edited junk is possible, and AUTO is the safe default
     * (worst case it trusts the per-model flag, never runs for models that
     * punctuate). Strictness lives in the SPI layer, which rejects unknown
     * values at write time.
     */
    fun modeFromPref(value: String): Mode = when (value) {
        PREF_OFF -> Mode.OFF
        PREF_ALWAYS -> Mode.ALWAYS
        else -> Mode.AUTO
    }

    /**
     * Degenerate-output guard: punctuation may ADD characters, never collapse
     * them. Below [MIN_POLISHED_FRACTION] of the original length the polished
     * text is a model stutter or a truncation, and the original is kept.
     */
    fun acceptablePolish(polished: String, original: String): Boolean =
        polished.length >= original.length * MIN_POLISHED_FRACTION

    /** Collapse guard threshold; see [acceptablePolish]. */
    const val MIN_POLISHED_FRACTION = 0.4

    /** Below this the transcript is a word or two: nothing to punctuate. */
    const val MIN_LENGTH_CHARS = 12

    /** Preference values, the single source every consumer references
     *  (settings dropdown, SPI validation, modeFromPref). */
    const val PREF_OFF = "off"
    const val PREF_AUTO = "auto"
    const val PREF_ALWAYS = "always"
    val MODE_PREFS = listOf(PREF_OFF, PREF_AUTO, PREF_ALWAYS)

    /** Skip threshold: fewer than one terminal mark per this many words = unpunctuated. */
    const val TERMINALS_PER_WORDS_FLOOR = 40

    /** Gemma context guard; ~8k tokens leaves headroom below 4 chars/token. */
    const val MAX_TRANSCRIPT_CHARS = 12_000

    private val WHITESPACE = Regex("\\s+")

    /** Terminal punctuation across the scripts the catalog serves. */
    private val TERMINALS = listOf('.', '!', '?', '…', '。', '！', '？')
}
