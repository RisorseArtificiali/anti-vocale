package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TASK-910: long pauses become paragraph breaks. The gap between sentence
 * cues decides the separator; the alignment gate is the safety net.
 */
class ParagraphBreaksTest {

    private fun cue(start: Long, end: Long, text: String) =
        TimedSegment(startMs = start, endMs = end, text = text)

    @Test
    fun `a gap past the threshold breaks the paragraph`() {
        val cues = listOf(
            cue(0, 4_000, "First sentence."),
            cue(6_000, 9_000, "Second sentence."),
        )
        val text = "First sentence. Second sentence."
        assertEquals("First sentence.\n\nSecond sentence.", ParagraphBreaks.apply(cues, text))
    }

    @Test
    fun `an ordinary sentence pause stays a single space`() {
        val cues = listOf(
            cue(0, 4_000, "First sentence."),
            cue(4_800, 9_000, "Second sentence."),
        )
        val text = "First sentence. Second sentence."
        assertEquals(text, ParagraphBreaks.apply(cues, text))
    }

    @Test
    fun `the boundary gap is exactly the threshold`() {
        val cues = listOf(
            cue(0, 4_000, "A."),
            cue(5_500, 6_000, "B."),
        )
        assertEquals("A.\n\nB.", ParagraphBreaks.apply(cues, "A. B."))
    }

    @Test
    fun `multiple paragraphs break at every long gap`() {
        val cues = listOf(
            cue(0, 1_000, "One."),
            cue(3_000, 4_000, "Two."),
            cue(4_500, 5_500, "Three."),
            cue(9_000, 10_000, "Four."),
        )
        assertEquals("One.\n\nTwo. Three.\n\nFour.", ParagraphBreaks.apply(cues, "One. Two. Three. Four."))
    }

    @Test
    fun `no qualifying gap leaves the text untouched even with irregular whitespace`() {
        // Review F1: sub-threshold gaps + a recognizer newline between the
        // sentences; there is nothing to add, so the text (and its own
        // formatting) passes through untouched.
        val cues = listOf(
            cue(0, 4_000, "First sentence."),
            cue(4_800, 9_000, "Second sentence."),
        )
        val text = "First sentence.\nSecond sentence."
        assertEquals(text, ParagraphBreaks.apply(cues, text))
    }

    @Test
    fun `misaligned cues degrade to the text unchanged`() {
        // The cues do not carry the transcript's words: no rebuild, ever.
        val cues = listOf(
            cue(0, 4_000, "First sentence."),
            cue(6_000, 9_000, "DIFFERENT words entirely."),
        )
        val text = "First sentence. Second sentence."
        assertEquals(text, ParagraphBreaks.apply(cues, text))
    }

    @Test
    fun `whitespace differences inside cues do not block the rebuild`() {
        // The recognizer's text carries a stray double space; the gate
        // tolerates it, and the cue's own text is preserved verbatim (the
        // internal double space survives the rebuild, as asserted below).
        val cues = listOf(
            cue(0, 4_000, "First  sentence."),
            cue(6_000, 9_000, "Second sentence."),
        )
        assertEquals("First  sentence.\n\nSecond sentence.", ParagraphBreaks.apply(cues, "First  sentence. Second sentence."))
    }

    @Test
    fun `fewer than two cues or blank text pass through`() {
        val single = listOf(cue(0, 4_000, "Only one."))
        assertEquals("Only one.", ParagraphBreaks.apply(single, "Only one."))
        assertEquals("", ParagraphBreaks.apply(emptyList(), ""))
        assertEquals("  ", ParagraphBreaks.apply(listOf(cue(0, 1, "a"), cue(9_999, 10_000, "b")), "  "))
    }

    @Test
    fun `negative or overlapping gaps never break`() {
        // Defensive: overlapping cues (which should not exist) read as a
        // short gap, never a paragraph.
        val cues = listOf(
            cue(0, 5_000, "A."),
            cue(4_000, 6_000, "B."),
        )
        assertEquals("A. B.", ParagraphBreaks.apply(cues, "A. B."))
    }
}
