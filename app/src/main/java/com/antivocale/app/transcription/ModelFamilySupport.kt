package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ModelFamily
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig

/** True for transducer joiner files, which also answer to GigaAM's "joint" naming. */
private fun isJoinerLike(name: String) =
    name.contains("joiner", ignoreCase = true) || name.contains("joint", ignoreCase = true)

/** True for tokens/vocab text files (the shared tokens-role keyword test). */
private fun isTokensLike(name: String) =
    name.contains("tokens", ignoreCase = true) || name.contains("vocab", ignoreCase = true)

/** True for files whose names mark them as transducer exports (rnnt/joiner/joint). */
private fun isTransducerHinted(name: String) =
    name.contains("rnnt", ignoreCase = true) || isJoinerLike(name)

/**
 * Shared tokens-role selection ladder, single definition for all family supports:
 * exact "tokens.txt" (and exact "vocab.txt" when [exactVocab]), then the first
 * .txt tokens-like file satisfying [prefer], then the first avoiding [avoid],
 * then any tokens-like .txt. Null when no candidate exists. [prefer] and [avoid]
 * carry each family's hint predicates (rnnt-first, ctc-first, transducer-free).
 */
private fun pickTokens(
    files: List<String>,
    exactVocab: Boolean = true,
    prefer: ((String) -> Boolean)? = null,
    avoid: ((String) -> Boolean)? = null,
): String? {
    fun byPredicate(p: (String) -> Boolean) =
        files.firstOrNull { it.endsWith(".txt") && isTokensLike(it) && p(it) }
    return files.firstOrNull { it.equals("tokens.txt", ignoreCase = true) }
        ?: (if (exactVocab) files.firstOrNull { it.equals("vocab.txt", ignoreCase = true) } else null)
        ?: prefer?.let { byPredicate(it) }
        ?: avoid?.let { byPredicate { f -> !avoid(f) } }
        ?: byPredicate { true }
}

/** Family-mismatch discriminator message shared by the non-transducer families. */
private const val TRANSDUCER_MISMATCH =
    "candidate set looks like a transducer; pick the TRANSDUCER family"

/**
 * Encoder + decoder + tokens copy plan shared by the whisper and canary families
 * (same non-transducer file set; single definition so the two cannot drift):
 * role selection prefers non-transducer-hinted candidates, so a mixed folder
 * picks the right files deterministically regardless of listing order, and a
 * role whose only keyword matches are transducer-hinted files rejects the folder
 * as a bare transducer set (the model_type metadata check cannot: NeMo
 * transducer encoders also carry model_type as a KEY, key-presence not value).
 * Tokens prefer non-hinted candidates so listing order cannot hand the role to
 * the transducer vocab.
 */
private fun pickNonTransducerPlan(files: List<String>): Map<String, String>? {
    val onnxCandidates = files.filter { it.endsWith(".onnx") }
    fun findByRole(vararg keywords: String): String? =
        onnxCandidates.firstOrNull { f -> !isTransducerHinted(f) && keywords.any { f.contains(it, ignoreCase = true) } }
    fun findTransducerHinted(vararg keywords: String): String? =
        onnxCandidates.firstOrNull { f -> keywords.any { f.contains(it, ignoreCase = true) } }
    val encoder = findByRole("encoder")
        ?: findTransducerHinted("encoder")?.let { throw IllegalArgumentException(TRANSDUCER_MISMATCH) }
        ?: return null
    val decoder = findByRole("decoder")
        ?: findTransducerHinted("decoder")?.let { throw IllegalArgumentException(TRANSDUCER_MISMATCH) }
        ?: return null
    val tokens = pickTokens(
        files,
        exactVocab = false,
        avoid = ::isTransducerHinted,
    ) ?: return null
    return linkedMapOf(
        SherpaBackend.CANONICAL_ENCODER to encoder,
        SherpaBackend.CANONICAL_DECODER to decoder,
        SherpaBackend.CANONICAL_TOKENS to tokens,
    )
}

