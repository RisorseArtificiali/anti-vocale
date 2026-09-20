package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-579: the corpus is the documented failure class plus the
 * must-pass reals from the 2026-09-20 spike (see the detector's KDoc
 * for thresholds and the rejected wps arm).
 */
class RepetitionLoopDetectorTest {

    private fun loop(phrase: String, times: Int) = (List(times) { phrase }).joinToString(" ")

    private val normalTranscript = """
        Ihr erstes war der Slalom, bei dem sie in ihrem ersten Lauf ein Did not finish
        erhielt. 6 der 116 Teilnehmer erzielten in diesem Rennen das gleiche Ergebnis.
        Hier diskutierten Probleme in der Regel viel detaillierter ab. Normalerweise in
        Kombination mit praktischer Erfahrung. Die lokalen Behörden ermahnen die Einwohner
        in der Nähe der Anlage dazu, sich innerhalb von Gebäuden aufzuhalten und kein
        Leitungswasser zu trinken. Auf vereisten und verschneiten Straßen ist die Haftung
        gering. Handgefertigte Produkte können als Antik bezeichnet werden, obwohl sie
        nur als ähnliche Erzeugnis aus Massenproduktion sind.
    """.trimIndent()

    @Test
    fun `the Muy bien incident transcript fires on compression`() {
        // 20 repeats: the exact user incident shape (19.8 s budget-filled loop).
        assertEquals(
            RepetitionLoopDetector.REASON_COMPRESSION,
            RepetitionLoopDetector.detect(loop("¡Muy bien!", 20)))
    }

    @Test
    fun `a budget-filling long-phrase loop fires`() {
        assertEquals(
            RepetitionLoopDetector.REASON_COMPRESSION,
            RepetitionLoopDetector.detect(loop("Grazie per aver chiamato", 99)))
    }

    @Test
    fun `a loop of full sentences fires despite varied punctuation`() {
        assertEquals(
            RepetitionLoopDetector.REASON_COMPRESSION,
            RepetitionLoopDetector.detect(loop(
                "Die Wissenschaft weist nun darauf hin, dass diese massive " +
                    "Kohlenstoffwirtschaft die Biosphäre aus einem ihrer stabilen " +
                    "Zustände herausgebracht hat.", 40)))
    }

    @Test
    fun `a loop buried after a normal opening fires`() {
        // The multi-chunk shape: a clean chunk followed by a looping one.
        // The global ratio dilutes; the n-gram window must catch it.
        val text = normalTranscript + " " + loop("¡Muy bien!", 30)
        assertEquals(
            RepetitionLoopDetector.REASON_COMPRESSION,
            RepetitionLoopDetector.detect(text))
    }

    @Test
    fun `an alternating two-phrase loop fires`() {
        val text = loop("Okay. Alright, let's do that.", 80)
        assertEquals(
            RepetitionLoopDetector.REASON_COMPRESSION,
            RepetitionLoopDetector.detect(text))
    }

    @Test
    fun `normal transcripts pass`() {
        assertNull(RepetitionLoopDetector.detect(normalTranscript))
        assertNull(RepetitionLoopDetector.detect(
            "Ma che cazzo dice?" +
                " Questo è un messaggio vocale normale con una frase intera" +
                " e poi un'altra frase ancora, abbastanza lunga da superare" +
                " la soglia minima di parole del rilevatore senza ripeterle."))
    }

    @Test
    fun `condensations never fire, however short`() {
        // A summary of an hour-long recording: the AC the length-ratio guard broke.
        assertNull(RepetitionLoopDetector.detect(
            "Zusammenfassung: der Sprecher berichtet über seinen ersten Slalom-Wettkampf " +
                "und anschließend über ein wissenschaftliches Thema zur Kohlenstoffwirtschaft " +
                "sowie zum Schluss über gefrorene Straßen und handgefertigte Produkte."))
        assertNull(RepetitionLoopDetector.detect("Ma che cazzo dice?"))
    }

    @Test
    fun `short texts are exempt even when internally repetitive`() {
        // Under the word floor: cosmetic repeats in a brief answer are not the class.
        assertNull(RepetitionLoopDetector.detect(loop("¡Muy bien!", 4)))
    }

    @Test
    fun `a long clean transcript does not fire`() {
        // The 2026-09-20 review measured clean prose crossing a whole-text
        // 2.4 ratio near 1950 Italian words (this fixture's whole-text
        // ratio measures 22.6 under zlib; its worst 40-word window 1.68),
        // which is exactly why both arms are windowed.
        assertNull(RepetitionLoopDetector.detect(diverseProse(2200)))
    }

    /** Deterministic prose-shaped text: a real vocabulary revisited with a
     *  stride mix, so windows stay diverse while the whole text compresses
     *  without bound like real prose. */
    private fun diverseProse(words: Int): String {
        val vocab = listOf(
            "casa", "giorno", "tempo", "vita", "mano", "parte", "mondo", "occhio",
            "donna", "uomo", "anno", "ora", "modo", "cosa", "punto", "serie",
            "numero", "città", "nome", "gente", "signore", "signora", "bambino",
            "acqua", "fuoco", "terra", "aria", "mare", "monte", "fiume", "bosco",
            "strada", "piazza", "chiesa", "scuola", "libro", "parola", "voce",
            "canto", "musica", "gioco", "sport", "cibo", "vino", "pane", "frutta",
            "estate", "inverno", "primavera", "autunno", "pioggia", "neve",
            "vento", "sole", "luna", "stella", "notte", "mattino", "sera",
            "lavoro", "riposo", "sonno", "sogno", "pensiero", "ricordo", "amore",
            "amicizia", "famiglia", "madre", "padre", "figlio", "fratello",
            "sorella", "nonna", "nonno", "macchina", "treno", "aereo", "nave",
            "bicicletta", "viaggio", "vacanza", "ristorante", "caffè",
            "colazione", "pranzo", "cena", "dolce", "torta", "gelato",
            "medico", "farmacia", "ospedale", "salute", "guarigione", "dolore",
            "gioia", "tristezza")
        return (0 until words).joinToString(" ") { i ->
            vocab[(i * 37 + (i / 41) * 13) % vocab.size]
        }
    }

    @Test
    fun `a whitespace-free CJK loop fires`() {
        // Whitespace-free scripts collapse to one word; without the
        // codepoint fallback both arms would bypass the loop entirely.
        val text = "ありがとうございます。".repeat(60)
        assertEquals(
            RepetitionLoopDetector.REASON_COMPRESSION,
            RepetitionLoopDetector.detect(text))
    }

    @Test
    fun `a trailing loop shorter than the window step still fires`() {
        // 55 clean words then a 6-repeat loop: step-20 windows from 0 cover
        // [0,60) only, so the tail must get its own anchored window.
        val clean = (1..55).joinToString(" ") { "parola$it" }
        val text = clean + " " + loop("ciao a tutti quanto", 6)
        // The loop is short enough that its window compresses hard; either
        // arm firing proves the anchored tail window covered it.
        org.junit.Assert.assertNotNull(RepetitionLoopDetector.detect(text))
    }

    @Test
    fun `reason tokens are stable strings for the persisted context`() {
        assertEquals("compression", RepetitionLoopDetector.REASON_COMPRESSION)
        assertEquals("ngram", RepetitionLoopDetector.REASON_NGRAM)
    }
}
