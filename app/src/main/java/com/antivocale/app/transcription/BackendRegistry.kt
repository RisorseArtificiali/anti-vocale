package com.antivocale.app.transcription

import com.antivocale.app.R
import android.content.Context
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelRecordsProvider
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.data.catalog.CatalogDisplay
import com.antivocale.app.data.catalog.CatalogStringKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Immutable metadata about one transcription backend, tying together the two
 * independent identifier schemes the app currently dispatches on:
 *
 *  - the [backendId] string ([TranscriptionBackend.id] / the persisted
 *    `transcriptionBackend` preference),
 *  - the [shareAlias] manifest activity-alias used to route share targets
 *    (single source since TASK-323: ShareReceiverActivity and ShareTargetManager
 *    resolve it through the registry; the manifest activity-alias android:name
 *    attributes themselves stay literal strings).
 *
 * It also carries the saved-model-path preference accessors and the
 * display-name derivation, so the parallel `when` blocks can collapse into
 * registry lookups. The old bookkeeping [ExtractionService.ModelType] enum is
 * gone (consolidation): sherpa-onnx dispatch now keys on the [backendId] string
 * everywhere (service, orchestrator, view model).
 *
 * The five sherpa-onnx static descriptors are built from the bundled catalog
 * (share alias, streaming flag, fixed display-name resource and the path-derived
 * display name all come from the catalog entry), so the registry cannot drift
 * from the catalog; the LLM backend (LiteRT, not a catalog entry) stays
 * hand-written here by design.
 *
 * Display-name contract: if [displayNameResId] is non-null the backend has a
 * fixed localized name (`context.getString(displayNameResId)`); otherwise call
 * [deriveDisplayName] with the saved model path — a variant title resolved from
 * the catalog for entries without an explicit display (Whisper, Qwen3-ASR), else
 * the model file name. This is the single implementation of the derivations
 * (ActiveModelRepository consumes it since TASK-321).
 *
 * Backend-id VALIDATION site (TASK-394): TaskerRequestReceiver.isKnownBackendId
 * accepts EXTRA_BACKEND_ID values against "llm" + BundledCatalog ids + the
 * external: prefix (the registry is Hilt-scoped and the receiver has no
 * injection). A new static backend outside the catalog must update that
 * validation too or Tasker overrides will reject it as unknown.
 */
data class BackendDescriptor(
    /** Value of the backend's `BACKEND_ID` companion constant (e.g. "sherpa-onnx"). */
    val backendId: String,

    /**
     * Share-target activity-alias for this backend: the manifest
     * activity-alias class name that routes shared audio to it. Single
     * source since TASK-323 (ShareReceiverActivity's backendIdForAlias and
     * ShareTargetManager resolve it here); the manifest android:name
     * attributes cannot reference runtime values and stay literal strings,
     * pinned by BackendRegistryTest. Blank is a valid sentinel: backends with
     * no share target carry "", and ShareTargetManager skips them during
     * component sync.
     */
    val shareAlias: String,

    /** True for the streaming recognizer backend (Nemotron); all others are batch. */
    val isStreaming: Boolean = false,

    /**
     * Cold-start speed estimate: audio-seconds processed per compute-second.
     * Used ONLY when TranscriptionCalibrator has fewer than 2 samples for this
     * model on this device (AudioDurationPolicy.resolveEstimateMsPerSec tiers).
     * The conservative default of 1f overestimates time, never underestimates.
     */
    val rtfEstimate: Float = 1f,

    /** Dedicated localized display name, or null when the name derives from the model path. */
    val displayNameResId: Int? = null,

    /**
     * Derives the user-visible model name from the saved model path.
     * Used when [displayNameResId] is null; the [Context] supplies localized
     * variant titles where the catalog resolves one.
     */
    val deriveDisplayName: (context: Context, path: String) -> String = { _, path -> File(path).name },

    /** Saved model-path preference flow for this backend. */
    val modelPathFlow: (PreferencesManager) -> Flow<String?>,

    /** Persists this backend's model path preference. */
    val saveModelPath: suspend (PreferencesManager, String) -> Unit,

    /** Clears this backend's model path preference. */
    val clearModelPath: suspend (PreferencesManager) -> Unit,
)

