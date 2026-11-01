package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-928: the one token-to-word derivation both persist paths share.
 * Marker (or leading-whitespace, whisper's BPE) grouping opens words,
 * continuation tokens extend them, and a group count that cannot back
 * every whitespace word of the cue text is null (index alignment is the
 * whole contract). The cue-envelope estimate for marker-less streams is
 * its own function with its own refusals.
 */
class TimedWordsTest {

    @Test
    fun `marker tokens open one word each`() {
        val words = TimedWords.ofCue(
            "alpha beta",
            listOf(TimedToken("▁alpha", 0, 800), TimedToken("▁beta", 1000, 1800)),
        )!!
        assertEquals(listOf(TimedWord(0, 800), TimedWord(1000, 1800)), words)
    }

    @Test
    fun `continuation tokens extend the open word`() {
        // A subword stream: "Hel"|"lo" compose one word, the marker opens the next.
        val words = TimedWords.ofCue(
            "Hello mondo",
            listOf(TimedToken("Hel", 0, 300), TimedToken("lo", 300, 700), TimedToken("▁mondo", 800, 1500)),
        )!!
        assertEquals(listOf(TimedWord(0, 700), TimedWord(800, 1500)), words)
    }

    @Test
    fun `a group count that cannot back the text words is null`() {
        // Two marker groups for a three-word text: index alignment impossible.
        assertNull(
            TimedWords.ofCue(
                "alpha beta gamma",
                listOf(TimedToken("▁alpha", 0, 800), TimedToken("▁beta", 1000, 1800)),
            )
        )
    }

    @Test
    fun `no tokens is null`() {
        assertNull(TimedWords.ofCue("alpha", emptyList()))
    }

    @Test
    fun `punctuation glued to a token does not change the word count`() {
        val words = TimedWords.ofCue(
            "alpha, beta.",
            listOf(TimedToken("▁alpha,", 0, 800), TimedToken("▁beta.", 1000, 1800)),
        )!!
        assertEquals(listOf(TimedWord(0, 800), TimedWord(1000, 1800)), words)
    }

    @Test
    fun `whisper tokens open words with a leading space`() {
        // Whisper's base64 tokens.txt decodes to space-led word starts (the
        // symbol table only rewrites U+2581 for non-byte-BPE vocabs).
        val words = TimedWords.ofCue(
            "alpha beta",
            listOf(TimedToken(" alpha", 0, 800), TimedToken(" beta", 1000, 1800)),
        )!!
        assertEquals(listOf(TimedWord(0, 800), TimedWord(1000, 1800)), words)
    }

    @Test
    fun `whisper continuations extend the open word`() {
        val words = TimedWords.ofCue(
            "Hello mondo",
            listOf(TimedToken("Hel", 0, 300), TimedToken("lo", 300, 700), TimedToken(" mondo", 800, 1500)),
        )!!
        assertEquals(listOf(TimedWord(0, 700), TimedWord(800, 1500)), words)
    }

    @Test
    fun `marker-less tokens cannot back a multi-word text`() {
        // Parakeet's BPE: no markers, no leading spaces; one group for two
        // words is a count refusal, the honesty gate holding.
        assertNull(
            TimedWords.ofCue(
                "alpha beta",
                listOf(TimedToken("alpha", 0, 800), TimedToken("beta", 1000, 1800)),
            )
        )
    }

    @Test
    fun `a bare separator token opens no word`() {
        // Whisper can emit the bare-space piece (GPT-2 id 220) as the
        // stream's first token: it detokenizes to nothing, so it must not
        // open a group. The word count gate would refuse every such stream.
        val words = TimedWords.ofCue(
            "alpha beta",
            listOf(TimedToken(" ", 100, 140), TimedToken(" alpha", 140, 800), TimedToken(" beta", 1000, 1800)),
        )!!
        assertEquals(listOf(TimedWord(140, 800), TimedWord(1000, 1800)), words)
    }

    @Test
    fun `the whisper-turbo probe stream yields measured words`() {
        // wordtap-probe-9-turbo: the decoded sentence's simulated token
        // stream, space-led pieces with Italian subword splits, the comma
        // as a continuation, and the leading bare-space piece. Measured
        // grouping must survive it; the first word's span is its own
        // token's, not the separator's and not an estimate grid.
        val tokens = listOf(
            TimedToken(" ", 1199, 1205),
            TimedToken(" Questa", 1205, 1750),
            TimedToken(" è", 1750, 1810),
            TimedToken(" una", 1810, 1900),
            TimedToken(" prova", 1900, 2150),
            TimedToken(" del", 2150, 2250),
            TimedToken(" tocco", 2250, 2550),
            TimedToken(" sulla", 2550, 2700),
            TimedToken(" parola", 2700, 3000),
            TimedToken(",", 3000, 3060),
            TimedToken(" tocca", 3060, 3300),
            TimedToken(" la", 3300, 3400),
            TimedToken(" parola", 3400, 3700),
            TimedToken(" do", 3700, 3800),
            TimedToken("nine", 3800, 4050),
            TimedToken(" per", 4050, 4200),
            TimedToken(" rias", 4200, 4400),
            TimedToken("colta", 4400, 4700),
            TimedToken("re", 4700, 4900),
            TimedToken(" questo", 4900, 5100),
            TimedToken(" punto", 5100, 6077),
        )
        val words = TimedWords.ofCue(
            "Questa è una prova del tocco sulla parola, tocca la parola donine per riascoltare questo punto",
            tokens,
        )
        assertEquals(16, words?.size)
        assertEquals(TimedWord(1205, 1750), words!![0])
        assertEquals("the comma rides its word", 3060L, words[7].endMs)
        assertEquals(TimedWord(4200, 4900), words[13])
        assertEquals(TimedWord(5100, 6077), words[15])
    }

    @Test
    fun `the nemotron streaming probe stream yields measured words`() {
        // wordtap-probe-11-logs, decoded on-device by Nemotron 3.5 streaming
        // (desktop sherpa-onnx 2.0.1 repro, same model files): the greeting
        // never enters the stream, word-start pieces are space-led BUT words
        // that begin with a bare piece (Questa|Q u e sta, donine|do ni ne,
        // riascoltare|ria sco l ta re) carry no opener, and the vocab emits
        // the standalone space piece exactly at those word breaks. A
        // separator must close the open word there, or four words glue into
        // their predecessors (12 groups vs 16) and the row falls to the
        // estimate. Ends follow the successor-start rule TimedTokens applies
        // to a duration-less stream.
        val starts = listOf(
            1199, 1199, 1199, 1199, 1199, 1279, 1359, 1439, 1519, 1599,
            1759, 2079, 2079, 2319, 2479, 2559, 2639, 2719, 2799, 3119,
            3439, 3439, 3519, 3519, 3679, 3679, 3759, 3839, 3919, 4159,
            4159, 4399, 4479, 4639, 4719, 4719, 4879, 4879, 4959, 5039,
            5599, 5759, 5759, 5839,
        )
        val pieces = listOf(
            " ", " ", "Q", "u", "e", "sta", " è", " una", " pro", "va",
            " del", " t", "o", "cco", " su", "lla", " par", "o", "la", ",",
            " t", "o", "c", "ca", " ", "la", " par", "o", "la", " ",
            "do", "ni", "ne", " per", " ", "ria", "sco", "l", "ta", "re",
            " questo", " ", "pu", "nto",
        )
        val tokens = pieces.mapIndexed { i, piece ->
            val end = if (i + 1 < pieces.size) starts[i + 1] else starts.last() + 400
            TimedToken(piece, starts[i].toLong(), end.toLong())
        }
        val words = TimedWords.ofCue(
            "Questa è una prova del tocco sulla parola, tocca la parola donine per riascoltare questo punto",
            tokens,
        )
        assertEquals(16, words?.size)
        // The separator contributes no span: a word's end stays inside its
        // own pieces, never reaching the separator's or the next word's time.
        assertEquals(TimedWord(1199, 1359), words!![0])
        assertEquals("donine opens at its own bare piece", TimedWord(4159, 4639), words[11])
        assertEquals("riascoltare opens at its own bare piece", TimedWord(4719, 5599), words[13])
        assertEquals(TimedWord(5759, 6239), words[15])
    }

    @Test
    fun `the cue-envelope estimate spreads words evenly`() {
        val words = TimedWords.proportionalEstimate("alpha beta gamma", 0, 3000)!!
        assertEquals(
            listOf(TimedWord(0, 1000), TimedWord(1000, 2000), TimedWord(2000, 3000)),
            words,
        )
    }

    @Test
    fun `the estimate closes the last word at the cue end`() {
        val words = TimedWords.proportionalEstimate("alpha beta gamma", 0, 2500)!!
        assertEquals(
            listOf(TimedWord(0, 833), TimedWord(833, 1666), TimedWord(1666, 2500)),
            words,
        )
    }

    @Test
    fun `the estimate refuses an empty text or a non-positive span`() {
        assertNull(TimedWords.proportionalEstimate("   ", 0, 3000))
        assertNull(TimedWords.proportionalEstimate("alpha", 500, 500))
        assertNull(TimedWords.proportionalEstimate("alpha", 800, 500))
    }
}
