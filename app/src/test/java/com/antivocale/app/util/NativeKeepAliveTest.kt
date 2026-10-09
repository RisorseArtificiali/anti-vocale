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

    // TASK-789: the drain release (a manual unload requested while a native
    // call was in flight must not release under the live call).

    @Test
    fun `drain release runs immediately when no work is in flight`() = runTest {
        var released = 0
        val t = timer()
        t.requestDrainRelease { released++ }
        assertEquals(1, released)
    }

    @Test
    fun `drain release waits for in-flight work and fires at the last endWork`() = runTest {
        var released = 0
        val t = timer()
        t.beginWork()
        t.beginWork()
        t.requestDrainRelease { released++ }
        assertEquals(0, released)
        t.endWork()
        assertEquals(0, released) // one bracket member still inside
        t.endWork()
        assertEquals(1, released) // drained
    }

    @Test
    fun `drain release fires once and a later request can re-arm it`() = runTest {
        var released = 0
        val t = timer()
        t.requestDrainRelease { released++ }
        assertEquals(1, released)
        t.requestDrainRelease { released++ }
        assertEquals(2, released)
    }

    @Test
    fun `work started after the drain release waits for its own drain`() = runTest {
        var released = 0
        val t = timer()
        t.beginWork()
        t.requestDrainRelease { released++ }
        t.endWork()
        assertEquals(1, released) // the original bracket drained
        t.beginWork() // new work on the (already released) engine
        t.requestDrainRelease { released++ }
        assertEquals(1, released) // not yet: bracket held again
        t.endWork()
        assertEquals(2, released)
    }

    @Test
    fun `idle unload still fires with a spent drain release`() = runTest {
        var fires = 0
        var released = 0
        val t = timer { fires++ }
        t.requestDrainRelease { released++ }
        t.start()
        advanceTimeBy(5 * 60_000L + 1)
        assertEquals(1, fires)
        assertEquals(1, released) // the earlier drain already ran; no interference
    }

    @Test
    fun `a second drain request before the first drains runs both at the drain`() = runTest {
        // TASK-789 review F3: overwriting a pending callback would drop the
        // first release and leak its native bytes; the requests chain.
        var first = 0
        var second = 0
        val t = timer()
        t.beginWork()
        t.requestDrainRelease { first++ }
        t.requestDrainRelease { second++ }
        assertEquals(0, first)
        assertEquals(0, second)
        t.endWork()
        assertEquals(1, first)
        assertEquals(1, second)
    }
}
