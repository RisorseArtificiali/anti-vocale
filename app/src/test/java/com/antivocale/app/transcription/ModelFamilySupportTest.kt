package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.FilePin
import com.antivocale.app.data.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Family support table tests (plan v2b Tasks 4-7): one suite per support object,
 * covering role planning (including the real-world export namings), metadata
 * routing, and sherpa config construction.
 */
class ModelFamilySupportTest {

    private fun record(
        family: ModelFamily,
        modelType: String = "",
        languages: List<String> = emptyList(),
        options: Map<String, String> = emptyMap(),
    ) = ExternalModelRecord(
        id = "abc123", displayName = "test", dir = "/models/external/test-abc123",
        family = family, modelType = modelType, languages = languages,
        source = ExternalModelSource.LOCAL, sourceUrl = null,
        files = emptyMap(), sizeBytes = 0L, importedAt = 0L, options = options,
    )

    // ---- Shared family/modelType rules (single definition: defaultModelType/isValidModelType) ----

    @Test
    fun `defaultModelType maps every family, CTC to null`() {
        assertEquals("nemo_transducer", ModelFamilySupport.defaultModelType(ModelFamily.TRANSDUCER))
        assertEquals("", ModelFamilySupport.defaultModelType(ModelFamily.WHISPER))
        assertEquals("", ModelFamilySupport.defaultModelType(ModelFamily.SENSE_VOICE))
        assertEquals("", ModelFamilySupport.defaultModelType(ModelFamily.CANARY))
        assertNull(ModelFamilySupport.defaultModelType(ModelFamily.CTC))
    }

    @Test
    fun `isValidModelType accepts family-valid values and rejects the rest`() {
        assertTrue(ModelFamilySupport.isValidModelType(ModelFamily.TRANSDUCER, "nemo_transducer"))
        assertTrue(ModelFamilySupport.isValidModelType(ModelFamily.TRANSDUCER, "conformer_transducer"))
        assertTrue(ModelFamilySupport.isValidModelType(ModelFamily.TRANSDUCER, ""))
        assertFalse(ModelFamilySupport.isValidModelType(ModelFamily.TRANSDUCER, "nemo_ctc"))

        assertTrue(ModelFamilySupport.isValidModelType(ModelFamily.CTC, "nemo_ctc"))
        assertTrue(ModelFamilySupport.isValidModelType(ModelFamily.CTC, "zipformer_ctc"))
        assertFalse(ModelFamilySupport.isValidModelType(ModelFamily.CTC, ""))
        assertFalse(ModelFamilySupport.isValidModelType(ModelFamily.CTC, "nemo_transducer"))

        assertTrue(ModelFamilySupport.isValidModelType(ModelFamily.WHISPER, ""))
        assertFalse(ModelFamilySupport.isValidModelType(ModelFamily.WHISPER, "whisper"))
        assertTrue(ModelFamilySupport.isValidModelType(ModelFamily.SENSE_VOICE, ""))
        assertFalse(ModelFamilySupport.isValidModelType(ModelFamily.SENSE_VOICE, "sense_voice"))
        assertTrue(ModelFamilySupport.isValidModelType(ModelFamily.CANARY, ""))
        assertFalse(ModelFamilySupport.isValidModelType(ModelFamily.CANARY, "nemo_transducer"))
    }

    // ---- TASK-408: CanarySupport ----

