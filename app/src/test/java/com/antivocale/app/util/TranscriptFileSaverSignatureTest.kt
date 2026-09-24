package com.antivocale.app.util

import org.junit.Assert.*
import org.junit.Test

/**
 * TASK-647: the export's signed content, per format. The timed formats must
 * never take the signature inline (a stray line corrupts cues), and the note
 * line must be sanitized (one line, no '-->') so a multi-line user signature
 * cannot invalidate WebVTT.
 */
class TranscriptFileSaverSignatureTest {

    private fun decision(format: SubtitleFormatter.Format, content: String) =
        SubtitleFormatter.ExportDecision(format, content)

    @Test
    fun `blank signature returns the content unchanged`() {
        assertEquals(
            "ciao",
            TranscriptFileSaver.signedExport(decision(SubtitleFormatter.Format.TXT, "ciao"), "", "append"))
    }

    @Test
    fun `plain text rides the signature inline`() {
        assertEquals(
            "ciao\n-- AI --",
            TranscriptFileSaver.signedExport(
                decision(SubtitleFormatter.Format.TXT, "ciao"), "-- AI --", "append"))
        assertEquals(
            "-- AI --\nciao",
            TranscriptFileSaver.signedExport(
                decision(SubtitleFormatter.Format.TXT, "ciao"), "-- AI --", "prepend"))
    }

    @Test
    fun `vtt takes a NOTE inserted after the mandatory header, never before`() {
        val out = TranscriptFileSaver.signedExport(
            decision(SubtitleFormatter.Format.VTT, "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nciao"),
            "-- AI --", "append")
        assertTrue("WEBVTT stays the first line: $out", out.startsWith("WEBVTT\n"))
        assertTrue("the NOTE block follows the header: $out", out.contains("NOTE -- AI --\n"))
        assertFalse("no signature before the header", out.startsWith("NOTE"))
    }

    @Test
    fun `srt takes a pre-cue banner line`() {
        val out = TranscriptFileSaver.signedExport(
            decision(SubtitleFormatter.Format.SRT, "1\n00:00:01,000 --> 00:00:02,000\nciao"),
            "-- AI --", "append")
        assertTrue(out.startsWith("-- AI --\n\n1\n"))
    }

    @Test
    fun `a multi-line signature with the cue separator is sanitized to one safe line`() {
        val out = TranscriptFileSaver.signedExport(
            decision(SubtitleFormatter.Format.VTT, "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nciao"),
            "riga uno\nriga --> due", "append")
        assertTrue("single NOTE line: $out", out.contains("NOTE riga uno riga - due\n"))
        assertFalse("no embedded separator survives", out.contains("--> due"))
    }
}
