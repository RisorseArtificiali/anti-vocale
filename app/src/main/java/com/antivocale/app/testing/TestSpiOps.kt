package com.antivocale.app.testing

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.InferenceProvider
import com.antivocale.app.transcription.LlmTranscriptionBackend
import com.antivocale.app.transcription.PunctuationPolicy
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

    private suspend fun set(key: String?, value: String?, entry: String?): String {
        if (key == null) return setError("missing key extra")
        if (value == null) return setError("missing value extra for key '$key'")
        when (key) {
            "vad" -> {
                val enabled = value.toBooleanStrictOrNull()
                    ?: return setError("vad expects true or false, got '$value'")
                preferences.saveVadEnabled(enabled)
            }
            // Same strict boolean as vad: this toggle gates the interim
            // chunk notifications and the chunk nav.
            "progressive" -> {
                val enabled = value.toBooleanStrictOrNull()
                    ?: return setError("progressive expects true or false, got '$value'")
                preferences.saveProgressiveTranscription(enabled)
            }
            // TASK-276: the punctuation pass mode matches the settings
            // dropdown's exact set; anything else would silently run as AUTO.
            "punctuation" -> {
                if (value !in PUNCTUATION_MODES) {
                    return setError("punctuation expects one of ${PUNCTUATION_MODES.joinToString(", ")}, got '$value'")
                }
                preferences.savePunctuationMode(value)
            }
            "punctuation_prompt" -> preferences.savePunctuationPrompt(value)
            // TASK-451: strictly positive; non-positive silently falls back to
            // the default in NativeKeepAlive.setTimeout while get would report
            // the stored value. Values outside the dropdown
            // (SettingsViewModel.timeoutOptions) are accepted on purpose: any
            // positive int is honored downstream, and a timing test may want 3.
            "keep_alive" -> {
                val minutes = value.toIntOrNull()
                if (minutes == null || minutes <= 0) {
                    return setError("keep_alive expects a positive integer (minutes), got '$value'")
                }
                preferences.saveKeepAliveTimeout(minutes)
            }
            "threads" -> {
                val threads = value.toIntOrNull()
                    ?: return setError("threads expects an integer, got '$value'")
                preferences.saveThreadCount(threads)
            }
            "provider" -> {
                // Strict on purpose: the app silently resolves unknown providers
                // to CPU (InferenceProvider.resolve), which would make a typo'd
                // test run look like it used a real provider.
                if (value !in InferenceProvider.options) {
                    return setError(
                        "provider expects one of ${InferenceProvider.options.joinToString(", ")}, got '$value'")
                }
                preferences.saveInferenceProvider(value)
            }
            "backend" -> {
                if (!isKnownBackend(value)) {
                    return setError(
                        "unknown backend '$value' (expected a catalog id, '${LlmTranscriptionBackend.BACKEND_ID}' " +
                            "or '${ExternalModelRecord.BACKEND_ID_PREFIX}<record id>')")
                }
                preferences.saveTranscriptionBackend(value)
            }
            "language" -> preferences.saveTranscriptionLanguage(value)
            "model_path" -> preferences.saveModelPath(value)
            "sherpa_path" -> {
                if (entry == null || entry !in BuiltInBackendIds.ALL) {
                    return setError(
                        "sherpa_path requires entry=<catalog id> " +
                            "(${BuiltInBackendIds.ALL.joinToString(", ")}); " +
                            "got '${entry ?: "none"}'")
                }
                preferences.saveSherpaModelPath(entry, value)
            }
            else -> return setError("unknown key '$key'")
        }
        return JSONObject()
            .put("op", OP_SET)
            .put("key", key)
            .apply { if (key == "sherpa_path") put("entry", entry) }
            .put("value", value)
            .toString()
    }

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

        /** Every key accepted by `op=set`, in help order. */
        val SET_KEYS = listOf("vad", "progressive", "punctuation", "punctuation_prompt", "keep_alive", "threads", "provider", "backend", "language", "model_path", "sherpa_path")

        /** TASK-276: the single source is PunctuationPolicy.MODE_PREFS; the SPI only adds write-time strictness. */
        val PUNCTUATION_MODES = PunctuationPolicy.MODE_PREFS
    }
}
