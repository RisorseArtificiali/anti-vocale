package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ModelFamily
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
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
 * Single-model plan shared by the model+tokens families (SenseVoice, Dolphin
 * since GH #89): the "model"-basename rule cannot collide with encoder or
 * decoder roles, so it is safe as a role keyword. [extraNameHint] carries the
 * family's own filename hint (SenseVoice's sense_voice); one definition so the
 * two deliberately-ambiguous families cannot drift apart.
 */
private fun pickSingleModelPlan(
    files: List<String>,
    extraNameHint: (String) -> Boolean,
): Map<String, String>? {
    val model = files.firstOrNull { f ->
        f.endsWith(".onnx") && (
            f.substringBeforeLast('.').startsWith("model", ignoreCase = true) ||
                extraNameHint(f)
            )
    } ?: return null
    val tokens = pickTokens(files) ?: return null
    return linkedMapOf(
        SherpaBackend.CANONICAL_MODEL to model,
        SherpaBackend.CANONICAL_TOKENS to tokens,
    )
}

/**
 * The config tail every family shares (tokens path, threading, provider);
 * one definition so nine construction sites cannot drift (review round:
 * one missed copy gave a family a wrong tokens path that only surfaced at
 * native load).
 */
private fun OfflineModelConfig.withCommonTail(
    record: ExternalModelRecord,
    numThreads: Int,
    provider: String,
): OfflineModelConfig = copy(
    tokens = record.dir + "/" + SherpaBackend.CANONICAL_TOKENS,
    numThreads = numThreads,
    debug = false,
    provider = provider,
)

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

    /**
     * Canonical file names every import of this family must produce: the
     * production list for the one-shape families (the [requiredRolesFor]
     * default is exactly this). MOONSHINE alone returns the union of its two
     * generations' published names, which nothing in production reads
     * (moonshine overrides [requiredRolesFor] per generation).
     */
    fun requiredRoles(): List<String>

    /**
     * [requiredRoles] narrowed to the files an import with THESE file names
     * actually needs. Default: the full set (one-shape families). Moonshine
     * overrides: its v1 and v2 generations carry disjoint file sets, so the
     * load-time presence check must ask for the generation that exists, not
     * the union (the union would fail every import of either shape).
     */
    fun requiredRolesFor(files: List<String>): List<String> = requiredRoles()

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

    /**
     * TASK-481: extra cure text appended to this family's metadata failures
     * (import time and load time, one definition). Null (default): the
     * generic corrupt/wrong-family message stands alone.
     */
    fun metadataFailureGuidance(): String? = null

    companion object {
        /**
         * TASK-481: the cure appended to every transducer metadata failure,
         * at import time (the better moment) and at load time; one
         * definition so the two moments cannot promise different things.
         */
        const val TRANSDUCER_EXPORT_GUIDANCE =
            "Supported transducer exports are OFFLINE (non-streaming) ones carrying their " +
                "original vocab_size/subsampling_factor metadata (Parakeet, GigaAM, k2-fsa " +
                "offline zipformers); exports with 'streaming' in the name are not supported"

        /** Error raised when CTC is imported without an explicit modelType (single definition). */
        const val CTC_MODEL_TYPE_REQUIRED =
            "CTC family requires an explicit modelType: nemo_ctc, zipformer_ctc or omnilingual_ctc"

        /** The two sherpa CTC config subtypes (single definition for the
         *  engine mapping above, the import UI defaults, and the family
         *  chooser; a mismatched pair dies at native load, so every site
         *  must spell these identically). */
        const val CTC_TYPE_NEMO = "nemo_ctc"
        const val CTC_TYPE_ZIPFORMER = "zipformer_ctc"

        /** TASK-635: Meta omnilingual CTC (sherpa OfflineOmnilingualAsrCtcModelConfig). */
        const val CTC_TYPE_OMNILINGUAL = "omnilingual_ctc"

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
            ModelFamily.WHISPER, ModelFamily.SENSE_VOICE, ModelFamily.CANARY,
            ModelFamily.MOONSHINE, ModelFamily.DOLPHIN -> ""
            ModelFamily.CTC -> null
        }

        /** The modelType strings this family accepts ("" = no subtype); the
         *  ONE table both [isValidModelType] and error messages derive from. */
        fun validModelTypes(family: ModelFamily): List<String> = when (family) {
            ModelFamily.TRANSDUCER -> listOf("", "nemo_transducer", "conformer_transducer")
            ModelFamily.CTC -> listOf("nemo_ctc", "zipformer_ctc", CTC_TYPE_OMNILINGUAL)
            ModelFamily.WHISPER, ModelFamily.SENSE_VOICE, ModelFamily.CANARY,
            ModelFamily.MOONSHINE, ModelFamily.DOLPHIN -> listOf("")
        }

        /** True when [modelType] is a valid record modelType for [family] (single definition). */
        fun isValidModelType(family: ModelFamily, modelType: String): Boolean =
            modelType in validModelTypes(family)

        fun forFamily(family: ModelFamily): ModelFamilySupport = when (family) {
            ModelFamily.TRANSDUCER -> TransducerSupport
            ModelFamily.WHISPER -> WhisperSupport
            ModelFamily.CTC -> CtcSupport
            ModelFamily.SENSE_VOICE -> SenseVoiceSupport
            ModelFamily.CANARY -> CanarySupport
            ModelFamily.MOONSHINE -> MoonshineSupport
            ModelFamily.DOLPHIN -> DolphinSupport
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

    private const val EXPORT_GUIDANCE =
        "Known-good manual transducer imports are NeMo-style OFFLINE exports carrying " +
            "their original vocab_size and subsampling_factor metadata (Parakeet, GigaAM). " +
            "k2-fsa zipformer releases do not carry that metadata, streaming or not, and " +
            "streaming NeMo exports need the catalog's streaming entry, not the offline importer."

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

    override fun valueMetadataKey(): String = "vocab_size"

    /**
     * TASK-481 ground truth (encoder metadata dumps, eval/models): NeMo-style
     * OFFLINE exports (Parakeet, GigaAM) carry vocab_size/subsampling_factor
     * and import cleanly; k2-fsa zipformer releases carry NO vocab_size,
     * streaming or not, so both their variants fail the metadata gate; and a
     * streaming NeMo export carries full metadata but needs the catalog's
     * streaming entry, not the offline importer. One text for all three
     * outcomes, at import and at load.
     */
    override fun metadataFailureGuidance(): String = EXPORT_GUIDANCE

    override fun validateImportedModel(metadataValue: String?) {
        // TASK-481 gate honesty: key presence alone was defeated live by a
        // hand-patched encoder (a streaming zipformer with fake
        // vocab_size/subsampling_factor values imported cleanly and died at
        // transcription). Every other family validates its discriminator
        // value; the transducer family now does too: a vocab_size that is
        // not a plausible positive integer is a patched or corrupt export.
        if (metadataValue == null) return  // absent key stays with the presence chain
        val vocab = metadataValue.trim().toIntOrNull()
        require(vocab != null && vocab > 1) {
            "vocab_size metadata is \"$metadataValue\": not a plausible vocabulary size " +
                "(expect a number above 1). Hand-patched or corrupt exports fail here " +
                "on purpose. $EXPORT_GUIDANCE"
        }
    }

    override fun buildModelConfig(record: ExternalModelRecord, numThreads: Int, provider: String): OfflineModelConfig =
        OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "${record.dir}/${SherpaBackend.CANONICAL_ENCODER}",
                decoder = "${record.dir}/${SherpaBackend.CANONICAL_DECODER}",
                joiner = "${record.dir}/${SherpaBackend.CANONICAL_JOINER}"
            ),
            modelType = record.modelType,
        ).withCommonTail(record, numThreads, provider)
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
            modelType = "whisper",
        ).withCommonTail(record, numThreads, provider)
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
                modelType = "nemo_ctc",
            ).withCommonTail(record, numThreads, provider)
            "zipformer_ctc" -> OfflineModelConfig(
                zipformerCtc = OfflineZipformerCtcModelConfig(model = encoderPath),
                modelType = "zipformer_ctc",
            ).withCommonTail(record, numThreads, provider)
            ModelFamilySupport.CTC_TYPE_OMNILINGUAL -> OfflineModelConfig(
                // Mirrors sherpa's from_omnilingual_asr_ctc: the dedicated
                // config field, no model_type (empty default).
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = encoderPath),
                modelType = "",
            ).withCommonTail(record, numThreads, provider)
            else -> throw IllegalArgumentException(
                "unknown CTC modelType \"${record.modelType}\"; valid values: nemo_ctc, zipformer_ctc, omnilingual_ctc")
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
    /** Aliases [SherpaBackend.CANONICAL_MODEL], the single owner of the
     *  single-model canonical name (rename THERE, not here). */
    const val CANONICAL_MODEL = SherpaBackend.CANONICAL_MODEL

    override val family: ModelFamily = ModelFamily.SENSE_VOICE

    override fun requiredRoles(): List<String> = listOf(CANONICAL_MODEL, SherpaBackend.CANONICAL_TOKENS)

    override fun buildCopyPlan(files: List<String>): Map<String, String>? {
        // The shared single-model core (pickSingleModelPlan) plus this
        // family's own filename hint.
        return pickSingleModelPlan(files) { it.contains("sense_voice", ignoreCase = true) }
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
            modelType = "sense_voice",
        ).withCommonTail(record, numThreads, provider)
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
            modelType = "canary",
        ).withCommonTail(record, numThreads, provider)
    }
}

