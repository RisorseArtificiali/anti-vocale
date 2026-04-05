package com.antivocale.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the no-model-configured error detection logic used in
 * [InferenceService.isNoModelConfiguredError].
 *
 * The production method is private on an Android Service and cannot be tested
 * directly without Robolectric. Instead, this test mirrors the exact detection
 * logic and validates it against the real error messages produced by the model
 * loaders, ensuring the pattern contract is maintained.
 *
 * Production logic (InferenceService.kt):
 * ```kotlin
 * private fun isNoModelConfiguredError(error: Throwable): Boolean {
 *     val msg = error.message ?: return false
 *     return msg.contains("No ") && msg.contains("model configured")
 * }
 * ```
 *
 * If the production logic changes, this test must be updated to match.
 */
class NoModelErrorDetectionTest {

    /**
     * Mirror of [InferenceService.isNoModelConfiguredError].
     *
     * Returns true if the error message indicates no transcription model
     * is configured, which should trigger a user-friendly guidance
     * notification instead of a generic error.
     */
    private fun isNoModelConfiguredError(error: Throwable): Boolean {
        val msg = error.message ?: return false
        return msg.contains("No ") && msg.contains("model configured")
    }

    // ---------------------------------------------------------------------------
    // Positive cases: messages that SHOULD be detected as "no model configured"
    // These are the exact strings produced by the model loaders in InferenceService
    // ---------------------------------------------------------------------------

    @Test
    fun `detects no Whisper model configured`() {
        val error = IllegalStateException(
            "No Whisper model configured. Open the app to download a model."
        )
        assertTrue(isNoModelConfiguredError(error))
    }

    @Test
    fun `detects no Parakeet model configured`() {
        val error = IllegalStateException(
            "No Parakeet model configured. Open the app to download a model."
        )
        assertTrue(isNoModelConfiguredError(error))
    }

    @Test
    fun `detects no Qwen3-ASR model configured`() {
        val error = IllegalStateException(
            "No Qwen3-ASR model configured. Open the app to download a model."
        )
        assertTrue(isNoModelConfiguredError(error))
    }

    @Test
    fun `detects no LLM model configured`() {
        val error = IllegalStateException(
            "No LLM model configured. Open the app to download a model."
        )
        assertTrue(isNoModelConfiguredError(error))
    }

    // ---------------------------------------------------------------------------
    // Negative cases: real error messages from backends that should NOT trigger
    // the no-model notification
    // ---------------------------------------------------------------------------

    @Test
    fun `does not detect model directory not found error`() {
        // Produced by WhisperBackend, SherpaOnnxBackend, Qwen3AsrBackend
        val error = IllegalArgumentException(
            "Whisper model directory not found: /data/user/0/com.antivocale.app/models/whisper"
        )
        assertFalse(isNoModelConfiguredError(error))
    }

    @Test
    fun `does not detect generic backend load failure`() {
        // Produced by InferenceService.onFailure wrapping
        val error = RuntimeException("Failed to load backend: some JNI error")
        assertFalse(isNoModelConfiguredError(error))
    }

    @Test
    fun `does not detect null audio data error`() {
        val error = NullPointerException("Null audio data received")
        assertFalse(isNoModelConfiguredError(error))
    }

    @Test
    fun `does not detect model directory not found for Parakeet`() {
        val error = IllegalStateException(
            "Parakeet model directory not found: /path/to/model"
        )
        assertFalse(isNoModelConfiguredError(error))
    }

    @Test
    fun `does not detect model directory not found for Qwen3-ASR`() {
        val error = IllegalStateException(
            "Qwen3-ASR model directory not found: /path/to/model"
        )
        assertFalse(isNoModelConfiguredError(error))
    }

    // ---------------------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------------------

    @Test
    fun `returns false for throwable with null message`() {
        val error = RuntimeException()
        assertFalse(isNoModelConfiguredError(error))
    }

    @Test
    fun `returns false for empty message`() {
        val error = RuntimeException("")
        assertFalse(isNoModelConfiguredError(error))
    }

    @Test
    fun `returns false for message containing 'No' but not 'model configured'`() {
        val error = IllegalStateException("No audio permission granted")
        assertFalse(isNoModelConfiguredError(error))
    }

    @Test
    fun `returns false for message containing 'model configured' but not 'No'`() {
        val error = IllegalStateException("The model configured is outdated")
        assertFalse(isNoModelConfiguredError(error))
    }
}
