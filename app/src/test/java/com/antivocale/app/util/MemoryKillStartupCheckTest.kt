package com.antivocale.app.util

import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-426: the pure halves of the MemoryLimiter advisory (the read rides
 * NativeCrashDetector.mostRecentExit, the dedupe home is the same
 * SharedPreferences file, the notification is the 631 alertNotification
 * shape; those are the thin integration shell).
 */
class MemoryKillStartupCheckTest {

    @Test
    fun `the MemoryLimiter literal under REASON_OTHER discriminates the kill`() {
        // The literal the platform writes (Android 17 memory caps research,
        // finding 3); matched case-insensitively.
        assertTrue(
            MemoryKillStartupCheck.isMemoryLimiterKill(
                ApplicationExitInfo.REASON_OTHER, "MemoryLimiter:AnonSwap"))
    }

    @Test
    fun `a plain REASON_OTHER exit does not discriminate`() {
        assertFalse(
            MemoryKillStartupCheck.isMemoryLimiterKill(
                ApplicationExitInfo.REASON_OTHER, "user requested"))
        assertFalse(
            MemoryKillStartupCheck.isMemoryLimiterKill(ApplicationExitInfo.REASON_OTHER, null))
    }

    @Test
    fun `other reasons never discriminate, whatever the description`() {
        // The literal under the WRONG reason must not fire: LMK deaths carry
        // their own banner in NativeCrashDetector.
        assertFalse(
            MemoryKillStartupCheck.isMemoryLimiterKill(
                ApplicationExitInfo.REASON_LOW_MEMORY, "MemoryLimiter:AnonSwap"))
        assertFalse(
            MemoryKillStartupCheck.isMemoryLimiterKill(
                ApplicationExitInfo.REASON_CRASH, "MemoryLimiter"))
    }

    @Test
    fun `an advisory is due only for a recent memory kill newer than the last handled one`() {
        val now = 10_000_000_000L
        val inWindow = now - MemoryKillStartupCheck.ADVISE_WINDOW_MS
        // No advisory yet: a recent first kill advises.
        assertTrue(MemoryKillStartupCheck.adjudicate(isMemoryKill = true, exitTimestamp = inWindow, handledTimestamp = 0L, nowMs = now))
        // Same kill again (relaunch without a new kill): no second advisory.
        assertFalse(MemoryKillStartupCheck.adjudicate(isMemoryKill = true, exitTimestamp = inWindow, handledTimestamp = inWindow, nowMs = now))
        // A NEWER kill advises again.
        assertTrue(MemoryKillStartupCheck.adjudicate(isMemoryKill = true, exitTimestamp = inWindow + 1, handledTimestamp = inWindow, nowMs = now))
        // A non-memory exit never advises, whatever the timestamps.
        assertFalse(MemoryKillStartupCheck.adjudicate(isMemoryKill = false, exitTimestamp = inWindow, handledTimestamp = 0L, nowMs = now))
    }

    @Test
    fun `a kill older than the window does not advise`() {
        // TASK-426 review: "the last transcription may have been cut short"
        // is stale advice weeks later; the banner sibling applies a window
        // for the same reason.
        val now = 10_000_000_000L
        assertFalse(
            MemoryKillStartupCheck.adjudicate(
                isMemoryKill = true,
                exitTimestamp = now - MemoryKillStartupCheck.ADVISE_WINDOW_MS - 1,
                handledTimestamp = 0L,
                nowMs = now))
    }
}
