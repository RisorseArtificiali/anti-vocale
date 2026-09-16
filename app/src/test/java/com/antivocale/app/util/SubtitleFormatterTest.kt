package com.antivocale.app.util

import com.antivocale.app.transcription.TimedSegment
import org.junit.Assert.assertEquals
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
}
