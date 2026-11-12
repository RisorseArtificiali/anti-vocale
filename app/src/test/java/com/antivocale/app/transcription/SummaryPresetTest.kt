package com.antivocale.app.transcription

import com.antivocale.app.transcription.SummaryPolicy.instructionLadder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TASK-993: the preset ladder resolution. These tests pin the three
 * contracts the summary pass depends on: the preset's primary instruction,
 * the TASK-498 fallback (a failing primary attempt degrades to the
 * built-in recap, never a caption), and KEY TRUTH: the stored preset key
 * decides, a prompt under a non-custom preset is dormant text (legacy
 * states are promoted to custom by the one-shot reconcile migration, NOT
 * by runtime arbitration - do not reintroduce a prompt-over-key rule).
 */
class SummaryPresetTest {

    private val builtIn = "BUILT_IN"
    private val items = "ITEMS_PROMPT"
    private val minutes = "MINUTES_PROMPT"

    @Test
    fun `default preset runs the built-in once`() {
        assertEquals(listOf(builtIn), SummaryPolicy.instructionLadder("summary", "", builtIn, items, minutes))
    }

    @Test
    fun `unknown preset key falls back to the built-in`() {
        assertEquals(listOf(builtIn), SummaryPolicy.instructionLadder("garbage", "", builtIn, items, minutes))
        assertEquals(listOf(builtIn), SummaryPolicy.instructionLadder(null, "", builtIn, items, minutes))
    }

    @Test
    fun `action items and minutes presets ladder to the built-in fallback`() {
        assertEquals(listOf(items, builtIn), SummaryPolicy.instructionLadder("action_items", "", builtIn, items, minutes))
        assertEquals(listOf(minutes, builtIn), SummaryPolicy.instructionLadder("meeting_minutes", "", builtIn, items, minutes))
    }

    @Test
    fun `a stored prompt is dormant under a non-custom preset`() {
        // Key-truth (TASK-993 review round): the preset key decides; a
        // non-blank prompt left over under a non-custom preset must NOT
        // hijack the pass (legacy states are promoted to custom by the
        // one-shot reconcile, not by runtime arbitration).
        assertEquals(
            listOf(items, builtIn),
            SummaryPolicy.instructionLadder("action_items", "MY_PROMPT", builtIn, items, minutes))
    }

    @Test
    fun `custom preset with blank prompt degrades to the built-in`() {
        assertEquals(listOf(builtIn), SummaryPolicy.instructionLadder("custom", "", builtIn, items, minutes))
        assertEquals(listOf(builtIn), SummaryPolicy.instructionLadder("custom", "   ", builtIn, items, minutes))
    }

    @Test
    fun `a custom prompt identical to the built-in runs once`() {
        assertEquals(listOf(builtIn), SummaryPolicy.instructionLadder("custom", builtIn, builtIn, items, minutes))
    }
}
