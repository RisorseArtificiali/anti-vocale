package com.antivocale.app.util

import org.junit.Assert.*
import org.junit.Test

/** TASK-647: the signature assembly consumed by every exit surface. */
class TranscriptSignatureTest {

    @Test
    fun `append puts the signature after the transcript`() {
        assertEquals("ciao\n-- AI --", TranscriptSignature.apply("ciao", "-- AI --", "append"))
    }

    @Test
    fun `prepend puts the signature before the transcript`() {
        assertEquals("-- AI --\nciao", TranscriptSignature.apply("ciao", "-- AI --", "prepend"))
    }

    @Test
    fun `blank signature returns the transcript unchanged`() {
        assertEquals("ciao", TranscriptSignature.apply("ciao", "   ", "append"))
    }

    @Test
    fun `unknown position falls back to append`() {
        assertEquals("ciao\n-- AI --", TranscriptSignature.apply("ciao", "-- AI --", "sideways"))
    }

    @Test
    fun `blank transcript yields the signature alone, no dangling separator`() {
        assertEquals("-- AI --", TranscriptSignature.apply("", "-- AI --", "append"))
        assertEquals("-- AI --", TranscriptSignature.apply("", "-- AI --", "prepend"))
    }

    @Test
    fun `surrounding whitespace is trimmed on both parts`() {
        assertEquals("ciao\ns", TranscriptSignature.apply("  ciao\n", " s ", "append"))
    }
}
