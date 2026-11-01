package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-928: the one token-to-word derivation both persist paths share.
 * Marker (or leading-whitespace, whisper's BPE) grouping opens words,
 * continuation tokens extend them, and a group count that cannot back
 * every whitespace word of the cue text is null (index alignment is the
 * whole contract).
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
}
