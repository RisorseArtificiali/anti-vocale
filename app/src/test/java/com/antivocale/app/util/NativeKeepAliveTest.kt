package com.antivocale.app.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // TASK-791 AC (the "unit test at the NativeKeepAlive seam"): the
    // drain-release DISCIPLINE every native-engine owner follows
    // (SherpaBackend, ExternalSherpaBackend, LlmManager): flip state and
    // null the handle fields immediately, capture the stale handles, close
    // them once at the bracket drain. This fake owner pins the contract;
    // drift in the REAL owners is not caught here (LlmManager is
    // JVM-instantiable but its engine fields are private with no injection
    // seam, and the backends need Android) - TASK-793 owns that structural
    // pass, and this fake migrates with it.
    private class FakeHandle {
        var closed = 0
        fun close() { closed++ }
    }

    private class FakeNativeOwner(scope: kotlinx.coroutines.CoroutineScope) {
        val keepAlive = NativeKeepAlive(
            scope = scope,
            tag = "fake-owner",
            defaultTimeoutMinutes = 5,
            onIdleUnload = { unload() },
        )
        var handle: FakeHandle? = null
        var initialized = false

        /** TASK-606 shape: set inside the bracket before endWork when the
         *  abandoned native cancel never returns. */
        var wedged = false

        fun load(h: FakeHandle) { handle = h; initialized = true; keepAlive.start() }

        /** The owner's native-call shape: bracket first, field into a local. */
        fun call(): FakeHandle? = keepAlive.withWork { handle }

        fun unload() {
            keepAlive.stop()
            initialized = false
            val stale = handle
            handle = null
            if (wedged) return // the unload-time wedge: skip-close leak
            keepAlive.requestDrainRelease {
                // Re-check at the drain: the abandoned cancel lives OUTSIDE
                // the bracket, so a wedge inside the window still drains.
                if (!wedged) stale?.close()
            }
        }
    }

    @Test
    fun `owner discipline - unload mid-bracket fails new work and closes the stale handle once at the drain`() = runTest {
        val owner = FakeNativeOwner(backgroundScope)
        val h = FakeHandle()
        owner.load(h)

        // One call in flight: bracket held, local captured (the local keeps
        // working; that is a JVM guarantee, the drain timing is the pin).
        owner.keepAlive.beginWork()

        owner.unload() // the mid-call manual unload
        assertFalse(owner.initialized)
        assertNull(owner.handle)
        assertEquals(0, h.closed) // nothing released under the live call

        assertNull(owner.call()) // new work fails the nulled-field gate
        owner.keepAlive.endWork() // the original bracket drains
        assertEquals(1, h.closed) // the stale handle closed exactly once
    }

    @Test
    fun `owner discipline - a re-init before the drain spares the fresh handle`() = runTest {
        val owner = FakeNativeOwner(backgroundScope)
        val old = FakeHandle()
        val fresh = FakeHandle()
        owner.load(old)

        owner.keepAlive.beginWork()
        owner.unload() // captures old
        owner.load(fresh) // a re-init lands before the drain
        owner.keepAlive.endWork()

        assertEquals(1, old.closed) // the stale object released once
        assertEquals(0, fresh.closed) // the fresh handle spared
        assertEquals(fresh, owner.call()) // the re-inited owner serves the fresh handle
    }

    @Test
    fun `owner discipline - a wedge at unload keeps the skip-close leak`() = runTest {
        val owner = FakeNativeOwner(backgroundScope)
        val h = FakeHandle()
        owner.load(h)

        owner.wedged = true
        owner.keepAlive.beginWork()
        owner.unload()
        owner.keepAlive.endWork()

        assertEquals(0, h.closed) // TASK-606 F5: leaked until process restart, by design
    }

    @Test
    fun `owner discipline - a wedge landing inside the drain window skips the close at the drain`() = runTest {
        // The TASK-791 review's V1 pin: the abandoned native cancel lives
        // OUTSIDE the bracket, so the bracket still drains; the drain-time
        // re-check must not close under that abandoned call.
        val owner = FakeNativeOwner(backgroundScope)
        val h = FakeHandle()
        owner.load(h)

        owner.keepAlive.beginWork()
        owner.unload() // healthy at unload: the release is deferred
        owner.wedged = true // the abandon lands inside the window
        owner.keepAlive.endWork() // the bracket drains anyway

        assertEquals(0, h.closed) // no close under the abandoned native call
    }
}
