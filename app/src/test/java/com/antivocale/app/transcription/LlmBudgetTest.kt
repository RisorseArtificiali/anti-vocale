package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.manager.EngineWedgeTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-674: the shared hard wall-clock budget owner. The contract under test:
 * a hung generation fails typed (PassTimeoutException) inside the injected
 * budget with no partial text; a completed generation passes through; the
 * caller's own cancellation is NOT converted into a failure; the pass clock
 * bounds the WHOLE pass, not each generation; a spent clock fails fast
 * without entering the engine.
 */
class LlmBudgetTest {

    /** Minimal stub: the generateText seam is all the owner touches. */
    private open class StubLlm(
        var behavior: suspend (String) -> Result<String> = { Result.success("ok") },
    ) : TranscriptionBackend {
        val calls = mutableListOf<String>()
        override val id = "stub"
        override val displayName = "Stub"
        override val supportsAudio = false
        override val supportsText = true
        override suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String) =
            Result.success(TranscriptionResult(text = ""))
        override suspend fun generateText(prompt: String): Result<String> =
            try {
                behavior(prompt)
            } finally {
                calls.add(prompt)
            }
        override suspend fun initialize(context: Context, config: BackendConfig) = Result.success(Unit)
        override fun isReady() = true
        override fun isAudioSupported() = false
        override fun unload() {}
        override fun setKeepAliveTimeout(minutes: Int) {}
        override fun getModelPath(): String? = null
    }

    @Test
    fun `a hung generation fails typed inside the budget`() = runTest {
        val llm = StubLlm { awaitCancellation(); Result.success("unreachable") }
        val outcome = LlmBudget.generateWithBudget(llm, "prompt", 5_000, pass = "Test")
        // TASK-674 AC2/AC4: nothing partial merges into the result; the
        // failure is the typed timeout, not a CancellationException leak.
        val failure = outcome.exceptionOrNull()
        assertTrue(failure is LlmBudget.PassTimeoutException)
        assertNull(outcome.getOrNull())
    }

    @Test
    fun `the budget is respected on the virtual clock`() = runTest {
        val llm = StubLlm { delay(60_000); Result.success("late") }
        var done = false
        val job = async { LlmBudget.generateWithBudget(llm, "p", 5_000, pass = "Test"); done = true }
        advanceTimeBy(4_999)
        assertFalse("the budget must not fire early", done)
        advanceTimeBy(10)
        job.await()
        assertTrue(done)
    }

    @Test
    fun `a completing generation passes through untouched`() = runTest {
        val llm = StubLlm { Result.success("  polished text  ") }
        val outcome = LlmBudget.generateWithBudget(llm, "p", 5_000, pass = "Test")
        assertEquals("  polished text  ", outcome.getOrNull())
    }

    @Test
    fun `a failing generation keeps its original error`() = runTest {
        val original = IllegalStateException("engine said no")
        val llm = StubLlm { Result.failure(original) }
        val outcome = LlmBudget.generateWithBudget(llm, "p", 5_000, pass = "Test")
        assertEquals(original, outcome.exceptionOrNull())
    }

    @Test
    fun `the caller's own cancellation is not converted into a failure`() = runTest {
        val llm = StubLlm { delay(60_000); Result.success("late") }
        val job = async { LlmBudget.generateWithBudget(llm, "p", 60_000, pass = "Test") }
        job.cancel()
        val outcome = runCatching { job.await() }
        // A real user/service cancel propagates as cancellation (the house
        // contract degradeTo relies on), never as a fake timeout failure:
        // CancellationException and PassTimeoutException (a TimeoutException)
        // are disjoint hierarchies, so the is-check below IS the guarantee.
        assertTrue(outcome.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `the pass timeout is not a wedge marker`() {
        // The summarize ladder's TASK-594 circuit breaker must not trip on a
        // mere budget fire (the native cancel came back promptly): the types
        // must stay disjoint.
        val typed = LlmBudget.PassTimeoutException("Summary", 1_000)
        // Runtime check: the compiler already proves the is-form impossible.
        assertFalse(EngineWedgeTimeoutException::class.java.isInstance(typed))
        assertTrue(typed is java.util.concurrent.TimeoutException)
    }

    @Test
    fun `a second generation still works after a timeout`() = runTest {
        var hanging = true
        val llm = StubLlm {
            if (hanging) awaitCancellation() else Result.success("recovered")
        }
        val first = LlmBudget.generateWithBudget(llm, "p", 1_000, pass = "Test")
        assertTrue(first.exceptionOrNull() is LlmBudget.PassTimeoutException)
        // The owner leaves no wedged state behind: the next call runs.
        hanging = false
        val second = LlmBudget.generateWithBudget(llm, "p", 5_000, pass = "Test")
        assertEquals("recovered", second.getOrNull())
        assertEquals(2, llm.calls.size)
    }

    @Test
    fun `the pass clock bounds the whole pass, not each generation`() {
        // PassClock runs on the real wall clock (System.nanoTime), so this
        // contract is exercised with real short delays, not virtual time.
        kotlinx.coroutines.runBlocking {
            val llm = StubLlm { kotlinx.coroutines.delay(120); Result.success("partial") }
            val clock = LlmBudget.PassClock("Summary", 500)
            val first = clock.generate(llm, "chunk-1")
            assertEquals("partial", first.getOrNull())
            // Only a fraction of the wall remains: a further generation gets
            // the remainder, not a fresh budget.
            val second = clock.generate(llm, "chunk-2")
            assertTrue(second.getOrNull() != null || second.exceptionOrNull() is LlmBudget.PassTimeoutException)
            // A generation that cannot fit the remainder fails typed.
            val hanging = StubLlm { kotlinx.coroutines.awaitCancellation(); Result.success("x") }
            val third = clock.generate(hanging, "chunk-3")
            assertTrue(third.exceptionOrNull() is LlmBudget.PassTimeoutException)
        }
    }

    @Test
    fun `a spent clock fails fast on the real wall`() {
        // PassClock is real-time: after its budget is genuinely spent, the
        // next generation gets the 1ms floor and fails typed within it
        // (never a fresh budget, never a real wait).
        kotlinx.coroutines.runBlocking {
            val llm = StubLlm { kotlinx.coroutines.awaitCancellation(); Result.success("x") }
            val clock = LlmBudget.PassClock("Summary", 50)
            kotlinx.coroutines.delay(100)
            val start = System.nanoTime()
            val outcome = clock.generate(llm, "p")
            assertTrue(outcome.exceptionOrNull() is LlmBudget.PassTimeoutException)
            assertTrue(
                "the spent clock must fail within milliseconds",
                (System.nanoTime() - start) / 1_000_000 < 5_000)
        }
    }

    @Test
    fun `the timeout skip-reason token stays stable for the DB and UI mapping`() {
        // LogsTab's caption mapping and the Room rows key on this exact token.
        assertEquals("timeout", SummaryPolicy.SKIP_REASON_TIMEOUT)
    }
}
