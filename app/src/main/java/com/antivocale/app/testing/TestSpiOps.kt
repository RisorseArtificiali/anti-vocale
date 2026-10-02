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
import com.antivocale.app.transcription.RemoteOmnivoiceBackend
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
    /** TASK-736 device debugging: the RAM identity cache snapshot. Debug
     *  builds pass it; the main-source default keeps release construction
     *  unchanged. */
    private val identityCache: com.antivocale.app.receiver.VoiceNoteIdentityCache? = null,
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
            OP_IDENTITY_CACHE -> identityCache()
            OP_IMPORT -> importModel(url, family, modelType)
            OP_NOTIFY_MEMORY_ERROR -> notifyMemoryError()
            OP_CLIPBOARD -> clipboard()
            OP_NOTIFICATIONS -> notifications()
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
            .put("earlyPreviewEnabled", preferences.earlyPreviewEnabled.first())
            .put("interruptedRunNotifications", preferences.interruptedRunNotifications.first())
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
            .put("externalAutomationEnabled", preferences.externalAutomationEnabled.first())
            .put("voiceNoteIdentityEnabled", preferences.voiceNoteIdentityEnabled.first())
            // TASK-681: the LAN-offload config (endpoint visible for E2E
            // verification; the key is masked to its last 4 chars).
            .put("remoteOmnivoiceEnabled", preferences.remoteOmnivoiceEnabled.first())
            .put("remoteOmnivoiceEndpoint", preferences.remoteOmnivoiceEndpoint.first())
            .put("remoteOmnivoiceApiKeyMasked", preferences.remoteOmnivoiceApiKey.first()
                .takeLast(4).let { if (it.length < 4) "" else "****$it" })
            .put("remoteOmnivoiceModel", preferences.remoteOmnivoiceModel.first())
            .put("compactResultActions", preferences.compactResultActions.first())
            .put("languageChipEnabled", preferences.languageChipEnabled.first())
            // TASK-575: read-only over the SPI (records are written by loads).
            .put("measuredModelMemory", preferences.measuredModelMemory.first().entries.joinToString(",") { e -> e.key + "=" + e.value.runs + "runs" })
            // TASK-675: read-only over the SPI too (decodes write entries, a
            // manual selection clears them; device tests only need to read).
            .put("demotedBackends", JSONArray(preferences.demotedBackends.first()))
            .put("advancedSharingEnabled", preferences.advancedSharingEnabled.first())
            .put("showRetranscribeButton", preferences.showRetranscribeButton.first())
            .put("groupLogsByConversation", preferences.groupLogsByConversation.first())
            .put("showTechnicalDetails", preferences.showTechnicalDetails.first())
            .put("vadAdvisoryDismissed", preferences.vadAdvisoryDismissed.first())
            .put("onboardingCompleted", preferences.onboardingCompleted.first())
            // TASK-685: the seeded Models-filter favorite (null = untouched,
            // "" = cleared; device trials read the seed without UI scraping).
            .put("modelFilterLanguage", preferences.modelFilterLanguage.first() ?: JSONObject.NULL)
            .put("swipeActionMode", preferences.swipeActionMode.first())
            .put("themePreference", preferences.themePreference.first())
            .put("textScalePreference", preferences.textScalePreference.first())
            .put("refinementEnabled", preferences.refinementEnabled.first())
            .put("speakerLabelsEnabled", preferences.speakerLabelsEnabled.first())
            // TASK-670 (GH #83): the named-labels privacy gate, readable and
            // settable over the SPI so device trials can flip it.
            .put("speakerIdEnabled", preferences.speakerIdEnabled.first())
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
        // TASK-186: the early-preview gate (device trials flip it per clip).
        "early_preview" to preferences::saveEarlyPreviewEnabled,
        "interrupted_run_notifications" to preferences::saveInterruptedRunNotifications,
        "summarize" to preferences::saveSummarizeEnabled,
        "refinement_enabled" to preferences::saveRefinementEnabled,
        "speaker_labels_enabled" to preferences::saveSpeakerLabelsEnabled,
        // TASK-670: the named-labels privacy gate.
        "speaker_id_enabled" to preferences::saveSpeakerIdEnabled,
        "voice_note_identity_enabled" to preferences::saveVoiceNoteIdentityEnabled,
        "auto_copy" to preferences::saveAutoCopyEnabled,
        "vad_advisory" to preferences::saveVadAdvisoryDismissed,
        "onboarding" to preferences::saveOnboardingCompleted,
        "group_logs" to preferences::saveGroupLogsByConversation,
        "advanced_sharing" to preferences::saveAdvancedSharingEnabled,
        "show_retranscribe" to preferences::saveShowRetranscribeButton,
        "memory_protection" to preferences::saveMemoryProtection,
        // TASK-274: consent gate for the exported automation receivers.
        "external_automation" to preferences::saveExternalAutomationEnabled,
        // TASK-681: the LAN-offload gate (device E2E drives the whole
        // enable + configure + select sequence over the SPI).
        "remote_enabled" to preferences::saveRemoteOmnivoiceEnabled,
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
        // TASK-685 review R4: validated against the filter's offered entries
        // (a typo'd code would render an empty Models tab); blank is the
        // explicit clear and stays accepted.
        "model_filter_language" to Pair(
            com.antivocale.app.transcription.Language.FILTER_ENTRIES + "",
            preferences::saveModelFilterLanguage),
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
        // TASK-681: the LAN-offload config triple.
        "remote_endpoint" to preferences::saveRemoteOmnivoiceEndpoint,
        "remote_api_key" to preferences::saveRemoteOmnivoiceApiKey,
        "remote_model" to preferences::saveRemoteOmnivoiceModel,
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
                    "unknown backend '$value' (expected a catalog id, '${LlmTranscriptionBackend.BACKEND_ID}', " +
                        "'${RemoteOmnivoiceBackend.BACKEND_ID}' or '${ExternalModelRecord.BACKEND_ID_PREFIX}<record id>')"
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

    /**
     * TASK-649: keys whose handlers document blank-clear semantics. `am
     * broadcast --es value ""` DROPS the empty extra at the shell layer, so
     * the value arrives null; for these keys that null IS the intentional
     * blank (the documented way to clear), not a malformed broadcast.
     */
    private val blankClearingKeys = setOf(
        "external_catalog_url", "output_folder", "signature_text",
        "punctuation_prompt", "default_prompt", "summary_prompt",
    )

    private suspend fun set(key: String?, value: String?, entry: String?): String {
        if (key == null) return setError("missing key extra")
        val effectiveValue = if (value == null && key in blankClearingKeys) "" else value
        if (effectiveValue == null) return setError("missing value extra for key '$key'")
        val value = effectiveValue
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
     * TASK-736: the voice-note identity cache (RAM, names included - this
     * is the explicit adb inspection surface the E2E debugging needed; the
     * field report came back "no label" and nothing else could tell a
     * never-cached note from a duration-mismatch refusal).
     */
    private fun identityCache(): String {
        val cache = identityCache ?: return JSONObject()
            .put("op", OP_IDENTITY_CACHE)
            .put("error", "cache not wired")
            .toString()
        val array = JSONArray()
        for (note in cache.snapshot()) {
            array.put(
                JSONObject()
                    .put("package", note.packageName)
                    .put("sender", note.sender)
                    .put("durationSeconds", note.durationSeconds)
                    .put("postedAtMs", note.postedAtMs))
        }
        return JSONObject()
            .put("op", OP_IDENTITY_CACHE)
            .put("accepting", cache.accepting)
            .put("count", array.length())
            .put("entries", array)
            .toString()
    }

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
        val ctx = requireContext("notify_memory_error")
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

    /**
     * TASK-275/688 trial tool: reads the primary clip (label + text) so a
     * device trial verifies a copy EXACTLY, with no paste-into-a-field
     * proxy. Android 10+ lets only the focused app read the clipboard, so
     * the app must be foreground AND window-focused when the broadcast
     * lands (after a notification-action copy, am start the app first and
     * allow a beat); a read without focus is DENIED SILENTLY and answers
     * null clip text plus a note saying so, so a trial distinguishes
     * "retry after focusing" from an empty clipboard only by the note:
     * treat a nulled text with the note as NOT VERIFIED, never as a
     * failed copy. The text is capped (a repetition-loop clip would blow
     * the binder result channel, the TASK-506 class) with a truncated
     * flag. Debug receiver only; appContext is null under the shared unit
     * fakes.
     */
    private fun clipboard(): String {
        val ctx = requireContext("clipboard")
        val clip = ctx.getSystemService(android.content.ClipboardManager::class.java).primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(ctx)?.toString()
        return JSONObject()
            .put("op", OP_CLIPBOARD)
            .put("label", clip?.description?.label?.toString() ?: JSONObject.NULL)
            .put(
                "text",
                if (text == null) JSONObject.NULL else text.take(CLIPBOARD_TEXT_CAP))
            .put("textTruncated", text != null && text.length > CLIPBOARD_TEXT_CAP)
            .put(
                "note",
                if (clip == null) "no clip visible: either the clipboard is empty or the read was denied (app not focused)" else JSONObject.NULL)
            .toString()
    }

    /**
     * TASK-684/688 trial tool: lists THIS app's active notifications (id,
     * channel, title, text, bigText, action TITLES). Reading them from
     * the shade is a UI-driving trap (DND intercepts, heads-ups reorder,
     * the tree renders inconsistently); NotificationManager
     * .getActiveNotifications returns our own package's records with no
     * permission, so trials read notification CONTENT here and reserve
     * the shade for real visual checks. The Realme 3-button cap makes
     * action COMPOSITION the load-bearing fact, hence titles not a
     * count; text and bigText are capped per item (the receiver ships
     * the JSON over binder, the TASK-506 wall) and the collapsed
     * EXTRA_TEXT of a paged result notification is only one page, so the
     * full form rides alongside from EXTRA_BIG_TEXT.
     */
    private fun notifications(): String {
        val ctx = requireContext("notifications")
        val array = JSONArray()
        ctx.getSystemService(android.app.NotificationManager::class.java)
            .activeNotifications
            .sortedBy { it.id }
            .forEach { status ->
                val extras = status.notification.extras
                fun capped(key: String, value: CharSequence?): Any {
                    val s = value?.toString() ?: return JSONObject.NULL
                    return if (s.length <= NOTIFICATION_TEXT_CAP) s
                    else JSONObject().put("truncated", true).put(key, s.take(NOTIFICATION_TEXT_CAP))
                }
                array.put(
                    JSONObject()
                        .put("id", status.id)
                        .put("channel", status.notification.channelId ?: JSONObject.NULL)
                        .put(
                            "actions",
                            JSONArray(
                                status.notification.actions.orEmpty().map { it.title?.toString() ?: "" }))
                        .put("title", extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: JSONObject.NULL)
                        .put("text", capped("text", extras.getCharSequence(android.app.Notification.EXTRA_TEXT)))
                        .put("bigText", capped("bigText", extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT))))
            }
        return JSONObject().put("op", OP_NOTIFICATIONS).put("count", array.length()).put("items", array).toString()
    }

    /** One guard for the context-requiring ops (message and rationale live here once). */
    private fun requireContext(op: String): android.content.Context =
        appContext ?: error("$op requires a Context (debug receiver only)")

    private fun help(error: String? = null): String = JSONObject()
        .apply { error?.let { put("error", it) } }
        .put("op", OP_HELP)
        // OPS single-sources the enumeration: the array and the usage line
        // below both read it, so a new op cannot dispatch fine yet stay
        // unlisted in one of them (the drift TASK-469 deleted for set keys).
        .put("ops", JSONArray(OPS))
        .put("setKeys", JSONArray(SET_KEYS))
        .put(
            "usage",
            "am broadcast -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver " +
                "-a com.antivocale.app.TEST_SPI --es op=<${OPS.joinToString("|")}> " +
                "[--es key=<setKey> --es value=<newValue>] [--es entry=<catalogId> (sherpa_path only)] " +
                "[--es url=<entry-or-repo url> (import only)] [--es family=<ModelFamily> (import only, optional override)] "
            + "[--es model_type=<subtype> (import only, CTC: nemo_ctc/zipformer_ctc/omnilingual_ctc/paraformer)]")
        .put(
            "transcription",
            "transcription is NOT triggered here: broadcast com.antivocale.app.PROCESS_REQUEST with extras " +
                "request_type=audio file_path=<appReadablePath> task_id=<id> [backend_id=<backend>] " +
                "(TaskerRequestReceiver). TASK-274: that receiver is gated by the external_automation " +
                "consent toggle (default OFF): set key=external_automation value=true first or every " +
                "request answers STATUS_ERROR")
        .toString()

    companion object {
        const val OP_GET = "get"
        const val OP_SET = "set"
        const val OP_RECORDS = "records"
        internal const val OP_IDENTITY_CACHE = "identity_cache"
        const val OP_IMPORT = "import"
        const val OP_NOTIFY_MEMORY_ERROR = "notify_memory_error"
        const val OP_CLIPBOARD = "clipboard"
        const val OP_NOTIFICATIONS = "notifications"
        const val OP_HELP = "help"

        /**
         * Every op handle() dispatches; help() renders the array and the
         * usage line from this one list. The receiver's op=nav (TASK-486)
         * is deliberately absent: it is intercepted receiver-side before
         * handle() runs, so it lives in the receiver's own table.
         */
        val OPS = listOf(OP_GET, OP_SET, OP_RECORDS, OP_IDENTITY_CACHE, OP_IMPORT, OP_NOTIFY_MEMORY_ERROR, OP_CLIPBOARD, OP_NOTIFICATIONS, OP_HELP)

        /** clipboard op cap: keeps the result string far under the binder limit (TASK-506 class). */
        const val CLIPBOARD_TEXT_CAP = 64 * 1024

        /** notifications op per-item cap, same binder rationale; generous for a paged transcript. */
        const val NOTIFICATION_TEXT_CAP = 64 * 1024

        /** TASK-276: the single source is PunctuationPolicy.MODE_PREFS; the SPI only adds write-time strictness. */
        val PUNCTUATION_MODES = PunctuationPolicy.MODE_PREFS

        /** Persisted as the enum names (SettingsViewModel.saveThemePreference/Mode). */
        val THEME_TYPES = ThemeType.entries.map { it.name }
        val TEXT_SCALES = TextScale.entries.map { it.name }
        val THEME_MODES = ThemeMode.entries.map { it.name }
    }
}
