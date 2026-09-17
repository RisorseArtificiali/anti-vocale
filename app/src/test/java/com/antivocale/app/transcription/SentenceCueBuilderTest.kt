package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceCueBuilderTest {

    private fun token(text: String, startMs: Long, endMs: Long) =
        TimedToken(text = text, startMs = startMs, endMs = endMs)

    @Test
    fun punctuationSplit_oneCuePerSentence() {
        val tokens = listOf(
            token("One", 0, 300),
            token("sentence.", 400, 900),
            token("Two", 1000, 1300),
            token("sentence.", 1400, 1900),
        )
        val cues = SentenceCueBuilder.build(tokens, chunkStartMs = 0, chunkEndMs = 2000)
        assertEquals(
            listOf(
                TimedSegment(0, 900, "One sentence."),
                TimedSegment(1000, 1900, "Two sentence."),
            ),
            cues,
        )
    }

    @Test
    fun pauseSplit_noPunctuationNeeded() {
        val tokens = listOf(
            token("Alpha", 0, 500),
            token("beta", 1500, 2000),
        )
        val cues = SentenceCueBuilder.build(tokens, chunkStartMs = 0, chunkEndMs = 3000)
        assertEquals(
            listOf(
                TimedSegment(0, 500, "Alpha"),
                TimedSegment(1500, 2000, "beta"),
            ),
            cues,
        )
        assertTrue("cues must not overlap", cues[0].endMs <= cues[1].startMs)
    }

    @Test
    fun capSplit_prefersCommaInSecondHalf() {
        // 15 contiguous 500ms-spaced tokens; adding token 14 pushes the cue to
        // 7400ms, over the 7000ms cap. Token 11 is a comma in the second half.
        val tokens = (0 until 15).map { i ->
            val text = if (i == 11) "," else "w$i"
            token(text, startMs = i * 500L, endMs = i * 500L + 400)
        }
        val cues = SentenceCueBuilder.build(tokens, chunkStartMs = 0, chunkEndMs = 8000)
        assertEquals(
            listOf(
                TimedSegment(0, 5900, "w0 w1 w2 w3 w4 w5 w6 w7 w8 w9 w10,"),
                TimedSegment(6000, 7400, "w12 w13 w14"),
            ),
            cues,
        )
    }

    @Test
    fun capSplit_withoutComma_landsNearestTheFiveSecondTarget() {
        val tokens = (0 until 15).map { i ->
            token("w$i", startMs = i * 500L, endMs = i * 500L + 400)
        }
        val cues = SentenceCueBuilder.build(tokens, chunkStartMs = 0, chunkEndMs = 8000)
        // Token 9 ends at 4900ms, the boundary closest to the 5000ms target.
        assertEquals(
            listOf(
                TimedSegment(0, 4900, "w0 w1 w2 w3 w4 w5 w6 w7 w8 w9"),
                TimedSegment(5000, 7400, "w10 w11 w12 w13 w14"),
            ),
            cues,
        )
    }

    @Test
    fun wordStartMarker_normalizedAndPunctuationGlued() {
        val tokens = listOf(
            token("▁Hallo", 0, 400),
            token(",", 450, 500),
            token("▁welt", 600, 900),
            token(".", 950, 1000),
        )
        val cues = SentenceCueBuilder.build(tokens, chunkStartMs = 0, chunkEndMs = 1500)
        assertEquals(listOf(TimedSegment(0, 1000, "Hallo, welt.")), cues)
    }

    @Test
    fun clamping_cuesStayInsideTheChunkRange() {
        val tokens = listOf(
            token("early", -300, 200),
            token("late", 4800, 5200),
        )
        val cues = SentenceCueBuilder.build(tokens, chunkStartMs = 10_000, chunkEndMs = 15_000)
        assertEquals(
            listOf(
                TimedSegment(10_000, 10_200, "early"),
                TimedSegment(14_800, 15_000, "late"),
            ),
            cues,
        )
    }

    @Test
    fun blankCues_dropped() {
        val tokens = listOf(
            token("▁", 0, 100),
            token("▁", 200, 300),
        )
        assertEquals(
            emptyList<TimedSegment>(),
            SentenceCueBuilder.build(tokens, chunkStartMs = 0, chunkEndMs = 500),
        )
    }

    @Test
    fun singleToken_singleCue() {
        val tokens = listOf(token("▁ciao", 100, 500))
        assertEquals(
            listOf(TimedSegment(100, 500, "ciao")),
            SentenceCueBuilder.build(tokens, chunkStartMs = 0, chunkEndMs = 600),
        )
    }

    @Test
    fun emptyTokens_noCues() {
        assertEquals(
            emptyList<TimedSegment>(),
            SentenceCueBuilder.build(emptyList(), chunkStartMs = 0, chunkEndMs = 1000),
        )
    }

    @Test
    fun alignedChunkText_restoresRealWordsFromSubwordFragments() {
        // Parakeet BPE probe 2026-09-17: "Ihr erstes war der Slalom." decoded
        // as marker-less fragments that space-join into "I hr er st es".
        val tokens = listOf(
            token("I", 320, 500), token("hr", 500, 700), token("er", 700, 900),
            token("st", 900, 1100), token("es", 1100, 1300),
            token("war", 1400, 1700), token("der", 1750, 2000), token("S", 2050, 2200),
            token("l", 2200, 2300), token("al", 2300, 2450), token("om", 2450, 2600),
            token(".", 2700, 2800),
        )
        val chunkText = "Ihr erstes war der Slalom."
        val cues = SentenceCueBuilder.build(tokens, 0, 3000, chunkText)
        assertEquals(1, cues.size)
        assertEquals("Ihr erstes war der Slalom.", cues[0].text)
        assertEquals(320, cues[0].startMs)
        assertEquals(2800, cues[0].endMs)
    }

    @Test
    fun pauseSplitTrailingPunctuation_mergesIntoTheSentenceItCloses() {
        val tokens = listOf(
            token("Er", 0, 300), token("gebn", 300, 500), token("is", 500, 700),
            // 2s pause, then the ender arrives as its own token.
            token(".", 2900, 3000),
        )
        val cues = SentenceCueBuilder.build(tokens, 0, 3100, "Ergebnis.")
        assertEquals(1, cues.size)
        assertEquals("Ergebnis.", cues[0].text)
        assertEquals(0, cues[0].startMs)
        assertEquals(3000, cues[0].endMs)
    }

    @Test
    fun leadingWordlessCue_foldsIntoFirstWordedCue() {
        val tokens = listOf(
            token("(", 0, 100),
            token("▁ciao", 200, 600),
        )
        val cues = SentenceCueBuilder.build(tokens, 0, 700, "(ciao")
        assertEquals(1, cues.size)
        assertEquals("(ciao", cues[0].text)
        assertEquals(0, cues[0].startMs)
    }

    @Test
    fun unalignedChunkText_fallsBackToTokenJoin() {
        // The text is not the token sequence (extra word): alignment must fail
        // closed and the join path produce the space-joined tokens.
        val tokens = listOf(
            token("One", 0, 300), token(".", 400, 500),
        )
        val cues = SentenceCueBuilder.build(tokens, 0, 600, "Something else entirely")
        assertEquals(1, cues.size)
        assertEquals("One.", cues[0].text)
    }
}
