package com.antivocale.app.data

import com.antivocale.app.transcription.ModelFamilySupport
import org.json.JSONObject

/**
 * The schema-1 CDN-manifest import dialect (2026-11-07): a publisher-hosted
 * manifest ({"schema":1,"model":...,"base_url":...,"files":{name:{size,sha256}}})
 * whose entryUrl IS the manifest, so a community-catalog index can point at a
 * floating "latest" release without shipping a pinned entry JSON. The sha256
 * pins are trusted AT IMPORT TIME from the manifest and then pinned into the
 * imported record; a new CDN release is simply picked up by a fresh import,
 * and post-download rot is still caught by the hash verification of the
 * download core against the pins read this import.
 *
 * Known extra sections are ignored: "gguf" (llama-side weights on the same
 * CDN) and the Sabich "onnx" merged-form section, whose DIFFERENT layout
 * (files as a size-number map, a merged decoder_joint file set) must never be
 * confused with the authoritative top-level "files". Unknown FILENAMES inside
 * "files" (e.g. bpe.vocab) are ignored too, but the four transducer
 * requirements (encoder/decoder/joiner int8 onnx + tokens.txt) must all be
 * present or the synthesis rejects, naming what is missing.
 *
 * Pure (org.json only, no Android or network): unit-testable directly, so the
 * importer entry stays a thin fetch-synthesize-download shell.
 *
 * Residual known hole (same tracked class as the legacy entry path,
 * TransducerSupport): publishers not declaring the streaming field are
 * classified by the model-name heuristic, so a streaming model that neither
 * declares the field nor carries "streaming" in its model name registers
 * offline and aborts at first decode.
 */
object ExternalManifestV1 {

    /** The four files a transducer manifest must carry, in canonical role order. */
    private val REQUIRED_FILES = listOf(
        "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")

    private val SHA256_RE = Regex("^[0-9a-fA-F]{64}$")

    /**
     * True when [text] parses as a schema-1 manifest: an integer "schema" == 1,
     * a non-blank "base_url" string, and an object "files". An entry-JSON
     * document ("files" as an ARRAY) and a catalog index both answer false,
     * so the sniff can gate inside the existing entry-JSON import arm without
     * changing that arm's classification.
     */
    fun sniff(text: String): Boolean = runCatching {
        val o = JSONObject(text)
        o.optInt("schema", -1) == 1 &&
            o.optString("base_url").isNotBlank() &&
            o.optJSONObject("files") != null
    }.getOrDefault(false)

    /**
     * Synthesizes the importable entry from a schema-1 manifest text. The
     * [overrideName]/[overrideFamily]/[overrideLanguages]/[overrideLicence]/
     * [overrideStreaming] parameters carry the community-index metadata of an
     * index pick and merge OVER the manifest-derived values (the index is the
     * curated surface; the manifest only fills in what the index does not
     * declare). Throws [IllegalArgumentException] naming the missing required
     * files when the manifest does not carry all four.
     *
     * Streaming: the manifest's declared streaming is the optional top-level
     * "streaming" boolean when present (it wins both ways: explicit false on
     * a streaming-NAMED model is bare-importable offline, explicit true is
     * rejected bare), and the model-name heuristic ("streaming" in the
     * "model" name) only when the field is absent. A manifest that DECLARES
     * streaming under either rule must still be imported through an index
     * entry authoring the flag ([overrideStreaming]): a bare/direct import
     * would register it as offline and die at first transcription, so it is
     * rejected here with the catalog-entry instruction instead.
     */
    fun synthesize(
        text: String,
        manifestUrl: String,
        overrideName: String? = null,
        overrideFamily: ModelFamily? = null,
        overrideLanguages: List<String> = emptyList(),
        overrideLicence: String? = null,
        overrideStreaming: Boolean = false,
    ): ExternalModelEntryJson.Entry {
        val o = JSONObject(text)
        val filesJson = o.optJSONObject("files")
        if (filesJson == null) {
            throw IllegalArgumentException(
                "manifest at $manifestUrl carries no \"files\" object")
        }
        val rawBaseUrl = o.optString("base_url")
        if (rawBaseUrl.isBlank()) {
            throw IllegalArgumentException(
                "manifest at $manifestUrl carries no base_url")
        }
        val baseUrl = if (rawBaseUrl.endsWith("/")) rawBaseUrl else "$rawBaseUrl/"

        val missing = REQUIRED_FILES.filterNot { filesJson.has(it) }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException(
                "manifest at $manifestUrl is missing required transducer files: " +
                    "$missing; found: ${filesJson.keys().asSequence().toList()}")
        }
        val files = REQUIRED_FILES.map { name ->
            val f = filesJson.getJSONObject(name)
            val sha = f.optString("sha256", "")
            if (!SHA256_RE.matches(sha)) {
                throw IllegalArgumentException(
                    "manifest file $name is missing a valid sha256 pin; schema-1 manifests must pin every file")
            }
            if (f.isNull("size") || f.optLong("size", -1L) <= 0L) {
                throw IllegalArgumentException(
                    "manifest file $name is missing its size; schema-1 manifests must declare it")
            }
            ExternalModelEntryJson.EntryFile(name, baseUrl + name, sha, f.getLong("size"))
        }.toMutableList()
        // Split-ONNX sidecars (R7): a manifest-declared "<required>.data"
        // sibling joins the plan so the sidecar scanner's guidance stays
        // satisfiable for publishers shipping external-data encoders.
        for (name in REQUIRED_FILES) {
            val sidecar = "$name.data"
            if (!filesJson.has(sidecar)) continue
            val f = filesJson.getJSONObject(sidecar)
            val sha = f.optString("sha256", "")
            if (!SHA256_RE.matches(sha) || f.optLong("size", -1L) <= 0L) {
                throw IllegalArgumentException(
                    "manifest file $sidecar must declare both a sha256 pin and a size, like every other file")
            }
            files.add(ExternalModelEntryJson.EntryFile(sidecar, baseUrl + sidecar, sha, f.getLong("size")))
        }