/**
 * The family support table (spec: multi-family external models): per-family copy
 * planning, metadata routing, and sherpa config construction behind one interface,
 * so the importer (import-time) and [ExternalSherpaBackend] (load-time) share a
 * single definition and cannot drift.
 *
 * Each support object's KDoc doubles as the per-family documentation the spec
 * requires: expected file sets and the record [ExternalModelRecord.modelType]
 * mapping.
 */
sealed interface ModelFamilySupport {
    val family: ModelFamily

    /**
     * Mel-band count the recognizer's FeatureConfig must carry. Every family
     * ships 80 except CANARY (128: the encoder's feat_dim metadata; feeding 80
     * bands either fails the native load or decodes garbage). Single definition
     * so the importer docs and the engine config cannot drift.
     */
    val featureDim: Int
        get() = 80

    /** Canonical file names every import of this family must produce. */
    fun requiredRoles(): List<String>

    /** Maps source file names to canonical role names; null when any role has no candidate. */
    fun buildCopyPlan(files: List<String>): Map<String, String>?

    /** The canonical file the pre-native metadata check reads. */
    fun metadataFileRole(): String

    /** Metadata keys required for [modelType], for pre-native validation (exit(255) guard).
     *  The [modelType] parameter is consumed by the transducer support only; the other
     *  families ignore it (leaky but defensible: the alternative is a second dispatch
     *  layer for one consumer). */
    fun metadataKeys(modelType: String): List<String>

    /** Builds the sherpa [OfflineModelConfig] for [record] (engine-side). */
    fun buildModelConfig(record: ExternalModelRecord, numThreads: Int, provider: String): OfflineModelConfig

    /**
     * Optional metadata key (on the file named by [metadataFileRole]) whose VALUE
     * discriminates the family. Null (default) means the key-presence check plus
     * the copy-plan discriminators are enough; the value, when requested, is read
     * in the same tail pass as the key check and handed to
     * [validateImportedModel].
     */
    fun valueMetadataKey(): String? = null

    /**
     * Family-specific value-aware validation of the [valueMetadataKey] value,
     * fired after import (registerImported) and before the first native load.
     * Default no-op: most families are covered by the key-presence metadata check
     * plus the copy-plan structural discriminators.
     */
    fun validateImportedModel(metadataValue: String?) {}

    companion object {
        /** Error raised when CTC is imported without an explicit modelType (single definition). */
        const val CTC_MODEL_TYPE_REQUIRED =
            "CTC family requires an explicit modelType: nemo_ctc or zipformer_ctc"

        /** Record option keys, single definition for the supports and the import UI. */
        const val OPTION_WHISPER_LANGUAGE = "whisper.language"
        const val OPTION_WHISPER_TASK = "whisper.task"
        const val OPTION_SENSEVOICE_LANGUAGE = "sensevoice.language"
        const val OPTION_SENSEVOICE_ITN = "sensevoice.itn"
        const val OPTION_CANARY_LANGUAGE = "canary.language"

        /**
         * The family's default record modelType when the caller passes none: null means
         * "must be explicit" (CTC, where the value selects the sherpa config subtype).
         * Single definition shared by the importer entries and the entry-JSON parser.
         */
        fun defaultModelType(family: ModelFamily): String? = when (family) {
            ModelFamily.TRANSDUCER -> "nemo_transducer"
            ModelFamily.WHISPER, ModelFamily.SENSE_VOICE, ModelFamily.CANARY -> ""
            ModelFamily.CTC -> null
        }

        /** True when [modelType] is a valid record modelType for [family] (single definition). */
        fun isValidModelType(family: ModelFamily, modelType: String): Boolean = when (family) {
            ModelFamily.TRANSDUCER ->
                modelType.isEmpty() || modelType == "nemo_transducer" || modelType == "conformer_transducer"
            ModelFamily.CTC -> modelType == "nemo_ctc" || modelType == "zipformer_ctc"
            ModelFamily.WHISPER, ModelFamily.SENSE_VOICE, ModelFamily.CANARY -> modelType.isEmpty()
        }

        fun forFamily(family: ModelFamily): ModelFamilySupport = when (family) {
            ModelFamily.TRANSDUCER -> TransducerSupport
            ModelFamily.WHISPER -> WhisperSupport
            ModelFamily.CTC -> CtcSupport
            ModelFamily.SENSE_VOICE -> SenseVoiceSupport
            ModelFamily.CANARY -> CanarySupport
        }
    }
}