/**
 * Moonshine family (GH #89 light-models): tiny/base recognizers, EN plus the
 * 2026 KO/JA/ZH v2 exports. Two shapes, both accepted, and each import keeps
 * its own generation's file names as canonical (the sherpa config names the
 * files explicitly, so no renaming is needed):
 *  - v1: preprocess.onnx + encode.int8.onnx + uncached_decode.int8.onnx +
 *    cached_decode.int8.onnx + tokens
 *  - v2: encoder_model.ort + decoder_model_merged.ort + tokens (NOTE the
 *    .ort extension: only this family ships it).
 * Chunk cap 8s: the 2026-02-27 v2 .ort exports decode empty above ~9.25s of
 * total input INCLUDING the decode path's 1s silence pad (measured,
 * TASK-619; the backend table owns the number).
 */
object MoonshineSupport : ModelFamilySupport {
    // Role segments (the name before the first dot): the canonical file
    // names are the source's own, so the constants name ROLES, not files.
    internal const val V2_ROLE_ENCODER = "encoder_model"
    internal const val V2_ROLE_MERGED_DECODER = "decoder_model_merged"
    internal const val V1_ROLE_PREPROCESSOR = "preprocess"
    internal const val V1_ROLE_ENCODER = "encode"
    internal const val V1_ROLE_UNCACHED_DECODER = "uncached_decode"
    internal const val V1_ROLE_CACHED_DECODER = "cached_decode"

