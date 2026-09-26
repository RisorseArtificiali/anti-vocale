package com.antivocale.app.transcription

import android.util.Log
import com.antivocale.app.util.CrashReporter
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * TASK-674: the ONE hard wall-clock budget owner for every on-device LLM
 * post-pass (the punctuation/cleanup pass and the summary passes). The
 * engine-safety ceiling in LlmManager (TASK-520, 5 minutes) exists so a hung
 * stream becomes a failure instead of a frozen service; it is far too generous
 * to be a PASS budget. This owner adds the pass-level wall: a generation that
 * runs past its budget is cancelled, the raw transcript ships unchanged, and
 * the timeout is typed and observable.
 *
 * Cancellation reaches the engine: `withTimeout` cancels the awaiting
 * `llm.generateText` call, whose LlmManager path (generateInFreshConversation
 * via the TASK-594 ceiling) treats caller cancellation as the abandon
 * protocol: the native cancelProcess is dispatched, the conversation is left
 * to engine unload (the c0b731fe/TASK-606 abandonment handling), and
 * conversationMutex is released so the next generation is never wedged.
 *
 * The defaults are calibration placeholders (desktop-grade Omnivoice uses 4s;
 * the maintainer calibrates on the RMX3853): summaries are generous for the
 * TASK-121.4 long-file reality, the cleanup pass is a single short shot.
 */
object LlmBudget {

    private const val TAG = "LlmBudget"

    /** TASK-674: budget for the single-shot cleanup pass (TASK-666 contract:
     *  one short generation). Calibration placeholder, not a measurement. */
    const val CLEANUP_BUDGET_MS = 30_000L

    /** TASK-674: per-pass budget for the summary passes (single-shot ladder
     *  and the whole map-reduce run alike: the clock is shared across the
     *  ladder's attempts and the map chunks). Calibration placeholder for the
     *  TASK-121.4 long-file reality where on-device summaries legitimately
     *  take minutes. */
    const val SUMMARY_BUDGET_MS = 120_000L

    /**
     * The typed timeout: a plain [java.util.concurrent.TimeoutException]
     * subtype that is deliberately NOT an [EngineWedgeTimeoutException]. A
     * pass budget firing says nothing about the engine's health (the native
     * cancel came back promptly through the abandon protocol), so the
     * summarize ladder's wedge circuit breaker must not trigger on it; the
     * passes instead match this type for the honest skip reason.
     */
    class PassTimeoutException(pass: String, budgetMs: Long) :
        java.util.concurrent.TimeoutException(
            "$pass LLM pass exceeded its ${budgetMs / 1000}s wall-clock budget")

    /**
     * TASK-674: one generation under [budgetMs]. On timeout the underlying
     * call is cancelled (see the class KDoc for how far that reaches), the
     * timeout is logged and reported as a CrashReporter breadcrumb, and a
     * [Result.failure] carrying [PassTimeoutException] is returned: no
     * partial text can escape (the failure path is the same degrade the
     * passes already run for ordinary generation failures).
     */
    suspend fun generateWithBudget(
        llm: TranscriptionBackend,
        prompt: String,
        budgetMs: Long,
        pass: String,
    ): Result<String> = try {
        withTimeout(budgetMs) { llm.generateText(prompt) }
    } catch (e: TimeoutCancellationException) {
        val typed = PassTimeoutException(pass, budgetMs)
        // Review R1b/R6: the wording stays pass-level-neutral (a map-chunk
        // timeout does not ship anything; the ladder decides abort/retry).
        Log.w(TAG, "$pass generation exceeded its ${budgetMs}ms wall-clock budget", typed)
        // TASK-674: observability must never break the optional pass itself
        // (the playStore flavor reaches Firebase from here; any reporter
        // failure is observation loss, not a delivery failure).
        runCatching {
            CrashReporter.report(typed, "LlmBudget: $pass generation timed out (${budgetMs}ms budget)")
        }
        Result.failure(typed)
    }

    /**
     * TASK-674: the pass-level wall for multi-generation passes (the summary
     * ladder's attempts, the map-reduce chunks). [budgetMs] bounds the WHOLE
     * pass: every generation gets only the clock's remainder, so two ladder
     * attempts or ten map chunks cannot multiply the budget, and an exhausted
     * clock fails fast instead of entering the engine at all.
     */
    class PassClock(private val pass: String, private val budgetMs: Long) {
        private val deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L

        /** The remaining wall, floored at 1ms for callers that display it. */
        fun remainingMs(): Long =
            ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceIn(1L, budgetMs)

        /**
         * Review R2: a SPENT clock returns the typed failure directly and
         * never enters the engine: the ladder's later attempts and the
         * remaining map chunks cannot start a real generation only to
         * abandon it milliseconds later (each such entry dispatched another
         * native cancel and logged another non-fatal against a "0s budget").
         */
        suspend fun generate(llm: TranscriptionBackend, prompt: String): Result<String> {
            val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) {
                val typed = PassTimeoutException(pass, budgetMs)
                Log.w(TAG, "$pass pass clock is spent; failing fast without entering the engine")
                return Result.failure(typed)
            }
            return generateWithBudget(llm, prompt, remaining, pass)
        }
    }
}