/**
 * Single source of truth for transcription-backend metadata: the ordered list
 * of [BackendDescriptor]s plus lookups by backend-id and share alias.
 *
 * The list is the static six plus dynamic descriptors derived from the
 * external model store (spec: external models platform v2a): every valid
 * [ExternalModelRecord] yields one descriptor appended after the static
 * backends. The registry is therefore NO LONGER STATELESS, and the
 * construction assumptions built on statelessness are retired: consumers
 * must use the injected singleton (or resolve it via an entry point), never
 * a privately constructed or companion-held instance. Only DI assembles the
 * store+provider pair; extra instances would add duplicate records
 * collectors and split store mutations across racing read-modify-write
 * domains that can lose updates.
 *
 * Dispatch-site status (the sherpa-onnx consolidation removed the bookkeeping
 * [ExtractionService.ModelType] enum; all sites now key on backend-id strings):
 *  - [com.antivocale.app.data.ActiveModelRepository] — descriptor's model-path
 *    flow + display-name derivation; GGUF and unknown ids keep their legacy
 *    fallbacks locally (ggufModelPath / generic modelPath)
 *  - [com.antivocale.app.transcription.TranscriptionOrchestrator] — backend
 *    load keys on the [backendId]; its calibration display-name derivation is a
 *    string-keyed when (BACKEND_ID constants) that keeps its own dir-name
 *    semantics (see TranscriptionOrchestratorTest)
 *  - the share-target sites ([ShareReceiverActivity].backendIdForAlias via
 *    byShareAlias, [ShareTargetManager] component sync + has-model check)
 *  - [com.antivocale.app.ui.viewmodel.LogsViewModel] (re-transcribe picker)
 *  - [com.antivocale.app.ui.viewmodel.SettingsViewModel].loadCurrentModel
 *    (via ActiveModelRepository's activeModelFlow)
 *  - [com.antivocale.app.ui.viewmodel.ModelViewModel] (generic catalog layer)
 *  - [com.antivocale.app.service.ExtractionService] (downloads keyed by entry id)
 *
 * Deliberately not registered: the disabled GGUF backend (`gemma4_gguf`). It
 * has no BACKEND_ID constant and its manager is disabled (see the commented-out
 * provider in [com.antivocale.app.di.TranscriptionModule]); follow-up: give it
 * a BACKEND_ID and a descriptor if it is ever re-enabled.
 */
