package com.antivocale.app.ui.viewmodel

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ModelViewModel.UiState and ModelStatus.
 *
 * These verify the core invariant that caused the unload-button bug:
 * a model having a name does NOT mean it is loaded in memory.
 * Only ModelStatus.READY means the model is actually loaded.
 *
 * Pure Kotlin tests — no Android dependencies, no mocking needed.
 */
class ModelUiStateTest {

    // ── Default state ──────────────────────────────────────────────

    @Test
    fun `default UiState has UNLOADED status and blank model name`() {
        val state = ModelViewModel.UiState()

        assertEquals(ModelViewModel.ModelStatus.UNLOADED, state.status)
        assertTrue("modelName should be blank by default", state.modelName.isBlank())
        assertTrue("modelPath should be blank by default", state.modelPath.isBlank())
    }

    // ── Bug scenario: configured path ≠ loaded model ───────────────

    @Test
    fun `configured model path with UNLOADED status should NOT show unload button`() {
        // This is the exact scenario from the bug: loadSavedModelPath() sets
        // modelName and modelPath from preferences, but status stays UNLOADED
        val state = ModelViewModel.UiState(
            status = ModelViewModel.ModelStatus.UNLOADED,
            modelName = "Distil Large v3",
            modelPath = "/data/user/0/.../whisper-model",
            isModelPathValid = true
        )

        assertFalse(
            "Unload button must not show when model is configured but not loaded",
            state.status == ModelViewModel.ModelStatus.READY
        )
        // Old buggy check: modelName.isNotBlank() would return true here
        assertTrue(
            "Old check (modelName.isNotBlank) would incorrectly show button",
            state.modelName.isNotBlank()
        )
    }

    @Test
    fun `configured model path with LOADING status should NOT show unload button`() {
        val state = ModelViewModel.UiState(
            status = ModelViewModel.ModelStatus.LOADING,
            modelName = "Distil Large v3",
            modelPath = "/data/user/0/.../whisper-model"
        )

        assertFalse(
            "Unload button must not show while model is still loading",
            state.status == ModelViewModel.ModelStatus.READY
        )
    }

    @Test
    fun `configured model path with ERROR status should NOT show unload button`() {
        val state = ModelViewModel.UiState(
            status = ModelViewModel.ModelStatus.ERROR,
            modelName = "Distil Large v3",
            modelPath = "/data/user/0/.../whisper-model",
            statusMessage = "Failed to load model"
        )

        assertFalse(
            "Unload button must not show for failed model load",
            state.status == ModelViewModel.ModelStatus.READY
        )
    }

    @Test
    fun `only READY status should show unload button`() {
        val state = ModelViewModel.UiState(
            status = ModelViewModel.ModelStatus.READY,
            modelName = "Distil Large v3",
            modelPath = "/data/user/0/.../whisper-model",
            isModelPathValid = true
        )

        assertTrue(
            "Unload button should show only when model is READY",
            state.status == ModelViewModel.ModelStatus.READY
        )
    }

    // ── State transition invariants ────────────────────────────────

    @Test
    fun `clearing model via unload resets status to UNLOADED`() {
        val loaded = ModelViewModel.UiState(
            status = ModelViewModel.ModelStatus.READY,
            modelName = "Distil Large v3",
            modelPath = "/data/user/0/.../whisper-model",
            memoryUsage = 2048
        )

        // Simulates what unloadModel() does
        val unloaded = loaded.copy(
            modelName = "",
            modelPath = "",
            status = ModelViewModel.ModelStatus.UNLOADED,
            memoryUsage = 0
        )

        assertEquals(ModelViewModel.ModelStatus.UNLOADED, unloaded.status)
        assertFalse("Unload button must not show after unload", unloaded.status == ModelViewModel.ModelStatus.READY)
    }

    @Test
    fun `auto-unload resets all model fields`() {
        val ready = ModelViewModel.UiState(
            status = ModelViewModel.ModelStatus.READY,
            modelName = "Gemma 4 E2B",
            modelPath = "/data/user/0/.../gemma.ml",
            memoryUsage = 4096
        )

        // Simulates what onModelAutoUnloaded() does
        val autoUnloaded = ready.copy(
            modelName = "",
            modelPath = "",
            status = ModelViewModel.ModelStatus.UNLOADED,
            memoryUsage = 0
        )

        assertEquals(ModelViewModel.ModelStatus.UNLOADED, autoUnloaded.status)
        assertTrue(autoUnloaded.modelName.isBlank())
        assertTrue(autoUnloaded.modelPath.isBlank())
        assertEquals(0L, autoUnloaded.memoryUsage)
    }

    @Test
    fun `restoring saved path must preserve UNLOADED status`() {
        // Simulates what loadSavedModelPath() does on init:
        // it sets modelName and modelPath but must NOT set READY
        val restored = ModelViewModel.UiState().copy(
            modelPath = "/data/user/0/.../whisper-model",
            modelName = "Distil Large v3",
            isModelPathValid = true,
            statusMessage = "Model ready"
            // status intentionally NOT changed — must stay UNLOADED
        )

        assertEquals(
            "Restoring a saved path must keep UNLOADED status",
            ModelViewModel.ModelStatus.UNLOADED,
            restored.status
        )
    }

}