    override val family: ModelFamily = ModelFamily.MOONSHINE

    /** Union of both generations' canonical names (plus tokens); the
     *  files-aware narrowing below picks the generation actually present. */
    // Documentation/test union, DERIVED from the published lists (review
    // round: a hand-maintained third copy is what nothing in production
    // reads, so it rots silently). Nothing in the import or load path reads
    // this; requiredRolesFor(files) is the live narrowing.
    override fun requiredRoles(): List<String> = (V2_PUBLISHED + V1_PUBLISHED).distinct()

    /** Container-gated role-name test (review round): the ONE predicate
     *  shared with ModelFamilyDetector's truncated-set guard, which used to
     *  carry its own copy of the six constants. Sidecar rationale on
     *  [isContainerSegment]. */
    internal fun isRoleName(name: String): Boolean =
        name.isContainerSegment(ROLE_SEGMENTS)

    /** One container gate + segment matcher for the plan-side role lookups
     *  (isRoleName, byRole). The gate exists because a split-ONNX sidecar
     *  (encode.int8.onnx.data) or a same-stem note file shares the first
     *  segment: a plan role must resolve to a real container, and a
     *  truncated-set guard must not fire on sidecars. Generation INFERENCE
     *  (v2Generation below) deliberately does NOT use this gate; see there.
     *  A future container-extension change edits this line only. */
    private fun String.isContainerSegment(segments: Set<String>): Boolean =
        (endsWith(".onnx", ignoreCase = true) || endsWith(".ort", ignoreCase = true)) &&
            substringBefore('.').lowercase() in segments

