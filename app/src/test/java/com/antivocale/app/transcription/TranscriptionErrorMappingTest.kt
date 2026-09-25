package com.antivocale.app.transcription

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that [TranscriptionOrchestrator.userFacingErrorMessage] maps each
 * [TranscriptionException] variant to the correct localized string, and that
 * non-TranscriptionException errors fall back to the generic message.
 *
 * Also verifies that the mapped strings are non-empty and distinct (so the
 * user actually sees different messages for different failure modes).
 *
 * Uses Robolectric to resolve real string resources from the app's Context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class TranscriptionErrorMappingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `ModelLoadError maps to error_model_load string`() {
        val error = TranscriptionException.ModelLoadError("directory not found: /path")
        val msg = TranscriptionOrchestrator.userFacingErrorMessage(context, error)
        assertEquals(context.getString(R.string.error_model_load), msg)
    }

    @Test
    fun `CorruptModelFiles maps to the corrupt-healed re-download string`() {
        // TASK-660: the typed corruption verdict routes to the re-download
        // message. The former message-prefix match could never fire because
        // ModelLoadError prepends "Model load failed: " to every detail.
        val error = TranscriptionException.CorruptModelFiles(
            "corrupted model files (removed, re-download from the Models tab): turbo-encoder.int8.onnx")
        val msg = TranscriptionOrchestrator.userFacingErrorMessage(context, error)
        assertEquals(context.getString(R.string.error_model_corrupt_healed), msg)
    }

    @Test
    fun `NativeError maps to error_native string`() {
        val error = TranscriptionException.NativeError("JNI crash in sherpa-onnx")
        val msg = TranscriptionOrchestrator.userFacingErrorMessage(context, error)
        assertEquals(context.getString(R.string.error_native), msg)
    }

    @Test
    fun `NotInitialized maps to error_not_initialized string`() {
        val error = TranscriptionException.NotInitialized()
        val msg = TranscriptionOrchestrator.userFacingErrorMessage(context, error)
        assertEquals(context.getString(R.string.error_not_initialized), msg)
    }

    @Test
    fun `ExternalModelUnavailable maps to error_model_unavailable string`() {
        // TASK-342: a dangling external backend must NOT surface as the generic
        // "model may be corrupt" ModelLoadError message.
        val error = TranscriptionException.ExternalModelUnavailable("external:gone")
        val msg = TranscriptionOrchestrator.userFacingErrorMessage(context, error)
        assertEquals(context.getString(R.string.error_model_unavailable), msg)
    }

    @Test
    fun `NoTranscriptionProduced maps to generic transcription_failed string`() {
        val error = TranscriptionException.NoTranscriptionProduced()
        val msg = TranscriptionOrchestrator.userFacingErrorMessage(context, error)
        assertEquals(context.getString(R.string.transcription_failed), msg)
    }

    @Test
    fun `non-TranscriptionException error falls back to generic transcription_failed`() {
        val error = RuntimeException("some unexpected error")
        val msg = TranscriptionOrchestrator.userFacingErrorMessage(context, error)
        assertEquals(context.getString(R.string.transcription_failed), msg)
    }

    // ---- Distinctness: each error type should give a DIFFERENT user message ----

    @Test
    fun `ModelLoadError and NativeError produce different messages`() {
        val modelMsg = TranscriptionOrchestrator.userFacingErrorMessage(
            context, TranscriptionException.ModelLoadError("test")
        )
        val nativeMsg = TranscriptionOrchestrator.userFacingErrorMessage(
            context, TranscriptionException.NativeError("test")
        )
        assertTrue("ModelLoadError and NativeError should produce different messages",
            modelMsg != nativeMsg)
    }

    @Test
    fun `CorruptModelFiles message differs from the generic ModelLoadError message`() {
        // TASK-660: the heal verdict must surface the re-download instruction,
        // not the generic load failure.
        val corruptMsg = TranscriptionOrchestrator.userFacingErrorMessage(
            context, TranscriptionException.CorruptModelFiles("test")
        )
        val modelMsg = TranscriptionOrchestrator.userFacingErrorMessage(
            context, TranscriptionException.ModelLoadError("test")
        )
        assertTrue("CorruptModelFiles and ModelLoadError should produce different messages",
            corruptMsg != modelMsg)
    }

    @Test
    fun `NotInitialized message is distinct from ModelLoadError`() {
        val notInitMsg = TranscriptionOrchestrator.userFacingErrorMessage(
            context, TranscriptionException.NotInitialized()
        )
        val modelMsg = TranscriptionOrchestrator.userFacingErrorMessage(
            context, TranscriptionException.ModelLoadError("test")
        )
        assertTrue("NotInitialized and ModelLoadError should produce different messages",
            notInitMsg != modelMsg)
    }

    // ---- isNoModelConfiguredError type-based detection ----

    @Test
    fun `isNoModelConfiguredError returns true for NotInitialized`() {
        assertTrue(TranscriptionOrchestrator.isNoModelConfiguredError(
            TranscriptionException.NotInitialized()
        ))
    }

    @Test
    fun `isNoModelConfiguredError returns false for ModelLoadError`() {
        assertEquals(false, TranscriptionOrchestrator.isNoModelConfiguredError(
            TranscriptionException.ModelLoadError("missing files")
        ))
    }

    @Test
    fun `isNoModelConfiguredError returns false for NativeError`() {
        assertEquals(false, TranscriptionOrchestrator.isNoModelConfiguredError(
            TranscriptionException.NativeError("JNI crash")
        ))
    }

    @Test
    fun `isNoModelConfiguredError returns false for generic RuntimeException`() {
        assertEquals(false, TranscriptionOrchestrator.isNoModelConfiguredError(
            RuntimeException("something else")
        ))
    }
}