@Singleton
class BackendRegistry @Inject constructor(
    private val externalModelStore: ExternalModelStore,
    private val recordsProvider: ExternalModelRecordsProvider,
) {

    /** The six enabled static backends in canonical order (default backend first). */
    private val staticBackends: List<BackendDescriptor> by lazy {
        buildList {
            for (entryId in BuiltInBackendIds.ALL) {
                add(catalogDescriptor(entryId))
            }
            add(llmDescriptor())
        }
    }

    /**
     * The five sherpa-onnx backends are built from their bundled catalog entries so
     * the registry cannot drift from the catalog: the share alias, the streaming
     * flag (catalog `runtime`), the fixed display-name resource (the catalog
     * `display` resource-key resolved via [CatalogStringKeys]) and the
     * path-derived variant title (the catalog variant titles) all come from the
     * entry. The saved-path preference is the generic keyed accessor
     * ([PreferencesManager.sherpaModelPath]).
     *
     * Lazy because the registry is constructed before
     * [com.antivocale.app.BridgeApplication] calls BundledCatalog.attach(); the
     * catalog is only read on the first [backends] access, which in the app always
     * happens after attach.
     */
    private fun catalogDescriptor(entryId: String): BackendDescriptor {
        val entry = requireNotNull(BundledCatalog.byId(entryId)) {
            "catalog missing entry '$entryId' for backend registry"
        }
        // Cold-start RTF: Parakeet TDT is roughly 15x real time on a mid-range SoC;
        // the other offline sherpa families cluster around 4x. Calibrator samples
        // replace these after two runs on the actual device.
        val rtf = if (entry.id == BuiltInBackendIds.PARAKEET) 15f else 4f
        return BackendDescriptor(
            backendId = entry.id,
            shareAlias = entry.shareAlias,
            isStreaming = entry.isStreaming,
            rtfEstimate = rtf,
            displayNameResId = when {
                entry.hasExplicitDisplay && entry.display is CatalogDisplay.Resource ->
                    CatalogStringKeys.resolve(entry.display.key)
                else -> null
            },
            deriveDisplayName = { context, path ->
                SherpaModelManager.of(entry.id).detectVariant(File(path).name)
                    ?.let { variantName -> context.getString(CatalogVariantUi.of(entry.id, variantName).titleResId) }
                    ?: File(path).name
            },
            modelPathFlow = { it.sherpaModelPath(entry.id) },
            saveModelPath = { prefs, path -> prefs.saveSherpaModelPath(entry.id, path) },
            clearModelPath = { it.clearSherpaModelPath(entry.id) },
        )
    }

    private fun llmDescriptor(): BackendDescriptor = BackendDescriptor(
        backendId = LlmTranscriptionBackend.BACKEND_ID,
        shareAlias = "com.antivocale.app.ShareGemma",
        // Fixed localized label for parity with the other static backends: without
        // it, path-derived labels leak file names into the retranscribe picker.
        displayNameResId = R.string.llm_backend_name,
        // LLM decoding is near-real-time at best on device: 1f overestimates the
        // wait, which is the safe direction for a cold-start dialog estimate.
        rtfEstimate = 1f,
        // The LLM backend stores its model path in the generic preference.
        modelPathFlow = { it.modelPath },
        saveModelPath = { prefs, path -> prefs.saveModelPath(path) },
        clearModelPath = { it.clearModelPath() },
    )

    /** Static backends first (canonical order), then one descriptor per valid external record. */
    val backends: List<BackendDescriptor>
        get() = staticBackends + recordsProvider.records.value.map(::descriptorFor)

    /**
     * Derives one dynamic descriptor per imported record. Identity is the
     * record's uuid (backendId `external:<id>`), not a path preference:
     * saving a model path redirects the record's dir, clearing it deletes
     * the record.
     */
    private fun descriptorFor(record: ExternalModelRecord): BackendDescriptor = BackendDescriptor(
        backendId = record.backendId,
        shareAlias = "",  // spec: the ShareExternal family alias is synced separately
        // External families get the conservative cluster default: the catalog
        // Parakeet exception cannot be detected from ModelFamily alone.
        rtfEstimate = 4f,
        deriveDisplayName = { _, _ -> record.displayName },
        // The store (not the registry) owns the records JSON: the path flow derives
        // from its decoded list instead of a second raw-preference decoder here.
        modelPathFlow = { _ ->
            externalModelStore.recordsFlow.map { records ->
                records.firstOrNull { it.id == record.id }?.dir } },
        saveModelPath = { _, path ->
            // Identity is the uuid, not a path preference: a save redirects the record's
            // dir via a targeted update, so the captured snapshot reverts nothing else.
            externalModelStore.updateDir(record.id, path) },
        clearModelPath = { externalModelStore.delete(record.id) },
    )

    // The lookups recompute over backends per call: the lists are tiny and the
    // dynamic set can change between calls, so caching a map would go stale.

    /** Returns the descriptor for [backendId], or null if unknown (including null/blank). */
    fun byBackendId(backendId: String?): BackendDescriptor? =
        backendId?.let { id -> backends.firstOrNull { it.backendId == id } }

    /**
     * Returns the descriptor for a share-target [alias], or null if unknown
     * (including null/blank).
     */
    fun byShareAlias(alias: String?): BackendDescriptor? =
        alias?.let { a -> backends.firstOrNull { it.shareAlias == a } }
}