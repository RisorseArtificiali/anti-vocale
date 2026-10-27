package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-911: the corpus that pins [TranscriptFuzzySearch.THRESHOLD]. Every
 * MUST-MATCH pair is an ASR-style acoustic confusion (gemination, vowel
 * shift, voicing, diacritic loss); every MUST-NOT pair is either an
 * unrelated word or a short homophone the bar must keep out. The score
 * pins below document the margin on both sides.
 */
class TranscriptFuzzySearchTest {

    @Test
    fun `gemination errors match in both directions`() {
        assertTrue(TranscriptFuzzySearch.matches("Domani visitiamo il castelazo", "castellazzo"))
        assertTrue(TranscriptFuzzySearch.matches("Domani visitiamo il castellazzo", "castelazo"))
        assertTrue(TranscriptFuzzySearch.matches("Ci vediamo domatina alle nove", "mattina"))
    }

    @Test
    fun `vowel shifts and diacritic loss match`() {
        assertTrue(TranscriptFuzzySearch.matches("Devo andare in uspedale", "ospedale"))
        assertTrue(TranscriptFuzzySearch.matches("non capisco piu niente", "più"))
        assertTrue(TranscriptFuzzySearch.matches("la citta e bella", "città"))
        assertTrue(TranscriptFuzzySearch.matches("il treno parte per Bolonia", "Bologna"))
    }

    @Test
    fun `voicing confusion matches`() {
        assertTrue(TranscriptFuzzySearch.matches("pronto chi parla", "bronto"))
        assertTrue(TranscriptFuzzySearch.matches("ho bronto il libro", "pronto"))
    }

    @Test
    fun `clitics split so the inner word matches exactly`() {
        assertTrue(TranscriptFuzzySearch.matches("ho bevuto l'acqua", "acqua"))
        assertTrue(TranscriptFuzzySearch.matches("c'est fantastique", "est"))
    }

    @Test
    fun `cyrillic vowel errors match`() {
        assertTrue(TranscriptFuzzySearch.matches("привет как дела", "превет"))
    }

    @Test
    fun `cjk degrades gracefully instead of excluding the language`() {
        // CJK has no spaces: the whole query is one term and the whole
        // transcript chunk one word, so edit distance works over the
        // phrase and a single wrong character still matches.
        assertTrue(TranscriptFuzzySearch.matches("今天天起很好", "今天天气很好"))
    }

    @Test
    fun `similar mode never narrows below literal`() {
        // Prefix queries and CJK words inside an unspaced sentence are
        // verbatim substrings; the widening floor keeps them hits even
        // though the whole-word length gate would reject them.
        assertTrue(TranscriptFuzzySearch.matches("Domani visitiamo il castellazzo", "castel"))
        assertTrue(TranscriptFuzzySearch.matches("今天天气很好", "天气"))
    }

    @Test
    fun `a term that normalizes to nothing rides its substring floor`() {
        // A combining-mark-only fragment pasted from another app has no
        // fuzzy form: present verbatim it matches through the floor;
        // absent it fails the row like any other miss.
        assertTrue(TranscriptFuzzySearch.matches("caf\u0301e italiano", "\u0301"))
        assertFalse(TranscriptFuzzySearch.matches("caffe italiano", "\u0301"))
    }

    @Test
    fun `combining spacing marks fold too`() {
        // Devanagari matras are COMBINING_SPACING (Mc), not NON_SPACING:
        // they must fold like Latin diacritics or Indic never normalizes.
        assertFalse(TranscriptFuzzySearch.normalize("मुंबई").contains("ु"))
    }

    @Test
    fun `every query term must be found`() {
        val transcript = "L'appuntamento dal dentesta e domani"
        assertTrue(TranscriptFuzzySearch.matches(transcript, "appuntamento dentista"))
        assertFalse(TranscriptFuzzySearch.matches(transcript, "appuntamento parrucchiere"))
    }

    @Test
    fun `unrelated words do not match`() {
        assertFalse(TranscriptFuzzySearch.matches("il computer e nuovo", "tramonto"))
        assertFalse(TranscriptFuzzySearch.matches("andiamo al mare", "ospedale"))
    }

    @Test
    fun `short homophones stay below the bar`() {
        // era/ora differ by one vowel in three letters: admitting them
        // would make the mode return noise on every common short word.
        assertFalse(TranscriptFuzzySearch.matches("c'era una volta", "ora"))
    }

    @Test
    fun `a much longer or shorter word never matches`() {
        assertFalse(TranscriptFuzzySearch.matches("la casa in collina", "castellazzo"))
        assertFalse(TranscriptFuzzySearch.matches("il castellazzo e antico", "casa"))
    }

    @Test
    fun `blank queries and empty text never match`() {
        assertFalse(TranscriptFuzzySearch.matches("qualunque testo", "   "))
        assertFalse(TranscriptFuzzySearch.matches("", "parola"))
        assertFalse(TranscriptFuzzySearch.matches("... --- ...", "parola"))
    }

    @Test
    fun `row ranking puts the exact hit above the fuzzy hit`() {
        // castelazo/castellazzo normalize identically (gemination collapse),
        // so the ranking pair uses a confusion the collapse does NOT
        // erase: Bologna heard as Bolonia.
        val exact = TranscriptFuzzySearch.rowScore("il treno per Bologna", "Bologna")
        val fuzzy = TranscriptFuzzySearch.rowScore("il treno per Bolonia", "Bologna")
        assertTrue(exact > fuzzy)
        assertTrue(fuzzy >= TranscriptFuzzySearch.THRESHOLD)
    }

    @Test
    fun `threshold and its margins stay pinned`() {
        assertEquals(0.70, TranscriptFuzzySearch.THRESHOLD, 1e-9)
        // Weakest accepted member of the corpus: Bologna/Bolonia, whose
        // only surviving signal is edit distance over 7 characters.
        assertEquals(0.714, TranscriptFuzzySearch.termScore("Bolonia", "Bologna"), 0.01)
        // Strongest rejected member: era/ora, one vowel apart in three
        // letters, skeleton too short to score.
        assertEquals(0.667, TranscriptFuzzySearch.termScore("ora", "era"), 0.01)
    }
}
