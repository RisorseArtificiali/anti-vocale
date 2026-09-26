package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TASK-672 (GH #72): the collapse tiers, ported from the Omnivoice
 * reference (their fixtures and ours: the TASK-506 loop class, the
 * five-repeat rhetorical floor, the 60-char unit bound, and
 * idempotence).
 */
class RepetitionCollapseTest {

    @Test
    fun `exactly six identical words keep one copy`() {
        // Review F1: one copy survives; dropping the whole run amputated
        // emphatic speech (laughter, refusal) once past the threshold.
        assertEquals(
            "check this url later",
            RepetitionCollapse.collapse("check this url url url url url url later"))
    }

    @Test
    fun `five repeats survive`() {
        // Rhetorical repetition: the floor is five, six-in-a-row is a loop.
        assertEquals(
            "no, no, no, no, no",
            RepetitionCollapse.collapse("no, no, no, no, no"))
        assertEquals(
            "wow wow wow wow wow",
            RepetitionCollapse.collapse("wow wow wow wow wow"))
    }

    @Test
    fun `punctuation and case variants join one run`() {
        // "URL", "url." and "url," share one run identity, so the mixed
        // stream drops together.
        assertEquals(
            "found URL, today",
            RepetitionCollapse.collapse("found URL, url. URL url URL url URL today"))
    }

    @Test
    fun `multi-word loop collapses via the character pass`() {
        // No consecutive identical tokens: only the character-level pass
        // can see the 20-char unit ("thanks for watching ") repeat.
        assertEquals(
            "hi there bye",
            RepetitionCollapse.collapse(
                "hi there " + "thanks for watching ".repeat(6).trim() + " bye"))
    }

    @Test
    fun `no-space script loops collapse`() {
        // CJK and Thai carry no whitespace: the word pass sees one token.
        assertEquals(
            "hello bye",
            RepetitionCollapse.collapse("hello " + "非常感謝".repeat(8) + " bye"))
        assertEquals(
            "hello bye",
            RepetitionCollapse.collapse("hello " + "ขอบคุณครับ".repeat(8) + " bye"))
    }

    @Test
    fun `emphasis runs survive`() {
        // The two-char unit floor: a single elongated letter can never
        // form a repeating unit.
        assertEquals(
            "hmmmmm okay wooooow",
            RepetitionCollapse.collapse("hmmmmm okay wooooow"))
    }

    @Test
    fun `six all-punctuation tokens form no word run`() {
        // All-punctuation tokens never join a run, so the word pass keeps
        // them; this stream also carries no matchable character unit.
        assertEquals(
            ", , , , , ,",
            RepetitionCollapse.collapse(", , , , , ,"))
    }

    @Test
    fun `the repeating unit is bounded at sixty chars`() {
        // A 10-char unit repeated six times collapses...
        assertEquals("", RepetitionCollapse.collapse("abcdefghij".repeat(6)))
        // ...while a 61-char unit of pairwise-distinct chars (no shorter
        // period can exist) survives the same six repeats.
        val longUnit =
            "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".take(61)
        val text = longUnit.repeat(6)
        assertEquals(text, RepetitionCollapse.collapse(text))
    }

    @Test
    fun `legitimate six-plus emphasis keeps its content`() {
        // Review F1: the repetition IS the content; one copy carries it.
        assertEquals(
            "guarda ah che ridere",
            RepetitionCollapse.collapse("guarda ah ah ah ah ah ah ah che ridere"))
        assertEquals(
            "ti dico no e basta",
            RepetitionCollapse.collapse("ti dico no no no no no no e basta"))
    }

    @Test
    fun `clean text with newlines and double spaces is returned unchanged`() {
        // Review F5: a no-op pass must not flatten formatting.
        val text = "prima riga del resoconto\nseconda riga  con doppio spazio"
        assertEquals(text, RepetitionCollapse.collapse(text))
    }

    @Test
    fun `empty and blank text pass through`() {
        assertEquals("", RepetitionCollapse.collapse(""))
        // Review F5: blank in, blank out unchanged (no rewrite without a loop).
        assertEquals("   ", RepetitionCollapse.collapse("   "))
    }

    @Test
    fun `a real loop transcript keeps its prose`() {
        // The TASK-506 data class: clean prose around an exactly-6 run.
        assertEquals(
            "ti ringrazio molto grazie arrivederci",
            RepetitionCollapse.collapse(
                "ti ringrazio molto grazie grazie grazie grazie grazie grazie arrivederci"))
        assertEquals(
            "pronto pronto? no ti sento bene",
            RepetitionCollapse.collapse(
                "pronto pronto? no no no no no no ti sento bene"))
    }

    @Test
    fun `collapse is idempotent`() {
        val loops = listOf(
            "check this url url url url url url later",
            "found URL, url. URL url URL url URL today",
            "hi there " + "thanks for watching ".repeat(6).trim() + " bye",
            "hello " + "非常感謝".repeat(8) + " bye",
            "abcdefghij".repeat(6),
        )
        for (loop in loops) {
            val once = RepetitionCollapse.collapse(loop)
            assertEquals("second pass changed: $once", once, RepetitionCollapse.collapse(once))
        }
    }
}
