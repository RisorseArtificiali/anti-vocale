package com.antivocale.app.ui.components

import com.antivocale.app.transcription.TimedSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-841: the cue-gap skip semantics, pure. The semantic note under
 * test: seeking lands ONLY on cue starts (recognized speech), sub-second
 * pauses stay (prosody, not silence), trailing silence has no target, and
 * the offer gate hides the toggle on rows with nothing to skip.
 */
class PlaybackCueGapsTest {

    private fun cue(start: Long, end: Long) = TimedSegment(startMs = start, endMs = end, text = "t")

    @Test
    fun `a position inside a skippable gap seeks to the next cue start`() {
        val cues = listOf(cue(0, 2_000), cue(6_000, 8_000))
        assertEquals(6_000L, PlaybackCueGaps.seekTarget(cues, 3_000))
        // Near the target, inside the settle window: NO seek (the guard
        // lives here, so the controller never re-seeks what it already
        // reached; re-issuing a pending async seek thrashes the pipeline).
        assertNull(PlaybackCueGaps.seekTarget(cues, 5_900))
    }

    @Test
    fun `a sub-second pause is prosody and stays`() {
        val cues = listOf(cue(0, 2_000), cue(2_700, 4_000))
        assertNull("a 700ms pause must not be skipped", PlaybackCueGaps.seekTarget(cues, 2_300))
        assertFalse(PlaybackCueGaps.hasSkippableGap(cues))
    }

    @Test
    fun `a position inside a cue seeks nowhere`() {
        val cues = listOf(cue(0, 2_000), cue(6_000, 8_000))
        assertNull(PlaybackCueGaps.seekTarget(cues, 1_000))
        assertNull(PlaybackCueGaps.seekTarget(cues, 7_000))
    }

    @Test
    fun `leading silence seeks to the first cue, trailing seeks nowhere`() {
        val cues = listOf(cue(3_000, 4_000))
        assertEquals(3_000L, PlaybackCueGaps.seekTarget(cues, 0))
        assertNull(PlaybackCueGaps.seekTarget(cues, 5_000))
        // A short lead-in is not silence either.
        assertNull(PlaybackCueGaps.seekTarget(listOf(cue(800, 4_000)), 0))
    }

    @Test
    fun `unsorted cues and the offer gate`() {
        val gapless = listOf(cue(0, 2_000), cue(2_100, 4_000), cue(4_050, 6_000))
        assertFalse(PlaybackCueGaps.hasSkippableGap(gapless))
        val gapped = listOf(cue(2_100, 4_000), cue(0, 2_000), cue(9_000, 10_000))
        assertTrue("order must not matter", PlaybackCueGaps.hasSkippableGap(gapped))
        assertFalse("one cue alone has no gaps", PlaybackCueGaps.hasSkippableGap(listOf(cue(0, 5_000))))
    }
}
