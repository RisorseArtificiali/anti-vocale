package com.antivocale.app.manager

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

/**
 * TASK-644: the LiteRT engine is warmed outside backendManager's bookkeeping
 * (the preload receiver initializes directly), so LlmManager.initialize is
 * the only gate for the residency identity. A warm engine must NOT
 * early-return READY for a DIFFERENT model path: the path switch must fall
 * through into a full re-initialization (the same identity rule the
 * orchestrator applies to warm sherpa backends). The observable is
 * validation: the early return never touches the filesystem, a fall-through
 * does, so a nonexistent target path discriminates the two branches without
 * any native engine.
 */
class LlmManagerPathResidencyTest {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val context = mockk<Context>(relaxed = true)

    @Test
    fun `same path on a warm engine keeps the no-op early return`() {
        val manager = LlmManager(scope)
        manager.warmForTest("/models/gemma-a.litertlm")

        val result = manager.initialize(context, "/models/gemma-a.litertlm")

        // The early return succeeds WITHOUT file validation: the path string
        // never needs to exist.
        assertTrue(result.isSuccess)
    }

    @Test
    fun `different path on a warm engine falls through to re-initialization`() {
        val manager = LlmManager(scope)
        manager.warmForTest("/models/gemma-a.litertlm")
        val missing = "/models/does-not-exist-gemma-b.litertlm"

        val result = manager.initialize(context, missing)

        // Old behavior: success (early return, the new path never touched).
        // TASK-644 behavior: the engine is torn down and the load path runs,
        // which names the missing file instead of silently keeping variant A.
        assertTrue(result.isFailure)
        assertTrue(
            "expected the fall-through validation, got: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("file not found") == true)
    }
}
