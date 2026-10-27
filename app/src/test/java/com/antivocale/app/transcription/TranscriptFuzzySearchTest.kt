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

    private fun matches(text: String, query: String): Boolean =
        TranscriptFuzzySearch.scoreIfMatches(text, query) != null

    private fun rowScore(text: String, query: String): Double =
        TranscriptFuzzySearch.scoreIfMatches(text, query) ?: 0.0

    @Test
    fun `gemination errors match in both directions`() {
        assertTrue(matches("Domani visitiamo il castelazo", "castellazzo"))
        assertTrue(matches("Domani visitiamo il castellazzo", "castelazo"))
        assertTrue(matches("Ci vediamo domatina alle nove", "mattina"))
    }

    @Test
    fun `vowel shifts and diacritic loss match`() {
        assertTrue(matches("Devo andare in uspedale", "ospedale"))
        assertTrue(matches("non capisco piu niente", "più"))
        assertTrue(matches("la citta e bella", "città"))
        assertTrue(matches("il treno parte per Bolonia", "Bologna"))
    }

    @Test
    fun `voicing confusion matches`() {
        assertTrue(matches("pronto chi parla", "bronto"))
        assertTrue(matches("ho bronto il libro", "pronto"))
    }

    @Test
    fun `clitics split so the inner word matches exactly`() {
        assertTrue(matches("ho bevuto l'acqua", "acqua"))
        assertTrue(matches("c'est fantastique", "est"))
    }

    @Test
    fun `a clitic QUERY splits too, so a mis-heard inner word is findable`() {
        // Review round: the query used to keep its apostrophe through
        // normalization while row words did not, so "l'acqua" could never
        // fuzzy-match a transcript whose inner word the model mis-heard.
        assertTrue(matches("ho bevuto l'acgua", "l'acqua"))
    }

    @Test
    fun `cyrillic vowel errors match`() {
        assertTrue(matches("привет как дела", "превет"))
    }

    @Test
    fun `cjk degrades gracefully instead of excluding the language`() {
        // CJK has no spaces: the whole query is one term and the whole
        // transcript chunk one word, so edit distance works over the
        // phrase and a single wrong character still matches.
        assertTrue(matches("今天天起很好", "今天天气很好"))
    }

    @Test
    fun `similar mode never narrows below literal`() {
        // Prefix queries and CJK words inside an unspaced sentence are
        // verbatim substrings; the widening floor keeps them hits even
        // though the whole-word length gate would reject them.
        assertTrue(matches("Domani visitiamo il castellazzo", "castel"))
        assertTrue(matches("今天天气很好", "天气"))
    }

    @Test
    fun `a query of only combining marks finds nothing, cleanly`() {
        // Tokenization keeps letters and digits only, so a pasted
        // mark-only fragment never becomes a term: no match, no crash, no
        // match-everything.
        assertFalse(matches("caf\u0301e italiano", "\u0301"))
        assertFalse(matches("caffe italiano", "\u0301"))
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
        assertTrue(matches(transcript, "appuntamento dentista"))
        assertFalse(matches(transcript, "appuntamento parrucchiere"))
    }

    @Test
    fun `unrelated words do not match`() {
        assertFalse(matches("il computer e nuovo", "tramonto"))
        assertFalse(matches("andiamo al mare", "ospedale"))
    }

    @Test
    fun `short homophones stay below the bar`() {
        // era/ora differ by one vowel in three letters: admitting them
        // would make the mode return noise on every common short word.
        assertFalse(matches("c'era una volta", "ora"))
    }

    @Test
    fun `a much longer or shorter word never matches`() {
        assertFalse(matches("la casa in collina", "castellazzo"))
        assertFalse(matches("il castellazzo e antico", "casa"))
    }

    @Test
    fun `blank queries and empty text never match`() {
        assertFalse(matches("qualunque testo", "   "))
        assertFalse(matches("", "parola"))
        assertFalse(matches("... --- ...", "parola"))
    }

    @Test
    fun `row ranking puts the exact hit above the fuzzy hit`() {
        // castelazo/castellazzo normalize identically (gemination collapse),
        // so the ranking pair uses a confusion the collapse does NOT
        // erase: Bologna heard as Bolonia.
        val exact = rowScore("il treno per Bologna", "Bologna")
        val fuzzy = rowScore("il treno per Bolonia", "Bologna")
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
