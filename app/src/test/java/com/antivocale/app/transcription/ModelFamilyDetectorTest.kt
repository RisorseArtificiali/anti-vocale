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
        // A ctc-hinted name (GigaAM's v3_ctc) is the genuine-CTC tell.
        // A generic model.onnx set is NOT this case: it is the CTC/SenseVoice
        // ambiguity covered by its own test below.
        val files = listOf("v3_ctc.int8.onnx", "tokens.txt")
        val r = ModelFamilyDetector.detect(files)
        assertEquals(ModelFamilyDetector.Result.Detected(ModelFamily.CTC), r)
    }

    @Test
    fun `encoder-named ctc file with a ctc hint stays detected`() {
        // istupakov-style mixed repos ship CTC under encoder-ish names; the
        // ctc hint in the name is the genuine-CTC tell, not a truncation.
        val files = listOf("encoder-ctc.onnx", "tokens.txt")
        assertEquals(
            ModelFamilyDetector.Result.Detected(ModelFamily.CTC),
            ModelFamilyDetector.detect(files))
    }

    @Test
    fun `truncated whisper set is ambiguous with ctc so the chooser decides`() {
        // [encoder.onnx, tokens] is the exact CTC shape AND exactly a
        // transducer/whisper/canary export one or two files short: an
        // interrupted copy. Importing as CTC would die at native load; the
        // chooser makes every pick fail fast naming what is missing, and a
        // whisper/canary folder name narrows straight to the right family.
        val files = listOf("encoder.int8.onnx", "tokens.txt")
        val r = ModelFamilyDetector.detect(files)
        assertTrue(r is ModelFamilyDetector.Result.Ambiguous)
        r as ModelFamilyDetector.Result.Ambiguous
        assertEquals(
            listOf(ModelFamily.TRANSDUCER, ModelFamily.CTC, ModelFamily.WHISPER,
                ModelFamily.CANARY),
            r.candidates)
    }

    @Test
    fun `truncated set with a sidecar file still reaches the chooser`() {
        // The README variant of the truncation: nothing exact-matches
        // (2-role plan over 3 files), CTC is the sole partial match, and
        // the same encoder-without-decoder tell downgrades it instead of
        // letting the partial tier auto-import it as CTC.
        val files = listOf("encoder.onnx", "tokens.txt", "README.md")
        val r = ModelFamilyDetector.detect(files)
        assertTrue(r is ModelFamilyDetector.Result.Ambiguous)
        r as ModelFamilyDetector.Result.Ambiguous
        assertEquals(
            listOf(ModelFamily.TRANSDUCER, ModelFamily.CTC, ModelFamily.WHISPER,
                ModelFamily.CANARY),
            r.candidates)
    }

    @Test
    fun `sense-voice set is ambiguous with ctc and dolphin (identical file shape)`() {
        // model.onnx + tokens.txt is the exact shape of three families
        // (SenseVoice, CTC, and since GH #89 Dolphin); the only true
        // discriminator is the model.onnx metadata or a family-name hint,
        // read later by the importer. Detection reports the candidates.
        val files = listOf("model.onnx", "tokens.txt")
        val r = ModelFamilyDetector.detect(files)
        assertTrue(r is ModelFamilyDetector.Result.Ambiguous)
        r as ModelFamilyDetector.Result.Ambiguous
        assertEquals(listOf(ModelFamily.CTC, ModelFamily.SENSE_VOICE, ModelFamily.DOLPHIN), r.candidates)
    }

    @Test
    fun `moonshine v2 set is detected unambiguously`() {
        // The .ort pair is moonshine-only: no other family plan matches it.
        val result = ModelFamilyDetector.detect(
            listOf("encoder_model.ort", "decoder_model_merged.ort", "tokens.txt"))
        assertEquals(ModelFamilyDetector.Result.Detected(ModelFamily.MOONSHINE), result)
    }

    @Test
    fun `moonshine v2 onnx-flavored set is detected unambiguously and keeps its names`() {
        // The transformers.js-style v2 export (review round): the plan must
        // accept the .onnx spelling AND keep it as the canonical name (the
        // bytes are protobuf ONNX; renaming to .ort would bypass the
        // split-file sidecar check and lie about the format).
        val files = listOf("encoder_model.onnx", "decoder_model_merged.onnx", "tokens.txt")
        // The .onnx spelling collides with the whisper/canary encoder+decoder
        // shape (both exact): honest ambiguity, the moonshine-named repo hint
        // narrows it (the .ort pair is the unambiguous spelling).
        val r = ModelFamilyDetector.detect(files)
        assertTrue(r is ModelFamilyDetector.Result.Ambiguous)
        assertEquals(
            listOf(ModelFamily.WHISPER, ModelFamily.CANARY, ModelFamily.MOONSHINE),
            (r as ModelFamilyDetector.Result.Ambiguous).candidates)
        assertEquals(ModelFamily.MOONSHINE,
            ModelFamilyDetector.narrow(r.candidates, "moonshine-base-uk-quantized"))
        val plan = MoonshineSupport.buildCopyPlan(files)!!
        assertEquals(listOf("encoder_model.onnx", "decoder_model_merged.onnx", "tokens.txt"),
            plan.keys.toList())
    }

    @Test
    fun `truncated v1 set reduced to encode plus tokens reaches the chooser`() {
        // The verification round caught the missing 'encode' tell: this set
        // was Detected(CTC) and imported as the wrong family (native exit).
        val r = ModelFamilyDetector.detect(listOf("encode.int8.onnx", "tokens.txt"))
        assertTrue(r is ModelFamilyDetector.Result.Ambiguous)
        assertTrue(ModelFamily.MOONSHINE in (r as ModelFamilyDetector.Result.Ambiguous).candidates)
    }

    @Test
    fun `moonshine v1 set is detected unambiguously`() {
        val result = ModelFamilyDetector.detect(
            listOf(
                "preprocess.onnx", "encode.int8.onnx",
                "uncached_decode.int8.onnx", "cached_decode.int8.onnx", "tokens.txt"))
        assertEquals(ModelFamilyDetector.Result.Detected(ModelFamily.MOONSHINE), result)
    }

    @Test
    fun `dolphin shares the model+tokens shape with ctc and sense-voice so the chooser decides`() {
        val result = ModelFamilyDetector.detect(listOf("model.int8.onnx", "tokens.txt"))
        assertEquals(
            ModelFamilyDetector.Result.Ambiguous(
                listOf(ModelFamily.CTC, ModelFamily.SENSE_VOICE, ModelFamily.DOLPHIN)),
            result)
    }

    @Test
    fun `truncated moonshine v1 set reaches the chooser instead of importing as ctc`() {
        // [preprocess, encode, tokens] with a decode file lost in sync: the
        // v1 names carry no encoder/decoder substring, so without the
        // moonshine-name tell CTC would be the sole partial match and import
        // a preprocess as a CTC encoder (native exit). GH #89 review round.
        val r = ModelFamilyDetector.detect(
            listOf("preprocess.onnx", "encode.int8.onnx", "tokens.txt"))
        assertTrue(r is ModelFamilyDetector.Result.Ambiguous)
        r as ModelFamilyDetector.Result.Ambiguous
        assertEquals(
            listOf(ModelFamily.TRANSDUCER, ModelFamily.CTC, ModelFamily.WHISPER,
                ModelFamily.CANARY, ModelFamily.MOONSHINE),
            r.candidates)
    }

    @Test
    fun `truncated moonshine v2 set is unknown, never a wrong import`() {
        // CTC's plan only reads .onnx candidates, so a lone .ort encoder is
        // not the CTC shape: nothing matches and the set is Unknown (a clean
        // refusal at import, which is the safe outcome for a lost .ort).
        assertEquals(
            ModelFamilyDetector.Result.Unknown,
            ModelFamilyDetector.detect(listOf("encoder_model.ort", "tokens.txt")))
    }

    @Test
    fun `random files are unknown`() {
        assertEquals(ModelFamilyDetector.Result.Unknown, ModelFamilyDetector.detect(listOf("readme.md", "notes.txt")))
        assertEquals(ModelFamilyDetector.Result.Unknown, ModelFamilyDetector.detect(emptyList()))
    }

    @Test
    fun `narrow matches family tokens in the terminal segment only`() {
        val candidates = listOf(ModelFamily.WHISPER, ModelFamily.CANARY)
        assertEquals(ModelFamily.CANARY, ModelFamilyDetector.narrow(candidates, "nemo-canary-en-de.zip"))
        assertEquals(ModelFamily.WHISPER, ModelFamilyDetector.narrow(candidates, "whisper-small-hi.onnx"))
        assertNull(ModelFamilyDetector.narrow(candidates, "sherpa-model-v2"))
        assertNull(ModelFamilyDetector.narrow(candidates, null))
        // Ancestor directories do not vote: the terminal segment is a canary
        // folder inside a whisper-named parent (round-2 ancestor bug).
        assertEquals(ModelFamily.CANARY,
            ModelFamilyDetector.narrow(candidates, "primary:Download/whisper-alternatives/canary-180m"))
        // Underscore spelling narrows like hyphen spelling.
        val cs = listOf(ModelFamily.CTC, ModelFamily.SENSE_VOICE)
        assertEquals(ModelFamily.SENSE_VOICE, ModelFamilyDetector.narrow(cs, "sense_voice"))
        assertEquals(ModelFamily.SENSE_VOICE, ModelFamilyDetector.narrow(cs, "sense-voice-small"))
        // Trailing slash does not blank the terminal segment.
        assertEquals(ModelFamily.SENSE_VOICE, ModelFamilyDetector.narrow(cs, "models/sense_voice/"))
    }

    @Test
    fun `dolphin-ctc naming stays a chooser decision (known limitation)`() {
        // GH #89 review: dolphin-base-CTC carries both tokens and the hint
        // alone cannot split "ctc as qualifier" from a genuinely mixed repo;
        // the chooser opens. Tracked on TASK-618 for a shape-aware fix.
        val cs = listOf(ModelFamily.CTC, ModelFamily.SENSE_VOICE, ModelFamily.DOLPHIN)
        assertNull(ModelFamilyDetector.narrow(cs, "sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02"))
    }

    @Test
    fun `narrow returns null on a dual-token tie so the chooser decides`() {
        // A folder literally naming both families: firstOrNull-by-enum-order
        // would silently import as CTC with the wrong config (round 3).
        val cs = listOf(ModelFamily.CTC, ModelFamily.SENSE_VOICE)
        assertNull(ModelFamilyDetector.narrow(cs, "sense-voice-ctc"))
        assertNull(ModelFamilyDetector.narrow(listOf(ModelFamily.WHISPER, ModelFamily.CANARY), "canary-from-whisper-distill"))
    }
}
