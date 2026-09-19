package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK-520: the shared context-guard chunker behind the map-reduce summary. */
class ContextChunkerTest {

    @Test
    fun `text within the budget is identity`() {
        assertEquals(listOf("short text"), ContextChunker.split("short text", maxChars = 100))
        assertEquals(listOf(""), ContextChunker.split("", maxChars = 100))
    }

    @Test
    fun `splits at sentence boundaries when they fit`() {
        val sentences = (1..10).joinToString(" ") { "Sentence $it with some words." }
        val chunks = ContextChunker.split(sentences, maxChars = 60)
        assertTrue("more than one chunk expected", chunks.size > 1)
        chunks.forEach { c ->
            assertTrue("chunk over budget: ${c.length}", c.length <= 60)
            // Every non-final chunk ends at a sentence boundary (hard cuts
            // only ever fire inside a single over-budget word).
            assertTrue("chunk not boundary-terminated: '$c'", c.endsWith("."))
        }
        // No content lost: the join covers every sentence.
        assertEquals(sentences.replace("  ", " ").trim(),
            chunks.joinToString(" ") { it.trim() }.replace("  ", " ").trim())
    }

    @Test
    fun `decimals are never sentence boundaries`() {
        val text = ("The value is 3.14159 and the count is 42. " ).repeat(20)
        val chunks = ContextChunker.split(text.trim(), maxChars = 80)
        chunks.forEach { c ->
            assertTrue("a hard cut inside the decimals: '$c'", c.endsWith("."))
            assertTrue("3.14 split across chunks", !c.endsWith("3.") && !c.endsWith("3.1"))
        }
    }

    @Test
    fun `a word longer than the budget gets a hard cut`() {
        val text = "a".repeat(250)
        val chunks = ContextChunker.split(text, maxChars = 100)
        assertEquals(listOf(100, 100, 50), chunks.map { it.length })
    }

    @Test
    fun `newline boundaries are preferred over mid-sentence cuts`() {
        val lines = (1..10).map { "line $it of the transcript" }.joinToString("\n")
        val chunks = ContextChunker.split(lines, maxChars = 60)
        chunks.forEach { c ->
            assertTrue(c.length <= 60)
            assertTrue("cut inside a line: '$c'", c.endsWith("transcript"))
        }
    }

    @Test
    fun `default budget is the context guard`() {
        val text = "x".repeat(PunctuationPolicy.MAX_TRANSCRIPT_CHARS + 1)
        val chunks = ContextChunker.split(text)
        assertEquals(2, chunks.size)
        assertEquals(PunctuationPolicy.MAX_TRANSCRIPT_CHARS, chunks[0].length)
    }
}