        val name = overrideName?.takeIf { it.isNotBlank() }
            ?: o.optString("model").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(
                "manifest at $manifestUrl declares neither a \"model\" name nor an index name")

        // Licence: the caller's (index-authored) wins; the manifest's own
        // "licence" field, or the Sabich-style "terms" URL, is the fallback.
        val licence = overrideLicence?.takeIf { it.isNotBlank() }
            ?: o.optString("licence").takeIf { it.isNotBlank() }
            ?: o.optString("terms").takeIf { it.isNotBlank() }

        val family = overrideFamily ?: ModelFamily.TRANSDUCER
        if (overrideStreaming && family != ModelFamily.TRANSDUCER) {
            throw IllegalArgumentException(
                "streaming=true is only supported for the TRANSDUCER family (schema-1 manifest at $manifestUrl)")
        }
        // Declared streaming: the explicit top-level boolean wins both ways;
        // the model-name heuristic fills in only when the field is absent.
        val manifestModel = o.optString("model")
        val declaresStreaming = if (o.has("streaming")) {
            o.optBoolean("streaming", false)
        } else {
            manifestModel.contains("streaming", ignoreCase = true)
        }
        if (!overrideStreaming && declaresStreaming) {
            throw IllegalArgumentException(
                "manifest model '$manifestModel' declares a streaming model: direct manifest imports " +
                    "cannot register streaming records, so import it from its catalog entry " +
                    "(which sets the streaming flag), not by pasting the manifest URL directly")
        }
        // The guard above means a declaring manifest only survives WITH an
        // index override, so the record's flag is the override alone; the
        // manifest's declaration only gates the bare-import rejection.
        val streaming = overrideStreaming
        // Streaming transducers take the EMPTY modelType (the bundled Nemotron
        // pattern: a streaming NeMo encoder carries vocab_size only, so the
        // nemo_transducer metadata trio cannot be demanded of it). Offline
        // ones take an explicit top-level "model_type" when the manifest
        // declares one, else the NeMo default: an offline schema-1 manifest
        // is ASSUMED a NeMo-style export (the metadata trio) unless it
        // declares its model_type, so a k2 zipformer manifest must say so.
        // has()-gated, not isNotBlank-gated: an explicit empty model_type is a
        // MEANINGFUL declaration (the k2 zipformer plain-transducer loader),
        // distinct from an absent field (the NeMo default).
        val manifestModelType = if (o.has("model_type")) o.optString("model_type") else null
        val modelType = when {
            streaming -> ""
            manifestModelType != null -> {
                if (!ModelFamilySupport.isValidModelType(family, manifestModelType)) {
                    throw IllegalArgumentException(
                        "manifest model_type '$manifestModelType' is not valid for ${family.name}")
                }
                manifestModelType
            }
            else -> ModelFamilySupport.defaultModelType(family)
                ?: throw IllegalArgumentException(ModelFamilySupport.CTC_MODEL_TYPE_REQUIRED)
        }

        return ExternalModelEntryJson.Entry(
            name = name,
            family = family,
            modelType = modelType,
            languages = overrideLanguages,
            options = emptyMap(),
            files = files,
            streaming = streaming,
            licence = licence,
        )
    }
}
