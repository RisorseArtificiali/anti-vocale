package com.antivocale.app.data.catalog

import org.json.JSONArray
import org.json.JSONObject

/**
 * Unified catalog model + parser (spec: catalog-driven backend platform v3).
 *
 * ONE schema serves both sides of the platform:
 *  - the bundled built-in catalog (`assets/models_catalog.json`, a
 *    `{"schemaVersion", "models": [...]}` document; every built-in sherpa-onnx
 *    model is one entry), and
 *  - a single external entry (user JSON): the same object minus the
 *    built-in-only fields (`id`, `variants`, per-variant `source`, `storageDir`,
 *    `shareAlias`, ...).
 *
 * Display contract (user decision): built-in entries reference localized strings
 * by resource NAME ([CatalogDisplay.Resource]); external entries MUST carry
 * literal `name`/`description` strings ([CatalogDisplay.Literal]) that the UI
 * shows as-is regardless of the device language.
 *
 * The parser is strict: every structural error throws
 * [IllegalArgumentException] with the offending entry/file named, so a wrong
 * catalog fails at import/startup, never mid-flight.
 */
object ModelCatalogJson {

    const val SCHEMA_VERSION = 1

    private const val HF_BASE_URL = "https://huggingface.co"

    /**
     * Flag keys the engine actually consumes, by field of [CatalogFlags]. A key not
     * in this set is a dead flag — parse-time rejection (rather than silent dropping)
     * keeps dead flags from re-entering the catalog unnoticed.
     */
    private val CONSUMED_FLAG_KEYS = setOf(
        "defaultVariant",
        "ensureParentDirs",
        "tailPadSeconds",
        "languageOption",
        "passLanguage",
        "metaKeys",
        "skipMetadataCheck",
        "whisperTailPaddings",
        "blankPenalty",
        "maxNewTokens",
        "chunkDurationSeconds",
        "maxAudioDurationSeconds",
    )

    /**
     * Parses the bundled catalog document (array of entries). Entries are the
     * full built-in shape ([CatalogEntry.id] and [CatalogEntry.variants] are
     * required).
     */
    fun parseCatalog(text: String): List<CatalogEntry> {
        val o = JSONObject(text)
        val version = o.optInt("schemaVersion", SCHEMA_VERSION)
        require(version == SCHEMA_VERSION) {
            "unsupported catalog schemaVersion $version (expected $SCHEMA_VERSION)"
        }
        val models = o.getJSONArray("models")
        return buildList {
            for (i in 0 until models.length()) {
                add(parseEntry(models.getJSONObject(i)))
            }
        }
    }

    /**
     * Parses a single entry object as text: either a built-in entry or an
     * external single-entry JSON (name/description literal, files with
     * url+sha256+size). External entries are rejected unless every file carries
     * its integrity pin and size (the import pre-flight is unconditional).
     */
    fun parseEntry(text: String): CatalogEntry = parseEntry(JSONObject(text))

    private fun parseEntry(o: JSONObject): CatalogEntry {
        val isExternal = !o.has("id")
        if (isExternal) return parseExternalEntry(o)

        val id = o.getString("id")
        val runtime = o.getString("runtime")
        require(runtime == "offline" || runtime == "online") {
            "entry $id: unsupported runtime '$runtime' (expected 'offline' or 'online')"
        }
        val variantArr = o.getJSONArray("variants")
        require(variantArr.length() > 0) { "entry $id has no variants" }
        val variants = buildList {
            for (i in 0 until variantArr.length()) add(parseVariant(variantArr.getJSONObject(i), id))
        }
        return CatalogEntry(
            id = id,
            runtime = runtime,
            modelType = o.getString("modelType"),
            family = o.getString("family"),
            display = requireNotNull(
                o.opt("display")?.let(::parseDisplay) ?: variants.first().title
            ) { "entry $id must declare display (or variant titles)" },
            hasExplicitDisplay = o.has("display"),
            description = o.opt("description")?.let(::parseDisplay),
            noteKey = o.optString("noteKey", "").ifBlank { null },
            speedComparison = o.optBoolean("speedComparison", false),
            shareAlias = o.optString("shareAlias", ""),
            storageDir = o.optString("storageDir", "").ifBlank { null },
            flags = parseFlags(o.optJSONObject("flags")),
            languages = optStringList(o, "languages"),
            variants = variants,
        )
    }

