package com.antivocale.app.testing

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelImportOperations
import com.antivocale.app.data.ExternalModelImporter
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.ModelFamily
import com.antivocale.app.transcription.ModelFamilySupport
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.InferenceProvider
import com.antivocale.app.transcription.LlmTranscriptionBackend
import com.antivocale.app.transcription.PunctuationPolicy
import com.antivocale.app.ui.theme.ThemeMode
import com.antivocale.app.ui.theme.TextScale
import com.antivocale.app.ui.theme.ThemeType
import com.antivocale.app.util.SubtitleFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Engine of the debug-only test SPI (TASK-409): the op handling behind
 * [com.antivocale.app.receiver.TestSpiReceiver], which lives in the debug
 * source set and is registered only by the debug manifest overlay.
 *
 * Why this class sits in `main` and not next to the receiver: the shared unit
 * test source set (src/test) compiles against every variant, including the
 * release ones where the debug receiver class does not exist. Keeping the op
 * handling here lets `TestSpiOpsTest` compile for all variants while the thin
 * receiver stays debug-only. Nothing else in `main` references this class, so
 * R8 strips it from both minified release flavors; only debug builds ship it.
 *
 * Every response is one line of JSON (grep-friendly): the receiver mirrors it
 * into setResultData and Log.i("TestSpi"). Not a product feature; an
 * engineering affordance for agent/CI device testing, replacing dozens of adb
 * UI-driving calls per session. Usage docs: docs/testing-spi.md.
 */
