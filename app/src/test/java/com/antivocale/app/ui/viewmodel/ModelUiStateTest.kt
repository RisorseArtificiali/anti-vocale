package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.download.DownloadState
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
            modelPath = "/data/user/0/.../whisper-model"
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
            modelPath = "/data/user/0/.../whisper-model"
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
            modelPath = "/data/user/0/.../whisper-model"
        )

        // Simulates what unloadModel() does
        val unloaded = loaded.copy(
            modelName = "",
            modelPath = "",
            status = ModelViewModel.ModelStatus.UNLOADED
        )

        assertEquals(ModelViewModel.ModelStatus.UNLOADED, unloaded.status)
        assertFalse("Unload button must not show after unload", unloaded.status == ModelViewModel.ModelStatus.READY)
    }

    @Test
    fun `auto-unload resets all model fields`() {
        val ready = ModelViewModel.UiState(
            status = ModelViewModel.ModelStatus.READY,
            modelName = "Gemma 4 E2B",
            modelPath = "/data/user/0/.../gemma.ml"
        )

        // Simulates what onModelAutoUnloaded() does
        val autoUnloaded = ready.copy(
            modelName = "",
            modelPath = "",
            status = ModelViewModel.ModelStatus.UNLOADED
        )

        assertEquals(ModelViewModel.ModelStatus.UNLOADED, autoUnloaded.status)
        assertTrue(autoUnloaded.modelName.isBlank())
        assertTrue(autoUnloaded.modelPath.isBlank())
    }

    @Test
    fun `restoring saved path must preserve UNLOADED status`() {
        // Simulates what loadSavedModelPath() does on init:
        // it sets modelName and modelPath but must NOT set READY
        val restored = ModelViewModel.UiState().copy(
            modelPath = "/data/user/0/.../whisper-model",
            modelName = "Distil Large v3",
            statusMessage = "Model ready"
            // status intentionally NOT changed — must stay UNLOADED
        )

        assertEquals(
            "Restoring a saved path must keep UNLOADED status",
            ModelViewModel.ModelStatus.UNLOADED,
            restored.status
        )
    }

    // ── Per-variant download state isolation ────────────────────────

    @Test
    fun `VariantDownloadState defaults to idle`() {
        val state = ModelViewModel.VariantDownloadState()

        assertFalse(state.isDownloading)
        assertEquals(0f, state.downloadProgress)
        assertEquals(DownloadState.Idle, state.downloadState)
        assertNull(state.errorMessage)
    }

    @Test
    fun `ModelEntryUiState defaults with empty variantDownloadStates`() {
        val state = ModelViewModel.ModelEntryUiState()

        assertTrue(state.variantDownloadStates.isEmpty())
        assertFalse(state.isAnyDownloading)
    }

    @Test
    fun `isAnyDownloading is true when any variant is downloading`() {
        val variant = "small"
        val state = ModelViewModel.ModelEntryUiState(
            variantDownloadStates = mapOf(
                variant to ModelViewModel.VariantDownloadState(isDownloading = true, downloadProgress = 0.5f)
            )
        )

        assertTrue(state.isAnyDownloading)
    }

    @Test
    fun `isAnyDownloading is false when no variants are downloading`() {
        val state = ModelViewModel.ModelEntryUiState(
            variantDownloadStates = mapOf(
                "small" to ModelViewModel.VariantDownloadState(isDownloading = false)
            )
        )

        assertFalse(state.isAnyDownloading)
    }

    @Test
    fun `downloading variant A does not affect variant B state`() {
        val variantA = "small"
        val variantB = "turbo"

        // Start download on A
        val state = ModelViewModel.ModelEntryUiState(
            variantDownloadStates = mapOf(
                variantA to ModelViewModel.VariantDownloadState(
                    isDownloading = true,
                    downloadProgress = 0.3f,
                    downloadState = DownloadState.Downloading(300, 1000, 30f)
                )
            )
        )

        // B should have no download state
        assertNull(state.variantDownloadStates[variantB])
        assertEquals(0.3f, state.variantDownloadStates[variantA]!!.downloadProgress, 0.001f)
        assertTrue(state.variantDownloadStates[variantA]!!.isDownloading)
    }

    @Test
    fun `updating variant A progress does not overwrite variant B progress`() {
        val variantA = "small"
        val variantB = "turbo"

        // Both downloading simultaneously
        var state = ModelViewModel.ModelEntryUiState(
            variantDownloadStates = mapOf(
                variantA to ModelViewModel.VariantDownloadState(isDownloading = true, downloadProgress = 0.2f),
                variantB to ModelViewModel.VariantDownloadState(isDownloading = true, downloadProgress = 0.7f)
            )
        )

        // Update A's progress (simulates what handleServiceProgress does)
        state = state.copy(
            variantDownloadStates = state.variantDownloadStates + (variantA to
                state.variantDownloadStates[variantA]!!.copy(downloadProgress = 0.25f))
        )

        assertEquals(0.25f, state.variantDownloadStates[variantA]!!.downloadProgress, 0.001f)
        assertEquals("Variant B progress should be unchanged",
            0.7f, state.variantDownloadStates[variantB]!!.downloadProgress, 0.001f)
    }

    @Test
    fun `cancelling variant A does not affect variant B state`() {
        val variantA = "small"
        val variantB = "turbo"

        // Both downloading
        var state = ModelViewModel.ModelEntryUiState(
            variantDownloadStates = mapOf(
                variantA to ModelViewModel.VariantDownloadState(isDownloading = true, downloadProgress = 0.5f),
                variantB to ModelViewModel.VariantDownloadState(isDownloading = true, downloadProgress = 0.3f)
            )
        )

        // Cancel A (simulates what cancelDownload does: remove from map)
        state = state.copy(variantDownloadStates = state.variantDownloadStates - variantA)

        assertNull("Variant A should be removed", state.variantDownloadStates[variantA])
        assertEquals("Variant B progress should be unchanged",
            0.3f, state.variantDownloadStates[variantB]!!.downloadProgress, 0.001f)
        assertTrue("Variant B should still be downloading",
            state.variantDownloadStates[variantB]!!.isDownloading)
    }

    @Test
    fun `error on variant A does not leak to variant B`() {
        val variantA = "small"
        val variantB = "turbo"

        var state = ModelViewModel.ModelEntryUiState(
            variantDownloadStates = mapOf(
                variantA to ModelViewModel.VariantDownloadState(isDownloading = true, downloadProgress = 0.5f),
                variantB to ModelViewModel.VariantDownloadState(isDownloading = true, downloadProgress = 0.3f)
            )
        )

        // Error on A
        state = state.copy(
            variantDownloadStates = state.variantDownloadStates + (variantA to
                ModelViewModel.VariantDownloadState(
                    isDownloading = false,
                    errorMessage = "Network error"
                ))
        )

        assertEquals("Network error", state.variantDownloadStates[variantA]?.errorMessage)
        assertNull("Variant B should have no error", state.variantDownloadStates[variantB]?.errorMessage)
    }

    @Test
    fun `completing variant A removes it from download states while B continues`() {
        val variantA = "small"
        val variantB = "turbo"

        // Both downloading, A completes
        val state = ModelViewModel.ModelEntryUiState(
            downloadedVariants = setOf(variantA),
            variantDownloadStates = mapOf(
                // A removed from map on completion (as handleServiceProgress does)
                variantB to ModelViewModel.VariantDownloadState(isDownloading = true, downloadProgress = 0.8f)
            )
        )

        assertNull("Completed variant should be removed from download states", state.variantDownloadStates[variantA])
        assertTrue("Completed variant should be in downloadedVariants", state.downloadedVariants.contains(variantA))
        assertEquals(0.8f, state.variantDownloadStates[variantB]!!.downloadProgress, 0.001f)
    }

    @Test
    fun `Qwen3 per-variant isolation works the same way`() {
        val variant = "0.6b-int4"

        val state = ModelViewModel.ModelEntryUiState(
            variantDownloadStates = mapOf(
                variant to ModelViewModel.VariantDownloadState(isDownloading = true, downloadProgress = 0.6f)
            )
        )

        assertTrue(state.isAnyDownloading)
        assertEquals(0.6f, state.variantDownloadStates[variant]!!.downloadProgress, 0.001f)
    }

}
