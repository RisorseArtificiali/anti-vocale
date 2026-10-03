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

    /**
     * TASK-666: CONSERVATIVE is the bounded-cleanup mode of the cleanup
     * contract (off | conservative | inherit). The contract's "inherit"
     * is AUTO/ALWAYS. The real inherited delta is PERMISSION versus
     * ENFORCEMENT: ALWAYS honors a user prompt override and ships no
     * output validation; CONSERVATIVE pins its fenced prompt (the
     * override is ignored) and validates the word sequence
     * ([conservativeAcceptable], which states the full contract at its
     * enforcement point).
     */
    enum class Mode { OFF, AUTO, ALWAYS, CONSERVATIVE }

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
        val words = wordCount(trimmed)
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
        // TASK-666: the conservative gate is mode-aware - the mode's
        // headline capability is PARAGRAPHING, so punctuated-but-
        // unparagraphed text (typical punctuating-model output) must run
        // too; the punctuation-density arm alone would silently no-op
        // exactly there. Short single-paragraph texts still skip (nothing
        // to paragraph), and the model-punctuates flag is irrelevant for a
        // mode the user picked explicitly.
        Mode.CONSERVATIVE -> needsPunctuation(transcript) || needsParagraphing(transcript)
    }

    /**
     * TASK-666: the conservative mode's paragraph arm. Substantial text
     * (at least [PARAGRAPHING_WORD_FLOOR] words) with no paragraph break
     * yet. The break test is a BLANK LINE (or two newlines with only
     * spaces between), never a single embedded '\\n': recognizer text
     * carries stray newline tokens, and one of them must not disable the
     * mode's headline capability.
     */
    private fun needsParagraphing(text: String): Boolean =
        !PARAGRAPH_BREAK.containsMatchIn(text) && wordCount(text) >= PARAGRAPHING_WORD_FLOOR

    /** Whitespace words of the trimmed text; the object's one counting idiom. */
    private fun wordCount(text: String): Int = WHITESPACE.split(text.trim()).size

    /** Too-long transcripts cannot fit Gemma's context: skip rather than truncate. */
    fun withinContextLimit(transcript: String): Boolean = transcript.length <= MAX_TRANSCRIPT_CHARS

    /**
     * The effective prompt: the user's override, or the localized curated
     * default supplied by the caller (a string resource). The composition
     * with the transcript is [ChunkPromptPolicy.finalPrompt]. TASK-666:
     * CONSERVATIVE never routes through here - the caller pins the fenced
     * conservative default and ignores the override by design (an
     * arbitrary override could break the fences the token validation
     * enforces). [promptIsFenced] is the one predicate every caller must
     * honor for that mode.
     */
    fun effectivePrompt(userPrompt: String, localizedDefault: String): String =
        userPrompt.trim().ifBlank { localizedDefault }

    /**
     * TASK-666: whether this mode PINS the fenced conservative prompt and
     * must bypass both the user override and [effectivePrompt]. The one
     * policy home for the mode-to-prompt dispatch; a future pass variant
     * consults this instead of re-deriving the special case.
     */
    fun promptIsFenced(mode: Mode): Boolean = mode == Mode.CONSERVATIVE

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
        PREF_CONSERVATIVE -> Mode.CONSERVATIVE
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

    /**
     * TASK-666: the CONSERVATIVE acceptance fence. Cleanup may change
     * punctuation, paragraph breaks, casing and spacing, and NOTHING
     * else: the token sequence (each word case-folded and
     * punctuation-stripped via [RepetitionCollapse.tokenKey]; each
     * punctuation-only token kept as a marker) must be IDENTICAL. One
     * added or removed WORD, one reorder, a translated output, or one
     * inserted standalone punctuation token fails here, the cleaned text
     * is discarded whole, and the original stands (the honest fallback,
     * the same class as the summary failure path). The punctuation-marker
     * strictness is deliberate: a stray inserted token that passed a
     * words-only fence would still trip the downstream TASK-598
     * word-count reconciliation and split the diarized surfaces from the
     * stored text. Note the fence is therefore strict about punctuation
     * MERGES too (a cleaned "ciao, come" against an original "ciao ,
     * come" is rejected): conservative keeps the original rather than
     * gambling on the cue reconciliation. Applied ON TOP of
     * [acceptablePolish] (the collapse guard), and it is NOT the whole
     * validation story: downstream, the diarized surfaces still pass the
     * cleaned text through the TASK-598 per-cue reconciliation
     * (alignPolishedText behind the SubtitleFormatter entry pair), which
     * keeps speaker-word attribution honest.
     */
    fun conservativeAcceptable(cleaned: String, original: String): Boolean =
        fenceTokens(cleaned) == fenceTokens(original)

    /**
     * The fence's invariant form: word tokens through the package's one
     * word-identity normalizer ([RepetitionCollapse.tokenKey]; underscores
     * survive it, a behavior-neutral delta for transcripts), with each
     * punctuation-only token kept as a marker so insertions and merges
     * both stay visible.
     */
    private fun fenceTokens(text: String): List<String> =
        text.split(WHITESPACE).map { token -> RepetitionCollapse.tokenKey(token) ?: PUNCT_TOKEN }

    private const val PUNCT_TOKEN = "\u0000punct"

    /** Below this the transcript is a word or two: nothing to punctuate. */
    const val MIN_LENGTH_CHARS = 12

    /** TASK-666: the conservative paragraph arm's word floor; see [needsParagraphing]. */
    const val PARAGRAPHING_WORD_FLOOR = 30

    /** TASK-666: a paragraph break = a blank line (two newlines, spaces allowed between). */
    private val PARAGRAPH_BREAK = Regex("\\n[ \\t]*\\n")

    /** Preference values, the single source every consumer references
     *  (settings dropdown, SPI validation, modeFromPref). */
    const val PREF_OFF = "off"
    const val PREF_AUTO = "auto"
    const val PREF_ALWAYS = "always"
    /** TASK-666: the bounded-cleanup value (see [Mode.CONSERVATIVE]). */
    const val PREF_CONSERVATIVE = "conservative"
    val MODE_PREFS = listOf(PREF_OFF, PREF_AUTO, PREF_ALWAYS, PREF_CONSERVATIVE)

    /** Skip threshold: fewer than one terminal mark per this many words = unpunctuated. */
    const val TERMINALS_PER_WORDS_FLOOR = 40

    /** Gemma context guard; ~8k tokens leaves headroom below 4 chars/token. */
    const val MAX_TRANSCRIPT_CHARS = 12_000

    private val WHITESPACE = Regex("\\s+")

    /** Terminal punctuation across the scripts the catalog serves. */
    private val TERMINALS = listOf('.', '!', '?', '…', '。', '！', '？')
}
