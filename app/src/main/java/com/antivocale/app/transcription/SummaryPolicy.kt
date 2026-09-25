package com.antivocale.app.transcription

/**
 * TASK-121.4: the summary-pass decision, single source. Pure Kotlin, no
 * Android imports, so the whole policy is JVM-testable.
 *
 * The pass chains Gemma after a long transcription and attaches a short
 * summary as metadata; the delivered transcript is never replaced. Like the
 * punctuation pass, every skip path exists to avoid a useless model load:
 * the pass costs a backend swap (unload ASR, load Gemma) plus generation
 * time, so the cheap checks below run BEFORE any of that.
 */
object SummaryPolicy {

    /**
     * Whether this transcript is long enough to be worth a summary. The pass
     * is opt-in and costs a second inference; short voice messages are their
     * own summary, so only genuinely long text (roughly 90+ words) qualifies.
     */
    fun needsSummary(text: String): Boolean = text.trim().length >= MIN_TRANSCRIPT_CHARS

    /**
     * Too-long transcripts cannot fit Gemma's context: skip rather than
     * truncate. Same guard value as the punctuation pass (the same Gemma
     * engine serves both), so the decision delegates to its single source.
     */
    fun withinContextLimit(transcript: String): Boolean =
        PunctuationPolicy.withinContextLimit(transcript)

    /**
     * The pass's effective instruction: the user's saved override, or the
     * built-in default when the override is blank or whitespace. Mirrors
     * [PunctuationPolicy.effectivePrompt] as the single named home of the
     * blank-fallback contract (TASK-483).
     */
    fun effectivePrompt(userPrompt: String, builtInDefault: String): String =
        userPrompt.trim().ifBlank { builtInDefault }

    /**
     * Degenerate-output guard: a summary is SHORTER than its input. Anything
     * below [MIN_SUMMARY_CHARS] is a model stutter, and anything above the
     * transcript scaled by [MAX_SUMMARY_FRACTION] is a rewrite, not a
     * summary; both are dropped (no summary attached, transcript delivered).
     */
    /**
     * TASK-607 F6: a floor for a LONE partial (one map chunk surviving of a
     * multi-chunk transcript): the length guard alone passes a chunk-echo; a
     * lone recap must cover at least [MIN_LONE_PARTIAL_COVERAGE_FRACTION] of
     * the transcript to count as a summary of it.
     */
    fun hasCoverageFloor(lonePartial: String, transcriptLength: Int): Boolean =
        lonePartial.length >= (transcriptLength * MIN_LONE_PARTIAL_COVERAGE_FRACTION).toInt().coerceAtLeast(MIN_SUMMARY_CHARS)

    fun acceptableSummary(summary: String, transcript: String): Boolean {
        if (summary.length < MIN_SUMMARY_CHARS) return false
        return summary.length <= transcript.length * MAX_SUMMARY_FRACTION
    }

    /**
     * TASK-659: recognizes the summary engine's state-entry overflow. The
     * map stage budgets chunks in CHARS while a fresh LiteRT session holds
     * a bounded number of TOKEN state entries, so a chunk from a script
     * with a worse chars-per-token ratio (Thai, Indic, CJK) can overflow
     * even a fresh session; the JNI surfaces it as LiteRtLmJniException
     * "Prefill input length exceeds available state entries" (device
     * evidence 2026-09-25), possibly wrapped, so the message chain is
     * walked. The substring is the ONLY signal the engine gives; if
     * sherpa-onnx/LiteRT ever exposes a typed error, switch there.
     */
    fun isPrefillOverflow(failure: Throwable?): Boolean {
        // Cause chain AND suppressed exceptions (a wrapping layer may attach
        // the real JNI failure as suppressed, the LlmManager cancellation
        // path already uses addSuppressed), with a depth cap against cyclic
        // chains some wrapping frameworks construct: the summary pass must
        // never hang on a pathological chain.
        fun match(t: Throwable?, depth: Int): Boolean {
            if (t == null || depth > 8) return false
            if (t.message?.contains(PREFILL_OVERFLOW_SIGNAL) == true) return true
            if (match(t.cause, depth + 1)) return true
            return t.suppressed.any { match(it, depth + 1) }
        }
        return match(failure, 0)
    }

    /**
     * TASK-659: the pieces one adaptive re-split of [text] yields at [budget]
     * chars, with a tail piece too short to ever pass the summary guards
     * merged into its predecessor (a sub-floor tail would burn a full
     * generation and then count as a dropped chunk). Shared by the map loop
     * and the tests so the formula cannot drift.
     */
    fun reSplitPieces(text: String, budget: Int): List<String> {
        var pieces = ContextChunker.split(text, budget)
        if (pieces.size > 1 &&
            pieces.last().length * MAX_SUMMARY_FRACTION < MIN_SUMMARY_CHARS
        ) {
            // The splitter consumed the boundary whitespace (cut + 1), so the
            // merge restores a separator: bare concatenation would fuse words
            // in every spaced script (review F3).
            pieces = pieces.dropLast(1).let { it.dropLast(1) + (it.last() + " " + pieces.last()) }
        }
        return pieces
    }

    /** Below this the transcript is an ordinary message: nothing to summarize. */
    const val MIN_TRANSCRIPT_CHARS = 600

    /** Floor for a plausible summary; see [acceptableSummary]. */
    const val MIN_SUMMARY_CHARS = 20

    /** TASK-607 F6: the lone-partial coverage floor fraction. */
    const val MIN_LONE_PARTIAL_COVERAGE_FRACTION = 0.25

    /** Stable DB tokens for why an attended summary attempt produced none.
     *  Rendered localized at the single LogsTab caption mapping; never
     *  persisted as user text. */
    const val SKIP_REASON_GUARDS = "guards"
    const val SKIP_REASON_CONTEXT = "context_limit" // legacy-read only: TASK-520
        // replaced the skip with map-reduce; existing rows still render it
    const val SKIP_REASON_NO_MODEL = "no_model"
    const val SKIP_REASON_FAILED = "failed"

    /** Ceiling relative to the transcript; see [acceptableSummary]. */
    const val MAX_SUMMARY_FRACTION = 1.2

    /** TASK-659: the LiteRT JNI message on state-entry overflow; see
     *  [isPrefillOverflow] (the only signal the engine gives). */
    // Anchored to the full fixed phrase (the truncated prefix would also
    // match a different future limit sharing the preamble); this is the
    // only signal the engine gives (device wording 2026-09-25).
    const val PREFILL_OVERFLOW_SIGNAL = "exceeds available state entries"
}
