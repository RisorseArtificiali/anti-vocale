package com.antivocale.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-928: the pure tap geometry. The contract a row rides on: the word
 * count of the text IS the count of the starts, a tap inside a word lands
 * on that word's start, and anything else is an honest null.
 */
class TranscriptWordTapsTest {

    @Test
    fun `a tap inside a word lands on its start`() {
        // "ciao mondo": ciao 0..3, mondo 5..9.
        val taps = TranscriptWordTaps.of("ciao mondo", listOf(100L, 600L))!!
        assertEquals(100L, taps.startMsAt(0))
        assertEquals(100L, taps.startMsAt(3))
        assertEquals(600L, taps.startMsAt(5))
        assertEquals(600L, taps.startMsAt(9))
    }

    @Test
    fun `punctuation rides its word`() {
        val taps = TranscriptWordTaps.of("mondo,", listOf(600L))!!
        assertEquals(600L, taps.startMsAt(4))
        assertEquals(600L, taps.startMsAt(5))
    }

    @Test
    fun `spaces and past-the-end are no-ops`() {
        val taps = TranscriptWordTaps.of("ciao mondo", listOf(100L, 600L))!!
        assertNull(taps.startMsAt(4))
        assertNull(taps.startMsAt(10))
        assertNull(taps.startMsAt(-1))
    }

    @Test
    fun `a count mismatch builds nothing`() {
        // Two displayed words, one timed word: the row stays read-only.
        assertNull(TranscriptWordTaps.of("ciao mondo", listOf(100L)))
        assertNull(TranscriptWordTaps.of("ciao", listOf(100L, 600L)))
    }

    @Test
    fun `an empty text with no starts is a valid empty target`() {
        val taps = TranscriptWordTaps.of("", emptyList())!!
        assertNull(taps.startMsAt(0))
    }
}