    private val ROLE_SEGMENTS = setOf(
        V2_ROLE_ENCODER, V2_ROLE_MERGED_DECODER,
        V1_ROLE_PREPROCESSOR, V1_ROLE_ENCODER,
        V1_ROLE_UNCACHED_DECODER, V1_ROLE_CACHED_DECODER,
    )

    // Generation selection has TWO predicates by design: the PLAN is strict
    // (a v2 import needs BOTH .ort files), while generation INFERENCE answers
    // "which shape is this folder" even when incomplete, so error messages
    // never send a v2 folder chasing v1 files. This is the single inference
    // definition; both callers below share it.
    /** Model containers only (verification round); rationale on
     *  [isContainerSegment]. */
    private fun Collection<String>.byRole(role: String): String? =
        firstOrNull { it.isContainerSegment(setOf(role)) }

    // STEM-ONLY, deliberately ungated (seventh review round): the plan-side
    // lookups are container-gated, but inference must answer "which shape is
    // this folder" even when the containers were lost and only split-file
    // sidecars survive (encoder_model.onnx.data still carries the v2 stem),
    // so the missing-files error keeps naming the v2 set instead of sending
    // a v2 folder chasing v1 files. The inference-vs-plan split is the
    // object comment above.
    private val V2_SEGMENTS = setOf(V2_ROLE_ENCODER, V2_ROLE_MERGED_DECODER)
    private fun v2Generation(names: Iterable<String>): Boolean =
        names.any { it.substringBefore('.').lowercase() in V2_SEGMENTS }

    // The generations' published spellings: ONE definition each (seventh
    // review round killed the emptyList()-means-published dual mode).
    private val V2_PUBLISHED = listOf(
        "$V2_ROLE_ENCODER.ort", "$V2_ROLE_MERGED_DECODER.ort", SherpaBackend.CANONICAL_TOKENS)
    private val V1_PUBLISHED = listOf(
        "$V1_ROLE_PREPROCESSOR.onnx", "$V1_ROLE_ENCODER.int8.onnx",
        "$V1_ROLE_UNCACHED_DECODER.int8.onnx", "$V1_ROLE_CACHED_DECODER.int8.onnx",
        SherpaBackend.CANONICAL_TOKENS,
    )

    // User-facing fallback names: the generation's published spelling, or
    // the file ACTUALLY present for that role when the folder carries a
    // non-canonical extension (an .onnx-flavored v2 export's error must
    // name encoder_model.onnx, not the .ort spelling it does not use)
    // (verification + review rounds: extensions derived, never hardcoded
    // against the set).
    private fun rolesForGeneration(v2: Boolean, files: List<String>): List<String> =
        (if (v2) V2_PUBLISHED else V1_PUBLISHED).map { published ->
            files.byRole(published.substringBefore('.')) ?: published
        }

    // The interface default supplies the plan keys; only the INCOMPLETE-set
    // fallback is moonshine-specific (generation inference, not the union).
    override fun requiredRolesFor(files: List<String>): List<String> =
        buildCopyPlan(files)?.keys?.toList() ?: rolesForGeneration(v2Generation(files), files)

    override fun buildCopyPlan(files: List<String>): Map<String, String>? {
        val tokens = pickTokens(files) ?: return null
        // Role matching on the FIRST dot-segment of the file name (review
        // round): that is the role proper, before quantization tags (.int8)
        // and container extensions (.onnx/.ort). "encode.int8.onnx" and
        // "encode.onnx" both read as role "encode"; "encoder_model.ort" and
        // "encoder_model.onnx" both read as role "encoder_model". The v2
        // canonical names PRESERVE the source extension: an .onnx-flavored v2
        // export must not be renamed to .ort (the bytes are protobuf ONNX,
        // the split-file sidecar check reads the canonical extension, and a
        // lie in the extension is an opaque native error later).
        val v2Encoder = files.byRole(V2_ROLE_ENCODER)
        val v2Decoder = files.byRole(V2_ROLE_MERGED_DECODER)
        if (v2Encoder != null && v2Decoder != null) {
            return linkedMapOf(
                v2Encoder to v2Encoder,
                v2Decoder to v2Decoder,
                SherpaBackend.CANONICAL_TOKENS to tokens,
            )
        }
        val pre = files.byRole(V1_ROLE_PREPROCESSOR)
        val enc = files.byRole(V1_ROLE_ENCODER)
        val uncached = files.byRole(V1_ROLE_UNCACHED_DECODER)
        val cached = files.byRole(V1_ROLE_CACHED_DECODER)
        if (pre != null && enc != null && uncached != null && cached != null) {
            return linkedMapOf(
                pre to pre,
                enc to enc,
                uncached to uncached,
                cached to cached,
                SherpaBackend.CANONICAL_TOKENS to tokens,
            )
        }
        return null
    }

