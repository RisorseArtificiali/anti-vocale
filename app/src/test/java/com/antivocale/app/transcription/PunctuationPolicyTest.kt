package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-276: pins the punctuation-pass gate. The skip paths are the point:
 * every one of them avoids loading Gemma for nothing.
 */
class PunctuationPolicyTest {

    @Test
    fun `needsPunctuation is true for unpunctuated speech`() {
        // GigaAM-style Russian output: words, spaces, nothing else.
        val russian =
            "привет как дела сегодня мы обсудим новый проект по распознаванию речи " +
                "и посмотрим что получилось у команды за последние недели работы"
        assertTrue(PunctuationPolicy.needsPunctuation(russian))
    }

    @Test
    fun `needsPunctuation is false for punctuated text`() {
        val italian =
            "Ciao, come stai? Oggi parliamo del progetto. È quasi finito, manca solo " +
                "la verifica! Ci vediamo domani. Ricordati il latte, grazie."
        assertFalse(PunctuationPolicy.needsPunctuation(italian))
    }

    @Test
    fun `a long dots-only paragraph counts punctuation occurrences`() {
        // The ALWAYS-mode trap the 2026-09-04 review caught (M1): counting
        // DISTINCT terminal characters scores this 60-word, five-sentence
        // paragraph as terminals=1 (only '.') and would re-polish it.
        // Occurrences: 5 dots -> 5*40 >= 60 -> skip.
        val paragraph = (1..5).joinToString(" ") { n ->
            "questa è la frase numero $n del paragrafo di prova del test" + if (n == 5) "." else "."
        }
        assertEquals(60, paragraph.split(" ").size)
        assertFalse(PunctuationPolicy.needsPunctuation(paragraph))
    }

    @Test
    fun `a lone trailing dot does not mark a long transcript as punctuated`() {
        // Whisper sometimes ends a chunk with one stray dot. A short one-liner
        // with a final dot is within the 1-per-40 floor and skips; a LONG
        // transcript carrying a single dot is still mostly unpunctuated and
        // must run the pass. 50 words, 1 terminal: 1*40 < 50.
        val words = (1..50).joinToString(" ") { "w$it" }
        val stray = "$words."
        assertTrue(PunctuationPolicy.needsPunctuation(stray))
        // ...but a single short sentence with its dot is fine as-is
        assertFalse(PunctuationPolicy.needsPunctuation("uno due tre quattro cinque sei sette."))
    }

    @Test
    fun `short transcripts never run the pass`() {
        assertFalse(PunctuationPolicy.needsPunctuation("привет"))
        assertFalse(PunctuationPolicy.needsPunctuation("ok done"))
        assertFalse(PunctuationPolicy.needsPunctuation("   "))
    }

    @Test
    fun `shouldRun combines mode flag and text`() {
        val unpunctuated = "один два три четыре пять шесть семь восемь девять десять одиннадцать"
        // OFF never runs
        assertFalse(PunctuationPolicy.shouldRun(PunctuationPolicy.Mode.OFF, false, unpunctuated))
        // AUTO: only non-punctuating models, and only when the text agrees
        assertTrue(PunctuationPolicy.shouldRun(PunctuationPolicy.Mode.AUTO, false, unpunctuated))
        assertFalse(PunctuationPolicy.shouldRun(PunctuationPolicy.Mode.AUTO, true, unpunctuated))
        // ALWAYS: any model, still gated on the text
        assertTrue(PunctuationPolicy.shouldRun(PunctuationPolicy.Mode.ALWAYS, true, unpunctuated))
        val punctuated = "Uno. Due. Tre. Quattro. Cinque. Sei. Sette. Otto. Nove. Dieci. Undici. Dodici."
        assertFalse(PunctuationPolicy.shouldRun(PunctuationPolicy.Mode.ALWAYS, false, punctuated))
    }

    @Test
    fun `context limit skips rather than truncates`() {
        val huge = "a".repeat(PunctuationPolicy.MAX_TRANSCRIPT_CHARS + 1)
        assertFalse(PunctuationPolicy.withinContextLimit(huge))
        assertTrue(PunctuationPolicy.withinContextLimit("a".repeat(PunctuationPolicy.MAX_TRANSCRIPT_CHARS)))
    }

    @Test
    fun `effective prompt prefers the user override and falls back to the localized default`() {
        assertEquals("custom", PunctuationPolicy.effectivePrompt("custom", "default"))
        assertEquals("default", PunctuationPolicy.effectivePrompt("", "default"))
        assertEquals("default", PunctuationPolicy.effectivePrompt("   ", "default"))
    }

    @Test
    fun `mode parsing is lenient toward AUTO for disk values`() {
        assertEquals(PunctuationPolicy.Mode.OFF, PunctuationPolicy.modeFromPref("off"))
        assertEquals(PunctuationPolicy.Mode.ALWAYS, PunctuationPolicy.modeFromPref("always"))
        assertEquals(PunctuationPolicy.Mode.AUTO, PunctuationPolicy.modeFromPref("auto"))
        // legacy or hand-edited junk: the safe default, never a crash
        assertEquals(PunctuationPolicy.Mode.AUTO, PunctuationPolicy.modeFromPref(""))
        assertEquals(PunctuationPolicy.Mode.AUTO, PunctuationPolicy.modeFromPref("banana"))
    }
}
