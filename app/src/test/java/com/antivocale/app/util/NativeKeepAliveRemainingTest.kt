package com.antivocale.app.util

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-574: the countdown the Settings banner shows is the REMAINING idle
 * time, not the configured timeout. Before the fix, getRemainingTimeSeconds
 * returned timeoutMinutes * 60 forever, so "Auto-unload in" never moved.
 * These tests pin the deadline arithmetic against a controllable clock.
 */
class NativeKeepAliveRemainingTest {

    private class Fixture {
        val now = AtomicLong(0)
        val scope: CoroutineScope = TestScope()
        fun keepAlive(timeoutMinutes: Int = 5) = NativeKeepAlive(
            scope = scope,
            tag = "test",
            defaultTimeoutMinutes = timeoutMinutes,
            onIdleUnload = {},
            clock = { now.get() },
        )
    }

    @Test
    fun `start arms a full countdown that decreases as the clock advances`() {
        val f = Fixture()
        val ka = f.keepAlive(timeoutMinutes = 5)
        assertNull("no countdown before start", ka.remainingSeconds())
        ka.start()
        assertEquals(5 * 60L, ka.remainingSeconds())
        f.now.set(30_000)
        assertEquals(5 * 60L - 30, ka.remainingSeconds())
        // Past the deadline it clamps at zero rather than going negative.
        f.now.set(5 * 60_000L + 5_000)
        assertEquals(0L, ka.remainingSeconds())
        ka.stop()
    }

    @Test
    fun `work in flight pauses the countdown and endWork re-arms it`() {
        val f = Fixture()
        val ka = f.keepAlive(timeoutMinutes = 2)
        ka.start()
        f.now.set(60_000)
        assertEquals("one minute elapsed of two", 60L, ka.remainingSeconds())
        ka.beginWork()
        assertNull("paused while transcribing", ka.remainingSeconds())
        f.now.set(90_000)
        assertNull(ka.remainingSeconds())
        ka.endWork()
        assertEquals("re-armed to the full timeout after work", 2 * 60L, ka.remainingSeconds())
        ka.stop()
    }

    @Test
    fun `stop and setTimeout restarts reset the deadline`() {
        val f = Fixture()
        val ka = f.keepAlive(timeoutMinutes = 5)
        ka.start()
        f.now.set(240_000) // 4 minutes in
        assertEquals(60L, ka.remainingSeconds())
        ka.setTimeout(10)
        assertEquals("restart carries the new timeout", 10 * 60L, ka.remainingSeconds())
        ka.stop()
        assertNull("no countdown after unload", ka.remainingSeconds())
    }
}