/**
 * Transducer (RNNT) family: the original v2a import shape.
 *
 * Expected file set: encoder + decoder + joiner .onnx plus a tokens/vocab .txt.
 * The joiner also answers to "joint" (GigaAM v3 ships
 * gigaam_v3_e2e_rnnt_joint.onnx: the RNNT file name, unlike sherpa's config key).
 * Tokens prefers exact names, then rnnt-hinted and ctc-free candidates (repos
 * shipping both CTC and RNNT variants have multiple vocab files).
 *
 * Record modelType: "nemo_transducer", "conformer_transducer", or empty, passed
 * straight through to [OfflineModelConfig.modelType].
 */
object TransducerSupport : ModelFamilySupport {
    override val family: ModelFamily = ModelFamily.TRANSDUCER

    override fun requiredRoles(): List<String> = listOf(
        SherpaBackend.CANONICAL_ENCODER,
        SherpaBackend.CANONICAL_DECODER,
        SherpaBackend.CANONICAL_JOINER,
        SherpaBackend.CANONICAL_TOKENS,
    )

    override fun buildCopyPlan(files: List<String>): Map<String, String>? {
        fun findByRole(vararg keywords: String) =
            files.firstOrNull { f -> f.endsWith(".onnx") && keywords.any { f.contains(it, ignoreCase = true) } }
        val encoder = findByRole("encoder") ?: return null
        val decoder = findByRole("decoder") ?: return null
        val joiner = findByRole("joiner", "joint") ?: return null
        // Tokens: prefer exact names, then family-aware matching. Repos that ship
        // both CTC and RNNT variants (istupakov) have multiple vocab files; a bare
        // contains("vocab") over an alphabetical listing picks the CTC one for an
        // RNNT import. The matcher prefers rnnt-hinted and ctc-free candidates.
        val tokens = pickTokens(
            files,
            prefer = { it.contains("rnnt", ignoreCase = true) },
            avoid = { it.contains("ctc", ignoreCase = true) },
        ) ?: return null
        return linkedMapOf(
            SherpaBackend.CANONICAL_ENCODER to encoder,
            SherpaBackend.CANONICAL_DECODER to decoder,
            SherpaBackend.CANONICAL_JOINER to joiner,
            SherpaBackend.CANONICAL_TOKENS to tokens,
        )
    }

    override fun metadataFileRole(): String = SherpaBackend.CANONICAL_ENCODER

    override fun metadataKeys(modelType: String): List<String> =
        SherpaBackend.requiredTransducerMetadataKeys(modelType)

    override fun buildModelConfig(record: ExternalModelRecord, numThreads: Int, provider: String): OfflineModelConfig =
        OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "${record.dir}/${SherpaBackend.CANONICAL_ENCODER}",
                decoder = "${record.dir}/${SherpaBackend.CANONICAL_DECODER}",
                joiner = "${record.dir}/${SherpaBackend.CANONICAL_JOINER}"
            ),
            tokens = "${record.dir}/${SherpaBackend.CANONICAL_TOKENS}",
            modelType = record.modelType,
            numThreads = numThreads,
            debug = false,
            provider = provider
        )
}

