package com.antivocale.app.transcription

import com.antivocale.app.data.ModelFamily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK-513 (GH #93): family detection from candidate file names. */
class ModelFamilyDetectorTest {

    @Test
    fun `transducer set is detected unambiguously`() {
        val files = listOf(
            "encoder.int8.onnx", "decoder.int8.onnx",
            "joiner.int8.onnx", "tokens.txt")
        val r = ModelFamilyDetector.detect(files)
        assertEquals(ModelFamilyDetector.Result.Detected(ModelFamily.TRANSDUCER), r)
    }

    @Test
    fun `whisper-shaped set is ambiguous between whisper and canary`() {
        val files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")
        val r = ModelFamilyDetector.detect(files)
        assertTrue(r is ModelFamilyDetector.Result.Ambiguous)
        r as ModelFamilyDetector.Result.Ambiguous
        assertEquals(listOf(ModelFamily.WHISPER, ModelFamily.CANARY), r.candidates)
    }

    @Test
    fun `ctc set is detected unambiguously`() {
        val files = listOf("encoder.int8.onnx", "tokens.txt")
        val r = ModelFamilyDetector.detect(files)
        assertEquals(ModelFamilyDetector.Result.Detected(ModelFamily.CTC), r)
    }

    @Test
    fun `sense-voice set is ambiguous with ctc (identical file shape)`() {
        // model.onnx + tokens.txt is the exact shape of BOTH families; the
        // only true discriminator is the model.onnx metadata, read later by
        // the importer. Detection reports the two candidates.
        val files = listOf("model.onnx", "tokens.txt")
        val r = ModelFamilyDetector.detect(files)
        assertTrue(r is ModelFamilyDetector.Result.Ambiguous)
        r as ModelFamilyDetector.Result.Ambiguous
        assertEquals(listOf(ModelFamily.CTC, ModelFamily.SENSE_VOICE), r.candidates)
    }

    @Test
    fun `random files are unknown`() {
        assertEquals(ModelFamilyDetector.Result.Unknown, ModelFamilyDetector.detect(listOf("readme.md", "notes.txt")))
        assertEquals(ModelFamilyDetector.Result.Unknown, ModelFamilyDetector.detect(emptyList()))
    }

    @Test
    fun `narrow matches family tokens in a name or url hint`() {
        val candidates = listOf(ModelFamily.WHISPER, ModelFamily.CANARY)
        assertEquals(ModelFamily.CANARY, ModelFamilyDetector.narrow(candidates, "nemo-canary-en-de.zip"))
        assertEquals(ModelFamily.WHISPER, ModelFamilyDetector.narrow(candidates, "whisper-small-hi.onnx"))
        assertNull(ModelFamilyDetector.narrow(candidates, "sherpa-model-v2"))
        assertNull(ModelFamilyDetector.narrow(candidates, null))
    }
}
