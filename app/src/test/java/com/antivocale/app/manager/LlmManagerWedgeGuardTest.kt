package com.antivocale.app.manager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * TASK-606: the wedge guardrails. F8: cross-attempt wedge memory
 * (a wedged engine fails every later generation fast instead of burning
 * another ceiling). F9: the native cancel runs under its own deadline and
 * is abandoned when it does not return (a leaked thread beats a frozen
 * caller). F3's MediaPipe deadline is covered by the same seams (the
 * ceiling + the flag) since the blocking call has no native cancel.
 */
class LlmManagerWedgeGuardTest {

    private fun newManager() = LlmManager(
        managerScope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `a wedged engine fails generateText fast with the wedge message`() = runBlocking {
        val manager = newManager()
        manager.engineWedged = true

        val result = manager.generateText("summarize this")

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("expected the wedge message, got: $msg", msg.contains("wedged"))
        assertTrue("the fast failure must be a timeout so the chunk loops' wedge arms match",
            result.exceptionOrNull() is java.util.concurrent.TimeoutException)
    }

    @Test
    fun `a wedged engine fails generateFromAudio fast`() = runBlocking {
        val manager = newManager()
        manager.engineWedged = true

        val result = manager.generateFromAudio("transcribe", ByteArray(16000 * 2))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("wedged") == true)
    }

    @Test
    fun `a wedged engine does not clear itself on a failed call`() = runBlocking {
        val manager = newManager()
        manager.engineWedged = true

        manager.generateText("x")

        assertTrue("only a successful initialize may clear the wedge", manager.engineWedged)
    }

    @Test
    fun `a hanging native cancel is abandoned after its deadline`() = runBlocking {
        val manager = newManager()
        manager.generationTimeoutMs = 50
        manager.nativeCancelTimeoutMs = 80
        val entered = CountDownLatch(1)

        val startedAt = System.currentTimeMillis()
        val outcome = manager.withGenerationCeiling(cancel = {
            entered.countDown()
            // The wedged engine's cancelProcess never returns.
            Thread.sleep(2_000)
        }) {
            kotlinx.coroutines.awaitCancellation()
        }
        val wallMs = System.currentTimeMillis() - startedAt

        assertTrue("expected the ceiling signal", outcome == null)
        // await(0, ..) is the non-blocking poll: count already at zero means it ran.
        check(entered.count == 0L) { "the cancel never started" }
        assertTrue("caller must proceed without waiting for the stuck cancel (took ${wallMs}ms)",
            wallMs < 1_500)
    }
}