/**
 * Whisper family: encoder + decoder + tokens; no joiner.
 *
 * Expected file set: one .onnx containing "encoder", one containing "decoder",
 * and one .txt tokens/vocab file. Tokens are MANDATORY even though the sherpa
 * whisper config itself takes no tokens path: every real whisper export ships a
 * tokens.txt and the app's decode path needs it (plan decision, Task 1 finding).
 * A joiner/joint .onnx that entered encoder/decoder role matching is rejected as
 * "looks like a transducer; pick the TRANSDUCER family" (structural
 * discriminator preventing a family mismatch from passing import and surfacing
 * as a runtime exit(255)); a joiner elsewhere in the folder is ignored, since a
 * parent directory legitimately holding several models must still import.
 * Role selection prefers non-rnnt/non-joiner-hinted candidates (deterministic in
 * mixed folders), and a role whose only keyword matches are transducer-hinted
 * files is rejected outright: the model_type metadata check cannot catch a
 * transducer encoder, because NeMo transducer encoders also carry model_type as
 * a key (key-presence, not value).
 *
 * Language: [options]["whisper.language"], falling back to [languages][0], then
 * "" (auto; sherpa-onnx performs no language validation per desktop spike).
 * Task: [options]["whisper.task"] defaulting to "transcribe".
 * tailPaddings stays at the sherpa default (-1).
 *
 * Record modelType: ignored; OfflineModelConfig.modelType = "whisper".
 */
object WhisperSupport : ModelFamilySupport {
    override val family: ModelFamily = ModelFamily.WHISPER

    override fun requiredRoles(): List<String> = listOf(
        SherpaBackend.CANONICAL_ENCODER,
        SherpaBackend.CANONICAL_DECODER,
        SherpaBackend.CANONICAL_TOKENS,
    )

    override fun buildCopyPlan(files: List<String>): Map<String, String>? = pickNonTransducerPlan(files)

    override fun metadataFileRole(): String = SherpaBackend.CANONICAL_ENCODER

    override fun metadataKeys(modelType: String): List<String> = listOf("model_type")

    override fun valueMetadataKey(): String = "model_type"

    override fun validateImportedModel(metadataValue: String?) {
        // Value-aware discriminator: key presence cannot tell a whisper encoder
        // from a NeMo transducer encoder (both carry a model_type KEY), but the
        // values differ (whisper encoders are "whisper-*"). A missing key (null
        // value) stays with the key-presence chain (metadataKeys above).
        if (metadataValue != null && !metadataValue.startsWith("whisper", ignoreCase = true)) {
            throw IllegalArgumentException(
                "model_type metadata is \"$metadataValue\": not a whisper encoder; pick the TRANSDUCER family for transducer exports")
        }
    }

    override fun buildModelConfig(record: ExternalModelRecord, numThreads: Int, provider: String): OfflineModelConfig {
        val language = record.options[ModelFamilySupport.OPTION_WHISPER_LANGUAGE]
            ?: record.languages.firstOrNull()
            ?: ""
        val task = record.options[ModelFamilySupport.OPTION_WHISPER_TASK] ?: "transcribe"
        return OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = "${record.dir}/${SherpaBackend.CANONICAL_ENCODER}",
                decoder = "${record.dir}/${SherpaBackend.CANONICAL_DECODER}",
                language = language,
                task = task,
            ),
            // tokens must be passed even though OfflineWhisperModelConfig takes
            // no tokens path: the built-in whisper config passes it and the
            // external one failed native validation without it (TASK-332).
            tokens = "${record.dir}/${SherpaBackend.CANONICAL_TOKENS}",
            modelType = "whisper",
            numThreads = numThreads,
            debug = false,
            provider = provider,
        )
    }
}

