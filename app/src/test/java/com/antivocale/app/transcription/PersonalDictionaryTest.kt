package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-994: the personal-dictionary parsing home. Pins: empty states stay
 * null (the instruction passes through untouched), whitespace lines are
 * dropped, and the caps bound the prompt rider.
 */
class PersonalDictionaryTest {

    @Test
    fun `empty and whitespace-only dictionaries yield null`() {
        assertNull(PersonalDictionary.promptTerms(""))
        assertNull(PersonalDictionary.promptTerms("   \n  \n\t"))
    }

    @Test
    fun `terms trim and join, blank lines drop`() {
        assertEquals(
            "Antinori, Anti-Vocale, Castellazzo",
            PersonalDictionary.promptTerms(" Antinori \n\nAnti-Vocale\n  \nCastellazzo\n"))
    }

    @Test
    fun `a validly stored dictionary is never truncated`() {
        // The save cap is 500 chars; the join's safety nets sit above it,
        // so every stored term reaches BOTH consumers (review F1).
        // A realistically stored 500-char dictionary (60 short terms):
        // fully honored, every term reaches the prompt rider.
        val realistic = (1..60).joinToString("\n") { "term-$it" }
        org.junit.Assert.assertTrue(realistic.length <= 500)
        assertEquals(60, PersonalDictionary.terms(realistic).size)
        org.junit.Assert.assertTrue(
            PersonalDictionary.promptTerms(realistic)!!.contains("term-60"))
    }

    @Test
    fun `the char cap stops at a term boundary, never mid-term`() {
        // TASK-994 review: a truncated spelling teaches the model the exact
        // wrong thing the dictionary exists to prevent. The join must stop
        // BEFORE the first overflowing term, so every emitted term is whole.
        val terms = (1..PersonalDictionary.MAX_TERMS).map { "a-very-long-dictionary-term-number-$it" }
        val joined = PersonalDictionary.promptTerms(terms.joinToString("\n"))!!
        org.junit.Assert.assertTrue(joined.length <= PersonalDictionary.MAX_CHARS)
        // The prefix property: the join is exactly the longest whole-term
        // prefix that fits.
        val fit = generateSequence(1) { if (it < terms.size) it + 1 else null }
            .takeWhile { n -> terms.take(n).joinToString(", ").length <= PersonalDictionary.MAX_CHARS }
            .last()
        assertEquals(terms.take(fit).joinToString(", "), joined)
    }

    @Test
    fun `withVocabularyLine appends only when terms exist`() {
        assertEquals("INSTR", PersonalDictionary.withVocabularyLine("INSTR", "") { "VOCAB: $it" })
        assertEquals(
            "INSTR\n\nVOCAB: A, B",
            PersonalDictionary.withVocabularyLine("INSTR", "A\nB") { "VOCAB: $it" })
    }
    @org.junit.Test
    fun `a max-cap dictionary never loses terms to the join`() {
        // 125 three-char terms store as 499 chars (under the 500 save cap);
        // the ", "-join is 623 chars, so MAX_CHARS must cover it or the
        // rider consumers would silently see fewer terms than Whistle.
        val terms = (1..125).joinToString("\n") { "abc" }
        val stored = terms.replace("\n", ",")
        org.junit.Assert.assertTrue(stored.length <= 500)
        val parsed = com.antivocale.app.transcription.PersonalDictionary.terms(stored)
        val joined = com.antivocale.app.transcription.PersonalDictionary.promptTerms(stored)
        org.junit.Assert.assertEquals(parsed.size, joined?.split(", ")?.size)
    }
}