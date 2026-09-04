package com.antivocale.app.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-388: deterministic proof of the c444b09 idle-unload race class on the
 * window itself, not on the worker. The seam ([NativeKeepAlive.idleUnloadWindowHook])
 * freezes the fire BETWEEN the passed workInFlight check and the unload, and a
 * work-start is released into that frozen window:
 *
 *  - Test A (damage, guard ABSENT): a minimal replica of the pre-c444b09 fire
 *    path (check and unload with no mutual exclusion against beginWork) lets
 *    the claim land inside the window, read a LIVE handle, and then the unload
 *    frees that exact handle underneath the in-flight work: use-after-unload.
 *
 *  - Test B (fix, guard PRESENT): the real NativeKeepAlive, same interleaving.
 *    beginWork cannot enter while the fire holds the lock mid-window, so the
 *    claim completes only AFTER the unload and the read-after-claim sees the
 *    post-unload null, which production converts to a clean NotInitialized
 *    failure (SherpaBackend/ExternalSherpaBackend read the recognizer only
 *    after beginWork). No freed native handle is ever touched.
 *
 * Determinism: no Thread.sleep, no polling. Test A interleaves on runTest's
 * single thread via suspension points; Test B runs the timer on a dedicated
 * real thread (the seam blocks inside a monitor, which cannot suspend, so
 * virtual time cannot apply) and pins the ordering with latches only. Every
 * bounded await is a failure path, never a pass condition.
 *
 * Sibling: NativeKeepAliveBytemanTest freezes the WORKER at beginWork entry
 * under -Pbyteman; this file freezes the UNLOAD mid-window in the standard
 * suite, covering the complementary half of the interleaving.
 */
class NativeKeepAliveRaceTest {

    /** Mirrors the backend unload contract: release() frees the live handle, then nulls the field. */
    private class NativeHandle {
        private val live: Any = Any()
        @Volatile var ref: Any? = live
        @Volatile var freed: Any? = null

        fun release() {
            freed = ref
            ref = null
        }
    }

    /**
     * Minimal replica of the PRE-c444b09 fire path (git show c444b09^): the
     * in-flight check and the unload ran with NO mutual exclusion against
     * beginWork (which itself took no lock), so a claim landing between the
     * two raced the native release head-on. Only the race-relevant core is
     * kept: the delay/cancel machinery is upstream of the window (a fire that
     * reached the check is already past its delay, and cancel cannot un-see
     * the check). The window hook mirrors the production seam's position.
     */
    private class PreC444b09FireStandIn {
        val workInFlight = AtomicInteger(0)
        private val timerActive = AtomicBoolean(true)
        var inFlightSeenByCheck = -1
            private set
        var inFlightAtUnload = -1
            private set

        fun beginWork() = workInFlight.incrementAndGet() // pre-fix: no lock
        fun endWork() = workInFlight.decrementAndGet()   // pre-fix: no lock

        suspend fun fire(onIdleUnload: () -> Unit, windowHook: suspend () -> Unit) {
            inFlightSeenByCheck = workInFlight.get()
            if (timerActive.get() && workInFlight.get() == 0) {
                windowHook() // check passed, unload pending: the race window
                inFlightAtUnload = workInFlight.get()
                onIdleUnload()
            }
        }
    }

    @Test
    fun `damage state is reachable when the guard is absent`() = runTest {
        val handle = NativeHandle()
        val live = handle.ref
        val standIn = PreC444b09FireStandIn()
        val windowEntered = CompletableDeferred<Unit>()
        val releaseWindow = CompletableDeferred<Unit>()

        val fire = launch {
            standIn.fire(
                onIdleUnload = { handle.release() },
                windowHook = {
                    windowEntered.complete(Unit)
                    releaseWindow.await() // frozen mid-window, unload NOT yet run
                },
            )
        }
        windowEntered.await() // the check passed; the unload is frozen mid-window

        // A transcription start lands INSIDE the frozen window (pre-fix
        // beginWork takes no lock, so the claim completes instantly).
        standIn.beginWork()
        val capturedByWork = handle.ref // backend contract: read AFTER beginWork
        assertNotNull("pre-fix read sees a LIVE handle inside the window", capturedByWork)

        releaseWindow.complete(Unit) // the unload runs underneath the in-flight claim
        fire.join()

        // The decision was made on stale data and the unload executed anyway.
        assertEquals("check saw no work", 0, standIn.inFlightSeenByCheck)
        assertEquals("unload ran with work in flight", 1, standIn.inFlightAtUnload)
        assertSame(live, capturedByWork)
        assertSame("the unload freed the exact handle the in-flight work still holds", live, handle.freed)
    }

    @Test
    fun `production guard closes the same window`() {
        val timerThread = Executors.newSingleThreadExecutor { r -> Thread(r, "nka-race-timer") }
        try {
            val scope = CoroutineScope(SupervisorJob() + timerThread.asCoroutineDispatcher())
            val handle = NativeHandle()
            val live = handle.ref
            val unloadCount = AtomicInteger(0)
            val keepAlive = NativeKeepAlive(
                scope = scope,
                tag = "test-nka-race",
                defaultTimeoutMinutes = 0, // delay(0): the timer fires immediately
                onIdleUnload = {
                    handle.release()
                    unloadCount.incrementAndGet()
                },
            )

            val windowEntered = CountDownLatch(1)
            val releaseWindow = CountDownLatch(1)
            val windowReleased = AtomicBoolean(false)
            val refAtFreeze = AtomicReference<Any?>(null)
            keepAlive.idleUnloadWindowHook = {
                // The seam: after the passed in-flight check, before the
                // unload, WITH the lock held. Freeze here.
                refAtFreeze.set(handle.ref)
                windowEntered.countDown()
                windowReleased.set(releaseWindow.await(10, TimeUnit.SECONDS)) // bounded bail-out
            }
            keepAlive.start()

            // The window is entered by construction: the hook counts down only
            // after the check PASSED, and it runs while the fire holds the lock.
            assertTrue("idle-unload window never entered", windowEntered.await(10, TimeUnit.SECONDS))
            // The seam's position contract: the freeze point runs while the
            // handle is still live, i.e. strictly BEFORE the unload.
            assertSame("seam must fire before the unload runs", live, refAtFreeze.get())

            // A transcription start racing the fire: claim BEFORE the handle
            // read, exactly as both backends do it.
            val workerArrived = CountDownLatch(1)
            val workerDone = CountDownLatch(1)
            val observedByWork = AtomicReference<Any?>(null)
            val worker = Thread {
                workerArrived.countDown()
                keepAlive.beginWork()
                observedByWork.set(handle.ref)
                keepAlive.endWork()
                workerDone.countDown()
            }
            worker.start()

            // The guard: the worker HAS reached the claim (arrival latch) and
            // cannot complete it there, because the fire still holds the lock
            // mid-window (monitor semantics; the release latch is still closed).
            assertTrue("worker never reached the claim", workerArrived.await(10, TimeUnit.SECONDS))
            assertEquals("beginWork must be blocked inside the frozen window", 1L, workerDone.count)

            releaseWindow.countDown()

            assertTrue("worker never completed after the window closed", workerDone.await(10, TimeUnit.SECONDS))
            assertTrue("window release latch timed out (hook bail-out fired)", windowReleased.get())

            // Read-after-claim sees the post-unload null: production maps this
            // to the clean NotInitialized failure, never a native call.
            assertNull("read-after-claim must see the post-unload null", observedByWork.get())
            assertEquals(1, unloadCount.get())
            assertSame("exactly the live handle was freed by the unload", live, handle.freed)
        } finally {
            timerThread.shutdownNow()
        }
    }
}
