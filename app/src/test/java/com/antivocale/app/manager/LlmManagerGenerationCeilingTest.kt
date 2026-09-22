package com.antivocale.app.manager

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
}
