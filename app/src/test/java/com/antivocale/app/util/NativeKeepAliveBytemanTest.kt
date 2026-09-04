package com.antivocale.app.util

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * TASK-388: deterministic proof of the idle-unload vs work-start interleaving
 * (the c444b09 race class) on the REAL NativeKeepAlive. The Byteman rule freezes
 * a worker AT ENTRY of beginWork: the delay(0) timer then wins the lock, sees
 * workInFlight == 0 and unloads, exactly the documented residual window ("work
 * that queued while we held the lock"). Test A pins that the window is reachable
 * and that the production consumer contract (read AFTER beginWork sees the
 * post-unload null, clean NotInitialized failure, no use-after-free) holds.
 * Test B is the fix twin: claim FIRST and the same immediate timer fire can
 * never unload while work is in flight.
 *
 * Rule health is asserted via the byteman.nka.froze system property set by the
 * rule BEFORE blocking (guide trap 2: a rule that never fired fails the test).
 */
class NativeKeepAliveBytemanTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val unloaded = AtomicBoolean(false)

    /** Mirrors the backend contract: volatile handle nulled by unload. */
    @Volatile private var recognizer: Any? = Any()

    private fun newKeepAlive() = NativeKeepAlive(
        scope = scope, tag = "test-nka",
        defaultTimeoutMinutes = 0, // delay(0): the timer fires immediately
        onIdleUnload = {
            recognizer = null
            unloaded.set(true)
        },
    )

    @Before
    fun requireAgentAndArmRule() {
        assumeTrue(System.getProperty("byteman.agent") == "true")
        System.setProperty("byteman.nka.race", "true")
        System.clearProperty("byteman.nka.froze")
    }

    @After
    fun disarmRule() {
        System.clearProperty("byteman.nka.race")
        System.clearProperty("byteman.nka.froze")
        scope.cancel()
    }

    /** Polls [condition] every 10ms; false on [ms] timeout. */
    private fun awaitUntil(ms: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(10)
        }
        return true
    }

    /**
     * DAMAGE interleaving, made deterministic: the worker is frozen before
     * claiming work, the timer unloads underneath it, and the claim lands on an
     * unloaded backend. The production consumer contract turns this into a clean
     * NotInitialized failure (read-after-beginWork sees null), never a
     * use-after-free on a released native handle.
     */
    @Test
    fun `work queued during unload starts on an unloaded backend and fails cleanly`() {
        val ka = newKeepAlive()

        // Worker FIRST, timer SECOND: the worker is guaranteed frozen at the
        // beginWork entry (workInFlight still 0, no timer running) before the
        // delay(0) fire starts, so the race window below is entered by
        // construction, not by scheduler luck.
        val claimed = AtomicBoolean(false)
        val worker = Thread {
            ka.beginWork() // rule freezes here while the timer unloads
            claimed.set(true)
            ka.endWork()
        }
        worker.start()

        // Rule health assertion: the freeze MUST have fired.
        assertTrue("rule not armed: byteman.nka.froze never set", awaitUntil { System.getProperty("byteman.nka.froze") != null })

        ka.start()

        // NOW the timer: delay(0) fires, the lock is free, workInFlight == 0
        // (the worker is frozen BEFORE claiming), so the unload runs underneath.
        assertTrue("timer did not unload under the frozen worker", awaitUntil { unloaded.get() })

        // Release happens via the rule's waitFor timeout (<= 5s): the claim then
        // lands on the unloaded backend.
        worker.join(15_000)
        assertFalse(worker.isAlive)
        assertTrue(claimed.get())

        // The production contract: a read AFTER beginWork sees the post-unload
        // null and converts it to a clean failure (SherpaBackend's documented
        // "this read sees the post-unload null" branch).
        assertNull("read-after-claim must observe the unload", recognizer)
    }

    /**
     * FIX twin: the same immediate timer fire, but work claims FIRST. The
     * unload decision must see workInFlight > 0 and abort; nothing unloads
     * while work is in flight, and the handle stays live for the reader.
     */
    @Test
    fun `work claimed first blocks the immediate unload and keeps the handle live`() {
        System.clearProperty("byteman.nka.race") // rule inert: claim-first is the real path
        val ka = newKeepAlive()
        recognizer = Any()

        ka.beginWork()
        ka.start() // delay(0) fire happens while work is claimed

        // Give the timer coroutine ample time to run its check; it must abort.
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) Thread.sleep(50)

        assertFalse("unload must not fire while work is in flight", unloaded.get())
        assertNotNull("handle must stay live for the claimed work", recognizer)

        ka.endWork() // releases; the re-armed delay(0) timer unloads now
        assertTrue("idle unload must fire after the work ends", awaitUntil { unloaded.get() })
        assertNull(recognizer)
    }
}