    @Test
    fun `canary plan maps encoder decoder tokens and ignores a bystander joiner`() {
        val plan = CanarySupport.buildCopyPlan(
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"))
        assertEquals(
            mapOf(
                "encoder.int8.onnx" to "encoder.int8.onnx",
                "decoder.int8.onnx" to "decoder.int8.onnx",
                "tokens.txt" to "tokens.txt",
            ),
            plan,
        )
        // a joiner elsewhere in the folder never entered canary role matching (the
        // whisper semantics: a parent directory holding several models is legal);
        // family mismatches are caught by the model_type VALUE check instead.
        val multiModelFolder = CanarySupport.buildCopyPlan(
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"))
        assertNotNull(multiModelFolder)
        assertEquals("tokens.txt", multiModelFolder!!["tokens.txt"])
    }

    @Test
    fun `canary metadata validation accepts only EncDecMultiTaskModel`() {
        // null (missing key) stays with the key-presence chain; the canary value passes
        CanarySupport.validateImportedModel("EncDecMultiTaskModel")
        CanarySupport.validateImportedModel(null)
        try {
            CanarySupport.validateImportedModel("whisper")
            fail("a whisper encoder must not pass canary validation")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun `canary model config conditions the recognizer on the chosen language`() {
        val record = this.record(ModelFamily.CANARY, languages = listOf("de"))
        val config = CanarySupport.buildModelConfig(record, numThreads = 4, provider = "cpu")
        assertEquals("de", config.canary.srcLang)
        assertEquals("de", config.canary.tgtLang)
        assertEquals("canary", config.modelType)
        // no languages, no option: the sherpa default en
        val bare = record.copy(languages = emptyList())
        assertEquals("en", CanarySupport.buildModelConfig(bare, 4, "cpu").canary.srcLang)
        // the import-time option wins over the record languages
        val opted = record.copy(options = mapOf(ModelFamilySupport.OPTION_CANARY_LANGUAGE to "fr"))
        assertEquals("fr", CanarySupport.buildModelConfig(opted, 4, "cpu").canary.srcLang)
    }

    // ---- Task 4: TransducerSupport (behavior moved verbatim from the importer) ----

    @Test
    fun `transducer plan maps roles by keyword and rejects missing roles`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.TRANSDUCER)
        val plan = support.buildCopyPlan(listOf("gigaam_encoder_int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"))
        assertEquals("gigaam_encoder_int8.onnx", plan!!["encoder.int8.onnx"])
        assertEquals("decoder.onnx", plan["decoder.int8.onnx"])
        assertEquals("joiner.onnx", plan["joiner.int8.onnx"])
        assertEquals("tokens.txt", plan["tokens.txt"])

        assertNull(support.buildCopyPlan(listOf("tokens.txt")))
        assertNull(support.buildCopyPlan(emptyList()))
    }

    @Test
    fun `transducer plan accepts gigaam joint naming and rnnt-hinted vocab`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.TRANSDUCER)
        val gigaam = support.buildCopyPlan(listOf(
            "gigaam_v3_e2e_rnnt_encoder_int8.onnx",
            "gigaam_v3_e2e_rnnt_decoder.onnx",
            "gigaam_v3_e2e_rnnt_joint.onnx",
            "gigaam_v3_e2e_rnnt_tokens.txt",
        ))
        assertEquals("gigaam_v3_e2e_rnnt_joint.onnx", gigaam!!["joiner.int8.onnx"])

        val istupakov = support.buildCopyPlan(listOf(
            "v3_e2e_rnnt_encoder.int8.onnx",
            "v3_e2e_rnnt_decoder.int8.onnx",
            "v3_e2e_rnnt_joint.int8.onnx",
            "v3_e2e_rnnt_vocab.txt",
        ))
        assertEquals("v3_e2e_rnnt_vocab.txt", istupakov!!["tokens.txt"])
    }