/**
 * CTC family: encoder + tokens; no decoder or joiner.
 *
 * Expected file set: one .onnx acoustic model (preferably named with "encoder",
 * but CTC exports like GigaAM's v3_ctc.int8.onnx omit the keyword) and one .txt
 * tokens/vocab file. A joiner/joint .onnx is rejected as "looks like a
 * transducer; pick the TRANSDUCER family" only when it entered encoder role
 * matching (the fallback tier, i.e. the folder holds a bare transducer set), and
 * a selected rnnt-hinted encoder, or a non-ctc-hinted encoder alongside a
 * joiner-like file in the pool (the generic sherpa-canonical names carry no rnnt
 * hint), is rejected the same way; ctc-hinted winners stay importable so the
 * mixed istupakov repo folder (joint included) still imports.
 *
 * Token and encoder selection mirror the transducer matcher but with CTC
 * preference: repos that ship both CTC and RNNT variants (istupakov) have
 * multiple vocab and encoder files; ctc-hinted tokens are picked first and
 * rnnt-hinted files deprioritized, so a GigaAM CTC import never accidentally
 * picks the RNNT files.
 *
 * Record modelType selects the sherpa config subtype:
 * - "nemo_ctc" -> [OfflineNemoEncDecCtcModelConfig] (NeMo encoder-decoder CTC)
 * - "zipformer_ctc" -> [OfflineZipformerCtcModelConfig] (Zipformer CTC)
 * - any other value -> [IllegalArgumentException] naming valid values.
 *
 * Metadata: empty (GigaAM CTC exports carry only "onnx.infer" per desktop
 * validation; no family-identifying metadata to check).
 */
object CtcSupport : ModelFamilySupport {
    override val family: ModelFamily = ModelFamily.CTC

    override fun requiredRoles(): List<String> = listOf(
        SherpaBackend.CANONICAL_ENCODER,
        SherpaBackend.CANONICAL_TOKENS,
    )

    override fun buildCopyPlan(files: List<String>): Map<String, String>? {
        val onnxCandidates = files.filter { it.endsWith(".onnx") }
        // Encoder tiers, rnnt-hinted files deprioritized (never selected when a
        // CTC-compatible candidate exists): keyword non-rnnt, any non-rnnt,
        // keyword, any. CTC exports may not contain "encoder" in the filename
        // (e.g. GigaAM's v3_ctc.int8.onnx), hence the keyword-free tiers.
        val eligible = onnxCandidates.filterNot(::isJoinerLike)
        val encoder = eligible.firstOrNull { it.contains("encoder", ignoreCase = true) && !it.contains("rnnt", ignoreCase = true) }
            ?: eligible.firstOrNull { !it.contains("rnnt", ignoreCase = true) }
            ?: eligible.firstOrNull { it.contains("encoder", ignoreCase = true) }
            ?: eligible.firstOrNull()
            // Structural discriminator over the fallback tier only: with no
            // joiner-free candidate left, a joiner/joint .onnx means the folder
            // holds a bare transducer set. A joiner elsewhere never entered CTC
            // role matching (a parent directory holding several models is
            // legitimate).
            ?: onnxCandidates.firstOrNull(::isJoinerLike)?.let {
                throw IllegalArgumentException(TRANSDUCER_MISMATCH)
            }
            ?: return null
        // A selected rnnt-hinted encoder is only reachable when the pool holds
        // nothing but a transducer set (the tiers above prefer every
        // non-rnnt candidate first), and the CTC metadata check is a no-op
        // (metadataKeys is empty), so reject it here instead of at exit(255).
        if (encoder.contains("rnnt", ignoreCase = true)) throw IllegalArgumentException(TRANSDUCER_MISMATCH)
        // Generic sherpa-canonical names carry no rnnt hint: a joiner in the pool
        // alongside a NON-ctc-hinted selected encoder is the transducer tell.
        // ctc-hinted winners stay importable (the istupakov mixed repo ships CTC
        // and RNNT variants, joint included, in one folder).
        if (onnxCandidates.any(::isJoinerLike) && !encoder.contains("ctc", ignoreCase = true)) {
            throw IllegalArgumentException(TRANSDUCER_MISMATCH)
        }
        // Tokens: prefer exact names, then ctc-hinted (mirror of transducer's rnnt-first).
        val tokens = pickTokens(
            files,
            prefer = { it.contains("ctc", ignoreCase = true) },
            avoid = { it.contains("rnnt", ignoreCase = true) },
        ) ?: return null
        return linkedMapOf(
            SherpaBackend.CANONICAL_ENCODER to encoder,
            SherpaBackend.CANONICAL_TOKENS to tokens,
        )
    }

