package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test


/** TASK-678: cues now carry transient tokens; these tests pin cue geometry. */
private fun List<com.antivocale.app.transcription.TimedSegment>.withoutTransientTokens() =
    map { it.copy(tokens = emptyList()) }

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
            cues.withoutTransientTokens(),
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
            cues.withoutTransientTokens(),
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
            cues.withoutTransientTokens(),
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
            cues.withoutTransientTokens(),
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
        assertEquals(listOf(TimedSegment(0, 1000, "Hallo, welt.")), cues.withoutTransientTokens())
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
            cues.withoutTransientTokens(),
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
            SentenceCueBuilder.build(tokens, chunkStartMs = 0, chunkEndMs = 600)
                .withoutTransientTokens(),
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

    @Test
    fun pureSeparatorTokens_doNotAbortAlignment() {
        // Parakeet separates words with bare U+2581 tokens; they carry no
        // character, so the alignment must skip them (not fail closed) and the
        // cue text must still come from the chunk text.
        val tokens = listOf(
            token("I", 0, 200), token("hr", 200, 400), token("▁", 400, 500),
            token("war", 600, 900),
        )
        val cues = SentenceCueBuilder.build(tokens, 0, 1000, "Ihr war")
        assertEquals(1, cues.size)
        assertEquals("Ihr war", cues[0].text)
    }

    // ---- TASK-851: the original-timeline mapping inside VAD-merged chunks ----

    @Test
    fun `originalTimelineMs is the flat shift without anchors`() {
        assertEquals(6_200L, SentenceCueBuilder.originalTimelineMs(1_200L, 5_000L, emptyList()))
        // A single anchor at buffer 0 IS the flat shift.
        assertEquals(6_200L, SentenceCueBuilder.originalTimelineMs(
            1_200L, 5_000L, listOf(0L to 5_000L)))
    }

    @Test
    fun `originalTimelineMs restores the stripped silence past each anchor`() {
        // A merged chunk of two segments: buffer [0,1000) is original
        // [0,1000); the second segment starts at buffer 1000 but its
        // ORIGINAL start is 5000 (3000ms of stripped silence between them).
        val anchors = listOf(0L to 0L, 1_000L to 5_000L)
        // Inside the first segment: identity.
        assertEquals(700L, SentenceCueBuilder.originalTimelineMs(700L, 0L, anchors))
        // At the second anchor: the original start.
        assertEquals(5_000L, SentenceCueBuilder.originalTimelineMs(1_000L, 0L, anchors))
        // Past it: advance in lockstep (no stripping inside a segment).
        assertEquals(5_500L, SentenceCueBuilder.originalTimelineMs(1_500L, 0L, anchors))
        // A non-zero chunk start rides along (the anchor carries originals).
        assertEquals(6_500L, SentenceCueBuilder.originalTimelineMs(1_500L, 1_000L,
            listOf(0L to 1_000L, 1_000L to 6_000L)))
    }

    @Test
    fun `a cue in the second merged segment lands on the original timeline`() {
        // The drift case from TASK-851: without anchors the second
        // segment's cue lands 3000ms early.
        val anchors = listOf(0L to 0L, 1_000L to 5_000L)
        // The inter-token pause (900->1800 = 900ms) exceeds PAUSE_SPLIT_MS,
        // so the two tokens land in two cues.
        val cues = SentenceCueBuilder.build(
            listOf(
                token("hello", 100, 900),
                token("world", 1_800, 2_600)),
            chunkStartMs = 0, chunkEndMs = 6_000,
            anchors = anchors)
        assertEquals(2, cues.size)
        // First cue: inside segment one, identity.
        assertEquals(100L, cues[0].startMs)
        assertEquals(900L, cues[0].endMs)
        // Second cue: shifted past the stripped silence to the original
        // (buffer 1800 is 800ms past the 1000ms anchor).
        assertEquals(5_800L, cues[1].startMs)
        // The mapped end (6600) clamps to the chunk range.
        assertEquals(6_000L, cues[1].endMs)
        assertEquals(5_800L, cues[1].tokens.single().startMs)
    }

    // ---- TASK-541 item 2: marker-less token families join directly ----

    @Test
    fun `character-level CTC tokens compose words, the explicit space token breaks them`() {
        // The omnilingual-CTC external-import shape (real tokens.txt: 99.96%
        // single-char pieces, an explicit " " token, no U+2581 markers;
        // Latin coverage is lowercase-only): "one tre".
        val toks = listOf(
            token("o", 0, 60), token("n", 60, 120), token("e", 120, 180),
            token(" ", 180, 200),
            token("t", 200, 260), token("r", 260, 320), token("e", 320, 380))
        val cues = SentenceCueBuilder.build(toks, chunkStartMs = 0, chunkEndMs = 380)
        assertEquals(1, cues.size)
        assertEquals("one tre", cues[0].text)
    }

    @Test
    fun `marker-less word tokens keep the space join when no space token exists`() {
        // The discriminator is the explicit whitespace token: a range with
        // NO space token is a word-token stream (or a sentencepiece word
        // pair) and keeps the separator, matching every pre-existing pin.
        val toks = listOf(token("One", 0, 300), token("sentence.", 300, 600))
        val cues = SentenceCueBuilder.build(toks, chunkStartMs = 0, chunkEndMs = 600)
        assertEquals(1, cues.size)
        assertEquals("One sentence.", cues[0].text)
    }

    @Test
    fun `a char CTC cue range without its own space token still glues`() {
        // Review F1's repro: a pause split isolating "o","n","e","." into
        // its own cue leaves that range with NO whitespace token; the
        // stream-level discriminator must still glue it (the per-range form
        // rendered "o n e.").
        val toks = listOf(
            token("o", 0, 60), token("n", 60, 120), token("e", 120, 180), token(".", 180, 200),
            token(" ", 200, 220),
            token("t", 1400, 1460), token("w", 1460, 1520), token("o", 1520, 1580), token(".", 1580, 1600))
        val cues = SentenceCueBuilder.build(toks, chunkStartMs = 0, chunkEndMs = 1600)
        assertEquals(2, cues.size)
        assertEquals("one.", cues[0].text)
        assertEquals("two.", cues[1].text)
    }

    @Test
    fun `a lone trailing punctuation flash cue at the chunk boundary drops`() {
        // TASK-541 item 1's observed device shape: the model's trailing
        // period token past chunkEnd clamps to start==end; the flash line
        // must drop (no visible text lost).
        val toks = listOf(
            token("▁Ciao", 0, 400), token("▁mondo", 400, 800), token(".", 30_100, 30_100))
        val cues = SentenceCueBuilder.build(toks, chunkStartMs = 0, chunkEndMs = 30_000)
        assertEquals(1, cues.size)
        assertEquals("Ciao mondo", cues[0].text)
    }

    @Test
    fun `a flash cue carrying real words stays visible`() {
        // The conservative half of the rule: only punctuation-only flash
        // cues drop; real text at a collapsed range remains (visible-text
        // loss would be worse than a flash line).
        val toks = listOf(
            token("▁Ciao", 0, 400), token("▁mondo", 400, 800), token("▁fine", 30_100, 30_100))
        val cues = SentenceCueBuilder.build(toks, chunkStartMs = 0, chunkEndMs = 30_000)
        assertEquals(2, cues.size)
        assertEquals("fine", cues[1].text)
    }

    @Test
    fun `marker-carrying ranges keep the space join`() {
        val toks = listOf(token("▁Ciao", 0, 300), token("▁mondo", 300, 600))
        val cues = SentenceCueBuilder.build(toks, chunkStartMs = 0, chunkEndMs = 600)
        assertEquals(1, cues.size)
        assertEquals("Ciao mondo", cues[0].text)
    }

    @Test
    fun `marker-less CTC punctuation still glues`() {
        val toks = listOf(
            token("o", 0, 60), token("n", 60, 120), token("e", 120, 180),
            token(",", 180, 200), token(" ", 200, 220),
            token("t", 220, 280), token("r", 280, 340), token("e", 340, 400))
        val cues = SentenceCueBuilder.build(toks, chunkStartMs = 0, chunkEndMs = 400)
        assertEquals(1, cues.size)
        assertEquals("one, tre", cues[0].text)
    }
}
