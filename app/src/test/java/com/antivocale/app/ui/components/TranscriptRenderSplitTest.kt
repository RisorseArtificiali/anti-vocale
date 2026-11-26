package com.antivocale.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-1034 (GH #190): the long-transcript render split. The panel now
 * renders EVERY character as per-paragraph blocks, so the two properties
 * pinned here are the ones the render depends on: the split is an exact
 * partition of the original (concatenation identity, so global word-tap
 * offsets and search offsets stay correct across blocks) and no block can
 * approach the per-node Constraints limit that bricked History before
 * TASK-506 (the monster case, AC#2 of the task).
 */
class TranscriptRenderSplitTest {

    @Test
    fun `a short text is one block at base zero`() {
        val blocks = splitForRender("hello world", maxBlockChars = 100, maxBlockLines = 100)
        assertEquals(listOf(RenderBlock(0, "hello world")), blocks)
    }

    @Test
    fun `paragraph windows split on complete lines with global bases`() {
        val text = "a\n\nb\n\nc"
        val blocks = splitForRender(text, maxBlockChars = 4, maxBlockLines = 100)
        // Behavior, not a specific latest-vs-earliest newline choice: no
        // block splits inside a line (every interior seam neighbors a
        // newline), the bases are offsets in the ORIGINAL string, and the
        // concatenation is exact.
        assertEquals(text, blocks.joinToString("") { it.text })
        assertTrue(blocks.size >= 2)
        val seams = blocks.zipWithNext { l, r -> l.base + l.text.length }
        assertTrue("every interior seam sits on a line boundary",
            seams.all { text[it] == '\n' })
        assertEquals(blocks.drop(1).map { it.base }, seams)
    }

    @Test
    fun `a monster no-newline paragraph chunks within the ceiling`() {
        val text = "x".repeat(50_000)
        val blocks = splitForRender(text, maxBlockChars = 4_000, maxBlockLines = 100_000)
        // AC#2, mechanically: no measured node above the ceiling.
        assertTrue("every block is within the ceiling",
            blocks.all { it.text.length <= 4_000 })
        assertEquals("nothing lost", text, blocks.joinToString("") { it.text })
        assertEquals("bases are the running offsets",
            (0 until 50_000 step 4_000).toList(), blocks.map { it.base })
    }

    @Test
    fun `a newline-dense window is line-bounded, not just char-bounded`() {
        // The Constraints breach is LINE-count driven (code-review round):
        // a repetition loop of newline tokens yields windows that are
        // nearly all lines; the char ceiling alone would permit ~4000-line
        // blocks (~264K px at typical density), past the 262142px limit.
        val text = "\n".repeat(30_001) // past the panel threshold, all lines
        val blocks = splitForRender(text, maxBlockChars = 4_000, maxBlockLines = 600)
        assertTrue("no block exceeds the line ceiling",
            blocks.all { it.text.count { c -> c == '\n' } <= 600 })
        assertEquals("nothing lost", text, blocks.joinToString("") { it.text })
        assertTrue("actually line-split", blocks.size >= 50)
    }

    @Test
    fun `the task-506 monster case splits safely`() {
        // The 1.28M-character repetition-loop class, no newline, no space:
        // the hard-cut fallback. Ceiling + identity are what AC#2 needs.
        val text = "loop ".repeat(256_000) // 1.28M chars, whitespace runs
        val blocks = splitForRender(text, maxBlockChars = 4_000, maxBlockLines = 100_000)
        assertTrue(blocks.all { it.text.length <= 4_000 })
        assertEquals(text, blocks.joinToString("") { it.text })
        assertTrue("the monster is actually split", blocks.size >= 300)
    }

    @Test
    fun `a whitespace-boundary chunk never splits mid-word when a space exists`() {
        // A long single paragraph WITH spaces: the chunk boundary prefers
        // the latest whitespace in the window, so blocks end on word edges.
        val words = (1..2_000).joinToString(" ") { "word$it" }
        val blocks = splitForRender(words, maxBlockChars = 500, maxBlockLines = 100_000)
        assertEquals(words, blocks.joinToString("") { it.text })
        // A word is never split: at every block boundary, at least one side
        // of the seam is whitespace (the boundary sits BETWEEN words).
        val seams = blocks.zipWithNext { a, b -> a.base + a.text.length }
        assertTrue("every chunk seam sits between words",
            seams.all { i -> words[i - 1] == ' ' || words[i] == ' ' })
    }
}
