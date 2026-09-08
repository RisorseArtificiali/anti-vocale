package com.antivocale.app.transcription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-121.4: pins the summary-pass gate. The skip paths are the point:
 * every one of them avoids loading Gemma for nothing, and the delivered
 * transcript must never depend on the summary's quality.
 */
class SummaryPolicyTest {

    /** Comfortably over [SummaryPolicy.MIN_TRANSCRIPT_CHARS], built so it stays
     *  that way if the threshold moves (the first test asserts the length). */
    private val longTranscript = buildString {
        while (length <= SummaryPolicy.MIN_TRANSCRIPT_CHARS) {
            append("la riunione di oggi ha coperto il budget, le scadenze e i nomi dei responsabili, ")
        }
    }.trim()

    @Test
    fun `needsSummary is true only for genuinely long transcripts`() {
        assertTrue(longTranscript.length > SummaryPolicy.MIN_TRANSCRIPT_CHARS)
        assertTrue(SummaryPolicy.needsSummary(longTranscript))
    }

    @Test
    fun `needsSummary is false for ordinary voice messages`() {
        assertFalse(SummaryPolicy.needsSummary("ciao, ti chiamo dopo"))
        assertFalse(SummaryPolicy.needsSummary("a".repeat(SummaryPolicy.MIN_TRANSCRIPT_CHARS - 1)))
        assertFalse(SummaryPolicy.needsSummary("   "))
    }

    @Test
    fun `needsSummary measures trimmed text`() {
        // Padding must not push a short message over the threshold.
        val padded = "ciao".padEnd(SummaryPolicy.MIN_TRANSCRIPT_CHARS + 10)
        assertFalse(SummaryPolicy.needsSummary(padded))
    }

    @Test
    fun `needsSummary accepts a transcript exactly at the threshold`() {
        assertTrue(SummaryPolicy.needsSummary("a".repeat(SummaryPolicy.MIN_TRANSCRIPT_CHARS)))
    }

    @Test
    fun `context limit skips rather than truncates`() {
        // Delegates to the punctuation pass's single-source guard (same Gemma
        // engine, same limit): a divergence here would mean one pass truncates
        // where the other skips.
        val huge = "a".repeat(PunctuationPolicy.MAX_TRANSCRIPT_CHARS + 1)
        assertFalse(SummaryPolicy.withinContextLimit(huge))
        assertTrue(SummaryPolicy.withinContextLimit("a".repeat(PunctuationPolicy.MAX_TRANSCRIPT_CHARS)))
    }

    @Test
    fun `acceptableSummary accepts a shorter text of plausible length`() {
        assertTrue(SummaryPolicy.acceptableSummary(
            "The speaker plans the project budget for next quarter.", longTranscript))
    }

    @Test
    fun `acceptableSummary rejects a model stutter`() {
        // Below the floor: a stray word or an empty echo is not a summary.
        assertFalse(SummaryPolicy.acceptableSummary("Sì.", longTranscript))
        assertFalse(SummaryPolicy.acceptableSummary("", longTranscript))
    }

    @Test
    fun `acceptableSummary rejects a rewrite that outgrew the transcript`() {
        // A "summary" longer than 1.2x the transcript is a rewrite or a loop.
        val oversized = "x".repeat((longTranscript.length * SummaryPolicy.MAX_SUMMARY_FRACTION).toInt() + 1)
        assertFalse(SummaryPolicy.acceptableSummary(oversized, longTranscript))
        // Exactly at the ceiling is still acceptable (border, not over).
        val atCeiling = "x".repeat((longTranscript.length * SummaryPolicy.MAX_SUMMARY_FRACTION).toInt())
        assertTrue(SummaryPolicy.acceptableSummary(atCeiling, longTranscript))
    }
}