    @Test
    fun `transducer metadata routing delegates to the sherpa rule`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.TRANSDUCER)
        assertEquals("encoder.int8.onnx", support.metadataFileRole())
        assertEquals(listOf("vocab_size", "subsampling_factor", "model_type"), support.metadataKeys("nemo_transducer"))
        assertEquals(listOf("vocab_size"), support.metadataKeys(""))
    }

    @Test
    fun `transducer requiredRoles lists the four canonical files`() {
        assertEquals(
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
            ModelFamilySupport.forFamily(ModelFamily.TRANSDUCER).requiredRoles(),
        )
    }

    @Test
    fun `transducer model config mirrors the external engine block`() {
        val config = ModelFamilySupport.forFamily(ModelFamily.TRANSDUCER)
            .buildModelConfig(record(ModelFamily.TRANSDUCER, modelType = "nemo_transducer"), numThreads = 4, provider = "cpu")
        assertEquals("/models/external/test-abc123/encoder.int8.onnx", config.transducer.encoder)
        assertEquals("/models/external/test-abc123/decoder.int8.onnx", config.transducer.decoder)
        assertEquals("/models/external/test-abc123/joiner.int8.onnx", config.transducer.joiner)
        assertEquals("/models/external/test-abc123/tokens.txt", config.tokens)
        assertEquals("nemo_transducer", config.modelType)
        assertEquals(4, config.numThreads)
        assertEquals("cpu", config.provider)
    }

    // ---- Task 5: WhisperSupport ----

    @Test
    fun `whisper plan maps encoder decoder tokens and rejects joiner files`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.WHISPER)
        val plan = support.buildCopyPlan(listOf("base-encoder.int8.onnx", "base-decoder.int8.onnx", "base-tokens.txt"))
        assertEquals("base-encoder.int8.onnx", plan!!["encoder.int8.onnx"])
        assertEquals("base-decoder.int8.onnx", plan["decoder.int8.onnx"])
        assertEquals("base-tokens.txt", plan["tokens.txt"])
        assertNull(support.buildCopyPlan(listOf("encoder.onnx", "decoder.onnx")))
    }

    @Test
    fun `whisper plan ignores a transducer joiner from a multi-model folder`() {
        // A parent SAF folder legitimately holding several models (here a whisper
        // pair plus a full transducer set) must still import as Whisper: the joiner
        // never entered whisper role matching, so the discriminator must not fire.
        val support = ModelFamilySupport.forFamily(ModelFamily.WHISPER)
        val plan = support.buildCopyPlan(listOf(
            "whisper-encoder.onnx",
            "whisper-decoder.onnx",
            "whisper-tokens.txt",
            "rnnt_encoder.int8.onnx",
            "rnnt_decoder.int8.onnx",
            "rnnt_joint.int8.onnx",
            "rnnt_tokens.txt",
        ))
        assertEquals("whisper-encoder.onnx", plan!!["encoder.int8.onnx"])
        assertEquals("whisper-decoder.onnx", plan["decoder.int8.onnx"])
        assertEquals("whisper-tokens.txt", plan["tokens.txt"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `whisper plan rejects a joiner-like file that entered role matching`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.WHISPER)
        // The encoder candidate itself is joint-named: it entered encoder role
        // matching, so the discriminator fires.
        support.buildCopyPlan(listOf("encoder_joint.onnx", "decoder.onnx", "tokens.txt"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `whisper plan rejects a pure transducer set`() {
        // The model_type metadata check cannot catch this (NeMo transducer
        // encoders also carry model_type; the check is key-presence, not value),
        // so the copy plan itself must reject the mismatch.
        ModelFamilySupport.forFamily(ModelFamily.WHISPER).buildCopyPlan(listOf(
            "rnnt_encoder.onnx", "rnnt_decoder.onnx", "rnnt_joint.onnx", "rnnt_tokens.txt"))
    }

    @Test
    fun `whisper validation rejects a generic-named transducer encoder by model_type value`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.WHISPER)
        // Generic names defeat the plan discriminators, so the copy plan itself
        // succeeds and the metadata VALUE check must catch the mismatch: whisper
        // encoders carry model_type "whisper-*"; NeMo transducer encoders carry a
        // model_type KEY with a non-whisper value (key-presence cannot tell them apart).
        val plan = support.buildCopyPlan(listOf("encoder.onnx", "decoder.onnx", "tokens.txt"))
        assertEquals("encoder.onnx", plan!!["encoder.int8.onnx"])

        val nemoEncoder = java.io.File.createTempFile("nemo-encoder", ".onnx")
        nemoEncoder.deleteOnExit()
        nemoEncoder.writeBytes("model_type".toByteArray() +
            byteArrayOf(0x12, 0x12) + "EncDecRNNTBPEModel".toByteArray())
        try {
            support.validateImportedModel(SherpaBackend.onnxMetadataValue(nemoEncoder, "model_type"))
            throw AssertionError("expected IllegalArgumentException for a non-whisper model_type value")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        val whisperEncoder = java.io.File.createTempFile("whisper-encoder", ".onnx")
        whisperEncoder.deleteOnExit()
        whisperEncoder.writeBytes("model_type".toByteArray() +
            byteArrayOf(0x12, 0x0c) + "whisper-base".toByteArray())
        support.validateImportedModel(SherpaBackend.onnxMetadataValue(whisperEncoder, "model_type"))

        // Missing key stays with the key-presence chain: no value, no verdict.
        val bare = java.io.File.createTempFile("bare", ".onnx")
        bare.deleteOnExit()
        bare.writeBytes("vocab_size".toByteArray() + byteArrayOf(0x12, 0x02) + "42".toByteArray())
        support.validateImportedModel(SherpaBackend.onnxMetadataValue(bare, "model_type"))
    }

    @Test
    fun `whisper plan prefers non-rnnt candidates regardless of listing order`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.WHISPER)
        val whisperFirst = listOf(
            "whisper-encoder.onnx", "whisper-decoder.onnx", "whisper-tokens.txt",
            "rnnt_encoder.int8.onnx", "rnnt_decoder.int8.onnx", "rnnt_joint.int8.onnx", "rnnt_tokens.txt")
        val rnntFirst = whisperFirst.reversed()
        for (listing in listOf(whisperFirst, rnntFirst)) {
            val plan = support.buildCopyPlan(listing)!!
            assertEquals("whisper-encoder.onnx", plan["encoder.int8.onnx"])
            assertEquals("whisper-decoder.onnx", plan["decoder.int8.onnx"])
            assertEquals("whisper-tokens.txt", plan["tokens.txt"])
        }
    }

    @Test
    fun `whisper requiredRoles lists encoder decoder and tokens`() {
        assertEquals(
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"),
            ModelFamilySupport.forFamily(ModelFamily.WHISPER).requiredRoles(),
        )
    }

    @Test
    fun `whisper metadata routing points at encoder and checks model_type`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.WHISPER)
        assertEquals("encoder.int8.onnx", support.metadataFileRole())
        assertEquals(listOf("model_type"), support.metadataKeys(""))
    }

    @Test
    fun `whisper model config builds OfflineWhisperModelConfig with language and task`() {
        val config = ModelFamilySupport.forFamily(ModelFamily.WHISPER)
            .buildModelConfig(
                record(ModelFamily.WHISPER, options = mapOf("whisper.language" to "it")),
                numThreads = 4, provider = "cpu",
            )
        assertEquals("/models/external/test-abc123/encoder.int8.onnx", config.whisper.encoder)
        assertEquals("/models/external/test-abc123/decoder.int8.onnx", config.whisper.decoder)
        // tokens must be set even though the whisper sub-config takes no tokens
        // path: without it native validation rejects the config on device (TASK-332)
        assertEquals("/models/external/test-abc123/tokens.txt", config.tokens)
        assertEquals("it", config.whisper.language)
        assertEquals("transcribe", config.whisper.task)
        assertEquals("whisper", config.modelType)
        assertEquals(4, config.numThreads)
        assertEquals("cpu", config.provider)
    }

    @Test
    fun `whisper language defaults to record first language then empty string`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.WHISPER)
        // No option, no record language -> empty (auto)
        val autoConfig = support.buildModelConfig(record(ModelFamily.WHISPER), numThreads = 1, provider = "cpu")
        assertEquals("", autoConfig.whisper.language)

        // No option, record has language -> first language
        val langConfig = support.buildModelConfig(
            record(ModelFamily.WHISPER, languages = listOf("ar", "en")), numThreads = 1, provider = "cpu")
        assertEquals("ar", langConfig.whisper.language)

        // Option overrides record language
        val optConfig = support.buildModelConfig(
            record(ModelFamily.WHISPER, languages = listOf("ar"), options = mapOf("whisper.language" to "en")),
            numThreads = 1, provider = "cpu")
        assertEquals("en", optConfig.whisper.language)
    }

    // ---- Task 6: CtcSupport ----

    @Test
    fun `ctc plan maps encoder and tokens`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.CTC)
        val plan = support.buildCopyPlan(listOf("v3_ctc.int8.onnx", "v3_e2e_ctc_vocab.txt"))
        assertEquals("v3_ctc.int8.onnx", plan!!["encoder.int8.onnx"])
        assertEquals("v3_e2e_ctc_vocab.txt", plan["tokens.txt"])
        assertNull(support.buildCopyPlan(listOf("encoder.onnx")))
    }

    @Test
    fun `ctc plan picks ctc-hinted vocab over rnnt-hinted in mixed pool`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.CTC)
        // istupakov repo ships both v3_e2e_rnnt_vocab.txt and v3_e2e_ctc_vocab.txt
        val plan = support.buildCopyPlan(listOf(
            "v3_ctc.int8.onnx",
            "v3_e2e_ctc_vocab.txt",
            "v3_e2e_rnnt_vocab.txt",
        ))
        assertEquals("v3_e2e_ctc_vocab.txt", plan!!["tokens.txt"])
    }

    @Test
    fun `ctc plan ignores a transducer set from a multi-model folder`() {
        // The istupakov-style parent folder ships CTC and RNNT variants together:
        // the joiner never entered CTC role matching (the rnnt encoder is only
        // deprioritized, never selected here), so the import must succeed.
        val support = ModelFamilySupport.forFamily(ModelFamily.CTC)
        val plan = support.buildCopyPlan(listOf(
            "v3_e2e_ctc_vocab.txt",
            "v3_e2e_rnnt_encoder.int8.onnx",
            "v3_e2e_rnnt_decoder.int8.onnx",
            "v3_e2e_rnnt_joint.int8.onnx",
            "v3_e2e_rnnt_vocab.txt",
            "v3_ctc.int8.onnx",
        ))
        assertEquals("v3_ctc.int8.onnx", plan!!["encoder.int8.onnx"])
        assertEquals("v3_e2e_ctc_vocab.txt", plan["tokens.txt"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ctc plan rejects a joiner-like file that entered role matching`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.CTC)
        // No encoder-keyword file exists, so the fallback tier considers every
        // .onnx: the joiner entered encoder role matching and must be rejected.
        support.buildCopyPlan(listOf("joiner.onnx", "tokens.txt"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ctc plan rejects a pure transducer set`() {
        // Only rnnt-hinted candidates exist: the rnnt encoder would win the
        // keyword fallback and the import-time metadata check is a no-op for CTC
        // (metadataKeys is empty), so the copy plan itself must reject it.
        ModelFamilySupport.forFamily(ModelFamily.CTC).buildCopyPlan(listOf(
            "rnnt_encoder.onnx", "rnnt_decoder.onnx", "rnnt_joint.onnx", "rnnt_tokens.txt"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ctc plan rejects a generic-named pure transducer set`() {
        // sherpa-canonical generic names carry no rnnt hint; the joiner in the
        // pool plus a non-ctc-hinted selected encoder is the tell.
        ModelFamilySupport.forFamily(ModelFamily.CTC).buildCopyPlan(listOf(
            "encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"))
    }

    @Test
    fun `ctc requiredRoles lists encoder and tokens`() {
        assertEquals(
            listOf("encoder.int8.onnx", "tokens.txt"),
            ModelFamilySupport.forFamily(ModelFamily.CTC).requiredRoles(),
        )
    }

    @Test
    fun `ctc metadata routing returns empty keys`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.CTC)
        assertEquals("encoder.int8.onnx", support.metadataFileRole())
        assertEquals(emptyList<String>(), support.metadataKeys(""))
    }

    @Test
    fun `ctc nemo model config builds OfflineNemoEncDecCtcModelConfig`() {
        val config = ModelFamilySupport.forFamily(ModelFamily.CTC)
            .buildModelConfig(record(ModelFamily.CTC, modelType = "nemo_ctc"), numThreads = 4, provider = "cpu")
        assertEquals("/models/external/test-abc123/encoder.int8.onnx", config.nemo.model)
        assertEquals("nemo_ctc", config.modelType)
        assertEquals(4, config.numThreads)
        assertEquals("cpu", config.provider)
    }

    @Test
    fun `ctc zipformer model config builds OfflineZipformerCtcModelConfig`() {
        val config = ModelFamilySupport.forFamily(ModelFamily.CTC)
            .buildModelConfig(record(ModelFamily.CTC, modelType = "zipformer_ctc"), numThreads = 2, provider = "nnpapi")
        assertEquals("/models/external/test-abc123/encoder.int8.onnx", config.zipformerCtc.model)
        assertEquals("zipformer_ctc", config.modelType)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ctc buildModelConfig rejects unknown modelType`() {
        ModelFamilySupport.forFamily(ModelFamily.CTC)
            .buildModelConfig(record(ModelFamily.CTC, modelType = "bad_type"), numThreads = 1, provider = "cpu")
    }

    // ---- Task 7: SenseVoiceSupport ----

    @Test
    fun `sensevoice plan maps model and tokens`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.SENSE_VOICE)
        val plan = support.buildCopyPlan(listOf("sense_voice.onnx", "tokens.txt"))
        assertEquals("sense_voice.onnx", plan!!["model.int8.onnx"])
        assertEquals("tokens.txt", plan["tokens.txt"])
        assertNull(support.buildCopyPlan(listOf("tokens.txt")))
    }

    @Test
    fun `sensevoice plan accepts sherpa canonical model source names`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.SENSE_VOICE)
        // sherpa SenseVoice repos ship the acoustic model as model.onnx or
        // model.int8.onnx: both must match the model role.
        val quantized = support.buildCopyPlan(listOf("model.int8.onnx", "tokens.txt"))
        assertEquals("model.int8.onnx", quantized!!["model.int8.onnx"])
        val plain = support.buildCopyPlan(listOf("model.onnx", "tokens.txt"))
        assertEquals("model.onnx", plain!!["model.int8.onnx"])
    }

    @Test
    fun `sensevoice model keyword does not match encoder files`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.SENSE_VOICE)
        // If only an encoder .onnx is present (no sense_voice/model keyword), plan is null.
        assertNull(support.buildCopyPlan(listOf("encoder.int8.onnx", "tokens.txt")))
    }

    @Test
    fun `sensevoice requiredRoles lists model and tokens`() {
        assertEquals(
            listOf("model.int8.onnx", "tokens.txt"),
            ModelFamilySupport.forFamily(ModelFamily.SENSE_VOICE).requiredRoles(),
        )
    }

    @Test
    fun `sensevoice metadata routing points at model file`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.SENSE_VOICE)
        assertEquals("model.int8.onnx", support.metadataFileRole())
        assertEquals(emptyList<String>(), support.metadataKeys(""))
    }

    @Test
    fun `sensevoice model config builds OfflineSenseVoiceModelConfig`() {
        val config = ModelFamilySupport.forFamily(ModelFamily.SENSE_VOICE)
            .buildModelConfig(
                record(ModelFamily.SENSE_VOICE, options = mapOf("sensevoice.language" to "zh", "sensevoice.itn" to "true")),
                numThreads = 4, provider = "cpu",
            )
        assertEquals("/models/external/test-abc123/model.int8.onnx", config.senseVoice.model)
        assertEquals("zh", config.senseVoice.language)
        assertEquals(true, config.senseVoice.useInverseTextNormalization)
        assertEquals("sense_voice", config.modelType)
        assertEquals(4, config.numThreads)
        assertEquals("cpu", config.provider)
    }

    @Test
    fun `sensevoice language defaults to empty and itn defaults to false`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.SENSE_VOICE)
        val config = support.buildModelConfig(record(ModelFamily.SENSE_VOICE), numThreads = 1, provider = "cpu")
        assertEquals("", config.senseVoice.language)
        assertEquals(false, config.senseVoice.useInverseTextNormalization)
    }

    @Test
    fun `sensevoice itn parses true and 1 as enabled`() {
        val support = ModelFamilySupport.forFamily(ModelFamily.SENSE_VOICE)
        val trueConfig = support.buildModelConfig(
            record(ModelFamily.SENSE_VOICE, options = mapOf("sensevoice.itn" to "true")), numThreads = 1, provider = "cpu")
        assertEquals(true, trueConfig.senseVoice.useInverseTextNormalization)

        val oneConfig = support.buildModelConfig(
            record(ModelFamily.SENSE_VOICE, options = mapOf("sensevoice.itn" to "1")), numThreads = 1, provider = "cpu")
        assertEquals(true, oneConfig.senseVoice.useInverseTextNormalization)

        val falseConfig = support.buildModelConfig(
            record(ModelFamily.SENSE_VOICE, options = mapOf("sensevoice.itn" to "0")), numThreads = 1, provider = "cpu")
        assertEquals(false, falseConfig.senseVoice.useInverseTextNormalization)
    }
}
