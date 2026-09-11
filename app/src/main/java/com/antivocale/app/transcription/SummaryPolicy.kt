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
    fun acceptableSummary(summary: String, transcript: String): Boolean {
        if (summary.length < MIN_SUMMARY_CHARS) return false
        return summary.length <= transcript.length * MAX_SUMMARY_FRACTION
    }

    /** Below this the transcript is an ordinary message: nothing to summarize. */
    const val MIN_TRANSCRIPT_CHARS = 600

    /** Floor for a plausible summary; see [acceptableSummary]. */
    const val MIN_SUMMARY_CHARS = 20

    /** Stable DB tokens for why an attended summary attempt produced none.
     *  Rendered localized at the single LogsTab caption mapping; never
     *  persisted as user text. */
    const val SKIP_REASON_GUARDS = "guards"
    const val SKIP_REASON_CONTEXT = "context_limit"
    const val SKIP_REASON_NO_MODEL = "no_model"
    const val SKIP_REASON_FAILED = "failed"

    /** Ceiling relative to the transcript; see [acceptableSummary]. */
    const val MAX_SUMMARY_FRACTION = 1.2
}
