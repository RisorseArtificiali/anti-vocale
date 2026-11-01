package com.antivocale.app.ui.components

import com.antivocale.app.data.local.TimedSegmentsConverter
import com.antivocale.app.util.SubtitleFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * wordtap-probe-9-turbo device pin (TASK-928/939 maintainer trial): the row
 * whose taps fired one word off. The exact result text and persisted cue
 * (estimate words on the constant 304ms grid, cue text = result text) run
 * through the same mapping the History tab uses: parse cues, align the
 * displayed text, flatMap word starts, build the tap geometry. The contract
 * under test: the character offset of word N resolves to word N's OWN start.
 */
class HistoryWordTapChainTest {

    private val resultText =
        "Questa è una prova del tocco sulla parola, tocca la parola donine per riascoltare questo punto"

    // The persisted segments JSON of the probe row; the words grid is the
    // cue-envelope estimate (1199..6077 spread over 16 words, step 304).
    private val rowJson =
        """[{"startMs":1199,"endMs":6077,""" +
            """"text":"Questa è una prova del tocco sulla parola, tocca la parola donine per riascoltare questo punto",""" +
            """"words":[1199,1503,1503,1807,1807,2111,2111,2415,2415,2719,2719,3023,3023,3327,3327,3631,""" +
            """3631,3935,3935,4239,4239,4543,4543,4847,4847,5151,5151,5455,5455,5759,5759,6077],""" +
            """"wordsEstimated":true}]"""

    @Test
    fun `every displayed word taps its own start on the probe-9 estimate row`() {
        val cues = TimedSegmentsConverter.fromJson(rowJson)
        assertEquals(1, cues.size)
        val aligned = SubtitleFormatter.alignPolishedText(resultText, cues)
        assertNotNull("count and letters alignment hold on the device row", aligned)
        val starts = aligned!!.flatMap { cue -> cue.words.map { it.startMs } }
        val taps = TranscriptWordTaps.of(resultText, starts)

        val words = resultText.split(Regex("\\s+")).filter { it.isNotBlank() }
        assertEquals("estimate words back every displayed word", words.size, starts.size)
        var offset = 0
        for (i in words.indices) {
            while (offset < resultText.length && resultText[offset].isWhitespace()) offset++
            val wordStart = words[i][0]
            val wordEnd = offset + words[i].length - 1
            assertEquals("word $i '${words[i]}' taps its own start",
                starts[i], taps!!.startMsAt(offset))
            assertEquals("word $i '${words[i]}' taps its own start at its last char",
                starts[i], taps!!.startMsAt(wordEnd))
            offset += words[i].length
        }
    }

    @Test
    fun `the probe-5 capitalization polish does not shift the mapping`() {
        // The same row shape where the polish pass changed case only
        // ("donine" -> "Donine"): alignment re-texts the cue, timing rides.
        val polished = resultText.replace("donine", "Donine")
        val cues = TimedSegmentsConverter.fromJson(rowJson)
        val aligned = SubtitleFormatter.alignPolishedText(polished, cues)
        assertNotNull("a case-only polish still aligns", aligned)
        val starts = aligned!!.flatMap { cue -> cue.words.map { it.startMs } }
        val taps = TranscriptWordTaps.of(polished, starts)
        assertNotNull(taps)
        assertEquals(starts[11], taps!!.startMsAt(polished.indexOf("Donine")))
    }
}
