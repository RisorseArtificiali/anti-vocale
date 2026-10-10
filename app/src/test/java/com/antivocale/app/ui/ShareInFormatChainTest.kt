package com.antivocale.app.ui

import com.antivocale.app.data.local.TimedSegmentsConverter
import com.antivocale.app.util.SubtitleFormatter
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-800 device-report pin: the maintainer picked "text with timestamps"
 * and received plain text. This pins the whole JVM-pure chain with the
 * EXACT segments JSON a real row carries: parse -> resolveExport ->
 * timedTxt must produce clock-prefixed lines, not the plain transcript.
 */
class ShareInFormatChainTest {

    private val rowJson =
        """[{"startMs":1520,"endMs":3359,"text":"Ciao Elisa Pesta"},""" +
            """{"startMs":7279,"endMs":8880,"text":"Ciao Editabetta"},""" +
            """{"startMs":21200,"endMs":22400,"text":"item"}]"""

    @Test
    fun `txt_timed with a real row's segments produces clock-prefixed lines`() {
        val segments = TimedSegmentsConverter.fromJson(rowJson)
        assertTrue("segments parsed non-empty", segments.isNotEmpty())
        val decision = SubtitleFormatter.resolveExport(
            SubtitleFormatter.Format.TXT_TIMED, "plain stored transcript",
            segments, failedChunkCount = 0)
        // Not degraded, and the content carries the mm:ss-mm:ss prefix.
        assertTrue("format not degraded, was ${decision.format}",
            decision.format == SubtitleFormatter.Format.TXT_TIMED)
        assertTrue("content has clock prefixes: ${decision.content.take(40)}",
            decision.content.contains("0:01-0:03 Ciao Elisa Pesta"))
    }

    @Test
    fun `a row with null segments degrades to plain txt (the honest gate)`() {
        val segments = TimedSegmentsConverter.fromJson(null)
        val decision = SubtitleFormatter.resolveExport(
            SubtitleFormatter.Format.TXT_TIMED, "stored text", segments, 0)
        assertTrue("degraded to TXT", decision.format == SubtitleFormatter.Format.TXT)
    }
}