    /** External single entry: literal display, one synthesized variant carrying the files. */
    private fun parseExternalEntry(o: JSONObject): CatalogEntry {
        val display = parseDisplay(o.opt("name") ?: o.opt("display")
            ?: throw IllegalArgumentException("external entry must declare a literal name"))
        require(display is CatalogDisplay.Literal) {
            "external entry name must be a literal string (external strings are never localized)"
        }
        val description = o.opt("description")?.let(::parseDisplay)
        val filesArr = o.optJSONArray("files")
            ?: throw IllegalArgumentException("external entry '${display.text}' has no files")
        require(filesArr.length() > 0) { "external entry '${display.text}' has no files" }
        val files = buildList {
            for (i in 0 until filesArr.length()) {
                add(parseExternalFile(filesArr.getJSONObject(i), display.text))
            }
        }
        return CatalogEntry(
            id = "",
            runtime = "offline",
            modelType = o.optString("modelType", "nemo_transducer"),
            family = o.optString("family", "TRANSDUCER"),
            display = display,
            hasExplicitDisplay = o.has("display") || o.has("name"),
            description = description,
            flags = parseFlags(o.optJSONObject("flags")),
            languages = optStringList(o, "languages"),
            variants = listOf(
                CatalogVariant(
                    name = "default",
                    title = display,
                    description = description,
                    dirName = "",
                    estimatedSizeMB = 0,
                    languages = optStringList(o, "languages"),
                    source = CatalogSource(kind = "url"),
                    files = files,
                )
            ),
        )
    }

    private fun parseVariant(o: JSONObject, entryId: String): CatalogVariant {
        val name = o.getString("name")
        val filesArr = o.optJSONArray("files")
            ?: throw IllegalArgumentException("entry $entryId variant $name has no files")
        require(filesArr.length() > 0) { "entry $entryId variant $name has no files" }
        val files = buildList {
            for (i in 0 until filesArr.length()) {
                add(parseBuiltInFile(filesArr.get(i), entryId, name))
            }
        }
        return CatalogVariant(
            name = name,
            title = o.opt("title")?.let(::parseDisplay),
            description = o.opt("description")?.let(::parseDisplay),
            badgeKey = o.optString("badgeKey", "").ifBlank { null },
            dirName = o.getString("dirName"),
            estimatedSizeMB = o.getLong("estimatedSizeMB"),
            languages = optStringList(o, "languages"),
            source = parseSource(o.getJSONObject("source"), entryId, name),
            files = files,
        )
    }

    /** Built-in files: plain name shorthand or an object that may pin sha256. */
    private fun parseBuiltInFile(raw: Any, entryId: String, variantName: String): CatalogFile =
        when (raw) {
            is String -> CatalogFile(name = raw)
            is JSONObject -> CatalogFile(
                name = raw.getString("name"),
                sha256 = raw.optString("sha256", "").ifBlank { null },
            )
            else -> throw IllegalArgumentException(
                "entry $entryId variant $variantName: files must be strings or objects")
        }

    /** External files: url + sha256 (64 hex) + size are all mandatory. */
    private fun parseExternalFile(o: JSONObject, entryName: String): CatalogFile {
        val name = o.getString("name")
        val sha = o.optString("sha256", "")
        require(sha.length == 64) {
            "external entry '$entryName' file $name is missing its sha256 pin; hashless entries are rejected"
        }
        if (!o.has("size") || o.isNull("size")) {
            throw IllegalArgumentException(
                "external entry '$entryName' file $name is missing its size; entries must declare it")
        }
        val url = o.optString("url", "")
        require(url.isNotBlank()) { "external entry '$entryName' file $name is missing its url" }
        return CatalogFile(name = name, url = url, sha256 = sha, size = o.getLong("size"))
    }

    private fun parseSource(o: JSONObject, entryId: String, variantName: String): CatalogSource {
        val kind = o.getString("kind")
        return when (kind) {
            "huggingface" -> CatalogSource(
                kind = kind,
                repo = o.optString("repo", "").ifBlank {
                    throw IllegalArgumentException("entry $entryId variant $variantName: huggingface source needs a repo")
                },
            )
            "url" -> CatalogSource(
                kind = kind,
                template = o.optString("template", "").ifBlank {
                    throw IllegalArgumentException("entry $entryId variant $variantName: url source needs a template")
                }.let { t ->
                    require(t.contains("{file}")) {
                        "entry $entryId variant $variantName: url template must contain {file}"
                    }
                    t
                },
            )
            else -> throw IllegalArgumentException(
                "entry $entryId variant $variantName: unknown source kind '$kind' (expected 'huggingface' or 'url')")
        }
    }

