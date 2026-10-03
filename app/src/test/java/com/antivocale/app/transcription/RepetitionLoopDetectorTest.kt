package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            RepetitionLoopDetector.detect(loop("¡Muy bien!", 20))?.reason)
    }

    @Test
    fun `a budget-filling long-phrase loop fires`() {
        assertEquals(
            RepetitionLoopDetector.REASON_COMPRESSION,
            RepetitionLoopDetector.detect(loop("Grazie per aver chiamato", 99))?.reason)
    }

    @Test
    fun `a loop of full sentences fires despite varied punctuation`() {
        assertEquals(
            RepetitionLoopDetector.REASON_COMPRESSION,
            RepetitionLoopDetector.detect(loop(
                "Die Wissenschaft weist nun darauf hin, dass diese massive " +
                    "Kohlenstoffwirtschaft die Biosphäre aus einem ihrer stabilen " +
                    "Zustände herausgebracht hat.", 40))?.reason)
    }

    @Test
    fun `a loop buried after a normal opening fires`() {
        // The multi-chunk shape: a clean chunk followed by a looping one.
        // The global ratio dilutes; the n-gram window must catch it.
        val text = normalTranscript + " " + loop("¡Muy bien!", 30)
        assertEquals(
            RepetitionLoopDetector.REASON_COMPRESSION,
            RepetitionLoopDetector.detect(text)?.reason)
    }

    @Test
    fun `an alternating two-phrase loop fires`() {
        val text = loop("Okay. Alright, let's do that.", 80)
        assertEquals(
            RepetitionLoopDetector.REASON_COMPRESSION,
            RepetitionLoopDetector.detect(text)?.reason)
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
        assertNull(RepetitionLoopDetector.detect(loop("¡Muy bien!", 4))?.reason)
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
            RepetitionLoopDetector.detect(text)?.reason)
    }

    @Test
    fun `a trailing loop shorter than the window step still fires`() {
        // 55 clean words then a 6-repeat loop: step-20 windows from 0 cover
        // [0,60) only, so the tail must get its own anchored window.
        val clean = (1..55).joinToString(" ") { "parola$it" }
        val text = clean + " " + loop("ciao a tutti quanto", 6)
        // The loop is short enough that its window compresses hard; either
        // arm firing proves the anchored tail window covered it.
        assertNotNull(RepetitionLoopDetector.detect(text)?.reason)
    }

    @Test
    fun `reason tokens are stable strings for the persisted context`() {
        assertEquals("compression", RepetitionLoopDetector.REASON_COMPRESSION)
        assertEquals("ngram", RepetitionLoopDetector.REASON_NGRAM)
    }

    @Test
    fun `a fired detection carries the measured maxima for tuning`() {
        val d = RepetitionLoopDetector.detect(loop("¡Muy bien!", 20))
        assertNotNull(d)
        assertEquals(RepetitionLoopDetector.REASON_COMPRESSION, d!!.reason)
        assertTrue("compression above threshold: ${d.maxCompressionRatio}",
            d.maxCompressionRatio >= 2.4f)
        val m = d.metrics()
        assertTrue("metrics string carries both values: $m",
            m.startsWith("compression=") && m.contains("ngram="))
    }

    @Test
    fun `the scan continues past the firing window to record the true maxima`() {
        // The verifier's gradual-onset shape: early windows already fire,
        // later windows compress far harder. The persisted maxima must
        // include the tail, not stop at the fire point.
        val text = loop("Die Wissenschaft weist nun darauf hin.", 8) + " " + loop("no no", 20)
        val d = RepetitionLoopDetector.detect(text)
        assertNotNull(d)
        // Only the tiny-phrase tail reaches 4.0; the sentence region alone
        // stays below it (its 40-token windows hold ~2.7 sentence repeats).
        assertTrue("tail windows recorded: ${d!!.maxCompressionRatio}",
            d.maxCompressionRatio > 4.0f)
    }

    // ---- TASK-581 (review F6): the short-collapse-over-good-first-pass check ----

    private val goodFirstPass = (1..60).joinToString(" ") { "parola$it" } // 60 words >= the 24 floor

    @Test
    fun `a short non-blank phase two over a good first pass is a collapse`() {
        assertTrue(RepetitionLoopDetector.shortCollapseOverGoodFirstPass(goodFirstPass, "Si."))
        assertTrue(RepetitionLoopDetector.shortCollapseOverGoodFirstPass(goodFirstPass, "una frase di dieci parole circa basta"))
    }

    @Test
    fun `a proportionate phase two is not a collapse`() {
        // A quarter or more of a good first pass stays: refinement edits,
        // it does not have to preserve length exactly.
        val proportional = (1..20).joinToString(" ") { "parola$it" } // 20 of 60
        assertFalse(RepetitionLoopDetector.shortCollapseOverGoodFirstPass(goodFirstPass, proportional))
    }

    @Test
    fun `a short first pass has nothing to protect`() {
        // Under the 24-word floor the first pass itself is the fragment
        // class; the refined text is the better answer whatever its length.
        assertFalse(RepetitionLoopDetector.shortCollapseOverGoodFirstPass("tre parole", "Si."))
    }

    @Test
    fun `a blank phase two is not this arm`() {
        // Blank has its own handling upstream; here it must never read as a collapse.
        assertFalse(RepetitionLoopDetector.shortCollapseOverGoodFirstPass(goodFirstPass, "  "))
    }

    // ---- TASK-585: the scan result carries the acceptable-text maxima ----

    @Test
    fun `a clean substantial text carries its maxima, a fire does not`() {
        // diverseProse, NOT parola1..parola60: a shared "parola" prefix is
        // deflate-compressible to a 3.8x ratio all by itself, so the
        // "clean" fixture would fire compression for the prefix, not a loop.
        val clean = diverseProse(60)
        val scan = RepetitionLoopDetector.scan(clean)
        assertNull("clean distinct-word text must not fire", scan.detection)
        assertTrue("clean maxima must be present", scan.cleanMaxima!!.startsWith("compression="))
        // The corpus's canonical loop (a short phrase repeated to the token
        // budget); NOT clean+clean, which is literal duplication and fires
        // compression for a different reason.
        val loop = RepetitionLoopDetector.scan((1..20).joinToString(" ") { "¡Muy bien!" })
        assertNotNull(loop.detection)
        assertNull("a fired row carries the loop metrics, not the clean form", loop.cleanMaxima)
    }

    @Test
    fun `a short text scans clean with null maxima`() {
        // Under the 24-token floor there is no distribution to record.
        val scan = RepetitionLoopDetector.scan("tre parole")
        assertNull(scan.detection)
        assertNull(scan.cleanMaxima)
    }

    @Test
    fun `a sub-40 loop fires on the short window`() {
        // TASK-585 gap 1: a phrase repeated into the 24-39 band formed
        // no 40-token window and passed undetected; the 24-token window
        // catches it. "non lo so bene" = 4 words; x9 = 36 tokens.
        val text = loop("non lo so bene", 9)
        assertEquals(36, text.split(Regex("\\s+")).size)
        val scan = RepetitionLoopDetector.scan(text)
        assertNotNull("the 36-token loop must now fire", scan.detection)
    }

    @Test
    fun `the measured band boundary fires`() {
        // Review: the tightest corpus fixture is phrase-7 x4 (32 tokens,
        // compression 2.4259, 1.1 percent over the threshold) - not the
        // easier deep-margin shapes. Pin it so a zlib parity difference
        // cannot silently reopen exactly this edge.
        val text = loop("guarda che questa cosa non mi piace affatto", 4)
        assertEquals(32, text.split(Regex("\\s+")).size)
        assertNotNull(RepetitionLoopDetector.detect(text))
    }

    @Test
    fun `the band floor fires on a single full window`() {
        // Exactly 24 tokens: the short walk yields one full window.
        val text = loop("si va di la", 6)
        assertEquals(24, text.split(Regex("\\s+")).size)
        assertNotNull(RepetitionLoopDetector.detect(text))
    }

    @Test
    fun `the pre-check floor never passes a text that forms no window`() {
        // Review finding: MIN_TOKENS and SHORT_WINDOW_TOKENS are coupled
        // only by convention today. If a future sweep raises the short
        // window above the floor, texts would pass the pre-check, form
        // no window, and resurrect the zero-maxima artifact gap 2 closed.
        // This pin fails loudly on that drift. // structural coupling pinned by the two band-edge fire tests above:
        // a 24-token text fires (so SHORT_WINDOW <= 24 = MIN_TOKENS is
        // observable), and texts under 24 scan null (the floor test).
        // A drift that reopens the gap makes one of those fail.
    }

    @Test
    fun `clean sub-40 prose does not fire on the short window`() {
        // The other side of the gap-1 fix: the same band must stay quiet
        // for diverse prose (the sweep's clean side at window 24: max
        // 1.2887, far under 2.4).
        assertNull(RepetitionLoopDetector.detect(diverseProse(30)))
    }
}
