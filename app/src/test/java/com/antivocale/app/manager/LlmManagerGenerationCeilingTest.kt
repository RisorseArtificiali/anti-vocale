package com.antivocale.app.manager

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-594: the per-generation ceiling. withTimeoutOrNull alone cancels only
 * the awaiting coroutine (litertlm's callbackFlow awaitClose is a no-op), so
 * the helper's contract under test is: on ceiling, the NATIVE cancel runs and
 * the caller sees null; on completion, cancel never runs; a caller-scope
 * cancellation propagates rather than becoming a generation failure.
 */
class LlmManagerGenerationCeilingTest {

    private val manager = LlmManager(
        managerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
    )

    @Test
    fun `a hung generation returns null and cancels the native call`() = runBlocking {
        manager.generationTimeoutMs = 50
        var cancelled = 0
        val outcome = manager.withGenerationCeiling(cancel = { cancelled++ }) {
            awaitCancellation() // a stream that never yields
        }
        assertNull(outcome)
        assertEquals("the native generation must be cancelled on ceiling", 1, cancelled)
    }

    @Test
    fun `a completing generation returns its value and never cancels`() = runTest {
        var cancelled = 0
        val outcome = manager.withGenerationCeiling(cancel = { cancelled++ }) {
            "transcribed"
        }
        assertEquals("transcribed", outcome)
        assertEquals(0, cancelled)
    }

    @Test
    fun `a failing cancel does not mask the timeout`() = runBlocking {
        manager.generationTimeoutMs = 50
        val outcome = manager.withGenerationCeiling(cancel = { throw IllegalStateException("native gone") }) {
            awaitCancellation()
        }
        assertNull(outcome)
    }

    @Test
    fun `the default ceiling stays in the documented minutes range`() {
        // The generous default is the contract: on-device summaries take
        // minutes, so the ceiling must never regress to seconds.
        assertTrue(manager.generationTimeoutMs >= 60_000L)
    }

    @Test(expected = CancellationException::class)
    fun `caller cancellation propagates through the ceiling`() = runTest {
        manager.withGenerationCeiling(cancel = {}) {
            throw CancellationException("caller cancelled")
        }
        Unit // unreachable: the cancellation must escape, not become null
    }

    @Test
    fun `caller cancellation cancels the native generation before escaping`() = runBlocking {
        var cancelled = 0
        try {
            manager.withGenerationCeiling(cancel = { cancelled++ }) {
                throw CancellationException("caller cancelled")
            }
            // unreachable: the cancellation must escape, not become a value
            throw AssertionError("cancellation was swallowed")
        } catch (e: CancellationException) {
            assertEquals("native cancel runs on the cancellation path too", 1, cancelled)
        } finally {
            assertTrue("cancel ran", cancelled >= 1)
        }
    }

    @Test
    fun `a genuine caller cancellation whose native cancel never returns latches the wedge (TASK-791 F1)`() = runBlocking {
        // Probe-proven regression pin: the committed tests above throw a
        // SYNTHETIC CancellationException from a live caller, where the
        // outcome lambda still runs. A genuine cancellation (the caller's
        // Job already cancelled) makes withTimeoutOrNull { job.join() }
        // throw CancellationException at entry; before the NonCancellable
        // fix the outcome (and the wedge latch) was skipped entirely on
        // this, the PRIMARY cancel path, and the pending drain release
        // would close the engine under the abandoned native cancel.
        manager.generationTimeoutMs = 60_000
        manager.nativeCancelTimeoutMs = 150
        manager.engineWedged = false
        // The cancel lambda is a plain () -> Unit (the native call shape):
        // block the worker thread on a latch, the abandoned-cancel form.
        val release = java.util.concurrent.CountDownLatch(1)
        val job = launch {
            manager.withGenerationCeiling(cancel = { release.await() }) {
                awaitCancellation() // the generation hangs until cancelled
            }
        }
        withTimeout(5_000) {
            delay(200) // the block is inside the ceiling
            job.cancel() // the GENUINE cancellation
            job.join()
        }
        assertTrue("an abandoned native cancel on a genuine cancel must latch the wedge", manager.engineWedged)
        release.countDown() // release the abandoned worker
    }
}
