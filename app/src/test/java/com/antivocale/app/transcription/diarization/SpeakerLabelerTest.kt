package com.antivocale.app.transcription.diarization

import com.antivocale.app.transcription.TimedSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GH #83: fixtures derive from the 2026-09-20 prototype's ground truth
 * (claudedocs/diarization-prototype/): six alternating turns A B A B A B
 * over 70.9 s, eleven informative cues, all attributed correctly by
 * speech-time voting at cue level.
 */
class SpeakerLabelerTest {

    private fun cue(startSec: Float, endSec: Float, text: String = "x") =
        TimedSegment((startSec * 1000).toLong(), (endSec * 1000).toLong(), text)

    private fun seg(startSec: Float, endSec: Float, speaker: Int, confidence: Float? = null) =
        DiarizedSegment(startSec, endSec, speaker, confidence)

    /** The prototype file: A 0-12, B 12-24.5, A 24.5-40.5, B 40.5-61.5, A 61.5-66, B 66-70.9. */
    private val prototypeSegments = listOf(
        seg(0f, 12f, 0), seg(12f, 24.5f, 1), seg(24.5f, 40.5f, 0),
        seg(40.5f, 61.5f, 1), seg(61.5f, 66f, 0), seg(66f, 70.9f, 1),
    )

    @Test
    fun `cues inside one speaker's turn get that speaker`() {
        val cues = listOf(cue(0.32f, 7.2f), cue(13.36f, 20.24f), cue(30.16f, 37.12f))
        assertEquals(listOf(0, 1, 0), SpeakerLabeler.label(cues, prototypeSegments))
    }

    @Test
    fun `a cue crossing a turn boundary goes to the majority speaker`() {
        // Cue 11-14 s straddles the A|B boundary at 12 s: 1 s of A, 2 s of B.
        val cues = listOf(cue(11f, 14f))
        assertEquals(listOf(1), SpeakerLabeler.label(cues, prototypeSegments))
    }

    @Test
    fun `an exact tie resolves to the lower dense speaker id`() {
        // 1.5 s each side: an exact tie must not depend on HashMap
        // iteration order; the deterministic rule is the lower id.
        val cues = listOf(cue(10.5f, 13.5f))
        val labels = SpeakerLabeler.label(cues, prototypeSegments)
        assertEquals(1, labels.size)
        assertEquals(0, labels[0])
    }

    @Test
    fun `cues with no overlapping speech get no speaker`() {
        val cues = listOf(cue(80f, 85f), cue(0.32f, 7.2f))
        assertEquals(listOf(null, 0), SpeakerLabeler.label(cues, prototypeSegments))
    }

    @Test
    fun `empty inputs label nothing`() {
        assertNull(SpeakerLabeler.label(listOf(cue(0f, 1f)), emptyList()).single())
        assertEquals(emptyList<Int?>(), SpeakerLabeler.label(emptyList(), prototypeSegments))
    }

    @Test
    fun `sparse cluster ids renumber densely by first appearance`() {
        // The prototype's auto runs emitted {0,1,2,5,7}; dense order keeps
        // first-appearance semantics and the count.
        val renumbered = SpeakerLabeler.denseSegments(
            listOf(seg(0f, 1f, 5), seg(1f, 2f, 0), seg(2f, 3f, 5), seg(3f, 4f, 7)))
        assertEquals(listOf(0, 1, 0, 2), renumbered.map { it.speaker })
        assertEquals(3, renumbered.map { it.speaker }.toSet().size)
    }

    @Test
    fun `the unavailable confidence marker becomes null`() {
        val renumbered = SpeakerLabeler.denseSegments(
            listOf(seg(0f, 1f, 0, confidence = -2f), seg(1f, 2f, 1, confidence = 0.6f)))
        assertEquals(listOf(null, 0.6f), renumbered.map { it.confidence })
    }
}
