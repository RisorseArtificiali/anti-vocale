package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.FilePin
import com.antivocale.app.data.ModelFamily
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the external-models engine (plan v2a, Task 4): placeholder id
 * semantics, readiness, config-type safety, and the not-initialized transcription guard.
 * The recognizer path itself needs real ONNX artifacts and is covered by device tests.
 */
class ExternalSherpaBackendContractTest {
    private val backend = ExternalSherpaBackend(mockk(relaxed = true))

    @Test
    fun `placeholder id before init and after unload`() {
        assertEquals("external", backend.id)
        backend.unload()
        assertEquals("external", backend.id)
    }

    @Test
    fun `not ready before initialize`() {
        assertFalse(backend.isReady())
    }

    @Test
    fun `wrong config type fails cleanly`() = runTest {
        val result = backend.initialize(mockk(), BackendConfig.LiteRTConfig(modelPath = "/x"))
        assertTrue(result.isFailure)
    }

    @Test
    fun `blank transcription before init fails with NotInitialized`() = runTest {
        val result = backend.transcribeAudio(FloatArray(1600), 16000, "")
        assertTrue(result.exceptionOrNull() is TranscriptionException.NotInitialized)
    }

    @Test
    fun `missing record directory fails with ModelLoadError`() = runTest {
        val record = record(dir = "/nonexistent-external-dir")
        val result = backend.initialize(mockk(), BackendConfig.ExternalConfig(record, numThreads = 4, provider = "cpu"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TranscriptionException.ModelLoadError)
    }

    private fun record(dir: String) = ExternalModelRecord(
        id = "abc123def456",
        displayName = "GigaAM v3",
        dir = dir,
        family = ModelFamily.TRANSDUCER,
        modelType = "nemo_transducer",
        languages = listOf("ru"),
        source = ExternalModelSource.LOCAL,
        sourceUrl = null,
        files = SherpaBackend.REQUIRED_MODEL_FILES.associateWith { FilePin("00", verified = true) },
        sizeBytes = 1L,
        importedAt = 0L,
    )
}
