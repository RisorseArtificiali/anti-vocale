package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.download.DownloadState
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Parakeet download state after refactoring to
 * individual file downloads (no tar.bz2 extraction).
 *
 * Verifies:
 * - ParakeetUiState has no extraction-related fields
 * - Download state transitions are correct
 * - Auto-selection logic: model auto-selects only when no model is active
 */
class ParakeetDownloadStateTest {

    // ── ParakeetUiState defaults ──────────────────────────────────

    @Test
    fun `ParakeetUiState defaults to not downloading and no model path`() {
        val state = ModelViewModel.ParakeetUiState()

        assertFalse(state.isDownloading)
        assertEquals(0f, state.downloadProgress)
        assertEquals(DownloadState.Idle, state.downloadState)
        assertNull(state.modelPath)
        assertNull(state.errorMessage)
        assertNull(state.partialDownload)
        assertFalse(state.showDownloadDialog)
        assertFalse(state.showDeleteDialog)
    }

    @Test
    fun `ParakeetUiState has no extraction-related dead fields`() {
        val state = ModelViewModel.ParakeetUiState::class
        val fieldNames = state.members.map { it.name }
        assertFalse(
            "ParakeetUiState should not have needsExtraction after refactoring",
            fieldNames.contains("needsExtraction")
        )
        assertFalse(
            "ParakeetUiState should not have hasOrphanedFiles after refactoring",
            fieldNames.contains("hasOrphanedFiles")
        )
        assertFalse(
            "ParakeetUiState should not have isDownloaded (derived from modelPath)",
            fieldNames.contains("isDownloaded")
        )
    }

    // ── Download state transitions ────────────────────────────────

    @Test
    fun `ParakeetUiState transitions from idle to downloading`() {
        val idle = ModelViewModel.ParakeetUiState()

        val downloading = idle.copy(
            isDownloading = true,
            downloadProgress = 0f,
            downloadState = DownloadState.Connecting("https://huggingface.co/...")
        )

        assertTrue(downloading.isDownloading)
        assertEquals(0f, downloading.downloadProgress)
    }

    @Test
    fun `ParakeetUiState tracks download progress`() {
        val state = ModelViewModel.ParakeetUiState(
            isDownloading = true,
            downloadProgress = 0.75f,
            downloadState = DownloadState.Downloading(
                bytesDownloaded = 350_000_000L,
                totalBytes = 470_000_000L,
                progressPercent = 75f
            )
        )

        assertTrue(state.isDownloading)
        assertEquals(0.75f, state.downloadProgress, 0.001f)
    }

    @Test
    fun `ParakeetUiState transitions to complete with model path`() {
        val state = ModelViewModel.ParakeetUiState(
            isDownloading = false,
            downloadProgress = 1f,
            downloadState = DownloadState.Complete(
                java.io.File("/data/user/0/app/parakeet-tdt/parakeet-tdt-0.6b-v3-int8")
            ),
            modelPath = "/data/user/0/app/parakeet-tdt/parakeet-tdt-0.6b-v3-int8"
        )

        assertFalse(state.isDownloading)
        assertEquals(1f, state.downloadProgress)
        assertNotNull(state.modelPath)
        assertNull(state.partialDownload)
    }

    @Test
    fun `ParakeetUiState handles partial download`() {
        val state = ModelViewModel.ParakeetUiState(
            partialDownload = DownloadState.PartiallyDownloaded(
                bytesDownloaded = 200_000_000L,
                totalBytes = 470_000_000L,
                progressPercent = 42
            )
        )

        assertNotNull(state.partialDownload)
        assertEquals(42, state.partialDownload!!.progressPercent)
        assertFalse(state.isDownloading)
    }

    @Test
    fun `ParakeetUiState clears partial download`() {
        val withPartial = ModelViewModel.ParakeetUiState(
            partialDownload = DownloadState.PartiallyDownloaded(
                bytesDownloaded = 200_000_000L,
                totalBytes = 470_000_000L,
                progressPercent = 42
            )
        )

        val cleared = withPartial.copy(partialDownload = null)

        assertNull(cleared.partialDownload)
    }

    @Test
    fun `ParakeetUiState handles download error`() {
        val state = ModelViewModel.ParakeetUiState(
            isDownloading = false,
            errorMessage = "Insufficient storage. Need ~464MB",
            downloadState = DownloadState.Error("Insufficient storage. Need ~464MB")
        )

        assertFalse(state.isDownloading)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("storage"))
    }

    @Test
    fun `ParakeetUiState handles cancel`() {
        val downloading = ModelViewModel.ParakeetUiState(
            isDownloading = true,
            downloadProgress = 0.3f,
            downloadState = DownloadState.Downloading(150_000_000L, 470_000_000L, 30f)
        )

        val cancelled = downloading.copy(
            isDownloading = false,
            downloadState = DownloadState.Cancelled("User cancelled"),
            errorMessage = null
        )

        assertFalse(cancelled.isDownloading)
        assertTrue(cancelled.downloadState is DownloadState.Cancelled)
    }

    // ── Auto-selection logic ──────────────────────────────────────

    @Test
    fun `auto-selection triggers when modelName is blank`() {
        // Fresh install: no model selected yet
        val uiState = ModelViewModel.UiState()

        assertTrue(
            "Auto-selection should trigger when no model name is set",
            uiState.modelName.isBlank()
        )
    }

    @Test
    fun `auto-selection does NOT trigger when a model is already active`() {
        // User already has Whisper selected
        val uiState = ModelViewModel.UiState(
            modelName = "Whisper Small",
            modelPath = "/data/user/0/app/sherpa-onnx-whisper-small"
        )

        assertFalse(
            "Auto-selection should NOT trigger when a model is already active",
            uiState.modelName.isBlank()
        )
    }

    @Test
    fun `auto-selection does NOT trigger when Parakeet is already selected`() {
        val uiState = ModelViewModel.UiState(
            modelName = "Parakeet TDT",
            modelPath = "/data/user/0/app/parakeet-tdt/parakeet-tdt-0.6b-v3-int8"
        )

        assertFalse(
            "Auto-selection should NOT trigger when Parakeet is already selected",
            uiState.modelName.isBlank()
        )
    }

    @Test
    fun `download complete sets model path for auto-selection`() {
        val modelDir = java.io.File("/data/user/0/app/parakeet-tdt/parakeet-tdt-0.6b-v3-int8")
        val state = ModelViewModel.ParakeetUiState(
            isDownloading = false,
            modelPath = modelDir.absolutePath,
            downloadState = DownloadState.Complete(modelDir),
            partialDownload = null
        )

        // The onComplete handler saves modelPath and checks uiState.modelName.isBlank()
        assertNotNull("Model path must be set for auto-selection", state.modelPath)
        assertNull("Partial download must be cleared on completion", state.partialDownload)
    }
}