    override fun metadataFileRole(): String = SherpaBackend.CANONICAL_ENCODER

    override fun metadataKeys(modelType: String): List<String> = emptyList()

    override fun buildModelConfig(record: ExternalModelRecord, numThreads: Int, provider: String): OfflineModelConfig {
        val encoderPath = "${record.dir}/${SherpaBackend.CANONICAL_ENCODER}"
        return when (record.modelType) {
            "nemo_ctc" -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = encoderPath),
                tokens = "${record.dir}/${SherpaBackend.CANONICAL_TOKENS}",
                modelType = "nemo_ctc",
                numThreads = numThreads,
                debug = false,
                provider = provider,
            )
            "zipformer_ctc" -> OfflineModelConfig(
                zipformerCtc = OfflineZipformerCtcModelConfig(model = encoderPath),
                tokens = "${record.dir}/${SherpaBackend.CANONICAL_TOKENS}",
                modelType = "zipformer_ctc",
                numThreads = numThreads,
                debug = false,
                provider = provider,
            )
            else -> throw IllegalArgumentException(
                "unknown CTC modelType \"${record.modelType}\"; valid values: nemo_ctc, zipformer_ctc")
        }
    }
}

/**
 * SenseVoice family: a single model .onnx plus a tokens file; no encoder/decoder
 * split and no joiner.
 *
 * Expected file set: one .onnx whose name contains "sense_voice" (sherpa
 * SenseVoice repos also ship the bare "model.onnx"/"model.int8.onnx" names,
 * matched by a basename "model" prefix) and one .txt tokens/vocab file. The model keyword
 * match deliberately does NOT answer to "encoder": an encoder-only candidate
 * pool means the wrong family was picked, and returning null surfaces that at
 * import time instead of as a runtime exit(255).
 *
 * Language: [options]["sensevoice.language"], defaulting to "" (sherpa performs
 * no language validation per desktop spike; "" is the auto-detect sentinel).
 * ITN: [options]["sensevoice.itn"] where "true"/"1" enable inverse text
 * normalization and anything else (including absent) leaves it off.
 *
 * Record modelType: ignored; OfflineModelConfig.modelType = "sense_voice".
 */
object SenseVoiceSupport : ModelFamilySupport {
    /** Canonical single-model file name (the .int8.onnx convention of the table). */
    const val CANONICAL_MODEL = "model.int8.onnx"

    override val family: ModelFamily = ModelFamily.SENSE_VOICE

    override fun requiredRoles(): List<String> = listOf(CANONICAL_MODEL, SherpaBackend.CANONICAL_TOKENS)

    override fun buildCopyPlan(files: List<String>): Map<String, String>? {
        val model = files.firstOrNull { f ->
            f.endsWith(".onnx") && (
                f.contains("sense_voice", ignoreCase = true) ||
                    // sherpa SenseVoice repos ship the acoustic model as model.onnx or
                    // model.int8.onnx; a basename "model" prefix cannot match encoder
                    // or decoder files, so it is safe as a role keyword.
                    f.substringBeforeLast('.').startsWith("model", ignoreCase = true)
                )
        } ?: return null
        val tokens = pickTokens(files) ?: return null
        return linkedMapOf(
            CANONICAL_MODEL to model,
            SherpaBackend.CANONICAL_TOKENS to tokens,
        )
    }

    override fun metadataFileRole(): String = CANONICAL_MODEL

    override fun metadataKeys(modelType: String): List<String> = emptyList()

    override fun buildModelConfig(record: ExternalModelRecord, numThreads: Int, provider: String): OfflineModelConfig {
        val language = record.options[ModelFamilySupport.OPTION_SENSEVOICE_LANGUAGE] ?: ""
        // "true"/"1" enable ITN; anything else (including absent) leaves it off.
        val itn = record.options[ModelFamilySupport.OPTION_SENSEVOICE_ITN]?.let { it == "true" || it == "1" } ?: false
        return OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = "${record.dir}/$CANONICAL_MODEL",
                language = language,
                useInverseTextNormalization = itn,
            ),
            tokens = "${record.dir}/${SherpaBackend.CANONICAL_TOKENS}",
            modelType = "sense_voice",
            numThreads = numThreads,
            debug = false,
            provider = provider,
        )
    }
}

