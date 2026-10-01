package com.antivocale.app.transcription.diarization

import com.antivocale.app.transcription.TimedSegment
import com.antivocale.app.transcription.TimedToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-678 (GH #83): the straddling-cue re-split, the pure half. The ACs
 * map: word-boundary split (AC1), the tier chain (AC2), word conservation
 * (AC3), boundary-inside-a-word / adjacent / no-tokens / overlapping (AC4).
 */
class SpeakerResplitTest {

    /** Tokens for the words "alpha beta gamma delta" spread over 0..4000ms. */
    private fun fourWordTokens(): List<TimedToken> = listOf(
        TimedToken("▁alpha", 0, 800),
        TimedToken("▁beta", 1000, 1800),
        TimedToken("▁gamma", 2000, 2800),
        TimedToken("▁delta", 3000, 4000),
    )

    private val cue = TimedSegment(
        startMs = 0, endMs = 4000,
        text = "alpha beta gamma delta",
        speaker = 0, speakerName = "Chiara",
    )

    /** Speaker 1 holds 1200..4000 (70% of the cue): a real straddle. */
    private val straddle = listOf(
        DiarizedSegment(0f, 1.2f, 0, 0.9f),
        DiarizedSegment(1.2f, 4.0f, 1, 0.9f),
    )

    @Test
    fun `tier one splits at the word nearest the boundary`() {
        val outcome = SpeakerResplit.resplit(cue, fourWordTokens(), straddle)
        assertEquals(SpeakerResplit.Tier.WORD_BOUNDARY, outcome.tier)
        assertEquals(2, outcome.cues.size)
        // Boundary 1200ms; word-gap midpoints: alpha/beta 900, beta/gamma
        // 1900, gamma/delta 2900. Nearest to 1200 is 900 (alpha/beta).
        val (first, second) = outcome.cues
        assertEquals("alpha", first.text)
        assertEquals("beta gamma delta", second.text)
        // Each half carries ITS OWN voice: 0 before the boundary, 1 after.
        assertEquals(0, first.speaker)
        assertEquals(1, second.speaker)
        assertTrue("halves touch, in order", second.startMs >= first.endMs)
    }

    @Test
    fun `word conservation holds across the split`() {
        val outcome = SpeakerResplit.resplit(cue, fourWordTokens(), straddle)
        val original = cue.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val rebuilt = outcome.cues.flatMap { it.text.split(Regex("\\s+")).filter { w -> w.isNotEmpty() } }
        assertEquals(original, rebuilt)
    }

    @Test
    fun `no tokens degrade to the proportional tier two`() {
        val outcome = SpeakerResplit.resplit(cue, emptyList(), straddle)
        assertEquals(SpeakerResplit.Tier.SILENCE_GAP, outcome.tier)
        assertEquals(2, outcome.cues.size)
        // Four even words over 4000ms, boundary 1200ms: the split nearest is
        // after word 1 (alpha alone) or word 2 - both halves must be >= 400ms.
        val rebuilt = outcome.cues.flatMap { it.text.split(Regex("\\s+")).filter { w -> w.isNotEmpty() } }
        assertEquals(listOf("alpha", "beta", "gamma", "delta"), rebuilt)
    }

    @Test
    fun `a single word lands on the honest mixed label`() {
        val single = cue.copy(text = "alpha", startMs = 0, endMs = 900)
        val outcome = SpeakerResplit.resplit(
            single,
            listOf(TimedToken("▁alpha", 0, 900)),
            listOf(
                DiarizedSegment(0f, 0.5f, 0, 0.9f),
                DiarizedSegment(0.5f, 0.9f, 1, 0.9f),
            ),
        )
        assertEquals(SpeakerResplit.Tier.MIXED_LABEL, outcome.tier)
        val mixed = outcome.cues.single()
        assertTrue(mixed.mixedSpeakers)
        assertNull(mixed.speaker)
        assertNull(mixed.speakerName)
    }

    @Test
    fun `token words disagreeing with the text degrade instead of splitting blind`() {
        // Three token groups for a four-word text: the counts disagree.
        val badTokens = fourWordTokens().drop(1)
        val outcome = SpeakerResplit.resplit(cue, badTokens, straddle)
        // Degrade lands on tier two (proportional), NOT a blind token split.
        assertEquals(SpeakerResplit.Tier.SILENCE_GAP, outcome.tier)
    }

    @Test
    fun `a short second voice is jitter, not a straddle`() {
        val jitter = listOf(
            DiarizedSegment(0f, 3.8f, 0, 0.9f),
            DiarizedSegment(3.8f, 4.0f, 1, 0.9f), // 200ms < the 300ms floor
        )
        val outcome = SpeakerResplit.resplit(cue, fourWordTokens(), jitter)
        assertNull(outcome.tier)
        // Unsplit, tokens consumed; no fallbackSpeaker passed, so the id
        // waits for the caller's label (same geometry, honest labeling).
        assertEquals(listOf(cue.copy(tokens = emptyList(), speaker = null)), outcome.cues)
    }

    @Test
    fun `a surrounding overlap has no interior boundary, honest mix`() {
        val surrounding = listOf(DiarizedSegment(0f, 10f, 1, 0.9f))
        val outcome = SpeakerResplit.resplit(cue, fourWordTokens(), surrounding)
        // Single voice total: second voice IS the only voice; no votes pair
        // exists, so no action (the cue is one speaker's).
        assertNull(outcome.tier)
    }

    @Test
    fun `halves too short stay whole with the mixed label`() {
        // Boundary at 300ms with words at 0..300, 300..600, ... : any split
        // leaves a half under the 400ms floor when the cue itself is short.
        val short = TimedSegment(0, 700, "alpha beta", speaker = 0)
        val tokens = listOf(
            TimedToken("▁alpha", 0, 300),
            TimedToken("▁beta", 300, 700),
        )
        val outcome = SpeakerResplit.resplit(
            short, tokens,
            listOf(
                DiarizedSegment(0f, 0.3f, 0, 0.9f),
                DiarizedSegment(0.3f, 0.7f, 1, 0.9f),
            ),
        )
        assertEquals(SpeakerResplit.Tier.MIXED_LABEL, outcome.tier)
        assertTrue(outcome.cues.single().mixedSpeakers)
        assertFalse(outcome.cues.single().speakerName != null)
    }
}