    private fun parseFlags(o: JSONObject?): CatalogFlags {
        if (o == null) return CatalogFlags()
        // Fail-fast on any flag the engine does not consume: a dead flag must never
        // re-enter the catalog silently (it would be parsed, stored, and ignored).
        val unknown = o.keys().asSequence().filterNot { it in CONSUMED_FLAG_KEYS }.toList()
        require(unknown.isEmpty()) {
            "catalog flags must only contain engine-consumed keys, found: ${unknown.joinToString()}"
        }
        return CatalogFlags(
            defaultVariant = o.optString("defaultVariant", "").ifBlank { null },
            ensureParentDirs = o.optBoolean("ensureParentDirs", false),
            tailPadSeconds = o.optDouble("tailPadSeconds", 0.0),
            languageOption = o.optBoolean("languageOption", false),
            passLanguage = o.optBoolean("passLanguage", false),
            metaKeys = optStringList(o, "metaKeys"),
            skipMetadataCheck = o.optBoolean("skipMetadataCheck", false),
            whisperTailPaddings = o.optInt("whisperTailPaddings", 0),
            blankPenalty = o.optDouble("blankPenalty", 0.0),
            maxNewTokens = o.optInt("maxNewTokens", 0),
            chunkDurationSeconds = o.optInt("chunkDurationSeconds", 0),
            maxAudioDurationSeconds = o.optInt("maxAudioDurationSeconds", 0),
        )
    }

    private fun parseDisplay(raw: Any?): CatalogDisplay? = when (raw) {
        is String -> CatalogDisplay.Literal(raw)
        is JSONObject -> {
            val key = raw.optString("resourceKey", "")
            if (key.isNotBlank()) CatalogDisplay.Resource(key)
            else CatalogDisplay.Literal(raw.optString("text", ""))
        }
        null -> null
        else -> throw IllegalArgumentException("display must be a string or an object")
    }

    private fun optStringList(o: JSONObject, key: String): List<String> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) add(arr.getString(i))
        }
    }
}

/**
 * One way to surface a display string. Built-in entries use [CatalogDisplay.Resource]
 * (a localized R.string by NAME); external entries always use [CatalogDisplay.Literal]
 * (the exact text from the user's JSON, shown regardless of locale).
 */
sealed interface CatalogDisplay {
    data class Resource(val key: String) : CatalogDisplay
    data class Literal(val text: String) : CatalogDisplay
}

/** Where a variant's files come from; [resolveUrl] derives per-file download urls. */
data class CatalogSource(
    /** "huggingface" (repo → `https://huggingface.co/<repo>/resolve/main/<file>`) or "url" (template with {file}). */
    val kind: String,
    val repo: String? = null,
    val template: String? = null,
) {
    init {
        require(kind == "huggingface" || kind == "url") { "unknown source kind: $kind" }
        if (kind == "huggingface") {
            require(!repo.isNullOrBlank()) { "huggingface source needs a repo" }
        }
    }

    fun resolveUrl(file: String): String = when (kind) {
        "huggingface" -> "$HF_BASE_URL/${requireNotNull(repo)}/resolve/main/$file"
        "url" -> {
            val t = requireNotNull(template) { "url source needs a template" }
            require(t.contains("{file}")) { "url template must contain {file}" }
            t.replace("{file}", file)
        }
        else -> throw IllegalArgumentException("unknown source kind: $kind")
    }

    private companion object {
        const val HF_BASE_URL = "https://huggingface.co"
    }
}

/** One file of a variant: destination name (may include a subdir), optional pin/size. */
data class CatalogFile(
    val name: String,
    val url: String? = null,
    val sha256: String? = null,
    val size: Long? = null,
)

/** One downloadable variant of an entry (e.g. Parakeet SmoothQuant vs Stock int8). */
data class CatalogVariant(
    val name: String,
    val title: CatalogDisplay? = null,
    val description: CatalogDisplay? = null,
    /** Optional R.string resource key for a badge rendered next to the card title. */
    val badgeKey: String? = null,
    val dirName: String,
    val estimatedSizeMB: Long,
    val languages: List<String> = emptyList(),
    val source: CatalogSource,
    val files: List<CatalogFile>,
)

