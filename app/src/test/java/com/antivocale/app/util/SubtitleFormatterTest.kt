package com.antivocale.app.util

import com.antivocale.app.transcription.TimedSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleFormatterTest {

    private val twoCues = listOf(
        TimedSegment(startMs = 0, endMs = 4_000, text = "prima frase"),
        TimedSegment(startMs = 61_500, endMs = 3_661_230, text = "seconda frase"),
    )

    @Test
    fun srt_cueSyntaxExact() {
        val expected = "1\n00:00:00,000 --> 00:00:04,000\nprima frase\n\n" +
            "2\n00:01:01,500 --> 01:01:01,230\nseconda frase\n"
        assertEquals(expected, SubtitleFormatter.srt(twoCues))
    }

    @Test
    fun vtt_headerAndSyntaxExact() {
        val expected = "WEBVTT\n\n" +
            "00:00:00.000 --> 00:00:04.000\nprima frase\n\n" +
            "00:01:01.500 --> 01:01:01.230\nseconda frase\n"
        assertEquals(expected, SubtitleFormatter.vtt(twoCues))
    }

    @Test
    fun timedTxt_shortClock() {
        val expected = "0:00-0:04 prima frase\n1:01-1:01:01 seconda frase"
        assertEquals(expected, SubtitleFormatter.timedTxt(twoCues))
    }

    @Test
    fun emptyList_rendersEmpty() {
        assertEquals("", SubtitleFormatter.srt(emptyList()))
        assertEquals("WEBVTT\n\n", SubtitleFormatter.vtt(emptyList()))
        assertEquals("", SubtitleFormatter.timedTxt(emptyList()))
    }

    @Test
    fun singleSegment_rendersSingleCue() {
        val srt = SubtitleFormatter.srt(listOf(TimedSegment(1_000, 2_000, "uno")))
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nuno\n", srt)
    }

    @Test
    fun msEdges_zeroAndSubSecondEnd() {
        val srt = SubtitleFormatter.srt(listOf(TimedSegment(0, 999, "x")))
        assertEquals("1\n00:00:00,000 --> 00:00:00,999\nx\n", srt)
    }

    @Test
    fun resolveExport_txtDefault_neverTouchesContent() {
        val decision = SubtitleFormatter.resolveExport(
            SubtitleFormatter.Format.TXT, "plain text", twoCues, failedChunkCount = 0)
        assertEquals(SubtitleFormatter.Format.TXT, decision.format)
        assertEquals("plain text", decision.content)
    }

    @Test
    fun resolveExport_timedFormatWithoutSegments_fallsBackToTxt() {
        val decision = SubtitleFormatter.resolveExport(
            SubtitleFormatter.Format.SRT, "plain text", emptyList(), failedChunkCount = 0)
        assertEquals(SubtitleFormatter.Format.TXT, decision.format)
        assertEquals("plain text", decision.content)
    }

    @Test
    fun resolveExport_failedChunks_fallBackToTxtWithNote() {
        val decision = SubtitleFormatter.resolveExport(
            SubtitleFormatter.Format.VTT, "partial text", twoCues, failedChunkCount = 3)
        assertEquals(SubtitleFormatter.Format.TXT, decision.format)
        assertEquals("partial text\n[3 chunks failed; timestamps omitted]", decision.content)
    }

    @Test
    fun resolveExport_validTimedRequest_usesSelectedFormat() {
        val decision = SubtitleFormatter.resolveExport(
            SubtitleFormatter.Format.TXT_TIMED, "ignored", twoCues, failedChunkCount = 0)
        assertEquals(SubtitleFormatter.Format.TXT_TIMED, decision.format)
        assertEquals(SubtitleFormatter.timedTxt(twoCues), decision.content)
    }

    @Test
    fun fromStored_unknownValue_fallsBackToTxt() {
        assertEquals(SubtitleFormatter.Format.TXT, SubtitleFormatter.Format.fromStored(null))
        assertEquals(SubtitleFormatter.Format.TXT, SubtitleFormatter.Format.fromStored("NOPE"))
        assertEquals(SubtitleFormatter.Format.VTT, SubtitleFormatter.Format.fromStored("VTT"))
    }

    // GH #83: the in-app display text uses the export turn convention.

    private fun labeledCue(start: Long, speaker: Int?, text: String) =
        TimedSegment(startMs = start, endMs = start + 3_000, text = text, speaker = speaker)

    @Test
    fun speakerAnnotated_prefixesTurnStartsAndSkipsContinuations() {
        val annotated = SubtitleFormatter.speakerAnnotated(
            listOf(
                labeledCue(0, 0, "Ciao."),
                labeledCue(4_000, 0, "Come va?"),
                labeledCue(9_000, 1, "Bene, tu?"),
                labeledCue(13_000, null, "..."),
                labeledCue(17_000, 0, "A dopo."),
            ))
        assertEquals("SPEAKER 1: Ciao.\nCome va?\nSPEAKER 2: Bene, tu?\n...\nSPEAKER 1: A dopo.", annotated)
    }

    @Test
    fun speakerAnnotated_nullWhenNoCueIsLabeled() {
        assertNull(
            SubtitleFormatter.speakerAnnotated(
                listOf(
                    TimedSegment(0, 3_000, "no labels"),
                    TimedSegment(4_000, 7_000, "plain row"),
                )))
        assertNull(SubtitleFormatter.speakerAnnotated(emptyList()))
    }

    // TASK-598 F2: the punctuation pass's output re-texts the cues.

    @Test
    fun alignPolishedText_match_retextsCuesAndKeepsTimingAndSpeakers() {
        val aligned = SubtitleFormatter.alignPolishedText(
            "Prima frase. Seconda, frase?",
            listOf(
                labeledCue(0, 0, "prima frase"),
                labeledCue(4_000, 1, "seconda frase"),
            ))
        assertEquals(
            listOf(
                TimedSegment(0, 3_000, "Prima frase.", 0),
                TimedSegment(4_000, 7_000, "Seconda, frase?", 1),
            ), aligned)
    }

    @Test
    fun alignPolishedText_wordCountMismatch_returnsNull() {
        assertNull(
            SubtitleFormatter.alignPolishedText(
                "una frase sola",
                listOf(
                    labeledCue(0, 0, "prima frase"),
                    labeledCue(4_000, 1, "seconda frase"),
                )))
    }

    @Test
    fun alignPolishedText_emptyPolishedAndCues_alignsToEmpty() {
        assertEquals(emptyList<TimedSegment>(), SubtitleFormatter.alignPolishedText("", emptyList()))
    }

    @Test
    fun alignPolishedText_emptyPolishedWithCues_returnsNull() {
        assertNull(SubtitleFormatter.alignPolishedText("", listOf(labeledCue(0, 0, "parola"))))
    }

    @Test
    fun alignPolishedText_singleCue_takesWholeText() {
        // Irregular whitespace collapses: the slice is the word sequence.
        val aligned = SubtitleFormatter.alignPolishedText(
            "  Ciao,  come va? ", listOf(labeledCue(0, 1, "ciao come va")))
        assertEquals(listOf(TimedSegment(0, 3_000, "Ciao, come va?", 1)), aligned)
    }

    @Test
    fun speakerAnnotated_polishedTextAligned_ridesPunctuationIntoTurns() {
        val annotated = SubtitleFormatter.speakerAnnotated(
            listOf(
                labeledCue(0, 0, "ciao come va"),
                labeledCue(4_000, 1, "bene tu"),
            ),
            polishedText = "Ciao, come va? Bene, tu?")
        assertEquals("SPEAKER 1: Ciao, come va?\nSPEAKER 2: Bene, tu?", annotated)
    }

    @Test
    fun alignPolishedText_compensatingMergeSplit_fallsBackNotMisattributes() {
        // Code review F1: total word count matches (5==5) but "gonna" split
        // into "Going to" while "all right" merged to "Alright"; the strict
        // per-cue letters check fails and the WHOLE alignment falls back to
        // the raw cue texts instead of shifting "stay." onto SPEAKER 2.
        assertNull(
            SubtitleFormatter.alignPolishedText(
                "Going to stay. Alright, man?",
                listOf(
                    labeledCue(0, 0, "gonna stay"),
                    labeledCue(4_000, 1, "all right man"),
                )))
    }

    @Test
    fun alignPolishedText_punctuationAndCaseOnlyChange_aligns() {
        // The contract's happy path: same letters per cue, new punctuation.
        val aligned = SubtitleFormatter.alignPolishedText(
            "Prima frase! Seconda, FRASE?",
            listOf(
                labeledCue(0, 0, "prima frase"),
                labeledCue(4_000, 1, "seconda frase"),
            ))
        assertEquals(
            listOf(
                TimedSegment(0, 3_000, "Prima frase!", 0),
                TimedSegment(4_000, 7_000, "Seconda, FRASE?", 1),
            ), aligned)
    }

    @Test
    fun speakerAnnotated_polishedTextMismatch_fallsBackToCueTexts() {
        val annotated = SubtitleFormatter.speakerAnnotated(
            listOf(
                labeledCue(0, 0, "ciao"),
                labeledCue(4_000, 1, "bene"),
            ),
            polishedText = "una frase del tutto diversa")
        assertEquals("SPEAKER 1: ciao\nSPEAKER 2: bene", annotated)
    }

    @Test
    fun speakerAnnotated_polishedTextStillNullWithoutLabels() {
        assertNull(
            SubtitleFormatter.speakerAnnotated(
                listOf(TimedSegment(0, 3_000, "no labels")),
                polishedText = "No labels."))
    }

    @Test
    fun candidateCheckBehaviorThroughTheEntryPoints() {
        val cues = listOf(
            labeledCue(0, 0, "prima frase"),
            labeledCue(4_000, 1, "seconda frase"),
        )
        // stored text IS the cues' join: no re-texting, the cues render as-is
        assertEquals(
            "SPEAKER 1: prima frase\nSPEAKER 2: seconda frase",
            SubtitleFormatter.nullableAnnotated("prima frase seconda frase", cues))
        // polished text: aligned cue texts carry the punctuation
        assertEquals(
            "SPEAKER 1: Prima frase.\nSPEAKER 2: Seconda frase.",
            SubtitleFormatter.nullableAnnotated("Prima frase. Seconda frase.", cues))
        // null stored text: nothing to fall back to, no annotated form
        assertNull(SubtitleFormatter.nullableAnnotated(null, cues))
    }

    // TASK-598 F5: the plain-txt export arms carry the turns.

    @Test
    fun resolveExport_txtArm_carriesAnnotatedTurnsWhenLabeled() {
        val decision = SubtitleFormatter.resolveExport(
            SubtitleFormatter.Format.TXT,
            "Ciao come va? Bene, tu?",
            listOf(
                labeledCue(0, 0, "ciao come va"),
                labeledCue(4_000, 1, "bene tu"),
            ),
            failedChunkCount = 0)
        assertEquals(SubtitleFormatter.Format.TXT, decision.format)
        assertEquals("SPEAKER 1: Ciao come va?\nSPEAKER 2: Bene, tu?", decision.content)
    }

    @Test
    fun resolveExport_failedChunksLabeled_noteRidesTheAnnotatedTxt() {
        val decision = SubtitleFormatter.resolveExport(
            SubtitleFormatter.Format.SRT,
            "ciao come va",
            listOf(labeledCue(0, 0, "ciao come va")),
            failedChunkCount = 2)
        assertEquals(SubtitleFormatter.Format.TXT, decision.format)
        assertEquals("SPEAKER 1: ciao come va\n[2 chunks failed; timestamps omitted]", decision.content)
    }
}
