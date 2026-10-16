package com.antivocale.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-809 (GH #146): the transcript-language heuristic. The detector's
 * product role is a HINT gate (fire the advisory only on a clear
 * mismatch), so the tests pin the gates as hard as the verdicts: short
 * text and mixed text yield null (no hint, never a guess), script hits
 * are unambiguous, and the Latin stopword margin rejects coin-flips.
 */
class TranscriptLanguageHeuristicTest {

    @Test
    fun `clear English text is detected`() {
        val text = "Hello there, I think that this is the recording you " +
            "wanted and it was not easy to have it ready for you today."
        assertEquals("en", TranscriptLanguageHeuristic.detect(text))
    }

    @Test
    fun `clear French text is detected`() {
        val text = "Bonjour, je vous appelle pour vous dire que nous " +
            "n'est pas là pour le week-end et que la livraison est prévue."
        assertEquals("fr", TranscriptLanguageHeuristic.detect(text))
    }

    @Test
    fun `English framing with stray French words stays safe`() {
        // English stopwords dominate, one French word present: the margin
        // gate must still resolve (en) or the advisory stays silent -
        // never a coin-flip hint. Constructed so en clears 2x runner-up.
        val text = "So I have that thing you know, and it is the best " +
            "croissant we have for you today, that is what I think."
        val got = TranscriptLanguageHeuristic.detect(text)
        // Either a confident en or null is acceptable product behavior;
        // a wrong non-null is not. en hits: have,that,thing?... assert
        // the only allowed outcomes.
        assertTrue(got == null || got == "en")
    }

    @Test
    fun `short text yields no opinion`() {
        assertNull(TranscriptLanguageHeuristic.detect("Ciao, il mio nome è Carlo."))
        assertNull(TranscriptLanguageHeuristic.detect(""))
    }

    @Test
    fun `mixed Latin languages with no margin yield null`() {
        // Balanced en/fr stopwords: the exact anti-case for a hint.
        val text = "le the la and les is que was nous you pour with have " +
            "dans that est it pas for the le the la and les is que was nous"
        assertNull(TranscriptLanguageHeuristic.detect(text))
    }

    @Test
    fun `same-script device languages never fire the advisory`() {
        // uk shares the Cyrillic block with the heuristic's "ru": a
        // correct Ukrainian transcript on a uk device must NOT read as a
        // mismatch (the block arm cannot tell them apart by design).
        val guessed = TranscriptLanguageHeuristic.detect(
            "Привіт, мене звати Оксана і я хочу сказати що це запис для перевірки")
        assertEquals("ru", guessed) // block only
        assertTrue(!TranscriptLanguageHeuristic.canDistinguish(guessed!!, "uk"))
        // Different scripts are always distinguishable.
        assertTrue(TranscriptLanguageHeuristic.canDistinguish("ru", "fr"))
        // Same script, both in the stopword map: distinguishable.
        assertTrue(TranscriptLanguageHeuristic.canDistinguish("fr", "en"))
        // Same script, device not in the map: NOT distinguishable (the
        // Romanian-manufactures-fr class).
        assertTrue(!TranscriptLanguageHeuristic.canDistinguish("fr", "ro"))
    }

    @Test
    fun `the sample cap keeps the verdict stable`() {
        val head = "Bonjour, je vous appelle pour vous dire que nous ne " +
            "sommes pas là pour le week-end et que la livraison est prévue. "
        val tail = "blah ".repeat(2_000)
        assertEquals(
            TranscriptLanguageHeuristic.detect(head),
            TranscriptLanguageHeuristic.detect(head + tail))
    }

    @Test
    fun `cyrillic script wins by block`() {
        val text = "Привет, меня зовут Сергей и я хочу сказать что это " +
            "тестовая запись для проверки определения языка."
        assertEquals("ru", TranscriptLanguageHeuristic.detect(text))
    }

    @Test
    fun `hebrew script wins by block`() {
        val text = "שלום, קוראים לי דני ואני רוצה להגיד שזו הקלטה לבדיקת " +
            "הזיהוי של השפה בטקסט."
        assertEquals("iw", TranscriptLanguageHeuristic.detect(text))
    }

    @Test
    fun `a latin transcript with a ten-percent foreign word stays latin`() {
        // Review F4: the one-tenth bound alone let a single 7-letter
        // Russian word flip a 70-letter transcript at exactly 10%. The
        // block must also exceed the Latin count to win.
        val text = ("The thing about this recording is that the level " +
            "привет here stays English for the rest of the message " +
            "because that is what it is, clearly.")
        val got = TranscriptLanguageHeuristic.detect(text)
        assertTrue(got == null || got == "en")
    }

    @Test
    fun `portuguese stopword overlaps stay on the right side of the margin`() {
        // The pt/es shared words (que, de, para, como, uma/una) compress
        // the margin but never manufacture the wrong call: pt's unique
        // stopwords (não, você, está) carry it over the 2x line.
        val text = "Eu não sei como você está hoje, mas a verdade não é " +
            "aquela que você pensa e não vamos mudar isso agora."
        assertEquals("pt", TranscriptLanguageHeuristic.detect(text))
    }

    @Test
    fun `a latin transcript with one foreign name stays latin`() {
        val text = "Ciao, il mio nome non è Iván ma il libro che ti ho " +
            "detto è anche quello con la copertina rossa per la scuola."
        assertEquals("it", TranscriptLanguageHeuristic.detect(text))
    }
}
