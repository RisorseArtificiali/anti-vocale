package com.antivocale.app.ui.viewmodel

import android.content.Context
import android.util.Log
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.HuggingFaceTokenManager
import com.antivocale.app.BuildConfig
import com.antivocale.app.data.ModelDownloader
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.di.ApplicationScope
import com.antivocale.app.data.ShareTargetManager
import com.antivocale.app.data.ExternalModelImportOperations
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalCatalog
import com.antivocale.app.data.ExternalCatalogRepository
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.LitertLmFile
import com.antivocale.app.data.LitertLmUrlImporter
import com.antivocale.app.data.ModelFamily
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.CatalogVariantUi
import com.antivocale.app.transcription.LlmTranscriptionBackend
import com.antivocale.app.transcription.SherpaModelDownloader
import com.antivocale.app.transcription.SherpaModelManager
import com.antivocale.app.transcription.TranscriptionLanguagePolicy
import com.antivocale.app.transcription.cleanOrphanedModelDirs
import com.antivocale.app.R
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.download.DownloadConfig
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.data.download.ResumeDownloadHelper
import com.antivocale.app.manager.LlmManager
import com.antivocale.app.service.ExtractionService
import com.antivocale.app.transcription.TranscriptionBackendManager
import com.antivocale.app.transcription.BackendConfig
import com.antivocale.app.transcription.InferenceProvider
import com.antivocale.app.benchmark.BenchmarkManager
import com.antivocale.app.benchmark.BenchmarkState
import com.antivocale.app.util.DeviceCompatibility
import com.antivocale.app.util.LocaleManager
import com.antivocale.app.util.formatFileSize
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@HiltViewModel
class ModelViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val activeModelRepository: ActiveModelRepository,
    private val tokenManager: HuggingFaceTokenManager,
    private val benchmarkManager: BenchmarkManager,
    private val backendManager: TranscriptionBackendManager,
    private val llmManager: LlmManager,
    private val shareTargetManager: ShareTargetManager,
    @ApplicationContext private val ctx: Context,
    private val backendRegistry: BackendRegistry,
    private val externalModelStore: ExternalModelStore,
    private val externalModelImporter: ExternalModelImportOperations,
    private val litertLmUrlImporter: LitertLmUrlImporter,
    private val externalCatalogRepository: ExternalCatalogRepository,
    // Process-lifetime scope for share-alias sync work (code review 2026-09-03):
    // on viewModelScope, a ViewModel clear mid-sync (DataStore reads + PackageManager
    // IPCs) killed the enablement and the affected model stayed MISSING from
    // share sheets until the next cold start. The application scope survives.
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    val tokenState = tokenManager.tokenState

    // ---- External catalog (TASK-401 catalog-URL architecture) ----

    /** UI state of the "Import from catalog" dialog. */
    sealed interface CatalogUiState {
        data object Loading : CatalogUiState
        data class Ready(
            val entries: List<ExternalCatalog.CatalogEntry>,
            /** True when the source is a user override rather than the official index. */
            val isOverride: Boolean,
            /** Human hint about HOW the index was resolved (remote/cached/bundled). */
            val offline: Boolean,
        ) : CatalogUiState
        data class Error(val message: String) : CatalogUiState
    }

    private val _catalogUiState = MutableStateFlow<CatalogUiState>(CatalogUiState.Loading)
    val catalogUiState: StateFlow<CatalogUiState> = _catalogUiState.asStateFlow()

    private var catalogLoadedForUrl: String? = null

    /** Loads (or reloads) the catalog from the configured source url. */
    fun loadExternalCatalog(force: Boolean = false) {
        viewModelScope.launch {
            val url = preferencesManager.externalCatalogUrl.first()
            if (!force && catalogLoadedForUrl == url &&
                _catalogUiState.value is CatalogUiState.Ready) return@launch
            _catalogUiState.value = CatalogUiState.Loading
            externalCatalogRepository.load().fold(
                onSuccess = { state ->
                    catalogLoadedForUrl = url
                    _catalogUiState.value = CatalogUiState.Ready(
                        entries = state.entries,
                        isOverride = when (val source = state.source) {
                            is ExternalCatalogRepository.Source.Remote -> source.isOverride
                            is ExternalCatalogRepository.Source.Cached -> source.isOverride
                            ExternalCatalogRepository.Source.BundledAsset -> false
                        },
                        offline = state.source !is ExternalCatalogRepository.Source.Remote,
                    )
                },
                onFailure = { e ->
                    Log.e(TAG, "catalog load failed", e)
                    _catalogUiState.value = CatalogUiState.Error(e.message ?: "unknown error")
                })
        }
    }

    /** Validates and persists a catalog override; reloads on success. */
    fun saveExternalCatalogUrl(url: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            if (!externalCatalogRepository.validateOverride(url)) {
                onResult(false)
                return@launch
            }
            preferencesManager.saveExternalCatalogUrl(url)
            loadExternalCatalog(force = true)
            onResult(true)
        }
    }

    /** Restores the official index as the source. */
    fun resetExternalCatalogUrl() {
        viewModelScope.launch {
            preferencesManager.saveExternalCatalogUrl(PreferencesManager.DEFAULT_EXTERNAL_CATALOG_URL)
            loadExternalCatalog(force = true)
        }
    }

    companion object {
        private const val TAG = "ModelViewModel"
    }

    enum class ModelStatus {
        UNLOADED, LOADING, READY, ERROR
    }

    /**
     * Download UI state for model download management — uses per-variant maps.
     */
    data class DownloadUiState(
        val selectedVariant: ModelDownloader.ModelVariant? = null,
        val variantDownloadStates: Map<ModelDownloader.ModelVariant, VariantDownloadState> = emptyMap(),
        val downloadError: ModelDownloader.DownloadError? = null,
        val downloadedModels: Set<ModelDownloader.ModelVariant> = emptySet(),
        val staleModels: Set<ModelDownloader.ModelVariant> = emptySet(),
        val hasToken: Boolean = false,
        val modelToDelete: ModelDownloader.ModelVariant? = null,
        val modelToUpdate: ModelDownloader.ModelVariant? = null,
        val showDownloadDialog: Boolean = false
    ) {
        val isAnyDownloading: Boolean get() = variantDownloadStates.values.any { it.isDownloading }
    }

    /**
     * Per-variant download state — isolates progress, errors, and downloading
     * flag so that concurrent downloads on different variants don't cross-contaminate.
     */
    data class VariantDownloadState(
        val downloadState: DownloadState = DownloadState.Idle,
        val downloadProgress: Float = 0f,
        val isDownloading: Boolean = false,
        val errorMessage: String? = null,
        val partialDownload: DownloadState.PartiallyDownloaded? = null
    )

    /**
     * Generic per-entry UI state for a bundled catalog model (Parakeet, Whisper,
     * Qwen3-ASR, Nemotron, GigaAM). Keyed by the catalog entry id, so the whole
     * sherpa-onnx family shares one code path; variant names are the catalog
     * variant names (strings), not per-model enums.
     */
    data class ModelEntryUiState(
        val downloadedVariants: Set<String> = emptySet(),
        val variantDownloadStates: Map<String, VariantDownloadState> = emptyMap(),
        val modelPath: String? = null,
        // Confirmation dialogs
        val showDownloadDialog: Boolean = false,
        val showDeleteDialog: Boolean = false,
        val selectedVariant: String? = null,
        val variantToDelete: String? = null,
        val variantsNeedingExtraction: Set<String> = emptySet(),
        val orphanedVariants: Set<String> = emptySet()
    ) {
        val isAnyDownloading: Boolean get() = variantDownloadStates.values.any { it.isDownloading }
    }

    data class UiState(
        val status: ModelStatus = ModelStatus.UNLOADED,
        val statusMessage: String = "",
        val modelPath: String = "",
        val modelName: String = "",
        // TASK-373: litert-lm HF url import (url field, repo candidates, busy flag)
        val litertLmUrlInput: String = "",
        val litertLmCandidates: List<LitertLmFile> = emptyList(),
        val litertLmImporting: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _filePickerEvent = MutableSharedFlow<Unit>()
    val filePickerEvent: SharedFlow<Unit> = _filePickerEvent.asSharedFlow()

    // Download state
    private val _downloadUiState = MutableStateFlow(DownloadUiState())
    val downloadUiState: StateFlow<DownloadUiState> = _downloadUiState.asStateFlow()

    // Generic catalog model state (keyed by catalog entry id)
    private val _catalogStates = MutableStateFlow<Map<String, ModelEntryUiState>>(emptyMap())
    val catalogStates: StateFlow<Map<String, ModelEntryUiState>> = _catalogStates.asStateFlow()

    sealed class SnackbarEvent {
        data class Message(val text: String) : SnackbarEvent()
        data object AuthRequired : SnackbarEvent()
    }

    private val _snackbarEvent = MutableSharedFlow<SnackbarEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val snackbarEvent: SharedFlow<SnackbarEvent> = _snackbarEvent.asSharedFlow()

    init {
        // Set up auto-unload callback
        llmManager.setOnAutoUnloadCallback {
            onModelAutoUnloaded()
        }
        // Set up external load callback (e.g., from ModelPreloadReceiver)
        llmManager.setOnExternalLoadCallback { modelPath ->
            onModelExternallyLoaded(modelPath)
        }
        // Observe ExtractionService progress state. Dispatch keys on the catalog
        // entry id (modelKey); anything unknown falls through to the LLM backend.
        viewModelScope.launch {
            ExtractionService.progressState.collect { progress ->
                when {
                    BundledCatalog.byId(progress.modelKey) != null -> handleCatalogProgress(progress.modelKey, progress)
                    progress.modelKey == LlmTranscriptionBackend.BACKEND_ID -> handleServiceProgressGemma(progress)
                }
            }
        }
        // Load saved model path on initialization
        loadSavedModelPath()
        // Check for downloaded models
        refreshDownloadedModels()
        // Check for HuggingFace token
        refreshTokenState()
        // Refresh all catalog-backed model entries
        refreshCatalogEntries()
        // Detect partial downloads
        detectPartialDownloads()
        // Reclaim disk space from stranded old-version model directories left by
        // format/variant pivots (e.g. fp32 Nemotron dir superseded by int8). Safe because
        // it is name-based: only deletes subdirs whose name is NOT a current variant.
        cleanOrphanedModelDirs()
    }

    /**
     * All bundled catalog entries in catalog order — the sections rendered by the
     * Model tab come from the catalog, never from hard-coded per-model code.
     */
    val catalogEntries: List<CatalogEntry> get() = BundledCatalog.entries()

    /**
     * Sweeps each catalog model's storage directory and deletes subdirectories whose name
     * is not a currently-known variant dir-name. Runs once at startup, off the main thread.
     * See [com.antivocale.app.transcription.cleanOrphanedModelDirs] for the safety contract.
     */
    private fun cleanOrphanedModelDirs() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = ctx
            var total = 0L
            val perEntry = mutableMapOf<String, Long>()
            for (entryId in BuiltInBackendIds.ALL) {
                val manager = SherpaModelManager.of(entryId)
                val reclaimed = cleanOrphanedModelDirs(
                    manager.getModelStorageDir(context),
                    manager.validModelDirNames
                )
                perEntry[entryId] = reclaimed
                total += reclaimed
            }
            if (total > 0L) {
                Log.i(
                    TAG,
                    "Reclaimed ${formatFileSize(total)} of orphaned model dirs " +
                        "(${perEntry.map { "${it.key}=${formatFileSize(it.value)}" }.joinToString(", ")})"
                )
            }
        }
    }

    // ==================== Service progress handlers ====================

    /** Updates a single variant's download state in the map, merging with existing state. */
    private fun <V> Map<V, VariantDownloadState>.updateVariant(
        variant: V,
        block: VariantDownloadState.() -> VariantDownloadState
    ): Map<V, VariantDownloadState> =
        this + (variant to (this[variant] ?: VariantDownloadState()).block())

    /** Removes a variant's download state from the map if the variant is non-null. */
    private fun <V> Map<V, VariantDownloadState>.removeVariant(variant: V?): Map<V, VariantDownloadState> =
        if (variant != null) this - variant else this

    /** Merges detected partials into the variant map, preserving existing partials. */
    private fun <V> Map<V, VariantDownloadState>.mergePartials(
        partials: Map<V, DownloadState.PartiallyDownloaded>
    ): Map<V, VariantDownloadState> {
        val updated = mapValues { (v, vds) ->
            vds.copy(partialDownload = partials[v] ?: vds.partialDownload)
        }.toMutableMap()
        for ((v, partial) in partials) {
            if (v !in updated) {
                updated[v] = VariantDownloadState(partialDownload = partial)
            }
        }
        return updated
    }

    /**
     * Shared skeleton for all model progress handlers.
     * Updates the given [stateFlow] with progress, then dispatches terminal states.
     */
    private fun handleServiceProgress(
        state: DownloadState,
        updateFlow: (DownloadState, Float?) -> Unit,
        onError: (String, Throwable?) -> Unit,
        onComplete: (File) -> Unit,
        onCancelled: () -> Unit = { detectPartialDownloads() }
    ) {
        when (state) {
            is DownloadState.Downloading -> {
                val progress = state.progressPercent / 100f
                updateFlow(state, progress)
            }
            is DownloadState.Error -> {
                updateFlow(state, null)
                onError(state.message, state.throwable)
                viewModelScope.launch { _snackbarEvent.tryEmit(SnackbarEvent.Message(state.message)) }
            }
            is DownloadState.Complete -> {
                updateFlow(state, 1f)
                onComplete(state.file)
            }
            is DownloadState.Cancelled -> {
                onCancelled()
            }
            else -> {
                updateFlow(state, null)
            }
        }
    }

    /**
     * Generic progress handler for the catalog-backed models. The variant name is
     * the catalog variant name (from [ExtractionService.ExtractionProgress.variant]),
     * resolved from progress (not ViewModel state) so auto-selection works even after
     * ViewModel recreation during download.
     */
    private fun handleCatalogProgress(entryId: String, progress: ExtractionService.ExtractionProgress) {
        val variantName = progress.variant
        handleServiceProgress(
            state = progress.downloadState,
            updateFlow = { state, prog ->
                if (variantName != null) {
                    updateEntry(entryId) { it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variantName) {
                        copy(downloadState = state, downloadProgress = prog ?: downloadProgress)
                    }) }
                }
            },
            onError = { msg, _ ->
                if (variantName != null) {
                    updateEntry(entryId) { it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variantName) {
                        copy(isDownloading = false, errorMessage = msg)
                    }) }
                }
                detectPartialDownloads()
            },
            onComplete = { file ->
                updateEntry(entryId) { it.copy(
                    modelPath = file.absolutePath,
                    downloadedVariants = if (variantName != null) it.downloadedVariants + variantName else it.downloadedVariants,
                    variantDownloadStates = it.variantDownloadStates.removeVariant(variantName),
                    variantsNeedingExtraction = it.variantsNeedingExtraction - (variantName ?: ""),
                    orphanedVariants = it.orphanedVariants - (variantName ?: "")
                ) }
                applicationScope.launch { shareTargetManager.onModelDownloaded() }
                if (variantName != null) {
                    // Persist the freshly downloaded variant as the saved preference.
                    viewModelScope.launch {
                        preferencesManager.saveSherpaModelPath(entryId, file.absolutePath)
                    }
                    if (_uiState.value.modelName.isBlank()) useModel(entryId, variantName)
                    val displayName = ctx.getString(CatalogVariantUi.of(entryId, variantName).titleResId)
                    viewModelScope.launch { _snackbarEvent.tryEmit(SnackbarEvent.Message(ctx.getString(R.string.catalog_model_downloaded, displayName))) }
                }
            },
            onCancelled = {
                if (variantName != null) {
                    updateEntry(entryId) { it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variantName) {
                        copy(isDownloading = false, errorMessage = null)
                    }) }
                }
                detectPartialDownloads()
            }
        )
    }

    private fun handleServiceProgressGemma(progress: ExtractionService.ExtractionProgress) {
        val variant = ModelDownloader.ModelVariant.entries
            .find { it.name.lowercase() == progress.variant }

        handleServiceProgress(
            state = progress.downloadState,
            updateFlow = { state, prog ->
                if (variant != null) {
                    _downloadUiState.update {
                        it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                            copy(downloadState = state, downloadProgress = prog ?: downloadProgress)
                        })
                    }
                }
            },
            onError = { msg, throwable ->
                val error = when (throwable) {
                    is ModelDownloader.DownloadError -> throwable
                    else -> ModelDownloader.DownloadError.NetworkError(msg, throwable)
                }
                if (variant != null) {
                    _downloadUiState.update {
                        it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                            copy(isDownloading = false, errorMessage = msg)
                        })
                    }
                }
                _downloadUiState.update { it.copy(downloadError = error) }
                val message = when (error) {
                    is ModelDownloader.DownloadError.AuthRequired -> "auth_required"
                    is ModelDownloader.DownloadError.AuthError -> "Invalid token. Check Settings."
                    is ModelDownloader.DownloadError.LicenseError -> "Accept license on HuggingFace"
                    is ModelDownloader.DownloadError.StorageError -> "Not enough storage"
                    is ModelDownloader.DownloadError.NetworkError -> "Network error: ${error.message}"
                }
                val event = if (error is ModelDownloader.DownloadError.AuthRequired) {
                    SnackbarEvent.AuthRequired
                } else {
                    SnackbarEvent.Message(message)
                }
                viewModelScope.launch { _snackbarEvent.tryEmit(event) }
            },
            onComplete = { file ->
                _downloadUiState.update {
                    it.copy(
                        variantDownloadStates = it.variantDownloadStates.removeVariant(variant)
                    )
                }
                refreshDownloadedModels()
                applicationScope.launch { shareTargetManager.onModelDownloaded() }
                if (_uiState.value.modelName.isBlank()) setDownloadedModel(file)
            },
            onCancelled = {
                if (variant != null) {
                    _downloadUiState.update {
                        it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                            copy(isDownloading = false, errorMessage = null)
                        })
                    }
                }
                detectPartialDownloads()
            }
        )
    }

    // ==================== Variant state helpers ====================

    /** Applies a block to one catalog entry's UI state, creating the default when absent. */
    private fun updateEntry(entryId: String, block: (ModelEntryUiState) -> ModelEntryUiState) {
        _catalogStates.update { it + (entryId to block(it[entryId] ?: ModelEntryUiState())) }
    }

    /**
     * Unified cancel for catalog models. Stops the service, then on IO
     * detects any partial download and atomically transitions from
     * Downloading → PartiallyDownloaded (or removes the variant if clean).
     * The variant stays in the map until the IO work finishes, so the UI
     * never renders an intermediate Idle frame.
     */
    private fun cancelVariantDownload(
        entryId: String,
        variantName: String,
        cancelAction: () -> Unit,
        detectPartial: (Context, String) -> DownloadState.PartiallyDownloaded?,
        getCurrentStates: () -> Map<String, VariantDownloadState>,
        applyUpdatedStates: (Map<String, VariantDownloadState>) -> Unit,
    ) {
        cancelAction()
        stopExtractionService(entryId, variantName)
        viewModelScope.launch(Dispatchers.IO) {
            val partial = detectPartial(ctx, variantName)
            val current = getCurrentStates()
            val updated = if (partial != null) {
                current + (variantName to VariantDownloadState(partialDownload = partial))
            } else {
                current - variantName
            }
            applyUpdatedStates(updated)
        }
    }

    // ==================== Service helpers ====================

    /**
     * Sends a cancel intent to [ExtractionService].
     * When [modelKey] and [variant] are provided, only that specific
     * download is cancelled; otherwise all active downloads are cancelled.
     */
    private fun stopExtractionService(
        modelKey: String? = null,
        variant: String? = null
    ) {
        val context = ctx
        val intent = Intent(context, ExtractionService::class.java).apply {
            action = ExtractionService.ACTION_CANCEL
            modelKey?.let { putExtra(ExtractionService.EXTRA_MODEL_KEY, it) }
            variant?.let { putExtra(ExtractionService.EXTRA_CANCEL_VARIANT, it) }
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Maps an enum variant to the lowercase string key used by [ExtractionService].
     */
    private fun <T : Enum<*>> variantKey(variant: T): String = variant.name.lowercase()

    /**
     * Detects partial downloads across all downloaders and updates state.
     */
    private fun detectPartialDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = ctx

            // Check Gemma variants (skip those currently downloading — their .tmp
            // file is live, not a leftover from a cancelled/interrupted download)
            val activeGemmaDownloads = _downloadUiState.value.variantDownloadStates
                .filter { it.value.isDownloading }.keys
            var gemmaPartialVariants = mapOf<ModelDownloader.ModelVariant, DownloadState.PartiallyDownloaded>()
            for (variant in ModelDownloader.ModelVariant.entries) {
                if (!ModelDownloader.isModelDownloaded(context, variant) && variant !in activeGemmaDownloads) {
                    val partial = ModelDownloader.detectPartialDownload(context, variant)
                    if (partial != null) {
                        gemmaPartialVariants = gemmaPartialVariants + (variant to partial)
                    }
                }
            }
            if (gemmaPartialVariants.isNotEmpty()) {
                _downloadUiState.update { state ->
                    state.copy(variantDownloadStates = state.variantDownloadStates.mergePartials(gemmaPartialVariants))
                }
            }

            // Check catalog model entries generically (all variants of every entry)
            for (entryId in BuiltInBackendIds.ALL) {
                val entry = BundledCatalog.byId(entryId) ?: continue
                val downloader = SherpaModelDownloader.of(entryId)
                val manager = SherpaModelManager.of(entryId)
                val current = _catalogStates.value[entryId] ?: ModelEntryUiState()
                val activeDownloads = current.variantDownloadStates
                    .filter { it.value.isDownloading }.keys
                val partials = mutableMapOf<String, DownloadState.PartiallyDownloaded>()
                val needsExtraction = mutableSetOf<String>()
                val orphaned = mutableSetOf<String>()
                for (variant in entry.variants) {
                    val vName = variant.name
                    if (!downloader.isModelDownloaded(context, vName)) {
                        if (vName !in activeDownloads) {
                            val partial = downloader.detectPartialDownload(context, vName)
                            if (partial != null) {
                                partials[vName] = partial
                            }
                        }
                        if (downloader.needsExtraction(context, vName)) {
                            needsExtraction.add(vName)
                        }
                        // Check for orphaned model directory
                        if (File(manager.getModelStorageDir(context), variant.dirName).exists()) {
                            orphaned.add(vName)
                        }
                    }
                }
                updateEntry(entryId) {
                    it.copy(
                        variantDownloadStates = it.variantDownloadStates.mergePartials(partials),
                        variantsNeedingExtraction = needsExtraction,
                        orphanedVariants = orphaned
                    )
                }
            }
        }
    }

    /**
     * Refreshes the token state from the token manager.
     */
    fun refreshTokenState() {
        _downloadUiState.update { it.copy(hasToken = tokenManager.hasToken()) }
    }

    // ==================== Catalog model state refresh ====================

    /** Refreshes download/path state for every catalog entry, off the main thread. */
    fun refreshCatalogEntries() {
        viewModelScope.launch(Dispatchers.IO) {
            for (entryId in BuiltInBackendIds.ALL) {
                refreshCatalogEntry(entryId)
            }
        }
    }

    /**
     * Discovers downloaded variants and resolves the active model path for one
     * catalog entry (auto-fallback semantics live in [SherpaModelManager]).
     */
    private suspend fun refreshCatalogEntry(entryId: String) {
        val context = ctx
        val entry = BundledCatalog.byId(entryId) ?: return
        val downloader = SherpaModelDownloader.of(entryId)
        val manager = SherpaModelManager.of(entryId)
        val downloadedVariants = entry.variants
            .filter { downloader.isModelDownloaded(context, it.name) }
            .map { it.name }
            .toSet()
        val activePath = manager.resolveActiveModelPath(context)
        val needsExtraction = mutableSetOf<String>()
        val orphaned = mutableSetOf<String>()
        for (variant in entry.variants) {
            val vName = variant.name
            if (vName !in downloadedVariants) {
                if (downloader.needsExtraction(context, vName)) needsExtraction.add(vName)
                if (File(manager.getModelStorageDir(context), variant.dirName).exists()) orphaned.add(vName)
            }
        }
        updateEntry(entryId) {
            it.copy(
                downloadedVariants = downloadedVariants,
                modelPath = activePath,
                variantsNeedingExtraction = needsExtraction,
                orphanedVariants = orphaned,
            )
        }
    }

    /** Saved model-path preference flow for a catalog entry (survives restarts). */
    fun savedModelPath(entryId: String): StateFlow<String?> =
        preferencesManager.sherpaModelPath(entryId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun loadSavedModelPath() {
        viewModelScope.launch {
            activeModelRepository.activeModelFlow.collect { active ->
                val path = active.modelPath
                val name = active.modelName
                if (path.isNullOrBlank()) {
                    // No model selected for this backend: clear the active model display.
                    // Also clear statusMessage so a stale "ready" message from the previously
                    // active backend does not linger after a backend switch.
                    _uiState.update { it.copy(modelPath = "", modelName = "", statusMessage = "") }
                } else {
                    // File-existence validation is backend-specific (directory vs file vs custom
                    // check). This when-block stays here because it drives the statusMessage, not
                    // because it dispatches preferences (that is now the repository's job).
                    // TASK-324: key on the registry descriptor instead of the backend-id strings,
                    // mirroring TranscriptionOrchestrator.ensureBackendLoaded. The disabled GGUF
                    // backend is unregistered, so its literal id is matched before the lookup; the
                    // registered LLM backend ("llm") and unknown ids (null descriptor) both fall to
                    // validateModelPath, exactly as the former string-keyed else did.
                    val isValid = when (active.backendId) {
                        "gemma4_gguf" -> {
                            val file = File(path)
                            file.exists() && file.isFile
                        }
                        else -> when (val descriptor = backendRegistry.byBackendId(active.backendId)) {
                            null -> validateModelPath(path)
                            else -> when {
                                descriptor.backendId == LlmTranscriptionBackend.BACKEND_ID -> validateModelPath(path)
                                else -> {
                                    val dir = File(path)
                                    dir.exists() && dir.isDirectory
                                }
                            }
                        }
                    }
                    val displayName = name ?: path.substringAfterLast("/")
                    val isLlm = active.backendId == LlmTranscriptionBackend.BACKEND_ID
                    _uiState.update {
                        it.copy(
                            modelPath = path,
                            modelName = displayName,
                            statusMessage = if (isValid) {
                                if (isLlm) ctx.getString(R.string.saved_model_found)
                                else ctx.getString(R.string.backend_model_ready, displayName)
                            } else {
                                if (isLlm) ctx.getString(R.string.saved_model_not_found)
                                else ctx.getString(R.string.backend_model_not_found, displayName)
                            }
                        )
                    }
                }
            }
        }
    }

    fun openFilePicker() {
        viewModelScope.launch {
            _filePickerEvent.emit(Unit)
        }
    }

    fun onModelSelected(context: Context, uri: Uri) {
        viewModelScope.launch {
            // Copy file to app-specific storage for reliable access
            val copiedPath = copyModelToAppStorage(context, uri)

            if (copiedPath != null) {
                // Persist the model path and activate the LLM backend: a manually
                // imported model file is an LLM asset; leaving the previous backend
                // (e.g. a catalog sherpa entry) would ignore it (same class as the
                // useDownloadedModel fix).
                preferencesManager.saveModelPath(copiedPath)
                preferencesManager.saveTranscriptionBackend(LlmTranscriptionBackend.BACKEND_ID)

                val fileName = extractFileName(copiedPath)
                _uiState.update { it.copy(
                    modelPath = copiedPath,
                    modelName = fileName,
                    status = ModelStatus.UNLOADED,
                    statusMessage = ctx.getString(R.string.model_selected, fileName)
                )}
            } else {
                _uiState.update { it.copy(
                    status = ModelStatus.ERROR,
                    statusMessage = ctx.getString(R.string.model_copy_failed)
                )}
            }
        }
    }

    // ==================== LiteRT-LM URL import (TASK-373) ====================

    fun updateLitertLmUrl(url: String) {
        _uiState.update { it.copy(litertLmUrlInput = url) }
    }

    /** Lists the repo's .litertlm candidates; failures surface via snackbar, prefs untouched. */
    // Dispatchers.IO is mandatory here: listModels does a synchronous OkHttp call
    // and Robolectric does not enforce the main-thread network policy that turns
    // this into NetworkOnMainThreadException on a real device.
    fun listLitertLmModels(url: String) = viewModelScope.launch(Dispatchers.IO) {
        _uiState.update { it.copy(litertLmImporting = true) }
        runCatching { litertLmUrlImporter.listModels(url) }
            .fold(
                onSuccess = { candidates ->
                    _uiState.update {
                        it.copy(litertLmCandidates = candidates, litertLmImporting = false)
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(litertLmImporting = false) }
                    _snackbarEvent.tryEmit(
                        SnackbarEvent.Message(e.message ?: ctx.getString(R.string.litertlm_no_models)))
                })
    }

    fun dismissLitertLmCandidates() {
        _uiState.update { it.copy(litertLmCandidates = emptyList(), litertLmUrlInput = "") }
    }

    /**
     * Downloads the chosen .litertlm file and activates it: same persistence tail
     * as onModelSelected (GH #23 route 3), model_path + backend "llm".
     */
    fun importLitertLmFile(url: String, file: LitertLmFile) =
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(
                litertLmImporting = true,
                // Cleared at START, not completion: keeps the auto-import
                // LaunchedEffect (keyed on candidates.size) from re-firing on
                // tab switches mid-download and double-starting the transfer.
                litertLmCandidates = emptyList()) }
            val modelsDir = File(ctx.filesDir, "models")
            val token = tokenManager.getEffectiveToken()
            val result = litertLmUrlImporter.importFromUrl(
                url, file.fileName, file.sizeBytes,
                modelsDir = modelsDir,
                freeBytes = { modelsDir.usableSpace },
                token = token,
                download = { dlUrl, target, sizeBytes, authHeader ->
                    ResumeDownloadHelper.downloadWithResume(
                        DownloadConfig(
                            url = dlUrl,
                            tempFile = File(target.path + ".tmp"),
                            targetFile = target,
                            estimatedSizeBytes = sizeBytes,
                            authHeader = authHeader))
                })
            _uiState.update { it.copy(
                litertLmImporting = false,
                litertLmUrlInput = "") }
            result.fold(
                onSuccess = { downloaded ->
                    preferencesManager.saveModelPath(downloaded.absolutePath)
                    preferencesManager.saveTranscriptionBackend(LlmTranscriptionBackend.BACKEND_ID)
                    _uiState.update { it.copy(
                        modelPath = downloaded.absolutePath,
                        modelName = downloaded.name,
                        status = ModelStatus.UNLOADED,
                        statusMessage = ctx.getString(R.string.model_selected, downloaded.name)) }
                    _snackbarEvent.tryEmit(
                        SnackbarEvent.Message(ctx.getString(R.string.model_selected, downloaded.name)))
                },
                onFailure = { e ->
                    _snackbarEvent.tryEmit(
                        SnackbarEvent.Message(e.message ?: ctx.getString(R.string.litertlm_no_models)))
                })
        }

    private fun copyModelToAppStorage(context: Context, uri: Uri): String? {
        return try {
            val fileName = getFileNameFromUri(context, uri)
            val destFile = File(context.filesDir, "models/$fileName")
            destFile.parentFile?.mkdirs()

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            destFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return it.getString(nameIndex)
                }
            }
        }
        return "model_${System.currentTimeMillis()}.litertlm"
    }

    fun loadModel(context: Context) {
        val modelPath = _uiState.value.modelPath

        if (modelPath.isBlank()) {
            _uiState.update { it.copy(
                status = ModelStatus.ERROR,
                statusMessage = ctx.getString(R.string.no_model_selected)
            )}
            return
        }

        if (!validateModelPath(modelPath)) {
            _uiState.update { it.copy(
                status = ModelStatus.ERROR,
                statusMessage = ctx.getString(R.string.model_file_not_found, modelPath)
            )}
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(
                status = ModelStatus.LOADING,
                statusMessage = ctx.getString(R.string.model_loading)
            )}

            val result = llmManager.initialize(context, modelPath)

            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(
                        status = ModelStatus.READY,
                        statusMessage = ctx.getString(R.string.model_ready_inference)
                    )}
                },
                onFailure = { error ->
                    _uiState.update { it.copy(
                        status = ModelStatus.ERROR,
                        statusMessage = ctx.getString(R.string.model_load_failed, error.message ?: "")
                    )}
                }
            )
        }
    }

    fun unloadModel() {
        backendManager.unloadAll()

        _uiState.update { it.copy(
            modelName = "",
            modelPath = "",
            status = ModelStatus.UNLOADED,
            statusMessage = ctx.getString(R.string.model_unloaded)
        )}
        Log.i(TAG, "Model unloaded manually")
    }

    fun clearError() {
        if (_uiState.value.status == ModelStatus.ERROR) {
            _uiState.update { it.copy(
                status = ModelStatus.UNLOADED,
                statusMessage = ""
            )}
        }
    }

    private fun validateModelPath(path: String): Boolean {
        val file = File(path)
        return file.exists() && file.isFile && file.length() > 0
    }

    private fun extractFileName(path: String): String {
        return File(path).name
    }

    /**
     * Called when the model is automatically unloaded due to inactivity timeout.
     */
    private fun onModelAutoUnloaded() {
        _uiState.update { it.copy(
            modelName = "",
            modelPath = "",
            status = ModelStatus.UNLOADED,
            statusMessage = ctx.getString(R.string.model_auto_unloaded)
        )}
    }

    /**
     * Called when the model is loaded externally (e.g., via ModelPreloadReceiver).
     * Updates the UI state to reflect the model is ready for inference.
     */
    private fun onModelExternallyLoaded(modelPath: String) {
        val modelName = extractFileName(modelPath)
        _uiState.update { it.copy(
            modelPath = modelPath,
            modelName = modelName,
            status = ModelStatus.READY,
            statusMessage = ctx.getString(R.string.model_externally_loaded)
        )}
    }

    // ==================== Model Download Management ====================

    /**
     * Refreshes the list of downloaded models.
     */
    fun refreshDownloadedModels() {
        viewModelScope.launch {
            val downloaded = ModelDownloader.ModelVariant.entries
                .filter { variant ->
                    ModelDownloader.isModelDownloaded(
                        ctx,
                        variant
                    )
                }
                .toSet()

            // Stale (update-available) variants are computed here, not in the Composable,
            // so per-recomposition file I/O stays out of the UI. Gated on the build flag so
            // there's zero stat/readText cost while speculative decoding is disabled.
            val stale = if (BuildConfig.MTP_SPECULATIVE_DECODING_ENABLED) {
                downloaded.filter { ModelDownloader.isModelUpdateAvailable(ctx, it) }.toSet()
            } else {
                emptySet()
            }

            _downloadUiState.update { it.copy(downloadedModels = downloaded, staleModels = stale) }
        }
    }

    /**
     * Selects a model variant for info/download.
     */
    fun selectModel(variant: ModelDownloader.ModelVariant) {
        _downloadUiState.update { it.copy(selectedVariant = variant) }
    }

    /**
     * Starts a download for the selected Gemma model variant via [ExtractionService].
     */
    fun startDownload(variant: ModelDownloader.ModelVariant) {
        _downloadUiState.update { it.copy(
            selectedVariant = variant,
            variantDownloadStates = it.variantDownloadStates + (variant to VariantDownloadState(
                downloadState = DownloadState.Idle,
                downloadProgress = 0f,
                isDownloading = true,
                errorMessage = null,
                partialDownload = null
            )),
            downloadError = null
        )}

        val context = ctx
        val intent = Intent(context, ExtractionService::class.java).apply {
            putExtra(ExtractionService.EXTRA_MODEL_KEY, LlmTranscriptionBackend.BACKEND_ID)
            putExtra(ExtractionService.EXTRA_VARIANT, variantKey(variant))
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Resumes a partial Gemma download.
     */
    fun resumeDownload(variant: ModelDownloader.ModelVariant) {
        _downloadUiState.update { it.copy(
            variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                copy(partialDownload = null)
            }
        ) }
        startDownload(variant)
    }

    /**
     * Clears a partial Gemma download.
     */
    fun clearPartialDownload(variant: ModelDownloader.ModelVariant) {
        viewModelScope.launch {
            ModelDownloader.clearPartialDownload(ctx, variant)
            _downloadUiState.update { it.copy(
                variantDownloadStates = it.variantDownloadStates - variant,
                downloadError = null
            )}
        }
    }

    /**
     * Cancels the ongoing Gemma download.
     */
    fun cancelDownload(variant: ModelDownloader.ModelVariant) {
        ModelDownloader.cancel(variant)
        stopExtractionService(LlmTranscriptionBackend.BACKEND_ID, variantKey(variant))
        viewModelScope.launch(Dispatchers.IO) {
            val partial = ModelDownloader.detectPartialDownload(ctx, variant)
            val current = _downloadUiState.value.variantDownloadStates
            val updated = if (partial != null) {
                current + (variant to VariantDownloadState(partialDownload = partial))
            } else {
                current - variant
            }
            _downloadUiState.update { it.copy(variantDownloadStates = updated, downloadError = null) }
        }
    }

    /**
     * Checks if a model variant is already downloaded.
     */
    fun isModelDownloaded(variant: ModelDownloader.ModelVariant): Boolean {
        return _downloadUiState.value.downloadedModels.contains(variant)
    }

    /**
     * Sets a downloaded model as the active model.
     */
    fun useDownloadedModel(variant: ModelDownloader.ModelVariant) {
        viewModelScope.launch {
            val modelPath = ModelDownloader.getLocalModelPath(
                ctx,
                variant
            )
            if (modelPath != null) {
                preferencesManager.saveModelPath(modelPath)
                // Switch to LLM backend when selecting an LLM model. Must be the
                // explicit "llm" id: the old DEFAULT_TRANSCRIPTION_BACKEND value
                // ("sherpa-onnx") is itself a catalog entry since the PR #28
                // consolidation, so it resolves to Parakeet instead of falling
                // through to the LLM loader and Gemma never became active.
                preferencesManager.saveTranscriptionBackend(LlmTranscriptionBackend.BACKEND_ID)
                val message = ctx.getString(R.string.model_selected_message, variant.displayName)
                _uiState.update { it.copy(
                    modelPath = modelPath,
                    modelName = variant.displayName,
                    status = ModelStatus.UNLOADED,
                    statusMessage = message
                )}
                _snackbarEvent.tryEmit(SnackbarEvent.Message(message))
            }
        }
    }

    /**
     * Deletes a downloaded model and refreshes the state.
     */
    fun deleteModel(variant: ModelDownloader.ModelVariant) {
        viewModelScope.launch {
            val success = ModelDownloader.deleteModel(ctx, variant)
            if (success) {
                refreshDownloadedModels()
                // Clear model path if this was the selected model
                if (_uiState.value.modelPath.contains(variant.fileName)) {
                    preferencesManager.saveModelPath("")
                    shareTargetManager.onModelDeleted(LlmTranscriptionBackend.BACKEND_ID)
                    _uiState.update { it.copy(
                        modelPath = "",
                        modelName = "",
                        status = ModelStatus.UNLOADED,
                        statusMessage = ctx.getString(R.string.model_deleted_status)
                    )}
                }
            }
        }
    }

    /**
     * Shows the delete confirmation dialog for a model.
     */
    fun showDeleteDialog(variant: ModelDownloader.ModelVariant) {
        _downloadUiState.update { it.copy(modelToDelete = variant) }
    }

    /**
     * Dismisses the delete confirmation dialog.
     */
    fun dismissDeleteDialog() {
        _downloadUiState.update { it.copy(modelToDelete = null) }
    }

    /**
     * Confirms deletion of the model.
     */
    fun confirmDeleteModel() {
        _downloadUiState.value.modelToDelete?.let { variant ->
            deleteModel(variant)
        }
        dismissDeleteDialog()
    }

    /**
     * Clears the download error state.
     */
    fun clearDownloadError() {
        _downloadUiState.update { it.copy(downloadError = null) }
    }

    // ==================== Gemma Confirmation Dialogs ====================

    /**
     * Shows the Gemma download confirmation dialog.
     */
    fun showDownloadDialog(variant: ModelDownloader.ModelVariant) {
        _downloadUiState.update { it.copy(selectedVariant = variant, showDownloadDialog = true) }
    }

    /**
     * Dismisses the Gemma download confirmation dialog.
     */
    fun dismissDownloadDialog() {
        _downloadUiState.update { it.copy(showDownloadDialog = false) }
    }

    /**
     * Confirms and starts the Gemma download.
     */
    fun confirmDownload() {
        val variant = _downloadUiState.value.selectedVariant ?: return
        // TASK-395: per-model RAM gate. Warn-and-proceed (not block): a user
        // on a borderline device may still want to try; the global 1.5GB floor
        // in DeviceCompatibility.check remains the hard gate.
        if (!DeviceCompatibility.hasRamForModel(ctx, variant.estimatedSizeMB)) {
            Log.w(TAG, "Device RAM below the estimated budget for ${variant.displayName} " +
                "(${variant.estimatedSizeMB}MB * headroom); proceeding with warning")
            _snackbarEvent.tryEmit(SnackbarEvent.Message(
                ctx.getString(R.string.model_ram_warning, variant.displayName)))
        }
        _downloadUiState.update { it.copy(showDownloadDialog = false) }
        startDownload(variant)
    }

    // ==================== Gemma Model Update (re-download stale artifact) =========

    /**
     * Shows the "update available" confirmation dialog for a stale Gemma variant — its
     * on-disk artifact predates [ModelDownloader.ModelVariant.modelVersion] (e.g. a
     * pre-2026-05-05 copy lacking the MTP drafter). Surfacing the prompt is additionally
     * gated on `BuildConfig.MTP_SPECULATIVE_DECODING_ENABLED` in the UI (ModelTab).
     */
    fun showUpdateDialog(variant: ModelDownloader.ModelVariant) {
        _downloadUiState.update { it.copy(modelToUpdate = variant) }
    }

    fun dismissUpdateDialog() {
        _downloadUiState.update { it.copy(modelToUpdate = null) }
    }

    /**
     * Confirms the update: deletes the stale cached file (and its version marker) WITHOUT
     * clearing the active-model preference, then re-downloads. The same `models/<fileName>`
     * path is repopulated on completion, so an active selection survives the refresh.
     *
     * Accepted transient: between delete and re-download completion the active-model path
     * briefly points at a missing file, so transcription fired in that window would fail to
     * load. This is a brief, user-initiated window; the UI shows the normal download spinner.
     */
    fun confirmUpdateModel() {
        val variant = _downloadUiState.value.modelToUpdate ?: return
        _downloadUiState.update { it.copy(modelToUpdate = null) }
        viewModelScope.launch {
            ModelDownloader.deleteModel(ctx, variant)
            refreshDownloadedModels()
            startDownload(variant)
        }
    }

    // ==================== Catalog Models (generic, catalog-driven) ====================

    /**
     * Starts a download for one catalog entry's variant via [ExtractionService].
     * The whole sherpa-onnx family (Parakeet, Whisper, Qwen3-ASR, Nemotron, GigaAM)
     * shares this single path; everything model-specific comes from the catalog.
     */
    fun startDownload(entryId: String, variantName: String, dismissDialog: Boolean = false, initialState: DownloadState = DownloadState.Connecting("")) {
        updateEntry(entryId) { it.copy(
            showDownloadDialog = if (dismissDialog) false else it.showDownloadDialog,
            selectedVariant = variantName,
            variantDownloadStates = it.variantDownloadStates + (variantName to VariantDownloadState(
                downloadState = initialState,
                downloadProgress = 0f,
                isDownloading = true,
                errorMessage = null,
                partialDownload = null
            ))
        ) }

        val context = ctx
        val intent = Intent(context, ExtractionService::class.java).apply {
            putExtra(ExtractionService.EXTRA_MODEL_KEY, entryId)
            putExtra(ExtractionService.EXTRA_VARIANT, variantName)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Resumes a partial catalog download.
     */
    fun resumeDownload(entryId: String, variantName: String) {
        updateEntry(entryId) { it.copy(
            variantDownloadStates = it.variantDownloadStates.updateVariant(variantName) {
                copy(partialDownload = null)
            }
        ) }
        startDownload(entryId, variantName)
    }

    /**
     * Clears a partial catalog download (e.g. the .tar file for Whisper; the model
     * directory is preserved — use [clearOrphanedFiles] to remove it).
     */
    fun clearPartialDownload(entryId: String, variantName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            SherpaModelDownloader.of(entryId).clearPartialDownload(ctx, variantName)
            updateEntry(entryId) { it.copy(
                variantDownloadStates = it.variantDownloadStates - variantName,
                variantsNeedingExtraction = it.variantsNeedingExtraction - variantName,
            ) }
            detectPartialDownloads()
        }
    }

    /**
     * Clears an orphaned catalog model directory (partial extraction leftovers).
     * The partial download file is preserved so extraction can be retried.
     */
    fun clearOrphanedFiles(entryId: String, variantName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            SherpaModelDownloader.of(entryId).deleteModel(ctx, variantName)
            updateEntry(entryId) { it.copy(orphanedVariants = it.orphanedVariants - variantName) }
            detectPartialDownloads()
        }
    }

    /**
     * Cancels a catalog model download.
     */
    fun cancelDownload(entryId: String, variantName: String) = cancelVariantDownload(
        entryId = entryId,
        variantName = variantName,
        cancelAction = { SherpaModelDownloader.of(entryId).cancel(variantName) },
        detectPartial = { context, v -> SherpaModelDownloader.of(entryId).detectPartialDownload(context, v) },
        getCurrentStates = { _catalogStates.value[entryId]?.variantDownloadStates ?: emptyMap() },
        applyUpdatedStates = { states -> updateEntry(entryId) { it.copy(variantDownloadStates = states) } }
    )

    /**
     * Uses a catalog model variant (switches the transcription backend to the entry id).
     */
    fun useModel(entryId: String, variantName: String) {
        viewModelScope.launch {
            val context = ctx
            val downloader = SherpaModelDownloader.of(entryId)
            // The catalog-state fallback is STALE (populated by an earlier scan), so its
            // path is disk-checked here; the first two resolutions read the disk fresh
            // and must stay free of extra IO (B2, TASK-342 device verification).
            val modelPath = downloader.getModelPath(context, variantName)
                ?: SherpaModelManager.of(entryId).resolveActiveModelPath(context)
                ?: _catalogStates.value[entryId]?.modelPath?.takeIf { File(it).exists() }
            if (modelPath != null) {
                preferencesManager.saveSherpaModelPath(entryId, modelPath)
                preferencesManager.saveTranscriptionBackend(entryId)

                val displayName = context.getString(CatalogVariantUi.of(entryId, variantName).titleResId)
                val message = context.getString(R.string.model_selected_message, displayName)
                _uiState.update {
                    it.copy(
                        modelName = displayName,
                        status = ModelStatus.UNLOADED,
                        statusMessage = message
                    )
                }

                _snackbarEvent.tryEmit(SnackbarEvent.Message(message))
                llmManager.resetKeepAliveTimer()
            } else {
                // No valid model directory resolved: previously a SILENT no-op, which
                // left the previous backend preference (possibly a dangling external id)
                // active while the user believed they had switched (TASK-342 defect 1).
                val displayName = context.getString(CatalogVariantUi.of(entryId, variantName).titleResId)
                _snackbarEvent.tryEmit(SnackbarEvent.Message(
                    context.getString(R.string.model_use_missing_files, displayName)))
            }
        }
    }

    /**
     * Deletes a catalog model variant. When the deleted variant is the saved active one,
     * re-resolves the active path (auto-fallback) or clears the saved path and backend.
     */
    fun deleteModel(entryId: String, variantName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = ctx
            val downloader = SherpaModelDownloader.of(entryId)
            val success = downloader.deleteModel(context, variantName)
            if (success) {
                val variant = BundledCatalog.byId(entryId)?.variant(variantName)
                updateEntry(entryId) { it.copy(
                    downloadedVariants = it.downloadedVariants - variantName,
                    variantsNeedingExtraction = it.variantsNeedingExtraction - variantName,
                    orphanedVariants = it.orphanedVariants - variantName,
                ) }
                // Re-resolve the active path; if the deleted variant was the saved one, fall back.
                val activePath = SherpaModelManager.of(entryId).resolveActiveModelPath(context)
                val savedPath = preferencesManager.sherpaModelPath(entryId).first()
                if (variant != null && savedPath != null && savedPath.contains(variant.dirName)) {
                    if (activePath != null) {
                        preferencesManager.saveSherpaModelPath(entryId, activePath)
                    } else {
                        preferencesManager.clearSherpaModelPath(entryId)
                        _uiState.update { it.copy(modelPath = "", modelName = "") }
                        shareTargetManager.onModelDeleted(entryId)
                    }
                }
                updateEntry(entryId) { it.copy(modelPath = activePath) }
                val displayName = context.getString(CatalogVariantUi.of(entryId, variantName).titleResId)
                _snackbarEvent.tryEmit(SnackbarEvent.Message(context.getString(R.string.catalog_model_deleted, displayName)))
            }
        }
    }

    // ==================== Catalog Confirmation Dialogs ====================

    fun showDownloadDialog(entryId: String, variantName: String) {
        updateEntry(entryId) { it.copy(showDownloadDialog = true, selectedVariant = variantName) }
    }

    fun dismissDownloadDialog(entryId: String) {
        updateEntry(entryId) { it.copy(showDownloadDialog = false) }
    }

    fun confirmDownload(entryId: String) {
        val state = _catalogStates.value[entryId] ?: return
        val variant = state.selectedVariant ?: return
        // TASK-395 follow-up: per-model RAM warning on the CATALOG path too (the
        // Gemma-only wiring left the heaviest sherpa models unwarned).
        val catalogVariant = com.antivocale.app.data.catalog.BundledCatalog
            .byId(entryId)?.variant(variant)
        if (catalogVariant != null &&
            !DeviceCompatibility.hasRamForModel(ctx, catalogVariant.estimatedSizeMB)) {
            Log.w(TAG, "Device RAM below the estimated budget for $variant " +
                "(${catalogVariant.estimatedSizeMB}MB * headroom); proceeding with warning")
            _snackbarEvent.tryEmit(SnackbarEvent.Message(
                ctx.getString(R.string.model_ram_warning, variant)))
        }
        val needsExtraction = state.variantsNeedingExtraction.contains(variant)
        startDownload(
            entryId,
            variant,
            dismissDialog = true,
            initialState = if (needsExtraction) DownloadState.Extracting(0, 0) else DownloadState.Connecting("")
        )
    }

    fun showDeleteDialog(entryId: String, variantName: String) {
        updateEntry(entryId) { it.copy(showDeleteDialog = true, variantToDelete = variantName) }
    }

    fun dismissDeleteDialog(entryId: String) {
        updateEntry(entryId) { it.copy(showDeleteDialog = false, variantToDelete = null) }
    }

    fun confirmDelete(entryId: String) {
        val state = _catalogStates.value[entryId] ?: return
        val variant = state.variantToDelete ?: return
        updateEntry(entryId) { it.copy(showDeleteDialog = false) }
        deleteModel(entryId, variant)
    }

    // ==================== External models (v2a) ====================

    /** Import progress for the external-models section (one import at a time). */
    sealed class ExternalImportState {
        data object Idle : ExternalImportState()

        /** TASK-398: URL imports report per-file download progress; the folder path
         *  stays on the no-arg shape (no download to report). */
        data class Importing(
            val fileName: String = "",
            val fileIndex: Int = 0,
            val fileCount: Int = 0,
            val bytes: Long = 0,
            val totalBytes: Long = 0,
            val progress: Float = 0f,
        ) : ExternalImportState()

        data class Error(val message: String) : ExternalImportState()
    }

    private val _externalImportState = MutableStateFlow<ExternalImportState>(ExternalImportState.Idle)
    val externalImportState: StateFlow<ExternalImportState> = _externalImportState.asStateFlow()

    /** Valid external records for the section cards (dir exists on disk). */
    val externalModels: StateFlow<List<ExternalModelRecord>> =
        externalModelStore.validRecordsFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Persisted active backend id, for card active-state keyed on identity (not display name). */
    val activeBackendId: StateFlow<String> =
        preferencesManager.transcriptionBackend
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
                PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND)

    /**
     * Folder import (SAF): the primary v2a entry. modelType is NOT passed for
     * non-CTC families: the importer's family-aware resolveModelType governs
     * (a stale UI string persisted on a non-transducer record is the bug that
     * rule prevents). CTC has no safe default, so its subtype is explicit.
     */
    fun importExternalFromFolder(
        context: Context,
        treeUri: Uri,
        family: ModelFamily,
        ctcModelType: String = "nemo_ctc",
        options: Map<String, String> = emptyMap(),
        languages: List<String> = emptyList(),
    ) = runExternalImport("External folder", onProgress = null) {
        externalModelImporter.importFromTreeUri(
            context, treeUri,
            modelType = ctcModelType(family, ctcModelType),
            family = family, options = options, languages = languages)
    }

    /** URL import: a HuggingFace repo URL or a catalog-entry JSON URL. */
    fun importExternalFromUrl(
        url: String,
        family: ModelFamily,
        ctcModelType: String = "nemo_ctc",
        options: Map<String, String> = emptyMap(),
        languages: List<String> = emptyList(),
    ) = runExternalImport("External URL",
        onProgress = { index, count, name, bytes, total ->
            _externalImportState.value = ExternalImportState.Importing(
                fileName = name, fileIndex = index, fileCount = count,
                bytes = bytes, totalBytes = total,
                progress = if (total > 0) bytes.toFloat() / total else 0f)
        }) { onProgress ->
        externalModelImporter.importFromUrl(
            url,
            modelType = ctcModelType(family, ctcModelType),
            family = family, options = options, languages = languages,
            onProgress = onProgress)
    }

    /** Only CTC takes an explicit modelType (it selects the sherpa config subtype);
     *  every other family passes null so the importer default applies. */
    private fun ctcModelType(family: ModelFamily, ctcModelType: String): String? =
        if (family == ModelFamily.CTC) ctcModelType else null

    /** Shared import scaffolding: progress state, IO dispatching, and the failure tail.
     *  [onProgress] is handed to the block so URL imports can stream download telemetry
     *  into the state (TASK-398); null for the folder path (nothing to report). */
    private fun runExternalImport(
        label: String,
        onProgress: ((Int, Int, String, Long, Long) -> Unit)?,
        block: suspend ((Int, Int, String, Long, Long) -> Unit) -> ExternalModelRecord,
    ) {
        val noop: (Int, Int, String, Long, Long) -> Unit = { _, _, _, _, _ -> }
        _externalImportState.value = ExternalImportState.Importing()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block(onProgress ?: noop) }
                .fold(
                    onSuccess = { record -> onExternalImported(record) },
                    onFailure = { e ->
                        Log.e(TAG, "$label import failed", e)
                        _externalImportState.value = ExternalImportState.Error(e.message ?: "unknown error")
                        _snackbarEvent.tryEmit(SnackbarEvent.Message(
                            ctx.getString(R.string.external_import_failed, e.message ?: "")))
                    },
                )
        }
    }

    private fun onExternalImported(record: ExternalModelRecord) {
        _externalImportState.value = ExternalImportState.Idle
        _snackbarEvent.tryEmit(SnackbarEvent.Message(ctx.getString(R.string.external_imported, record.displayName)))
        // Called from a non-suspend fold callback; the manager is suspend since TASK-264.
        applicationScope.launch { shareTargetManager.onModelDownloaded() }
        // First-run behavior: auto-select when nothing is active.
        if (_uiState.value.modelName.isBlank()) {
            viewModelScope.launch { activateExternalModel(record) }
        }
    }

    /** Selects an imported model as the active transcription backend. */
    fun useExternalModel(record: ExternalModelRecord) {
        viewModelScope.launch { activateExternalModel(record) }
    }

    private suspend fun activateExternalModel(record: ExternalModelRecord) {
        preferencesManager.saveTranscriptionBackend(record.backendId)
        // TASK-408: canary decodes empty on chunks cut mid-speech, so VAD-aligned
        // segmentation is part of the deal: flip the preference on at selection
        // time (visible in Settings) rather than overriding it silently. The
        // orchestrator also routes canary through VAD regardless (share aliases
        // and Tasker overrides never pass through here).
        if (record.family == ModelFamily.CANARY && !preferencesManager.vadEnabled.first()) {
            preferencesManager.saveVadEnabled(true)
        }
        val message = ctx.getString(R.string.model_selected_message, record.displayName)
        _uiState.update {
            it.copy(
                modelPath = record.dir,
                modelName = record.displayName,
                status = ModelStatus.UNLOADED,
                statusMessage = message
            )
        }
        _snackbarEvent.tryEmit(SnackbarEvent.Message(message))
        llmManager.resetKeepAliveTimer()
    }

    /**
     * Deletes a record: store first (so family sync sees the new world), then the files.
     * When the deleted model is the persisted active backend, reset to the default backend
     * (otherwise the orchestrator's registry lookup would fall through to the LLM loader).
     */
    fun deleteExternalModel(record: ExternalModelRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            externalModelStore.delete(record.id)
            File(record.dir).deleteRecursively()
            if (preferencesManager.transcriptionBackend.first() == record.backendId) {
                preferencesManager.saveTranscriptionBackend(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND)
                _uiState.update { it.copy(modelPath = "", modelName = "") }
            }
            applicationScope.launch { shareTargetManager.syncAll() }
            _snackbarEvent.tryEmit(SnackbarEvent.Message(
                ctx.getString(R.string.external_deleted, record.displayName)))
        }
    }

    /**
     * Sets a downloaded File as the active model.
     * Used internally after successful download.
     */
    private fun setDownloadedModel(file: File) {
        viewModelScope.launch {
            val modelPath = file.absolutePath
            preferencesManager.saveModelPath(modelPath)
            // Every legacy-download asset is an LLM (.litertlm) file; persisting
            // the path without switching the backend leaves the previous backend
            // active and Gemma never loads (same class as the c36199b fixes for
            // useDownloadedModel/onModelSelected; reported again as GH #23).
            preferencesManager.saveTranscriptionBackend(LlmTranscriptionBackend.BACKEND_ID)
            _uiState.update { it.copy(
                modelPath = modelPath,
                modelName = file.name,
                status = ModelStatus.UNLOADED,
                statusMessage = ctx.getString(R.string.downloaded_model_selected)
            )}
        }
    }

    // ==================== Benchmark ====================

    private data class BenchmarkTarget(
        val backendId: String,
        val modelPath: String,
        val displayName: String
    )

    private val _benchmarkState = MutableStateFlow<BenchmarkState>(BenchmarkState.Idle)
    val benchmarkState: StateFlow<BenchmarkState> = _benchmarkState.asStateFlow()

    private val _benchmarkTargetName = MutableStateFlow("")
    val benchmarkTargetName: StateFlow<String> = _benchmarkTargetName.asStateFlow()

    private var benchmarkJob: kotlinx.coroutines.Job? = null
    private var lastBenchmarkTarget: BenchmarkTarget? = null

    fun startBenchmark(backendId: String, modelPath: String, displayName: String) {
        benchmarkJob?.cancel()
        lastBenchmarkTarget = BenchmarkTarget(backendId, modelPath, displayName)
        _benchmarkTargetName.value = displayName
        _benchmarkState.value = BenchmarkState.Idle

        val backend = backendManager.getBackend(backendId) ?: run {
            _benchmarkState.value = BenchmarkState.Error("Unknown backend: $backendId")
            return
        }

        benchmarkJob = viewModelScope.launch(Dispatchers.IO) {
            val threadCount = preferencesManager.threadCount.first()
            val providerPref = preferencesManager.inferenceProvider.first()
            val resolvedProvider = InferenceProvider.resolve(providerPref)

            val config = when {
                backendId == "gemma4_gguf" -> BackendConfig.GgufConfig(
                    modelPath = modelPath,
                    threadCount = threadCount
                )
                BundledCatalog.byId(backendId) != null -> {
                    val entry = BundledCatalog.byId(backendId)!!
                    val lang = preferencesManager.transcriptionLanguage.first()
                    // Same variant + language resolution as the orchestrator's load path
                    // (TASK-434): the benchmark must measure what transcription would
                    // actually run with, and the "system" default must never reach the
                    // recognizer as a literal language code.
                    val variant = entry.variantForDirName(File(modelPath).name)
                    BackendConfig.SherpaOnnxConfig(
                        modelDir = modelPath,
                        numThreads = threadCount,
                        language = TranscriptionLanguagePolicy.resolveForEntry(
                            entry = entry,
                            variant = variant,
                            preference = lang,
                            uiLocale = LocaleManager.effectiveLocale(),
                        ),
                        provider = resolvedProvider
                    )
                }
                else -> {
                    _benchmarkState.value = BenchmarkState.Error("Unsupported backend for benchmark")
                    return@launch
                }
            }

            val result = benchmarkManager.runBenchmark(backend, config) { progress ->
                _benchmarkState.value = BenchmarkState.Running(progress)
            }
            _benchmarkState.value = result.fold(
                onSuccess = { BenchmarkState.Complete(it) },
                onFailure = { BenchmarkState.Error(it.message ?: "Benchmark failed") }
            )
        }
    }

    fun rerunBenchmark() {
        val target = lastBenchmarkTarget ?: return
        startBenchmark(target.backendId, target.modelPath, target.displayName)
    }

    fun cancelBenchmark() {
        benchmarkJob?.cancel()
        benchmarkJob = null
        _benchmarkState.value = BenchmarkState.Idle
    }

    fun dismissBenchmark() {
        _benchmarkState.value = BenchmarkState.Idle
        _benchmarkTargetName.value = ""
    }

    override fun onCleared() {
        super.onCleared()
        benchmarkJob?.cancel()
        llmManager.setOnAutoUnloadCallback(null)
        llmManager.setOnExternalLoadCallback(null)
    }
}
