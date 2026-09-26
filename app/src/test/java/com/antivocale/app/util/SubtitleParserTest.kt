package com.antivocale.app.util

import com.antivocale.app.transcription.TimedSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TASK-677 (GH #92, import half): the lenient SRT/VTT parse side, its
 * never-throw contract, and the AC#4 round trip (import(export(t)) == t)
 * over the export pair's real cue shapes.
 */
class SubtitleParserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cue(start: Long, end: Long, text: String, speaker: Int? = null) =
        TimedSegment(start, end, text, speaker)

    // ---- Canonical shapes ----

    @Test
    fun canonicalSrt_parsesCues() {
        val srt = "1\n" +
            "00:00:01,000 --> 00:00:03,500\n" +
            "prima frase\n" +
            "\n" +
            "2\n" +
            "00:01:04,250 --> 00:01:05,000\n" +
            "seconda frase\n"
        val result = SubtitleParser.parse(srt)
        assertNull(result.errorNote)
        assertEquals(0, result.skippedCues)
        assertEquals(
            listOf(cue(1000, 3500, "prima frase"), cue(64_250, 65_000, "seconda frase")),
            result.segments)
    }

    @Test
    fun bomAndCrlfAndCrAllParse() {
        assertEquals(
            listOf(cue(0, 1000, "a")),
            SubtitleParser.parse("﻿1\r\n00:00:00,000 --> 00:00:01,000\r\na\r\n").segments)
        assertEquals(
            listOf(cue(0, 1000, "a")),
            SubtitleParser.parse("1\r00:00:00,000 --> 00:00:01,000\ra\r").segments)
    }

    @Test
    fun missingCueNumbers_parse() {
        val srt = "00:00:00,000 --> 00:00:01,000\na\n\n00:00:01,000 --> 00:00:02,000\nb\n"
        assertEquals(listOf(cue(0, 1000, "a"), cue(1000, 2000, "b")), SubtitleParser.parse(srt).segments)
    }

    @Test
    fun duplicatedCueNumbers_parse() {
        val srt = "1\n00:00:00,000 --> 00:00:01,000\na\n\n" +
            "1\n00:00:01,000 --> 00:00:02,000\nb\n"
        assertEquals(listOf(cue(0, 1000, "a"), cue(1000, 2000, "b")), SubtitleParser.parse(srt).segments)
    }

    @Test
    fun dotDecimalsInSrt_parse() {
        val srt = "1\n00:00:01.500 --> 00:00:02.750\na\n"
        assertEquals(listOf(cue(1500, 2750, "a")), SubtitleParser.parse(srt).segments)
    }

    @Test
    fun blankSeparatorlessSrt_parsesViaNumberLookahead() {
        // Blocks with no blank line between them: the number line directly
        // above the next timing line closes the previous cue.
        val srt = "1\n00:00:00,000 --> 00:00:01,000\na\n2\n00:00:01,000 --> 00:00:02,000\nb\n"
        assertEquals(listOf(cue(0, 1000, "a"), cue(1000, 2000, "b")), SubtitleParser.parse(srt).segments)
    }

    // ---- WebVTT shapes ----

    @Test
    fun vtt_headerNoteIdentifierAndSettings_parse() {
        val vtt = "WEBVTT\n" +
            "\n" +
            "NOTE\n" +
            "this is a comment\n" +
            "\n" +
            "intro-cue\n" +
            "00:00:01.000 --> 00:00:02.000 align:start position:10%\n" +
            "hello\n" +
            "\n" +
            "00:02.500 --> 00:04.000\n" +
            "hours-less clock\n"
        val result = SubtitleParser.parse(vtt)
        assertNull(result.errorNote)
        assertEquals(listOf(cue(1000, 2000, "hello"), cue(2500, 4000, "hours-less clock")), result.segments)
    }

    @Test
    fun vtt_withoutHeader_parses() {
        val vtt = "00:00:00.000 --> 00:00:01.000\na\n"
        assertEquals(listOf(cue(0, 1000, "a")), SubtitleParser.parse(vtt).segments)
    }

    // ---- Lenience ----

    @Test
    fun arrowJunk_isAbsorbed() {
        // No spaces around the arrow, extra spaces, junk tokens on both sides.
        assertEquals(
            listOf(cue(1000, 4000, "a")),
            SubtitleParser.parse("00:00:01,000-->00:00:04,000\na\n").segments)
        assertEquals(
            listOf(cue(1000, 4000, "a")),
            SubtitleParser.parse("  x  00:00:01,000   -->   00:00:04,000  junk\na\n").segments)
    }

    @Test
    fun fractionDigits_areFractionalMilliseconds() {
        assertEquals(1500L, SubtitleParser.parseTimestamp("00:00:01,5"))
        assertEquals(1050L, SubtitleParser.parseTimestamp("00:00:01,05"))
        assertEquals(1005L, SubtitleParser.parseTimestamp("00:00:01,005"))
    }

    @Test
    fun invertedRange_clampsToStart() {
        assertEquals(
            listOf(cue(2000, 2000, "a")),
            SubtitleParser.parse("00:00:02,000 --> 00:00:01,000\na\n").segments)
    }

    @Test
    fun multilineCueText_joinsWithSpaces() {
        val srt = "1\n00:00:00,000 --> 00:00:02,000\nline one\nline two\n\n  \n"
        assertEquals(listOf(cue(0, 2000, "line one line two")), SubtitleParser.parse(srt).segments)
    }

    @Test
    fun blankTextCue_isCountedSkipped() {
        val srt = "1\n00:00:00,000 --> 00:00:01,000\n\n\n2\n00:00:01,000 --> 00:00:02,000\nb\n"
        val result = SubtitleParser.parse(srt)
        assertEquals(listOf(cue(1000, 2000, "b")), result.segments)
        assertEquals(1, result.skippedCues)
        assertNotNull(result.errorNote)
    }

    @Test
    fun brokenTimingLine_isCounted_andParsingResynchronizes() {
        val srt = "1\n" +
            "garbage --> not-a-clock\n" +
            "lost text\n" +
            "\n" +
            "2\n" +
            "00:00:05,000 --> 00:00:06,000\n" +
            "kept\n"
        val result = SubtitleParser.parse(srt)
        assertEquals(listOf(cue(5000, 6000, "kept")), result.segments)
        assertEquals(1, result.skippedCues)
        assertNotNull(result.errorNote)
    }

    @Test
    fun garbage_neverThrows_andYieldsEmpty() {
        for (garbage in listOf("", "just some prose\nwith no cues at all", "1\n2\n3\n", "﻿", "```json {}")) {
            val result = SubtitleParser.parse(garbage)
            assertTrue("input <$garbage> must parse to empty", result.segments.isEmpty())
            assertNotNull("input <$garbage> must carry a note", result.errorNote)
        }
    }

    @Test
    fun parseFile_missingOrNullPath_degradesToEmptyWithNote() {
        val result = SubtitleParser.parseFile("/nonexistent/path/subtitle.srt")
        assertTrue(result.segments.isEmpty())
        assertNotNull(result.errorNote)
        assertTrue(SubtitleParser.parseFile(null).segments.isEmpty())
    }

    @Test
    fun parseFile_readsRealFile() {
        val f = tmp.newFile("sample.srt")
        f.writeText("1\n00:00:00,000 --> 00:00:01,000\nfrom disk\n")
        assertEquals(listOf(cue(0, 1000, "from disk")), SubtitleParser.parseFile(f.absolutePath).segments)
    }

    // ---- Speaker labels (GH #83 export idiom) ----

    @Test
    fun speakerPrefix_parses_andPropagatesThroughTheTurn() {
        // The export labels only the FIRST cue of a turn; the rest of the
        // run inherits (that is the AC#4 contract for labeled exports).
        val srt = "1\n00:00:00,000 --> 00:00:01,000\nSPEAKER 1: a\n\n" +
            "2\n00:00:01,000 --> 00:00:02,000\nb\n\n" +
            "3\n00:00:02,000 --> 00:00:03,000\nSPEAKER 2: c\n"
        assertEquals(
            listOf(cue(0, 1000, "a", 0), cue(1000, 2000, "b", 0), cue(2000, 3000, "c", 1)),
            SubtitleParser.parse(srt).segments)
    }

    @Test
    fun foreignSpeakerZeroPrefix_staysLiteralText() {
        val srt = "1\n00:00:00,000 --> 00:00:01,000\nSPEAKER 0: a\n"
        assertEquals(listOf(cue(0, 1000, "SPEAKER 0: a", null)), SubtitleParser.parse(srt).segments)
    }

    // ---- AC#4: import(export(t)) == t ----

    @Test
    fun roundTrip_plainCues_srtAndVtt() {
        val cues = listOf(
            cue(0, 4_000, "prima frase"),
            cue(61_500, 3_661_230, "seconda frase"),
        )
        assertEquals(cues, SubtitleParser.parse(SubtitleFormatter.srt(cues)).segments)
        assertEquals(cues, SubtitleParser.parse(SubtitleFormatter.vtt(cues)).segments)
    }

    @Test
    fun roundTrip_unicodeText_srtAndVtt() {
        val cues = listOf(
            cue(0, 1500, "¿Qué tal? Café naïve 中文"),
            cue(2000, 3500, "العربية עברית ελληνικά"),
        )
        assertEquals(cues, SubtitleParser.parse(SubtitleFormatter.srt(cues)).segments)
        assertEquals(cues, SubtitleParser.parse(SubtitleFormatter.vtt(cues)).segments)
    }

    @Test
    fun roundTrip_speakerTurns_srtAndVtt() {
        val cues = listOf(
            cue(0, 1000, "a", 0),
            cue(1500, 2000, "b", 0),
            cue(2500, 3000, "c", 1),
            cue(3500, 4000, "d", 1),
        )
        assertEquals(cues, SubtitleParser.parse(SubtitleFormatter.srt(cues)).segments)
        assertEquals(cues, SubtitleParser.parse(SubtitleFormatter.vtt(cues)).segments)
    }

    @Test
    fun roundTrip_longHours_srtAndVtt() {
        val cues = listOf(cue(0, 500, "x"), cue(3_723_401_000L, 3_723_402_000L, "past 1000h"))
        assertEquals(cues, SubtitleParser.parse(SubtitleFormatter.srt(cues)).segments)
        assertEquals(cues, SubtitleParser.parse(SubtitleFormatter.vtt(cues)).segments)
    }

    @Test
    fun roundTrip_isStableForFileMultilineCues() {
        // A foreign file's two-line cue becomes one joined segment; the
        // export of THAT re-imports identically (stability, the honest
        // form of the round trip for inputs we normalize).
        val foreign = "1\n00:00:00,000 --> 00:00:02,000\nline one\nline two\n"
        val once = SubtitleParser.parse(foreign).segments
        assertEquals(listOf(cue(0, 2000, "line one line two")), once)
        assertEquals(once, SubtitleParser.parse(SubtitleFormatter.srt(once)).segments)
        assertEquals(once, SubtitleParser.parse(SubtitleFormatter.vtt(once)).segments)
    }
}