/** Reusable, per-entry tuning flags — the formalized per-model workarounds. */
data class CatalogFlags(
    /** Variant auto-selected by default (entry with several variants). */
    val defaultVariant: String? = null,
    /** Create parent dirs for each file (tokenizer/ subdirectory, e.g. Qwen3-ASR). */
    val ensureParentDirs: Boolean = false,
    /**
     * Append this many seconds of silence before decode (transducer tail-token fix:
     * Parakeet/GigaAM offline 1s, Nemotron online 1.5s so the streaming encoder has a
     * complete final chunk).
     */
    val tailPadSeconds: Double = 0.0,
    /** Expose the per-stream language option (Nemotron online, "auto" or a code). */
    val languageOption: Boolean = false,
    /**
     * Pass the user's transcription-language code to the recognizer. Offline
     * models that understand a per-utterance language (Whisper): "auto" maps to
     * "" (let the model detect); a concrete code is passed through.
     */
    val passLanguage: Boolean = false,
    /** ONNX metadata keys required on the encoder; default = vocab_size (+ nemo keys for nemo_transducer). */
    val metaKeys: List<String> = emptyList(),
    /** Skip the pre-native ONNX metadata scan (Whisper models carry no vocab_size metadata). */
    val skipMetadataCheck: Boolean = false,
    /** Whisper's native tailPaddings decode param, in 10ms units (e.g. 1000 = 10s). */
    val whisperTailPaddings: Int = 0,
    /** blankPenalty for the offline recognizer (Qwen3-ASR 1.0; 0.0 = sherpa default/disabled). */
    val blankPenalty: Double = 0.0,
    /** maxNewTokens for the offline recognizer (Qwen3-ASR 2048; 0 = sherpa default). */
    val maxNewTokens: Int = 0,
    /**
     * Max chunk duration in seconds for the orchestrator: >0 chunks longer audio
     * (Whisper/Qwen3-ASR 30); 0 processes the whole clip in one pass (Parakeet/GigaAM/Nemotron).
     */
    val chunkDurationSeconds: Int = 0,
    /**
     * The model's own per-pass audio cap in seconds, as shown in the model list
     * (GH #49 display fact; Whisper/Qwen3-ASR 30); 0 = no known limit. Display
     * only: runtime duration ceilings come from AudioDurationPolicy, not this.
     */
    val maxAudioDurationSeconds: Int = 0,
)

/** One model in the catalog: the identity + tuning + the variants to download. */
data class CatalogEntry(
    /** Backend id: a built-in BACKEND_ID constant ("sherpa-onnx", ...) or "" for an external single entry. */
    val id: String,
    /** Recognizer family: "offline" (OfflineRecognizer) or "online" (OnlineRecognizer, streaming). */
    val runtime: String,
    /** sherpa-onnx modelType: "nemo_transducer", "whisper", "qwen3_asr", "" (Nemotron online). */
    val modelType: String,
    /** Model family: "TRANSDUCER", "ENCODER_DECODER", "ENCODER_ONLY_CTC". */
    val family: String,
    val display: CatalogDisplay,
    /**
     * Whether the entry declares its own `display` (a fixed, localized name) vs
     * falling back to the first variant title. The registry uses this to decide
     * between a fixed display-name resource and path-derived names.
     */
    val hasExplicitDisplay: Boolean = false,
    val description: CatalogDisplay? = null,
    /** Info note resource key (ModelInfoProvider bestFor/notes). */
    val noteKey: String? = null,
    /** Whether the section header shows the model speed-comparison dialog. */
    val speedComparison: Boolean = false,
    /** Share-target activity-alias (built-ins only). */
    val shareAlias: String = "",
    /** filesDir subdir for built-ins; null for external entries (they own their dir). */
    val storageDir: String? = null,
    val flags: CatalogFlags = CatalogFlags(),
    /** Entry-level language support (variants may override). */
    val languages: List<String> = emptyList(),
    val variants: List<CatalogVariant>,
) {
    val isStreaming: Boolean get() = runtime == "online"

    fun variant(name: String?): CatalogVariant? = variants.firstOrNull { it.name == name }

    /** The default variant: flags.defaultVariant if declared, else the first. */
    val defaultVariant: CatalogVariant get() = flags.defaultVariant?.let(::variant) ?: variants.first()

    /** Effective language set: entry-level, or a variant's when it declares its own. */
    fun languagesFor(variant: CatalogVariant): List<String> =
        variant.languages.ifEmpty { languages }
}