    // No pre-native metadata gate for this family (the structural plan is the
    // discriminator): metadataKeys is empty and valueMetadataKey is null, so
    // nothing ever reads this path (the empty-gate short-circuit in SherpaBackend
    // returns first). Deliberate, not accidental: adding a metadata key for
    // moonshine means making this generation-aware, not just flipping a const.
    override fun metadataFileRole(): String = "$V1_ROLE_ENCODER.int8.onnx"

    override fun metadataKeys(modelType: String): List<String> = emptyList()

    override fun buildModelConfig(record: ExternalModelRecord, numThreads: Int, provider: String): OfflineModelConfig {
        // record.files.keys is the import-time truth (the canonical names the
        // import actually wrote): a stray file dropped into the directory
        // later cannot flip the generation under the load check's feet
        // (review round: presence check and config used to disagree).
        // The actual file names are the record's own canonical keys (the
        // plan keeps the source names, extension included), so the config
        // never points at a name the import did not write.
        fun fileFor(role: String) = requireNotNull(record.files.keys.byRole(role)) {
            "moonshine record missing the $role model file (drifted record?)"
        }
        val moonshine = if (v2Generation(record.files.keys)) {
            OfflineMoonshineModelConfig(
                encoder = "${record.dir}/${fileFor(V2_ROLE_ENCODER)}",
                mergedDecoder = "${record.dir}/${fileFor(V2_ROLE_MERGED_DECODER)}",
            )
        } else {
            OfflineMoonshineModelConfig(
                preprocessor = "${record.dir}/${fileFor(V1_ROLE_PREPROCESSOR)}",
                encoder = "${record.dir}/${fileFor(V1_ROLE_ENCODER)}",
                uncachedDecoder = "${record.dir}/${fileFor(V1_ROLE_UNCACHED_DECODER)}",
                cachedDecoder = "${record.dir}/${fileFor(V1_ROLE_CACHED_DECODER)}",
            )
        }
        return OfflineModelConfig(
            moonshine = moonshine,
        ).withCommonTail(record, numThreads, provider)
    }
}

/**
 * Dolphin family (GH #89 light-models): the multi-language offline Dolphin
 * recognizer (model.int8.onnx + tokens). The file shape is SenseVoice's, so
 * an unhinted model+tokens set is deliberately AMBIGUOUS between the two
 * (the chooser offers both; a dolphin-named folder or URL narrows the hint,
 * exactly like the whisper/canary pair).
 */
object DolphinSupport : ModelFamilySupport {
    /** Aliases [SherpaBackend.CANONICAL_MODEL], the single owner of the
     *  single-model canonical name (SenseVoice aliases the same constant;
     *  renaming means editing THAT one, not this alias). */
    const val CANONICAL_MODEL = SherpaBackend.CANONICAL_MODEL

    override val family: ModelFamily = ModelFamily.DOLPHIN

    override fun requiredRoles(): List<String> = listOf(CANONICAL_MODEL, SherpaBackend.CANONICAL_TOKENS)

    override fun buildCopyPlan(files: List<String>): Map<String, String>? =
        pickSingleModelPlan(files) { it.contains("dolphin", ignoreCase = true) }

    // No pre-native metadata gate: the shape plus the hint/chooser flow is
    // the discriminator for this family.
    override fun metadataFileRole(): String = CANONICAL_MODEL

    override fun metadataKeys(modelType: String): List<String> = emptyList()

    override fun buildModelConfig(record: ExternalModelRecord, numThreads: Int, provider: String): OfflineModelConfig =
        OfflineModelConfig(
            dolphin = OfflineDolphinModelConfig(
                model = "${record.dir}/$CANONICAL_MODEL",
            ),
        ).withCommonTail(record, numThreads, provider)
}
