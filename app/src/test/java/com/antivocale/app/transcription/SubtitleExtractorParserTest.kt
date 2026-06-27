package com.antivocale.app.transcription

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Pure-string fixtures for [SubtitleExtractor.stripTimestampsAndMarkup].
 *
 * Covers SRT blocks, WebVTT cues (with the `WEBVTT` header and `.` decimal separator),
 * tx3g / HTML-style `<>` markup tags (including WebVTT cue timestamps inside `<>`),
 * and multi-cue concatenation. The MIME argument is currently unused by the parser but
 * is threaded so future format-specific handling (ASS/SSA) does not change call sites.
 */
@RunWith(JUnit4::class)
class SubtitleExtractorParserTest {

    @Test
    fun `srt single cue strips index, timestamp range and trailing newline`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello world\n"
        val result = SubtitleExtractor.stripTimestampsAndMarkup(srt, "application/x-subrip")
        assertEquals("Hello world", result)
    }

    @Test
    fun `webvtt single cue strips header, timestamp range and trailing newline`() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello world\n"
        val result = SubtitleExtractor.stripTimestampsAndMarkup(vtt, "text/vtt")
        assertEquals("Hello world", result)
    }

    @Test
    fun `removes inline markup tags like italics`() {
        // tx3g / styled subtitle: <i></i> must be stripped, leaving the bare word.
        val raw = "1\n00:00:01,000 --> 00:00:02,000\n<i>Hello</i> world\n"
        val result = SubtitleExtractor.stripTimestampsAndMarkup(raw, "application/x-subrip")
        assertEquals("Hello world", result)
    }

    @Test
    fun `removes webvtt cue timestamp tags in angle brackets`() {
        // WebVTT allows inline <00:00:01.000> cue-time tags for karaoke-style cues.
        val raw = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<00:00:01.000>Hello <00:00:01.500>world\n"
        val result = SubtitleExtractor.stripTimestampsAndMarkup(raw, "text/vtt")
        assertEquals("Hello world", result)
    }

    @Test
    fun `multiple cues are joined deterministically with no indices, timestamps or blank lines`() {
        val srt = buildString {
            append("1\n"); append("00:00:01,000 --> 00:00:02,000\n"); append("First cue\n")
            append("\n")
            append("2\n"); append("00:00:02,500 --> 00:00:04,000\n"); append("Second cue\n")
            append("\n")
            append("3\n"); append("00:00:04,500 --> 00:00:06,000\n"); append("Third cue\n")
        }
        val result = SubtitleExtractor.stripTimestampsAndMarkup(srt, "application/x-subrip")
        // Deterministic join: cues separated by a single space, no stray timestamps/indices.
        assertEquals("First cue Second cue Third cue", result)
    }

    @Test
    fun `single-digit hour timestamps are stripped`() {
        // Some encoders emit H:MM:SS,MMM instead of HH:MM:SS,MMM.
        val srt = "1\n0:00:01,000 --> 0:00:02,000\nHello world\n"
        val result = SubtitleExtractor.stripTimestampsAndMarkup(srt, "application/x-subrip")
        assertEquals("Hello world", result)
    }

    @Test
    fun `empty input returns empty string`() {
        assertEquals("", SubtitleExtractor.stripTimestampsAndMarkup("", "text/vtt"))
    }

    @Test
    fun `blank-only input collapses to empty string`() {
        val result = SubtitleExtractor.stripTimestampsAndMarkup("\n\n  \n", "text/vtt")
        assertEquals("", result.trim())
    }
}
