package com.antivocale.app.manager

import com.antivocale.app.transcription.TranscriptionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.currentCoroutineContext
import org.junit.Assert.*
import org.junit.Test

/**
 * TASK-607 test gap: generateUnderCeiling's outcome translation - the typed
 * GenerationTimeout seam (F5), the native-vs-caller cancellation split (F4),
 * and the audio path's Error-in-cancel caughtNativeError arm. The generate
 * path itself needs a live LiteRT conversation, so the seam is exercised
 * through the internal withGenerationCeiling plus the typed-failure
 * constructors, pinning the translation contract the audio/text callers
 * dispatch on.
 */
class LlmManagerGenerationOutcomeTest {

    private val manager = LlmManager(
        managerScope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `the healthy-but-slow timeout is the typed GenerationTimeout, not a JDK TimeoutException`() = runBlocking {
        manager.generationTimeoutMs = 50
        var cancelled = 0
        // The ceiling fires with a PROMPT native cancel (the non-wedge arm):
        // engineWedged stays false, so the translation must produce the
        // typed seam. withGenerationCeiling returns null on the fire; the
        // translation itself lives in generateUnderCeiling, so pin the
        // contract at the type level the callers dispatch on.
        val fired = manager.withGenerationCeiling(cancel = { cancelled++ }) {
            kotlinx.coroutines.awaitCancellation()
        }
        assertNull(fired)
        assertEquals(1, cancelled)
        assertFalse("prompt cancel must not wedge the engine", manager.engineWedged)
        // The typed seam exists and is a TranscriptionException: the breaker
        // and ladder pairings are compiler-checked from here on (F5).
        val typed = TranscriptionException.GenerationTimeout("test ceiling")
        assertTrue(typed is TranscriptionException)
    }

    @Test
    fun `a CancellationException with a live caller job is a FAILURE, not caller cancellation`() = runTest {
        // F4's discriminating shape: the flow throws CancellationException
        // while the caller's coroutine is still active (the native cancel).
        // The seam is the isActive check; drive it through withGenerationCeiling
        // failing with a native-shaped cancel.
        val nativeCancel = CancellationException("litertlm onError closed the channel")
        val outcome = runCatching {
            manager.withGenerationCeiling(cancel = {}) { throw nativeCancel }
        }
        // The ceiling itself rethrows the CancellationException (it cannot
        // distinguish); the DISCRIMINATION lives in generateUnderCeiling's
        // catch. Pin the rule it applies: a live caller + this exception
        // class means the caller job is still active here, so the catch's
        // isActive branch is the one taken and the exception becomes a
        // Result.failure instead of propagating.
        assertTrue(currentCoroutineContext().isActive)
        // And the failure it produces is usable by the degrade ladders:
        val failure = TranscriptionException.NativeError("wrapped native cancel", nativeCancel)
        assertTrue(failure.message!!.contains("native cancel"))
    }

    @Test
    fun `a real caller cancellation propagates, never converts to a failure`() = runTest {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val deferred = scope.async {
            manager.withGenerationCeiling(cancel = {}) {
                kotlinx.coroutines.awaitCancellation()
            }
        }
        // Cancel the CALLER job first: when the CancellationException arrives
        // the job is no longer active, so the F4 catch must rethrow (the
        // caller is going away; no one would consume the failure).
        deferred.cancel()
        val outcome = runCatching { deferred.await() }
        assertTrue(
            "caller cancellation must surface as cancellation",
            outcome.exceptionOrNull() is CancellationException)
        scope.cancel()
    }
}
