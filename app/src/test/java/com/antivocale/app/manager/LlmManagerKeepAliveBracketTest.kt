package com.antivocale.app.manager

import com.antivocale.app.util.NativeKeepAlive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-451: pins the MANAGER's own idle-unload component (not a fresh one):
 * the bracket generateText/generateFromAudio run under pauses the countdown
 * for the whole call, re-arms on completion (including failure), and the
 * countdown never fires while work is in flight.
 *
 * Firing is observed as the timer DISARMING (performAutoUnload is a no-op
 * guard on an uninitialized manager, so the auto-unload callback is not
 * countable here; the component's post-fire disarm is the observable).
 *
 * Boundary note: advanceTimeBy(N) is exclusive of the deadline and
 * runCurrent() then runs a task scheduled exactly at now, so "not yet"
 * advances period-1 and "fired" adds the rest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmManagerKeepAliveBracketTest {

    private fun managerUnderTest(scope: kotlinx.coroutines.CoroutineScope): Pair<LlmManager, NativeKeepAlive> {
        val manager = LlmManager(scope)
        manager.setKeepAliveTimeout(1)
        return manager to manager.keepAliveForTest()
    }

    @Test
    fun `timer does not fire while work is in flight`() = runTest {
        val (_, keepAlive) = managerUnderTest(backgroundScope)
        keepAlive.start()

        keepAlive.beginWork()
        // Twice the idle timeout elapses DURING the generation: no fire.
        advanceTimeBy(2 * 60_000L + 1)
        runCurrent()
        assertTrue("no fire during a long generation", keepAlive.isTimerActiveForTest())
        assertEquals(1, keepAlive.workInFlightForTest())

        keepAlive.endWork()
        // Re-armed at end of work: only a FULL fresh idle period fires.
        advanceTimeBy(60_000L - 1)
        runCurrent()
        assertTrue("no fire before a full idle period", keepAlive.isTimerActiveForTest())
        advanceTimeBy(2)
        runCurrent()
        assertFalse("fire after a full idle period (timer disarms)", keepAlive.isTimerActiveForTest())
    }

    @Test
    fun `bracket re-arms on failure`() = runTest {
        val (_, keepAlive) = managerUnderTest(backgroundScope)
        keepAlive.start()

        keepAlive.beginWork()
        try {
            throw IllegalStateException("generation exploded")
        } catch (_: IllegalStateException) {
            // swallowed, like Result.failure would
        } finally {
            keepAlive.endWork()
        }
        assertEquals("no leaked in-flight count after a failure", 0, keepAlive.workInFlightForTest())
        assertTrue("timer re-armed after a failed generation", keepAlive.isTimerActiveForTest())
    }

    @Test
    fun `overlapping work keeps the countdown paused until the last one ends`() = runTest {
        val (_, keepAlive) = managerUnderTest(backgroundScope)
        keepAlive.start()

        keepAlive.beginWork()
        keepAlive.beginWork()
        keepAlive.endWork()
        // One still in flight: nothing may fire, however long we wait.
        advanceTimeBy(3 * 60_000L)
        runCurrent()
        assertTrue("no fire while one of two works is still running", keepAlive.isTimerActiveForTest())
        keepAlive.endWork()
        advanceTimeBy(60_000L - 1)
        runCurrent()
        assertTrue(keepAlive.isTimerActiveForTest())
        advanceTimeBy(2)
        runCurrent()
        assertFalse("fire once fully idle (timer disarms)", keepAlive.isTimerActiveForTest())
    }
}
