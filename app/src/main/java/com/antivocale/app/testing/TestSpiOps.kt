package com.antivocale.app.testing

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.InferenceProvider
import com.antivocale.app.transcription.LlmTranscriptionBackend
import com.antivocale.app.transcription.PunctuationPolicy
import com.antivocale.app.ui.theme.ThemeMode
import com.antivocale.app.ui.theme.ThemeType
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
) {

    suspend fun handle(
        op: String?,
        key: String? = null,
        value: String? = null,
        entry: String? = null,
    ): String = runCatching {
        when (op) {
            OP_GET -> get()
            OP_SET -> set(key, value, entry)
            OP_RECORDS -> records()
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
            .put("inferenceProvider", preferences.inferenceProvider.first())
            .put("transcriptionLanguage", preferences.transcriptionLanguage.first())
            .put("transcriptionBackend", backend)
            .put("activeModelPath", activeModelPath(backend) ?: JSONObject.NULL)
            .put("paths", paths)
            .put("summarizeEnabled", preferences.summarizeEnabled.first())
            .put("autoCopyEnabled", preferences.autoCopyEnabled.first())
            .put("forceModelLoad", preferences.forceModelLoad.first())
            .put("compactResultActions", preferences.compactResultActions.first())
            .put("advancedSharingEnabled", preferences.advancedSharingEnabled.first())
            .put("showRetranscribeButton", preferences.showRetranscribeButton.first())
            .put("groupLogsByConversation", preferences.groupLogsByConversation.first())
            .put("vadAdvisoryDismissed", preferences.vadAdvisoryDismissed.first())
            .put("onboardingCompleted", preferences.onboardingCompleted.first())
            .put("swipeActionMode", preferences.swipeActionMode.first())
            .put("themePreference", preferences.themePreference.first())
            .put("themeMode", preferences.themeMode.first())
            .put("defaultPrompt", preferences.defaultPrompt.first())
            .put("summaryPrompt", preferences.summaryPrompt.first())
            .put("outputFolderUri", preferences.outputFolderUri.first() ?: JSONObject.NULL)
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
        backend == LlmTranscriptionBackend.BACKEND_ID -> preferences.modelPath.first()
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
        "auto_copy" to preferences::saveAutoCopyEnabled,
        "vad_advisory" to preferences::saveVadAdvisoryDismissed,
        "onboarding" to preferences::saveOnboardingCompleted,
        "group_logs" to preferences::saveGroupLogsByConversation,
        "advanced_sharing" to preferences::saveAdvancedSharingEnabled,
        "show_retranscribe" to preferences::saveShowRetranscribeButton,
        "force_model_load" to preferences::saveForceModelLoad,
        "compact_result_actions" to preferences::saveCompactResultActions,
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
    )

    /** Free-text preferences: written as given, no parse. */
    private val textKeys: Map<String, suspend (String) -> Unit> = mapOf(
        "punctuation_prompt" to preferences::savePunctuationPrompt,
        "default_prompt" to preferences::saveDefaultPrompt,
        "summary_prompt" to preferences::saveSummaryPrompt,
        "external_catalog_url" to preferences::saveExternalCatalogUrl,
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
            putUnique("keep_alive") { value, _ ->
                val minutes = value.toIntOrNull()
                if (minutes == null || minutes <= 0) {
                    "keep_alive expects a positive integer (minutes), got '$value'"
                } else {
                    preferences.saveKeepAliveTimeout(minutes)
                    null
                }
            }
            putUnique("threads") { value, _ ->
                // Positive only: sherpa-onnx rejects num_threads < 1 at
                // recognizer load, and 0 would brick the next cold start.
                val threads = value.toIntOrNull()
                if (threads == null || threads <= 0) {
                    "threads expects a positive integer, got '$value'"
                } else {
                    preferences.saveThreadCount(threads)
                    null
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

    private fun help(error: String? = null): String = JSONObject()
        .apply { error?.let { put("error", it) } }
        .put("op", OP_HELP)
        .put("ops", JSONArray(listOf(OP_GET, OP_SET, OP_RECORDS, OP_HELP)))
        .put("setKeys", JSONArray(SET_KEYS))
        .put(
            "usage",
            "am broadcast -a com.antivocale.app.TEST_SPI --es op=<$OP_GET|$OP_SET|$OP_RECORDS|$OP_HELP> " +
                "[--es key=<setKey> --es value=<newValue>] [--es entry=<catalogId> (sherpa_path only)]")
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
        const val OP_HELP = "help"

        /** TASK-276: the single source is PunctuationPolicy.MODE_PREFS; the SPI only adds write-time strictness. */
        val PUNCTUATION_MODES = PunctuationPolicy.MODE_PREFS

        /** Persisted as the enum names (SettingsViewModel.saveThemePreference/Mode). */
        val THEME_TYPES = ThemeType.entries.map { it.name }
        val THEME_MODES = ThemeMode.entries.map { it.name }
    }
}