/**
 * Canary family (TASK-408): NeMo's EncDecMultiTaskModel exports, e.g. NVIDIA's
 * Canary 180M Flash (en/es/de/fr, ~207 MB int8: the lower-end tier the catalog
 * was missing).
 *
 * Expected file set: encoder + decoder .onnx plus a tokens .txt; the same shape
 * as Whisper, discriminated by the encoder's model_type VALUE
 * ("EncDecMultiTaskModel") rather than by structure. A joiner/joint .onnx is
 * rejected as a transducer signature like Whisper does.
 *
 * Language conditioning: the recognizer is BUILT with one srcLang/tgtLang pair
 * (sherpa performs no auto-detection for canary), so the working language is
 * chosen at import time: [options]["canary.language"], falling back to
 * [languages][0], then "en". srcLang == tgtLang (transcription, not
 * translation; the sherpa canary config fills both from the same value).
 *
 * Chunking: canary decodes degenerate beyond ~10s and, unlike whisper, emits
 * EMPTY transcripts for chunks that start mid-speech (measured on desktop:
 * fixed 8s cuts lose half the content; silence-aligned cuts are perfect; the
 * full measurement table is docs/research/canary-chunking-2026-08-29.md).
 * [ExternalSherpaBackend] therefore caps the family at 10s AND the family sets
 * [TranscriptionBackend.requiresVadAlignedChunking] so the orchestrator routes
 * it through VAD segmentation regardless of the user toggle; selecting a canary
 * model also flips the VAD preference on (visible in Settings).
 *
 * Record modelType: ignored; OfflineModelConfig.modelType = "canary".
 */
object CanarySupport : ModelFamilySupport {
    override val family: ModelFamily = ModelFamily.CANARY

    override val featureDim: Int = 128

    override fun requiredRoles(): List<String> = listOf(
        SherpaBackend.CANONICAL_ENCODER,
        SherpaBackend.CANONICAL_DECODER,
        SherpaBackend.CANONICAL_TOKENS,
    )

    override fun buildCopyPlan(files: List<String>): Map<String, String>? = pickNonTransducerPlan(files)

    override fun metadataFileRole(): String = SherpaBackend.CANONICAL_ENCODER

    override fun metadataKeys(modelType: String): List<String> = listOf("model_type")

    override fun valueMetadataKey(): String = "model_type"

    override fun validateImportedModel(metadataValue: String?) {
        // Value-aware discriminator, mirroring Whisper's: the canary export's
        // encoder carries model_type="EncDecMultiTaskModel"; a whisper or
        // transducer encoder under the CANARY family is a family mismatch.
        if (metadataValue != null && metadataValue != "EncDecMultiTaskModel") {
            throw IllegalArgumentException(
                "model_type metadata is \"$metadataValue\": not a canary (EncDecMultiTaskModel) encoder; " +
                    "pick the TRANSDUCER or WHISPER family for those exports")
        }
    }

    override fun buildModelConfig(record: ExternalModelRecord, numThreads: Int, provider: String): OfflineModelConfig {
        val language = record.options[ModelFamilySupport.OPTION_CANARY_LANGUAGE]
            ?: record.languages.firstOrNull()
            ?: "en"
        return OfflineModelConfig(
            canary = OfflineCanaryModelConfig(
                encoder = "${record.dir}/${SherpaBackend.CANONICAL_ENCODER}",
                decoder = "${record.dir}/${SherpaBackend.CANONICAL_DECODER}",
                srcLang = language,
                tgtLang = language,
                usePnc = true,
            ),
            tokens = "${record.dir}/${SherpaBackend.CANONICAL_TOKENS}",
            modelType = "canary",
            numThreads = numThreads,
            debug = false,
            provider = provider,
        )
    }
}
