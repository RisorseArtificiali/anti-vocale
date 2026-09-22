package com.antivocale.app.transcription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-631: the opt-in semantics of the load pre-flight gate. Protection off
 * (the default) never refuses; protection on refuses only on a concrete
 * tight measurement, and an unreadable value (0) fails open.
 */
class MemoryProtectionGateTest {

    @Test
    fun `protection off never refuses even with a tight margin`() {
        assertFalse(shouldRefuseForMemory(protectionOn = false, availBytes = 1L, requiredBytes = 10_000_000_000L))
    }

    @Test
    fun `protection on refuses a tight margin`() {
        assertTrue(shouldRefuseForMemory(protectionOn = true, availBytes = 2_000_000_000L, requiredBytes = 4_000_000_000L))
    }

    @Test
    fun `protection on passes an ample margin`() {
        assertFalse(shouldRefuseForMemory(protectionOn = true, availBytes = 8_000_000_000L, requiredBytes = 4_000_000_000L))
    }

    @Test
    fun `an unreadable memory value fails open`() {
        assertFalse(shouldRefuseForMemory(protectionOn = true, availBytes = 0L, requiredBytes = 4_000_000_000L))
    }
}
