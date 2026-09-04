package com.antivocale.app.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TASK-344 / issue #42: the idle-unload timer that returns native arena memory
 * after inactivity. Pinned behaviors: fires when idle, never fires while work
 * is in flight, restarts on timeout change, stop cancels it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeKeepAliveTest {

    private fun TestScope.timer(onFire: () -> Unit = {}) = NativeKeepAlive(
        scope = this,
        tag = "test",
        defaultTimeoutMinutes = 5,
        onIdleUnload = onFire,
    )

    @Test
    fun `fires after the timeout when idle`() = runTest {
        var fires = 0
        val t = timer { fires++ }
        t.start()
        advanceTimeBy(5 * 60_000L + 1)
        assertEquals(1, fires)
    }

    @Test
    fun `does not fire while work is in flight`() = runTest {
        var fires = 0
        val t = timer { fires++ }
        t.start()
        t.beginWork()
        advanceTimeBy(10 * 60_000L)
        assertEquals(0, fires)
        t.endWork()
        advanceTimeBy(5 * 60_000L + 1)
        assertEquals(1, fires)
    }

    @Test
    fun `timeout change restarts the countdown`() = runTest {
        var fires = 0
        val t = timer { fires++ }
        t.start()
        advanceTimeBy(4 * 60_000L)
        t.setTimeout(10)
        advanceTimeBy(2 * 60_000L) // past the original 5m mark
        assertEquals(0, fires)
        advanceTimeBy(8 * 60_000L + 1)
        assertEquals(1, fires)
    }

    @Test
    fun `stop cancels the timer`() = runTest {
        var fires = 0
        val t = timer { fires++ }
        t.start()
        t.stop()
        advanceTimeBy(60 * 60_000L)
        assertEquals(0, fires)
    }
}
