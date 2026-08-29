package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.ModelFamily
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Family-dispatch tests for the external-models engine (TASK-331): initialize
 * routes through [ModelFamilySupport], so missing-file and metadata failures
 * name the roles of the record's OWN family, not the hardcoded transducer set.
 * A real OfflineRecognizer cannot be constructed on the JVM, so these tests assert
 * the pre-native failure-mode ordering only.
 */
class ExternalSherpaBackendTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val backend = ExternalSherpaBackend()

    @Test
    fun `transducer dir missing decoder names the decoder role`() = runTest {
        val dir = tmp.newFolder("transducer")
        dir.resolve(SherpaBackend.CANONICAL_ENCODER).writeText("x")
        dir.resolve(SherpaBackend.CANONICAL_JOINER).writeText("x")
        dir.resolve(SherpaBackend.CANONICAL_TOKENS).writeText("x")

        val result = backend.initialize(mockk(), config(record(dir, ModelFamily.TRANSDUCER, "nemo_transducer")))

        assertTrue(result.exceptionOrNull() is TranscriptionException.ModelLoadError)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("expected decoder role in: $message", message.contains(SherpaBackend.CANONICAL_DECODER))
        assertFalse("must not blame sense-voice roles: $message", message.contains("model.int8.onnx"))
    }

    @Test
    fun `sense voice dir missing model file names the model role not encoder`() = runTest {
        val dir = tmp.newFolder("sensevoice")
        dir.resolve(SherpaBackend.CANONICAL_TOKENS).writeText("x")

        val result = backend.initialize(mockk(), config(record(dir, ModelFamily.SENSE_VOICE, "sense_voice")))

        assertTrue(result.exceptionOrNull() is TranscriptionException.ModelLoadError)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("expected model role in: $message", message.contains("model.int8.onnx"))
        assertFalse("must not blame transducer roles: $message", message.contains("encoder"))
    }

    @Test
    fun `sense voice record with model file absent fails before any config construction`() = runTest {
        val dir = tmp.newFolder("sensevoice-empty")
        dir.resolve(SherpaBackend.CANONICAL_TOKENS).writeText("x")

        val result = backend.initialize(mockk(), config(record(dir, ModelFamily.SENSE_VOICE, "sense_voice")))

        // The pre-flight missing-files check must fire before the metadata check or
        // the OfflineRecognizer constructor, so the failure is a ModelLoadError
        // naming the model role (not a native/linkage error from config construction).
        assertTrue(result.exceptionOrNull() is TranscriptionException.ModelLoadError)
        assertFalse(result.exceptionOrNull() is TranscriptionException.NativeError)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("model.int8.onnx"))
    }

    @Test
    fun `sense voice dir with model file present passes the missing-files pre-flight`() = runTest {
        val dir = tmp.newFolder("sensevoice-full")
        dir.resolve("model.int8.onnx").writeText("x")
        dir.resolve(SherpaBackend.CANONICAL_TOKENS).writeText("x")

        val result = backend.initialize(mockk(), config(record(dir, ModelFamily.SENSE_VOICE, "sense_voice")))

        // Past pre-flight the engine proceeds to native construction, which cannot
        // succeed on the JVM; assert only that the failure is no longer the
        // missing-files ModelLoadError (ordering guard, no native mocking).
        val message = result.exceptionOrNull()?.message ?: ""
        assertFalse("must be past the missing-files check: $message", message.contains("missing files"))
    }

    @Test
    fun `transducer encoder without metadata fails the pre-native metadata check`() = runTest {
        val dir = tmp.newFolder("transducer-meta")
        dir.resolve(SherpaBackend.CANONICAL_ENCODER).writeText("")
        dir.resolve(SherpaBackend.CANONICAL_DECODER).writeText("x")
        dir.resolve(SherpaBackend.CANONICAL_JOINER).writeText("x")
        dir.resolve(SherpaBackend.CANONICAL_TOKENS).writeText("x")

        val result = backend.initialize(mockk(), config(record(dir, ModelFamily.TRANSDUCER, "nemo_transducer")))

        assertTrue(result.exceptionOrNull() is TranscriptionException.ModelLoadError)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("expected metadata failure in: $message", message.contains("metadata"))
    }

    private fun config(record: ExternalModelRecord) =
        BackendConfig.ExternalConfig(record, numThreads = 4, provider = "cpu")


    @Test
    fun `whisper family reports 30s chunking others report single-pass (TASK-402)`() {
        // sherpa's whisper DecodeStream caps a single decode at 30s; the backend
        // must chunk external whisper imports exactly like the built-in one. The
        // getter derives from the family stored at initialize time; this test
        // pins the family matrix through the seam, the initialize wiring is
        // device-verified (both set sites sit next to configuredId).
        val dir = tmp.newFolder("fam")
        dir.resolve(SherpaBackend.CANONICAL_ENCODER).writeText("x")
        dir.resolve(SherpaBackend.CANONICAL_TOKENS).writeText("x")
        dir.resolve(SherpaBackend.CANONICAL_DECODER).writeText("x")

        // pre-configure state: single-pass (no family yet)
        assertNull(backend.maxChunkDurationSeconds)

        // whisper record configures the family -> 30
        backend.configureForTest(record(dir, ModelFamily.WHISPER, ""))
        assertEquals(30, backend.maxChunkDurationSeconds)

        // transducer -> null (any length in one pass)
        backend.configureForTest(record(dir, ModelFamily.TRANSDUCER, "nemo_transducer"))
        assertNull(backend.maxChunkDurationSeconds)

        // ctc and sensevoice -> null
        backend.configureForTest(record(dir, ModelFamily.CTC, "nemo_ctc"))
        assertNull(backend.maxChunkDurationSeconds)
        backend.configureForTest(record(dir, ModelFamily.SENSE_VOICE, ""))
        assertNull(backend.maxChunkDurationSeconds)

        // canary (TASK-408) -> 10: past ~10s the decode is superlinear and
        // degenerate, and it alone also demands VAD-aligned cuts.
        backend.configureForTest(record(dir, ModelFamily.CANARY, ""))
        assertEquals(10, backend.maxChunkDurationSeconds)
        assertTrue(backend.requiresVadAlignedChunking)
        // every other family keeps the plain-chunking contract
        backend.configureForTest(record(dir, ModelFamily.WHISPER, ""))
        assertFalse(backend.requiresVadAlignedChunking)
    }

    private fun record(dir: java.io.File, family: ModelFamily, modelType: String) = ExternalModelRecord(
        id = "abc123def456",
        displayName = "Test model",
        dir = dir.absolutePath,
        family = family,
        modelType = modelType,
        languages = emptyList(),
        source = ExternalModelSource.LOCAL,
        sourceUrl = null,
        files = emptyMap(),
        sizeBytes = 1L,
        importedAt = 0L,
    )
}