internal class TestSpiOps(
    private val preferences: PreferencesManager,
    private val externalModels: ExternalModelStore,
    private val importer: ExternalModelImportOperations,
    /** Needed by the notify_memory_error op; the debug receiver passes its context. */
    private val appContext: android.content.Context? = null,
) {

    suspend fun handle(
        op: String?,
        key: String? = null,
        value: String? = null,
        entry: String? = null,
        url: String? = null,
        family: String? = null,
        modelType: String? = null,
    ): String = runCatching {
        when (op) {
            OP_GET -> get()
            OP_SET -> set(key, value, entry)
            OP_RECORDS -> records()
            OP_IMPORT -> importModel(url, family, modelType)
            OP_NOTIFY_MEMORY_ERROR -> notifyMemoryError()
            OP_HELP -> help()
            else -> help(error = if (op == null) null else "unknown op '$op'")
        }
    }.getOrElse { e ->
        if (e is CancellationException) throw e
        JSONObject()
            .put("op", op ?: OP_HELP)
            .put("error", e.message ?: e.javaClass.simpleName)
            .toString()
    }

    private suspend fun get(): String {
        val backend = preferences.transcriptionBackend.first()
        val paths = JSONObject()
        for (id in BuiltInBackendIds.ALL) {
            paths.put(id, preferences.sherpaModelPath(id).first() ?: JSONObject.NULL)
        }
        paths.put(LlmTranscriptionBackend.BACKEND_ID, preferences.modelPath.first() ?: JSONObject.NULL)
        return JSONObject()
            .put("op", OP_GET)
            .put("vadEnabled", preferences.vadEnabled.first())
            .put("progressiveEnabled", preferences.progressiveTranscription.first())
            .put("punctuationMode", preferences.punctuationMode.first())
            .put("punctuationPrompt", preferences.punctuationPrompt.first())
            .put("threadCount", preferences.threadCount.first())
            .put("keepAliveTimeoutMinutes", preferences.keepAliveTimeout.first())
            .put("subtitleChoiceTimeoutMinutes", preferences.subtitleChoiceTimeoutMinutes.first())
            .put("inferenceProvider", preferences.inferenceProvider.first())
            .put("transcriptionLanguage", preferences.transcriptionLanguage.first())
            .put("transcriptionBackend", backend)
            .put("activeModelPath", activeModelPath(backend) ?: JSONObject.NULL)
            .put("paths", paths)
            .put("summarizeEnabled", preferences.summarizeEnabled.first())
            .put("autoCopyEnabled", preferences.autoCopyEnabled.first())
            .put("signatureEnabled", preferences.signatureEnabled.first())
            .put("signatureText", preferences.signatureText.first())
            .put("signaturePosition", preferences.signaturePosition.first())
            .put("memoryProtection", preferences.memoryProtection.first())
            .put("compactResultActions", preferences.compactResultActions.first())
            .put("languageChipEnabled", preferences.languageChipEnabled.first())
            // TASK-575: read-only over the SPI (records are written by loads).
            .put("measuredModelMemory", preferences.measuredModelMemory.first().entries.joinToString(",") { e -> e.key + "=" + e.value.runs + "runs" })
            .put("advancedSharingEnabled", preferences.advancedSharingEnabled.first())
            .put("showRetranscribeButton", preferences.showRetranscribeButton.first())
            .put("groupLogsByConversation", preferences.groupLogsByConversation.first())
            .put("showTechnicalDetails", preferences.showTechnicalDetails.first())
            .put("vadAdvisoryDismissed", preferences.vadAdvisoryDismissed.first())
            .put("onboardingCompleted", preferences.onboardingCompleted.first())
            .put("swipeActionMode", preferences.swipeActionMode.first())
            .put("themePreference", preferences.themePreference.first())
            .put("textScalePreference", preferences.textScalePreference.first())
            .put("refinementEnabled", preferences.refinementEnabled.first())
            .put("speakerLabelsEnabled", preferences.speakerLabelsEnabled.first())
            .put("themeMode", preferences.themeMode.first())
            .put("defaultPrompt", preferences.defaultPrompt.first())
            .put("summaryPrompt", preferences.summaryPrompt.first())
            .put("outputFolderUri", preferences.outputFolderUri.first() ?: JSONObject.NULL)
            .put("transcriptExportFormat", preferences.transcriptExportFormat.first())
            .put("externalCatalogUrl", preferences.externalCatalogUrl.first())
            .toString()
    }

    /**
     * Saved path of the currently selected backend: the record's dir for
     * `external:` ids, the generic preference for llm, the keyed sherpa
     * preference otherwise (null for an unknown id; no fallback guessing).
     */
    private suspend fun activeModelPath(backend: String): String? = when {
        backend.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX) ->
            externalModels.records().firstOrNull { it.backendId == backend }?.dir
        BuiltInBackendIds.isLlm(backend) -> preferences.modelPath.first()
        else -> preferences.sherpaModelPath(backend).first()
    }

    /**
     * Boolean preferences as one table: every key shares the strict
     * true/false parse (a coerced "yes" would make a test run silently mean
     * false), and SET_KEYS derives from the map so a writable key can never
     * be missing from help.
     */
    private val booleanKeys: Map<String, suspend (Boolean) -> Unit> = mapOf(
        "vad" to preferences::saveVadEnabled,
        "progressive" to preferences::saveProgressiveTranscription,
        "summarize" to preferences::saveSummarizeEnabled,
        "refinement_enabled" to preferences::saveRefinementEnabled,
        "speaker_labels_enabled" to preferences::saveSpeakerLabelsEnabled,
        "auto_copy" to preferences::saveAutoCopyEnabled,
        "vad_advisory" to preferences::saveVadAdvisoryDismissed,
        "onboarding" to preferences::saveOnboardingCompleted,
        "group_logs" to preferences::saveGroupLogsByConversation,
        "advanced_sharing" to preferences::saveAdvancedSharingEnabled,
        "show_retranscribe" to preferences::saveShowRetranscribeButton,
        "memory_protection" to preferences::saveMemoryProtection,
        "compact_result_actions" to preferences::saveCompactResultActions,
        "technical_details" to preferences::saveShowTechnicalDetails,
        "language_chip" to preferences::saveLanguageChipEnabled,
        "signature_enabled" to preferences::saveSignatureEnabled,
    )

    /**
     * Enum-valued preferences, validated against the app's own option sets:
     * the app resolves several of these silently to a default (provider to
     * CPU, punctuation to AUTO), which would make a typo'd test run look
     * like a real one.
     */
    private val choiceKeys: Map<String, Pair<List<String>, suspend (String) -> Unit>> = mapOf(
        "punctuation" to Pair(PUNCTUATION_MODES, preferences::savePunctuationMode),
        "provider" to Pair(InferenceProvider.options, preferences::saveInferenceProvider),
        "swipe_action" to Pair(PreferencesManager.SWIPE_ACTION_MODES, preferences::saveSwipeActionMode),
        "theme" to Pair(THEME_TYPES, preferences::saveThemePreference),
        "theme_mode" to Pair(THEME_MODES, preferences::saveThemeMode),
        "text_scale" to Pair(TEXT_SCALES, preferences::saveTextScale),
        "signature_position" to Pair(PreferencesManager.SIGNATURE_POSITIONS, preferences::saveSignaturePosition),
        // GH #92: device tests flip the auto-save format over adb.
        "transcript_export_format" to Pair(
            SubtitleFormatter.Format.entries.map { it.name },
            preferences::saveTranscriptExportFormat,
        ),
    )

    /** Free-text preferences: written as given, no parse. */
    private val textKeys: Map<String, suspend (String) -> Unit> = mapOf(
        "punctuation_prompt" to preferences::savePunctuationPrompt,
        "default_prompt" to preferences::saveDefaultPrompt,
        "summary_prompt" to preferences::saveSummaryPrompt,
        "signature_text" to preferences::saveSignatureText,
        // TASK-643: blank clears the key (a saved default literal would become a
        // phantom override at the next version bump); any other value saves.
        "external_catalog_url" to { v ->
            if (v.isNullOrBlank()) preferences.clearExternalCatalogUrl()
            else preferences.saveExternalCatalogUrl(v)
        },
        // An unset SAF folder is null, not "": blank clears.
        "output_folder" to { preferences.saveOutputFolderUri(it.ifBlank { null }) },
        "language" to preferences::saveTranscriptionLanguage,
        "model_path" to preferences::saveModelPath,
    )

    /**
     * TASK-469: ONE dispatch table for op=set, built from the documented
     * typed tables plus the four validators that used to ride a hand-listed
     * SPECIAL_SET_KEYS and a `when` (a new branch could dispatch fine yet
     * vanish from help: the drift this map deletes by construction). Each
     * entry validates, writes, and returns an error message or null.
     */
    private val setDispatch: Map<String, suspend (value: String, entry: String?) -> String?> =
        buildMap {
            // Duplicate keys must fail construction, not silently overwrite:
            // an overlap between the tables would hand the key to whichever
            // put ran last, inverting the old dispatch precedence with no
            // signal anywhere (SET_KEYS dedupes, the doc test sees one row).
            fun putUnique(key: String, handler: suspend (value: String, entry: String?) -> String?) {
                val previous = putIfAbsent(key, handler)
                require(previous == null) {
                    "SPI set key '$key' defined twice; tables must stay disjoint"
                }
            }
            booleanKeys.forEach { (key, save) ->
                putUnique(key) { value, _ ->
                    val enabled = value.toBooleanStrictOrNull()
                        ?: return@putUnique "$key expects true or false, got '$value'"
                    save(enabled)
                    null
                }
            }
            choiceKeys.forEach { (key, spec) ->
                val (options, save) = spec
                putUnique(key) { value, _ ->
                    if (value !in options) {
                        return@putUnique "$key expects one of ${options.joinToString(", ")}, got '$value'"
                    }
                    save(value)
                    null
                }
            }
            // (key, unit suffix for the error message, saver). keep_alive's
            // TASK-451 rationale applies to all: any positive int honored
            // downstream, the dropdowns offer the curated sets.
            val positiveIntKeys = listOf(
                Triple("subtitle_timeout", "minutes", preferences::saveSubtitleChoiceTimeoutMinutes),
                Triple("keep_alive", "minutes", preferences::saveKeepAliveTimeout),
                // sherpa-onnx rejects num_threads < 1 at the native load.
                Triple("threads", "threads", preferences::saveThreadCount),
            )
            textKeys.forEach { (key, save) ->
                putUnique(key) { value, _ ->
                    save(value)
                    null
                }
            }
            // TASK-451: strictly positive; non-positive silently falls back to
            // the default in NativeKeepAlive.setTimeout while get would report
            // the stored value. Values outside the dropdown
            // (SettingsViewModel.timeoutOptions) are accepted on purpose: any
            // positive int is honored downstream, and a timing test may want 3.
            // TASK-515 (reuse review): the third positive-int validator
            // tipped the copy count; one typed table now serves all of them
            // (the TASK-469 shape: the table IS the dispatch).
            positiveIntKeys.forEach { (key, unit, save) ->
                putUnique(key) { value, _ ->
                    val n = value.toIntOrNull()
                    if (n == null || n <= 0) {
                        "$key expects a positive integer ($unit), got '$value'"
                    } else {
                        save(n)
                        null
                    }
                }
            }
            putUnique("backend") { value, _ ->
                if (!isKnownBackend(value)) {
                    "unknown backend '$value' (expected a catalog id, '${LlmTranscriptionBackend.BACKEND_ID}' " +
                        "or '${ExternalModelRecord.BACKEND_ID_PREFIX}<record id>')"
                } else {
                    preferences.saveTranscriptionBackend(value)
                    null
                }
            }
            putUnique("sherpa_path") { value, entry ->
                if (entry == null || entry !in BuiltInBackendIds.ALL) {
                    "sherpa_path requires entry=<catalog id> " +
                        "(${BuiltInBackendIds.ALL.joinToString(", ")}); got '${entry ?: "none"}'"
                } else {
                    preferences.saveSherpaModelPath(entry, value)
                    null
                }
            }
        }

    /** Every key accepted by op=set: the dispatch map IS the list (TASK-469). */
    val SET_KEYS: List<String> = setDispatch.keys.sorted()

    private suspend fun set(key: String?, value: String?, entry: String?): String {
        if (key == null) return setError("missing key extra")
        if (value == null) return setError("missing value extra for key '$key'")
        // Lookup and invocation must stay separate: a found handler whose
        // validation passes returns null, which must not collapse into the
        // unknown-key branch.
        val handler = setDispatch[key] ?: return setError("unknown key '$key'")
        val error = handler(value, entry)
        if (error != null) return setError(error)
        return setAck(key, value, entry)
    }

    /**
     * The three prompt savers persist a capped copy (PROMPT_CAP); every other
     * text key stores verbatim. The ack must mirror what the store keeps:
     * echoing a truncated value for a verbatim key would recreate the exact
     * ack-vs-readback divergence this exists to close (TASK-485).
     */
    private val promptCappedKeys = setOf("summary_prompt", "punctuation_prompt", "default_prompt")
    private fun setAck(key: String, value: String, entry: String?): String = JSONObject()
        .put("op", OP_SET)
        .put("key", key)
        .apply { if (key == "sherpa_path") put("entry", entry) }
        .put(
            "value",
            if (key in promptCappedKeys) {
                value.take(PreferencesManager.PROMPT_CAP)
            } else {
                value
            }
        )
        .toString()

    /**
     * Same rule as TaskerRequestReceiver.isKnownBackendId (llm + built-in ids +
     * the external: prefix; dangling external ids fail loudly downstream at
     * model load), keyed on [BuiltInBackendIds] instead of the catalog asset so
     * this class stays JVM-testable without BundledCatalog.attach. The catalog
     * is pinned to that id set by BundledModelCatalogTest, so both checks
     * accept the same ids.
     */
    private fun isKnownBackend(id: String): Boolean = BuiltInBackendIds.isSelectableBackendId(id)

    /** Every set error carries the full key list: a debugging tool should self-describe. */
    private fun setError(message: String): String = JSONObject()
        .put("op", OP_SET)
        .put("error", message)
        .put("supportedKeys", JSONArray(SET_KEYS))
        .toString()

    /**
     * ALL records, not just the valid ones: dangling entries (dir removed from
     * disk) are exactly what a debugging session needs to see. Each element is
     * the record's own persisted JSON ([ExternalModelRecord.toJson], which is
     * what the store serializes) plus the derived backendId.
     */
    private suspend fun records(): String {
        val list = externalModels.records()
        val array = JSONArray()
        for (record in list) {
            array.put(record.toJson().put("backendId", record.backendId))
        }
        return JSONObject()
            .put("op", OP_RECORDS)
            .put("count", list.size)
            .put("records", array)
            .toString()
    }

    /**
     * TASK-550 device pass: the external-model import, driven without UI. The
     * importer classifies the url (catalog-entry JSON vs HuggingFace repo),
     * exactly the path the import dialog uses; the response is the imported
     * record, so a device test can chain set backend=external:<id> on it.
     */
    private suspend fun importModel(url: String?, family: String?, modelType: String?): String {
        if (url.isNullOrBlank()) {
            throw IllegalArgumentException("missing 'url' extra")
        }
        // TASK-618: optional family override, because URL imports are
        // detect-then-tell by design (the UI dialog owns the chooser) and a
        // headless test must be able to make the same choice the user makes.
        // Errors are THROWN: handle()'s runCatching wrapper already renders
        // them as the {op, error} envelope (one construction site, not four).
        val familyOverride = family?.let { raw ->
            ModelFamily.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "unknown family '$raw' (use one of: " +
                        ModelFamily.entries.joinToString("/") { it.name } + ")")
        }
        val parsedFamily = familyOverride ?: ModelFamily.TRANSDUCER
        // Catalog-entry JSON carries its own family AND modelType; either
        // override would be silently dropped there, so say so instead of
        // diverging from the dialog path the SPI mirrors. Checked BEFORE the
        // pair validation: on an entry URL the override is the specific
        // error, not the pair incoherence (review round). The nullable
        // [familyOverride], not the resolved [parsedFamily], drives the
        // test: an EXPLICIT family=TRANSDUCER on an entry URL is still an
        // override the entry would drop (simplify round: the resolved
        // default made it indistinguishable from absent).
        if ((familyOverride != null || modelType != null) &&
            ExternalModelImporter.isCatalogEntryUrl(url)
        ) {
            throw IllegalArgumentException(
                "family/model_type overrides apply to repo URLs only; entry JSON carries its own")
        }
        // model_type carries the CTC subtype (the dialog's nemo/zipformer
        // selector). Validated against the resolved family HERE (review
        // round): the repo-URL import path never runs isValidModelType, and
        // an incoherent pair (family=TRANSDUCER with nemo_ctc) would persist
        // and native-exit at load, the exit(255) class, debug build or not.
        if (modelType != null && !ModelFamilySupport.isValidModelType(parsedFamily, modelType)) {
            throw IllegalArgumentException(
                "model_type '$modelType' is not valid for family ${parsedFamily.name}")
        }
        val record = importer.importFromUrl(url, modelType = modelType, family = parsedFamily)
        return JSONObject()
            .put("op", OP_IMPORT)
            .put("record", record.toJson().put("backendId", record.backendId))
            .toString()
    }

    /**
     * TASK-625 trial tool: posts the production memory-failure error
     * notification (the exact ResultNotificationFactory builder both error
     * surfaces use) so the Open-setting action can be exercised on device
     * without engineering a real out-of-memory failure. Debug receiver only.
     */
    private fun notifyMemoryError(): String {
        val ctx = appContext ?: error("notify_memory_error requires a Context (debug receiver only)")
        val factory = com.antivocale.app.service.ResultNotificationFactory(ctx)
        val message = ctx.getString(
            com.antivocale.app.R.string.model_load_low_memory, "1.2GB", "4.8GB")
        val id = com.antivocale.app.service.ResultNotificationFactory.nextNotificationId()
        ctx.getSystemService(android.app.NotificationManager::class.java)
            .notify(id, factory.errorNotification(message, memoryAction = true))
        return JSONObject()
            .put("op", OP_NOTIFY_MEMORY_ERROR)
            .put("posted", id)
            .toString()
    }

    private fun help(error: String? = null): String = JSONObject()
        .apply { error?.let { put("error", it) } }
        .put("op", OP_HELP)
        .put("ops", JSONArray(listOf(OP_GET, OP_SET, OP_RECORDS, OP_IMPORT, OP_NOTIFY_MEMORY_ERROR, OP_HELP)))
        .put("setKeys", JSONArray(SET_KEYS))
        .put(
            "usage",
            "am broadcast -a com.antivocale.app.TEST_SPI --es op=<$OP_GET|$OP_SET|$OP_RECORDS|$OP_IMPORT|$OP_HELP> " +
                "[--es key=<setKey> --es value=<newValue>] [--es entry=<catalogId> (sherpa_path only)] " +
                "[--es url=<entry-or-repo url> (import only)] [--es family=<ModelFamily> (import only, optional override)] "
            + "[--es model_type=<subtype> (import only, CTC: nemo_ctc/zipformer_ctc/omnilingual_ctc)]")
        .put(
            "transcription",
            "transcription is NOT triggered here: broadcast com.antivocale.app.PROCESS_REQUEST with extras " +
                "request_type=audio file_path=<appReadablePath> task_id=<id> [backend_id=<backend>] " +
                "(TaskerRequestReceiver)")
        .toString()

    companion object {
        const val OP_GET = "get"
        const val OP_SET = "set"
        const val OP_RECORDS = "records"
        const val OP_IMPORT = "import"
        const val OP_NOTIFY_MEMORY_ERROR = "notify_memory_error"
        const val OP_HELP = "help"

        /** TASK-276: the single source is PunctuationPolicy.MODE_PREFS; the SPI only adds write-time strictness. */
        val PUNCTUATION_MODES = PunctuationPolicy.MODE_PREFS

        /** Persisted as the enum names (SettingsViewModel.saveThemePreference/Mode). */
        val THEME_TYPES = ThemeType.entries.map { it.name }
        val TEXT_SCALES = TextScale.entries.map { it.name }
        val THEME_MODES = ThemeMode.entries.map { it.name }
    }
}
