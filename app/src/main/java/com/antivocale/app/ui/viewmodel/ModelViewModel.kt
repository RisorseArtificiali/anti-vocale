package com.antivocale.app.ui.viewmodel

import android.content.Context
import android.util.Log
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antivocale.app.data.HuggingFaceTokenManager
import com.antivocale.app.data.ModelDownloader
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.ShareTargetManager
import com.antivocale.app.transcription.LlmTranscriptionBackend
import com.antivocale.app.transcription.NemotronDownloader
import com.antivocale.app.transcription.NemotronModelManager
import com.antivocale.app.transcription.NemotronStreamingBackend
import com.antivocale.app.R
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.manager.LlmManager
import com.antivocale.app.service.ExtractionService
import com.antivocale.app.transcription.Qwen3AsrBackend
import com.antivocale.app.transcription.SherpaOnnxBackend
import com.antivocale.app.transcription.WhisperBackend
import com.antivocale.app.transcription.ParakeetDownloader
import com.antivocale.app.transcription.ParakeetModelManager
import com.antivocale.app.transcription.WhisperDownloader
import com.antivocale.app.transcription.WhisperModelManager
import com.antivocale.app.transcription.Qwen3AsrDownloader
import com.antivocale.app.transcription.Qwen3AsrModelManager
// GGUF: import com.antivocale.app.transcription.Gemma4GgufModelManager
// GGUF: import com.antivocale.app.transcription.Gemma4GgufBackend
import com.antivocale.app.transcription.TranscriptionBackendManager
import com.antivocale.app.transcription.BackendConfig
import com.antivocale.app.transcription.InferenceProvider
import com.antivocale.app.benchmark.BenchmarkManager
import com.antivocale.app.benchmark.BenchmarkState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@HiltViewModel
class ModelViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val tokenManager: HuggingFaceTokenManager,
    private val benchmarkManager: BenchmarkManager,
    private val backendManager: TranscriptionBackendManager,
    private val llmManager: LlmManager,
    private val shareTargetManager: ShareTargetManager,
    @ApplicationContext private val ctx: Context
) : ViewModel() {

    val tokenState = tokenManager.tokenState

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
        val hasToken: Boolean = false,
        val modelToDelete: ModelDownloader.ModelVariant? = null,
        val showDownloadDialog: Boolean = false
    ) {
        val isAnyDownloading: Boolean get() = variantDownloadStates.values.any { it.isDownloading }
    }

    /**
     * Parakeet download UI state — uses per-variant maps like Qwen3-ASR.
     *
     * Note: Parakeet has NO user-facing variant selector. The active model is auto-resolved
     * at transcription time via [ParakeetModelManager.resolveActiveModelPath] (prefer
     * SmoothQuant, fall back to Stock int8). Both variants are still independently
     * downloadable / deletable from the UI.
     */
    data class ParakeetUiState(
        val selectedVariant: ParakeetModelManager.Variant? = null,
        val downloadedVariants: Set<ParakeetModelManager.Variant> = emptySet(),
        val variantDownloadStates: Map<ParakeetModelManager.Variant, VariantDownloadState> = emptyMap(),
        val modelPath: String? = null,
        val showDownloadDialog: Boolean = false,
        val showDeleteDialog: Boolean = false,
        val variantToDelete: ParakeetModelManager.Variant? = null
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
     * Whisper download UI state — uses per-variant maps for download tracking.
     */
    data class WhisperUiState(
        val selectedVariant: WhisperModelManager.Variant? = null,
        val downloadedVariants: Set<WhisperModelManager.Variant> = emptySet(),
        val variantDownloadStates: Map<WhisperModelManager.Variant, VariantDownloadState> = emptyMap(),
        val modelPath: String? = null,
        // Confirmation dialogs
        val showDownloadDialog: Boolean = false,
        val showDeleteDialog: Boolean = false,
        val variantToDelete: WhisperModelManager.Variant? = null,
        val variantsNeedingExtraction: Set<WhisperModelManager.Variant> = emptySet(),
        val orphanedVariants: Set<WhisperModelManager.Variant> = emptySet()
    ) {
        val isAnyDownloading: Boolean get() = variantDownloadStates.values.any { it.isDownloading }
    }

    /**
     * Qwen3-ASR download UI state — uses per-variant maps for download tracking.
     */
    data class Qwen3AsrUiState(
        val selectedVariant: Qwen3AsrModelManager.Variant? = null,
        val downloadedVariants: Set<Qwen3AsrModelManager.Variant> = emptySet(),
        val variantDownloadStates: Map<Qwen3AsrModelManager.Variant, VariantDownloadState> = emptyMap(),
        val modelPath: String? = null,
        val showDownloadDialog: Boolean = false,
        val showDeleteDialog: Boolean = false,
        val variantToDelete: Qwen3AsrModelManager.Variant? = null
    ) {
        val isAnyDownloading: Boolean get() = variantDownloadStates.values.any { it.isDownloading }
    }

    /**
     * Nemotron download UI state — single-variant (NemotronDownloader is `Unit`-keyed),
     * so a single download slot is tracked instead of a per-variant map.
     */
    data class NemotronUiState(
        val isDownloading: Boolean = false,
        val downloadProgress: Float = 0f,
        val downloadState: DownloadState = DownloadState.Idle,
        val modelPath: String? = null,
        val errorMessage: String? = null,
        val partialDownload: DownloadState.PartiallyDownloaded? = null,
        val showDownloadDialog: Boolean = false,
        val showDeleteDialog: Boolean = false
    )

    /**
     * GGUF download UI state — uses String-based variant keys since GGUF classes are disabled.
     * GGUF: change String back to Gemma4GgufModelManager.GgufVariant when re-enabling
     */
    data class GgufUiState(
        val selectedVariant: String? = null,
        val downloadedVariants: Set<String> = emptySet(),
        val variantDownloadStates: Map<String, VariantDownloadState> = emptyMap(),
        val modelPath: String? = null,
        val showDownloadDialog: Boolean = false,
        val showDeleteDialog: Boolean = false,
        val variantToDelete: String? = null
    ) {
        val isAnyDownloading: Boolean get() = variantDownloadStates.values.any { it.isDownloading }
    }

    data class UiState(
        val status: ModelStatus = ModelStatus.UNLOADED,
        val statusMessage: String = "",
        val modelPath: String = "",
        val modelName: String = ""
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _filePickerEvent = MutableSharedFlow<Unit>()
    val filePickerEvent: SharedFlow<Unit> = _filePickerEvent.asSharedFlow()

    // Download state
    private val _downloadUiState = MutableStateFlow(DownloadUiState())
    val downloadUiState: StateFlow<DownloadUiState> = _downloadUiState.asStateFlow()

    // Parakeet state - must be declared before init block
    private val _parakeetState = MutableStateFlow(ParakeetUiState())
    val parakeetState: StateFlow<ParakeetUiState> = _parakeetState.asStateFlow()

    // Whisper state - must be declared before init block
    private val _whisperState = MutableStateFlow(WhisperUiState())
    val whisperState: StateFlow<WhisperUiState> = _whisperState.asStateFlow()

    // Qwen3-ASR state - must be declared before init block
    private val _qwen3AsrState = MutableStateFlow(Qwen3AsrUiState())
    val qwen3AsrState: StateFlow<Qwen3AsrUiState> = _qwen3AsrState.asStateFlow()

    // Nemotron state - must be declared before init block
    private val _nemotronState = MutableStateFlow(NemotronUiState())
    val nemotronState: StateFlow<NemotronUiState> = _nemotronState.asStateFlow()

    // GGUF state - must be declared before init block
    private val _ggufState = MutableStateFlow(GgufUiState())
    val ggufState: StateFlow<GgufUiState> = _ggufState.asStateFlow()

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
        // Observe ExtractionService progress state
        viewModelScope.launch {
            ExtractionService.progressState.collect { progress ->
                when (progress.modelType) {
                    ExtractionService.ModelType.PARAKEET -> handleServiceProgressParakeet(progress)
                    ExtractionService.ModelType.WHISPER -> handleServiceProgressWhisper(progress)
                    ExtractionService.ModelType.QWEN3_ASR -> handleServiceProgressQwen3Asr(progress)
                    ExtractionService.ModelType.NEMOTRON -> handleServiceProgressNemotron(progress)
                    // GGUF: disabled
                    ExtractionService.ModelType.GEMMA4_GGUF -> { /* no-op */ }
                    ExtractionService.ModelType.GEMMA -> handleServiceProgressGemma(progress)
                }
            }
        }
        // Load saved model path on initialization
        loadSavedModelPath()
        // Check for downloaded models
        refreshDownloadedModels()
        // Check for HuggingFace token
        refreshTokenState()
        // Check for Parakeet model
        refreshParakeetState()
        // Check for Whisper model
        refreshWhisperState()
        // Check for Qwen3-ASR model
        refreshQwen3AsrState()
        // Check for Nemotron model
        refreshNemotronState()
        // GGUF: disabled — refreshGgufState() commented out
        // Detect partial downloads
        detectPartialDownloads()
        // Reclaim disk space from stranded old-version model directories left by
        // format/variant pivots (e.g. fp32 Nemotron dir superseded by int8). Safe because
        // it is name-based: only deletes subdirs whose name is NOT a current variant.
        cleanOrphanedModelDirs()
    }

    /**
     * Sweeps each backend's model storage directory and deletes subdirectories whose name
     * is not a currently-known variant dir-name. Runs once at startup, off the main thread.
     * See [com.antivocale.app.transcription.cleanOrphanedModelDirs] for the safety contract.
     */
    private fun cleanOrphanedModelDirs() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = ctx

            val parakeetReclaimed = com.antivocale.app.transcription.cleanOrphanedModelDirs(
                ParakeetModelManager.getModelStorageDir(context),
                ParakeetModelManager.validModelDirNames
            )
            val whisperReclaimed = com.antivocale.app.transcription.cleanOrphanedModelDirs(
                WhisperModelManager.getModelStorageDir(context),
                WhisperModelManager.validModelDirNames
            )
            val qwen3Reclaimed = com.antivocale.app.transcription.cleanOrphanedModelDirs(
                Qwen3AsrModelManager.getModelStorageDir(context),
                Qwen3AsrModelManager.validModelDirNames
            )
            val nemotronReclaimed = com.antivocale.app.transcription.cleanOrphanedModelDirs(
                NemotronModelManager.getModelStorageDir(context),
                NemotronModelManager.validModelDirNames
            )

            val total = parakeetReclaimed + whisperReclaimed + qwen3Reclaimed + nemotronReclaimed
            if (total > 0L) {
                Log.i(
                    TAG,
                    "Reclaimed ${com.antivocale.app.util.formatFileSize(total)} of orphaned model dirs " +
                        "(parakeet=${com.antivocale.app.util.formatFileSize(parakeetReclaimed)}, " +
                        "whisper=${com.antivocale.app.util.formatFileSize(whisperReclaimed)}, " +
                        "qwen3=${com.antivocale.app.util.formatFileSize(qwen3Reclaimed)}, " +
                        "nemotron=${com.antivocale.app.util.formatFileSize(nemotronReclaimed)})"
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
     * Shared skeleton for all three model-type progress handlers.
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

    private fun handleServiceProgressParakeet(progress: ExtractionService.ExtractionProgress) {
        val variant = ParakeetModelManager.Variant.entries
            .find { it.name.lowercase() == progress.variant }

        handleServiceProgress(
            state = progress.downloadState,
            updateFlow = { state, prog ->
                if (variant != null) {
                    _parakeetState.update {
                        it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                            copy(downloadState = state, downloadProgress = prog ?: downloadProgress)
                        })
                    }
                }
            },
            onError = { msg, _ ->
                if (variant != null) {
                    _parakeetState.update {
                        it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                            copy(isDownloading = false, errorMessage = msg)
                        })
                    }
                }
                detectPartialDownloads()
            },
            onComplete = { file ->
                _parakeetState.update {
                    it.copy(
                        modelPath = file.absolutePath,
                        downloadedVariants = if (variant != null) it.downloadedVariants + variant else it.downloadedVariants,
                        variantDownloadStates = it.variantDownloadStates.removeVariant(variant)
                    )
                }
                shareTargetManager.onModelDownloaded()
                if (variant != null) {
                    // Persist the freshly downloaded variant as the saved preference; auto-fallback
                    // will still prefer SmoothQuant at transcription time regardless of what's saved.
                    viewModelScope.launch {
                        preferencesManager.saveParakeetModelPath(file.absolutePath)
                    }
                    if (_uiState.value.modelName.isBlank()) useParakeetModel()
                    viewModelScope.launch { _snackbarEvent.tryEmit(SnackbarEvent.Message(ctx.getString(R.string.parakeet_downloaded))) }
                }
            },
            onCancelled = {
                if (variant != null) {
                    _parakeetState.update {
                        it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                            copy(isDownloading = false, errorMessage = null)
                        })
                    }
                }
                detectPartialDownloads()
            }
        )
    }

    private fun handleServiceProgressWhisper(progress: ExtractionService.ExtractionProgress) {
        // Resolve variant from progress (not ViewModel state) so auto-selection
        // works even after ViewModel recreation during download
        val variant = WhisperModelManager.Variant.entries
            .find { it.name.lowercase() == progress.variant }

        handleServiceProgress(
            state = progress.downloadState,
            updateFlow = { state, prog ->
                if (variant != null) {
                    _whisperState.update {
                        it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                            copy(downloadState = state, downloadProgress = prog ?: downloadProgress)
                        })
                    }
                }
            },
            onError = { msg, _ ->
                if (variant != null) {
                    _whisperState.update {
                        it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                            copy(isDownloading = false, errorMessage = msg)
                        })
                    }
                }
                detectPartialDownloads()
            },
            onComplete = { file ->
                _whisperState.update {
                    it.copy(
                        modelPath = file.absolutePath,
                        downloadedVariants = if (variant != null) it.downloadedVariants + variant else it.downloadedVariants,
                        variantDownloadStates = it.variantDownloadStates.removeVariant(variant)
                    )
                }
                shareTargetManager.onModelDownloaded()
                if (variant != null) {
                    if (_uiState.value.modelName.isBlank()) useWhisperModel(variant)
                    val displayName = ctx.getString(variant.titleResId)
                    viewModelScope.launch { _snackbarEvent.tryEmit(SnackbarEvent.Message(ctx.getString(R.string.whisper_downloaded, displayName))) }
                }
            },
            onCancelled = {
                if (variant != null) {
                    _whisperState.update {
                        it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                            copy(isDownloading = false, errorMessage = null)
                        })
                    }
                }
                detectPartialDownloads()
            }
        )
    }

    private fun handleServiceProgressQwen3Asr(progress: ExtractionService.ExtractionProgress) {
        val variant = Qwen3AsrModelManager.Variant.entries
            .find { it.name.lowercase() == progress.variant }

        handleServiceProgress(
            state = progress.downloadState,
            updateFlow = { state, prog ->
                if (variant != null) {
                    _qwen3AsrState.update {
                        it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                            copy(downloadState = state, downloadProgress = prog ?: downloadProgress)
                        })
                    }
                }
            },
            onError = { msg, _ ->
                if (variant != null) {
                    _qwen3AsrState.update {
                        it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                            copy(isDownloading = false, errorMessage = msg)
                        })
                    }
                }
                detectPartialDownloads()
            },
            onComplete = { file ->
                _qwen3AsrState.update {
                    it.copy(
                        modelPath = file.absolutePath,
                        downloadedVariants = if (variant != null) it.downloadedVariants + variant else it.downloadedVariants,
                        variantDownloadStates = it.variantDownloadStates.removeVariant(variant)
                    )
                }
                shareTargetManager.onModelDownloaded()
                if (variant != null) {
                    if (_uiState.value.modelName.isBlank()) useQwen3AsrModel(variant)
                    val displayName = ctx.getString(variant.titleResId)
                    viewModelScope.launch { _snackbarEvent.tryEmit(SnackbarEvent.Message(ctx.getString(R.string.qwen3_asr_downloaded, displayName))) }
                }
            },
            onCancelled = {
                if (variant != null) {
                    _qwen3AsrState.update {
                        it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                            copy(isDownloading = false, errorMessage = null)
                        })
                    }
                }
                detectPartialDownloads()
            }
        )
    }

    private fun handleServiceProgressNemotron(progress: ExtractionService.ExtractionProgress) {
        handleServiceProgress(
            state = progress.downloadState,
            updateFlow = { state, prog ->
                _nemotronState.update {
                    it.copy(downloadState = state, downloadProgress = prog ?: it.downloadProgress)
                }
            },
            onError = { msg, _ ->
                _nemotronState.update { it.copy(isDownloading = false, errorMessage = msg) }
                detectPartialDownloads()
            },
            onComplete = { file ->
                _nemotronState.update {
                    it.copy(
                        modelPath = file.absolutePath,
                        isDownloading = false,
                        downloadProgress = 1f,
                        downloadState = DownloadState.Idle
                    )
                }
                shareTargetManager.onModelDownloaded()
                viewModelScope.launch {
                    preferencesManager.saveNemotronModelPath(file.absolutePath)
                }
                if (_uiState.value.modelName.isBlank()) useNemotronModel()
                viewModelScope.launch {
                    _snackbarEvent.tryEmit(SnackbarEvent.Message(ctx.getString(R.string.nemotron_downloaded, ctx.getString(R.string.nemotron_name))))
                }
            },
            onCancelled = {
                _nemotronState.update { it.copy(isDownloading = false, errorMessage = null) }
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
                shareTargetManager.onModelDownloaded()
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

    /**
     * Unified cancel for all model types. Stops the service, then on IO
     * detects any partial download and atomically transitions from
     * Downloading → PartiallyDownloaded (or removes the variant if clean).
     * The variant stays in the map until the IO work finishes, so the UI
     * never renders an intermediate Idle frame.
     */
    private fun <V> cancelVariantDownload(
        variant: V,
        cancelAction: () -> Unit,
        detectPartial: (Context, V) -> DownloadState.PartiallyDownloaded?,
        getCurrentStates: () -> Map<V, VariantDownloadState>,
        applyUpdatedStates: (Map<V, VariantDownloadState>) -> Unit,
        modelType: ExtractionService.ModelType,
        variantKey: String
    ) {
        cancelAction()
        stopExtractionService(modelType, variantKey)
        viewModelScope.launch(Dispatchers.IO) {
            val partial = detectPartial(ctx, variant)
            val current = getCurrentStates()
            val updated = if (partial != null) {
                current + (variant to VariantDownloadState(partialDownload = partial))
            } else {
                current - variant
            }
            applyUpdatedStates(updated)
        }
    }

    // ==================== Service helpers ====================

    /**
     * Sends a cancel intent to [ExtractionService].
     * When [modelType] and [variant] are provided, only that specific
     * download is cancelled; otherwise all active downloads are cancelled.
     */
    private fun stopExtractionService(
        modelType: ExtractionService.ModelType? = null,
        variant: String? = null
    ) {
        val context = ctx
        val intent = Intent(context, ExtractionService::class.java).apply {
            action = ExtractionService.ACTION_CANCEL
            modelType?.let { putExtra(ExtractionService.EXTRA_MODEL_TYPE, it.key) }
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

            // Check Parakeet variants
            val activeParakeetDownloads = _parakeetState.value.variantDownloadStates
                .filter { it.value.isDownloading }.keys
            val parakeetPartials = mutableMapOf<ParakeetModelManager.Variant, DownloadState.PartiallyDownloaded>()
            for (variant in ParakeetModelManager.Variant.entries) {
                if (!ParakeetDownloader.isModelDownloaded(context, variant) && variant !in activeParakeetDownloads) {
                    val partial = ParakeetDownloader.detectPartialDownload(context, variant)
                    if (partial != null) {
                        parakeetPartials[variant] = partial
                    }
                }
            }
            if (parakeetPartials.isNotEmpty()) {
                _parakeetState.update {
                    it.copy(variantDownloadStates = it.variantDownloadStates.mergePartials(parakeetPartials))
                }
            }

            // Check Whisper variants
            val activeWhisperDownloads = _whisperState.value.variantDownloadStates
                .filter { it.value.isDownloading }.keys
            val whisperNeedsExtraction = mutableSetOf<WhisperModelManager.Variant>()
            val whisperOrphaned = mutableSetOf<WhisperModelManager.Variant>()
            val whisperPartials = mutableMapOf<WhisperModelManager.Variant, DownloadState.PartiallyDownloaded>()
            for (variant in WhisperModelManager.Variant.entries) {
                if (!WhisperDownloader.isModelDownloaded(context, variant)) {
                    if (variant !in activeWhisperDownloads) {
                        val partial = WhisperDownloader.detectPartialDownload(context, variant)
                        if (partial != null) {
                            whisperPartials[variant] = partial
                        }
                    }
                    if (WhisperDownloader.needsExtraction(context, variant)) {
                        whisperNeedsExtraction.add(variant)
                    }
                    // Check for orphaned model directory
                    val modelDir = java.io.File(
                        WhisperModelManager.getModelStorageDir(context),
                        WhisperDownloader.getModelDirName(variant)
                    )
                    if (modelDir.exists()) {
                        whisperOrphaned.add(variant)
                    }
                }
            }
            _whisperState.update {
                it.copy(
                    variantDownloadStates = it.variantDownloadStates.mergePartials(whisperPartials),
                    variantsNeedingExtraction = whisperNeedsExtraction,
                    orphanedVariants = whisperOrphaned
                )
            }

            // Check Qwen3-ASR variants
            val activeQwen3Downloads = _qwen3AsrState.value.variantDownloadStates
                .filter { it.value.isDownloading }.keys
            val qwen3Partials = mutableMapOf<Qwen3AsrModelManager.Variant, DownloadState.PartiallyDownloaded>()
            for (variant in Qwen3AsrModelManager.Variant.entries) {
                if (!Qwen3AsrDownloader.isModelDownloaded(context, variant) && variant !in activeQwen3Downloads) {
                    val partial = Qwen3AsrDownloader.detectPartialDownload(context, variant)
                    if (partial != null) {
                        qwen3Partials[variant] = partial
                    }
                }
            }
            if (qwen3Partials.isNotEmpty()) {
                _qwen3AsrState.update {
                    it.copy(variantDownloadStates = it.variantDownloadStates.mergePartials(qwen3Partials))
                }
            }

            // GGUF: disabled — partial download detection skipped entirely

            // Check Nemotron (single-variant) — only when not actively downloading
            if (!_nemotronState.value.isDownloading && NemotronDownloader.getModelPath(context) == null) {
                val nemotronPartial = NemotronDownloader.detectPartialDownload(context)
                if (nemotronPartial != null) {
                    _nemotronState.update { it.copy(partialDownload = nemotronPartial) }
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

    private fun loadSavedModelPath() {
        viewModelScope.launch {
            // Check which backend is selected
            val backend = preferencesManager.transcriptionBackend.first()

            when (backend) {
                SherpaOnnxBackend.BACKEND_ID -> {
                    // Load Parakeet model path
                    val parakeetPath = preferencesManager.parakeetModelPath.first()
                    if (!parakeetPath.isNullOrBlank()) {
                        val isValid = File(parakeetPath).exists()
                        val parakeetName = ctx.getString(R.string.parakeet_name)
                        _uiState.update { it.copy(
                            modelPath = parakeetPath,
                            modelName = parakeetName,
                            statusMessage = if (isValid) ctx.getString(R.string.backend_model_ready, parakeetName) else ctx.getString(R.string.backend_model_not_found, parakeetName)
                        )}
                    }
                }
                WhisperBackend.BACKEND_ID -> {
                    // Load Whisper model path
                    val whisperPath = preferencesManager.whisperModelPath.first()
                    if (!whisperPath.isNullOrBlank()) {
                        val modelDir = File(whisperPath)
                        val isValid = modelDir.exists() && modelDir.isDirectory
                        val model = WhisperModelManager.validateModelDirectory(modelDir)
                        val modelName = model?.variant?.let { v ->
                            ctx.getString(v.titleResId)
                        } ?: whisperPath.substringAfterLast("/")
                        _uiState.update { it.copy(
                            modelPath = whisperPath,
                            modelName = modelName,
                            statusMessage = if (isValid) ctx.getString(R.string.backend_model_ready, modelName) else ctx.getString(R.string.backend_model_not_found, modelName)
                        )}
                    }
                }
                Qwen3AsrBackend.BACKEND_ID -> {
                    // Load Qwen3-ASR model path
                    val qwen3Path = preferencesManager.qwen3AsrModelPath.first()
                    if (!qwen3Path.isNullOrBlank()) {
                        val modelDir = File(qwen3Path)
                        val isValid = modelDir.exists() && modelDir.isDirectory
                        val model = Qwen3AsrModelManager.validateModelDirectory(modelDir)
                        val modelName = model?.variant?.let { v ->
                            ctx.getString(v.titleResId)
                        } ?: qwen3Path.substringAfterLast("/")
                        _uiState.update { it.copy(
                            modelPath = qwen3Path,
                            modelName = modelName,
                            statusMessage = if (isValid) ctx.getString(R.string.backend_model_ready, modelName) else ctx.getString(R.string.backend_model_not_found, modelName)
                        )}
                    }
                }
                NemotronStreamingBackend.BACKEND_ID -> {
                    // Load Nemotron model path
                    val nemotronPath = preferencesManager.nemotronModelPath.first()
                    if (!nemotronPath.isNullOrBlank()) {
                        val modelDir = File(nemotronPath)
                        val isValid = modelDir.exists() && modelDir.isDirectory
                        val modelName = ctx.getString(R.string.nemotron_name)
                        _uiState.update { it.copy(
                            modelPath = nemotronPath,
                            modelName = modelName,
                            statusMessage = if (isValid) ctx.getString(R.string.backend_model_ready, modelName) else ctx.getString(R.string.backend_model_not_found, modelName)
                        )}
                    }
                }
                "gemma4_gguf" -> {
                    // GGUF: disabled — show filename only since Gemma4GgufModelManager is excluded
                    val ggufPath = preferencesManager.ggufModelPath.first()
                    if (!ggufPath.isNullOrBlank()) {
                        val ggufFile = File(ggufPath)
                        val isValid = ggufFile.exists() && ggufFile.isFile
                        val modelName = ggufPath.substringAfterLast("/")
                        _uiState.update { it.copy(
                            modelPath = ggufPath,
                            modelName = modelName,
                            statusMessage = if (isValid) ctx.getString(R.string.backend_model_ready, modelName) else ctx.getString(R.string.backend_model_not_found, modelName)
                        )}
                    }
                }
                else -> {
                    // Load LLM model path (Gemma, etc.)
                    val savedPath = preferencesManager.modelPath.first()
                    if (!savedPath.isNullOrBlank()) {
                        val isValid = validateModelPath(savedPath)
                        _uiState.update { it.copy(
                            modelPath = savedPath,
                            modelName = extractFileName(savedPath),
                            statusMessage = if (isValid) ctx.getString(R.string.saved_model_found) else ctx.getString(R.string.saved_model_not_found)
                        )}
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
                // Persist the model path
                preferencesManager.saveModelPath(copiedPath)

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

            _downloadUiState.update { it.copy(downloadedModels = downloaded) }
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
            putExtra(ExtractionService.EXTRA_MODEL_TYPE, ExtractionService.ModelType.GEMMA.key)
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
    fun cancelDownload(variant: ModelDownloader.ModelVariant) = cancelVariantDownload(
        variant,
        cancelAction = { ModelDownloader.cancel(variant) },
        detectPartial = { ctx, v -> ModelDownloader.detectPartialDownload(ctx, v) },
        getCurrentStates = { _downloadUiState.value.variantDownloadStates },
        applyUpdatedStates = { states -> _downloadUiState.update { it.copy(variantDownloadStates = states, downloadError = null) } },
        modelType = ExtractionService.ModelType.GEMMA,
        variantKey = variantKey(variant)
    )

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
                // Switch to LLM backend when selecting an LLM model
                preferencesManager.saveTranscriptionBackend(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND)
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
        _downloadUiState.update { it.copy(showDownloadDialog = false) }
        startDownload(variant)
    }

    // ==================== Parakeet Model Download ====================

    /**
     * Refreshes the Parakeet model state. Discovers downloaded variants and resolves
     * the auto-fallback active model path (prefer SmoothQuant, else Stock int8).
     */
    fun refreshParakeetState() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = ctx
            val downloadedVariants = ParakeetModelManager.Variant.entries
                .filter { ParakeetDownloader.isModelDownloaded(context, it) }
                .toSet()
            val activePath = ParakeetModelManager.resolveActiveModelPath(context)
            _parakeetState.update {
                it.copy(
                    downloadedVariants = downloadedVariants,
                    modelPath = activePath
                )
            }
        }
    }

    // ==================== Parakeet Confirmation Dialogs ====================

    fun showParakeetDownloadDialog(variant: ParakeetModelManager.Variant) {
        _parakeetState.update { it.copy(showDownloadDialog = true, selectedVariant = variant) }
    }

    fun dismissParakeetDownloadDialog() {
        _parakeetState.update { it.copy(showDownloadDialog = false) }
    }

    fun confirmParakeetDownload() {
        val variant = _parakeetState.value.selectedVariant ?: return
        startParakeetDownload(variant)
    }

    fun showParakeetDeleteDialog(variant: ParakeetModelManager.Variant) {
        _parakeetState.update { it.copy(showDeleteDialog = true, variantToDelete = variant) }
    }

    fun dismissParakeetDeleteDialog() {
        _parakeetState.update { it.copy(showDeleteDialog = false, variantToDelete = null) }
    }

    fun confirmParakeetDelete() {
        val variant = _parakeetState.value.variantToDelete ?: return
        _parakeetState.update { it.copy(showDeleteDialog = false) }
        deleteParakeetModel(variant)
    }

    // ==================== Parakeet Download Methods ====================

    fun startParakeetDownload(variant: ParakeetModelManager.Variant) {
        _parakeetState.update {
            it.copy(
                showDownloadDialog = false,
                selectedVariant = variant,
                variantDownloadStates = it.variantDownloadStates + (variant to VariantDownloadState(
                    downloadState = DownloadState.Connecting(""),
                    downloadProgress = 0f,
                    isDownloading = true,
                    errorMessage = null,
                    partialDownload = null
                ))
            )
        }
        val context = ctx
        val intent = Intent(context, ExtractionService::class.java).apply {
            putExtra(ExtractionService.EXTRA_MODEL_TYPE, ExtractionService.ModelType.PARAKEET.key)
            putExtra(ExtractionService.EXTRA_VARIANT, variantKey(variant))
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun cancelParakeetDownload(variant: ParakeetModelManager.Variant) = cancelVariantDownload(
        variant,
        cancelAction = { ParakeetDownloader.cancel(variant) },
        detectPartial = { ctx, v -> ParakeetDownloader.detectPartialDownload(ctx, v) },
        getCurrentStates = { _parakeetState.value.variantDownloadStates },
        applyUpdatedStates = { states -> _parakeetState.update { it.copy(variantDownloadStates = states) } },
        modelType = ExtractionService.ModelType.PARAKEET,
        variantKey = variantKey(variant)
    )

    fun resumeParakeetDownload(variant: ParakeetModelManager.Variant) {
        _parakeetState.update { it.copy(
            variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                copy(partialDownload = null)
            }
        ) }
        startParakeetDownload(variant)
    }

    fun clearParakeetPartialDownload(variant: ParakeetModelManager.Variant) {
        viewModelScope.launch(Dispatchers.IO) {
            ParakeetDownloader.clearPartialDownload(ctx, variant)
            _parakeetState.update {
                it.copy(variantDownloadStates = it.variantDownloadStates - variant)
            }
        }
    }

    /**
     * Uses the Parakeet model (switches backend to sherpa-onnx).
     *
     * The active variant is auto-resolved via [ParakeetModelManager.resolveActiveModelPath]
     * (prefer SmoothQuant, else Stock int8). There is no user variant selector for Parakeet.
     */
    fun useParakeetModel() {
        viewModelScope.launch {
            val context = ctx
            val modelPath = ParakeetModelManager.resolveActiveModelPath(context)
                ?: _parakeetState.value.modelPath
            if (modelPath != null) {
                // Save resolved Parakeet path and switch backend preference
                preferencesManager.saveParakeetModelPath(modelPath)
                preferencesManager.saveTranscriptionBackend(SherpaOnnxBackend.BACKEND_ID)

                val message = ctx.getString(R.string.model_selected_message, ctx.getString(R.string.parakeet_name))
                _uiState.update { it.copy(
                    modelPath = modelPath,
                    modelName = ctx.getString(R.string.parakeet_name),
                    status = ModelStatus.UNLOADED,
                    statusMessage = message
                )}

                _snackbarEvent.tryEmit(SnackbarEvent.Message(message))
                llmManager.resetKeepAliveTimer()
            }
        }
    }

    fun deleteParakeetModel(variant: ParakeetModelManager.Variant) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = ctx
            val success = ParakeetDownloader.deleteModel(context, variant)
            if (success) {
                _parakeetState.update {
                    it.copy(downloadedVariants = _parakeetState.value.downloadedVariants - variant)
                }
                // Re-resolve the active path; if the deleted variant was the saved one, clear it.
                val activePath = ParakeetModelManager.resolveActiveModelPath(context)
                val savedPath = preferencesManager.parakeetModelPath.first()
                if (savedPath != null && savedPath.contains(ParakeetDownloader.getModelDirName(variant))) {
                    if (activePath != null) {
                        preferencesManager.saveParakeetModelPath(activePath)
                    } else {
                        preferencesManager.saveParakeetModelPath("")
                        shareTargetManager.onModelDeleted(SherpaOnnxBackend.BACKEND_ID)
                    }
                }
                _parakeetState.update { it.copy(modelPath = activePath) }
                _snackbarEvent.tryEmit(SnackbarEvent.Message(context.getString(R.string.parakeet_deleted)))
            }
        }
    }

    // ==================== Whisper Model Download ====================

    /**
     * Refreshes the Whisper model state.
     */
    fun refreshWhisperState() {
        viewModelScope.launch {
            val context = ctx
            val downloadedVariants = WhisperModelManager.Variant.entries
                .filter { WhisperDownloader.isModelDownloaded(context, it) }
                .toSet()

            _whisperState.update { it.copy(
                downloadedVariants = downloadedVariants
            )}
        }
    }

    // ==================== Whisper Confirmation Dialogs ====================

    /**
     * Shows the Whisper download confirmation dialog.
     */
    fun showWhisperDownloadDialog(variant: WhisperModelManager.Variant) {
        _whisperState.update { it.copy(
            selectedVariant = variant,
            showDownloadDialog = true
        )}
    }

    /**
     * Dismisses the Whisper download confirmation dialog.
     */
    fun dismissWhisperDownloadDialog() {
        _whisperState.update { it.copy(showDownloadDialog = false) }
    }

    /**
     * Confirms and starts the Whisper download.
     */
    fun confirmWhisperDownload() {
        val variant = _whisperState.value.selectedVariant ?: return
        val needsExtraction = _whisperState.value.variantsNeedingExtraction.contains(variant)
        startWhisperDownload(
            variant,
            dismissDialog = true,
            initialState = if (needsExtraction) DownloadState.Extracting(0, 0) else DownloadState.Connecting("")
        )
    }

    /**
     * Shows the Whisper delete confirmation dialog.
     */
    fun showWhisperDeleteDialog(variant: WhisperModelManager.Variant) {
        _whisperState.update { it.copy(
            variantToDelete = variant,
            showDeleteDialog = true
        )}
    }

    /**
     * Dismisses the Whisper delete confirmation dialog.
     */
    fun dismissWhisperDeleteDialog() {
        _whisperState.update { it.copy(
            showDeleteDialog = false,
            variantToDelete = null
        )}
    }

    /**
     * Confirms and deletes the Whisper model.
     */
    fun confirmWhisperDelete() {
        val variant = _whisperState.value.variantToDelete ?: return
        _whisperState.update { it.copy(showDeleteDialog = false) }
        deleteWhisperModel(variant)
    }

    /**
     * Starts downloading a Whisper model variant via [ExtractionService].
     */
    fun startWhisperDownload(
        variant: WhisperModelManager.Variant,
        dismissDialog: Boolean = false,
        initialState: DownloadState = DownloadState.Connecting("")
    ) {
        _whisperState.update { it.copy(
            showDownloadDialog = if (dismissDialog) false else it.showDownloadDialog,
            selectedVariant = variant,
            variantDownloadStates = it.variantDownloadStates + (variant to VariantDownloadState(
                downloadState = initialState,
                downloadProgress = 0f,
                isDownloading = true,
                errorMessage = null,
                partialDownload = null
            ))
        )}

        val context = ctx
        val intent = Intent(context, ExtractionService::class.java).apply {
            putExtra(ExtractionService.EXTRA_MODEL_TYPE, ExtractionService.ModelType.WHISPER.key)
            putExtra(ExtractionService.EXTRA_VARIANT, variantKey(variant))
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Resumes a partial Whisper download.
     */
    fun resumeWhisperDownload(variant: WhisperModelManager.Variant) {
        _whisperState.update { it.copy(
            variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                copy(partialDownload = null)
            }
        ) }
        startWhisperDownload(variant)
    }

    /**
     * Clears a partial Whisper download (tar file only).
     * The model directory is preserved — use [clearOrphanedWhisperFiles] to remove it.
     */
    fun clearWhisperPartialDownload(variant: WhisperModelManager.Variant) {
        viewModelScope.launch {
            val context = ctx
            WhisperDownloader.clearPartialDownload(context, variant)
            _whisperState.update {
                it.copy(
                    variantDownloadStates = it.variantDownloadStates - variant,
                    variantsNeedingExtraction = emptySet()
                )
            }
            detectPartialDownloads()
        }
    }

    /**
     * Clears an orphaned Whisper model directory (partial extraction leftovers).
     * The tar file is preserved so extraction can be retried.
     */
    fun clearOrphanedWhisperFiles(variant: WhisperModelManager.Variant) {
        viewModelScope.launch {
            val context = ctx
            WhisperDownloader.deleteModel(context, variant)
            _whisperState.update {
                it.copy(
                    orphanedVariants = it.orphanedVariants - variant
                )
            }
            detectPartialDownloads()
        }
    }

    /**
     * Cancels the Whisper download.
     */
    fun cancelWhisperDownload(variant: WhisperModelManager.Variant) = cancelVariantDownload(
        variant,
        cancelAction = { WhisperDownloader.cancel(variant) },
        detectPartial = { ctx, v -> WhisperDownloader.detectPartialDownload(ctx, v) },
        getCurrentStates = { _whisperState.value.variantDownloadStates },
        applyUpdatedStates = { states -> _whisperState.update { it.copy(variantDownloadStates = states) } },
        modelType = ExtractionService.ModelType.WHISPER,
        variantKey = variantKey(variant)
    )

    /**
     * Uses the Whisper model (switches backend to whisper).
     */
    fun useWhisperModel(variant: WhisperModelManager.Variant) {
        viewModelScope.launch {
            val context = ctx
            val modelPath = WhisperDownloader.getModelPath(context, variant)
            if (modelPath != null) {
                val displayName = context.getString(variant.titleResId)
                // Save Whisper model path and switch backend preference
                preferencesManager.saveWhisperModelPath(modelPath)
                preferencesManager.saveTranscriptionBackend(WhisperBackend.BACKEND_ID)

                val message = context.getString(R.string.model_selected_message, displayName)
                _uiState.update { it.copy(
                    modelName = displayName,
                    status = ModelStatus.UNLOADED,
                    statusMessage = message
                )}

                _snackbarEvent.tryEmit(SnackbarEvent.Message(message))
            }
        }
    }

    /**
     * Deletes a Whisper model variant.
     */
    fun deleteWhisperModel(variant: WhisperModelManager.Variant) {
        viewModelScope.launch {
            val context = ctx
            val success = WhisperDownloader.deleteModel(context, variant)
            if (success) {
                val displayName = context.getString(variant.titleResId)
                // Update state to remove the variant
                _whisperState.update { it.copy(
                    downloadedVariants = _whisperState.value.downloadedVariants - variant
                )}
                // Clear saved path if this was the selected variant
                val savedPath = preferencesManager.whisperModelPath.first()
                if (savedPath?.contains(variant.dirName) == true) {
                    preferencesManager.clearWhisperModelPath()
                    _uiState.update { it.copy(modelPath = "", modelName = "") }
                    shareTargetManager.onModelDeleted(WhisperBackend.BACKEND_ID)
                }
                _snackbarEvent.tryEmit(SnackbarEvent.Message(context.getString(R.string.whisper_deleted, displayName)))
            }
        }
    }

    // ==================== Qwen3-ASR Model Download ====================

    /**
     * Refreshes the Qwen3-ASR model state.
     */
    fun refreshQwen3AsrState() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = ctx
            val downloadedVariants = Qwen3AsrModelManager.Variant.entries
                .filter { Qwen3AsrDownloader.isModelDownloaded(context, it) }
                .toSet()
            _qwen3AsrState.update { it.copy(downloadedVariants = downloadedVariants) }
        }
    }

    // ==================== Qwen3-ASR Confirmation Dialogs ====================

    fun showQwen3AsrDownloadDialog(variant: Qwen3AsrModelManager.Variant) {
        _qwen3AsrState.update { it.copy(showDownloadDialog = true, selectedVariant = variant) }
    }

    fun dismissQwen3AsrDownloadDialog() {
        _qwen3AsrState.update { it.copy(showDownloadDialog = false) }
    }

    fun confirmQwen3AsrDownload() {
        val variant = _qwen3AsrState.value.selectedVariant ?: return
        startQwen3AsrDownload(variant)
    }

    fun showQwen3AsrDeleteDialog(variant: Qwen3AsrModelManager.Variant) {
        _qwen3AsrState.update { it.copy(showDeleteDialog = true, variantToDelete = variant) }
    }

    fun dismissQwen3AsrDeleteDialog() {
        _qwen3AsrState.update { it.copy(showDeleteDialog = false, variantToDelete = null) }
    }

    fun confirmQwen3AsrDelete() {
        val variant = _qwen3AsrState.value.variantToDelete ?: return
        _qwen3AsrState.update { it.copy(showDeleteDialog = false) }
        deleteQwen3AsrModel(variant)
    }

    // ==================== Qwen3-ASR Download Methods ====================

    fun startQwen3AsrDownload(variant: Qwen3AsrModelManager.Variant) {
        _qwen3AsrState.update {
            it.copy(
                showDownloadDialog = false,
                selectedVariant = variant,
                variantDownloadStates = it.variantDownloadStates + (variant to VariantDownloadState(
                    downloadState = DownloadState.Connecting(""),
                    downloadProgress = 0f,
                    isDownloading = true,
                    errorMessage = null,
                    partialDownload = null
                ))
            )
        }
        val context = ctx
        val intent = Intent(context, ExtractionService::class.java).apply {
            putExtra(ExtractionService.EXTRA_MODEL_TYPE, ExtractionService.ModelType.QWEN3_ASR.key)
            putExtra(ExtractionService.EXTRA_VARIANT, variantKey(variant))
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun cancelQwen3AsrDownload(variant: Qwen3AsrModelManager.Variant) = cancelVariantDownload(
        variant,
        cancelAction = { Qwen3AsrDownloader.cancel(variant) },
        detectPartial = { ctx, v -> Qwen3AsrDownloader.detectPartialDownload(ctx, v) },
        getCurrentStates = { _qwen3AsrState.value.variantDownloadStates },
        applyUpdatedStates = { states -> _qwen3AsrState.update { it.copy(variantDownloadStates = states) } },
        modelType = ExtractionService.ModelType.QWEN3_ASR,
        variantKey = variantKey(variant)
    )

    fun resumeQwen3AsrDownload(variant: Qwen3AsrModelManager.Variant) {
        _qwen3AsrState.update { it.copy(
            variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                copy(partialDownload = null)
            }
        ) }
        startQwen3AsrDownload(variant)
    }

    fun clearQwen3AsrPartialDownload(variant: Qwen3AsrModelManager.Variant) {
        viewModelScope.launch(Dispatchers.IO) {
            Qwen3AsrDownloader.clearPartialDownload(ctx, variant)
            _qwen3AsrState.update {
                it.copy(variantDownloadStates = it.variantDownloadStates - variant)
            }
        }
    }

    fun useQwen3AsrModel(variant: Qwen3AsrModelManager.Variant) {
        viewModelScope.launch {
            val context = ctx
            val modelPath = Qwen3AsrDownloader.getModelPath(context, variant)
            if (modelPath != null) {
                preferencesManager.saveQwen3AsrModelPath(modelPath)
                preferencesManager.saveTranscriptionBackend(Qwen3AsrBackend.BACKEND_ID)

                val displayName = context.getString(variant.titleResId)
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
            }
        }
    }

    fun deleteQwen3AsrModel(variant: Qwen3AsrModelManager.Variant) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = ctx
            val success = Qwen3AsrDownloader.deleteModel(context, variant)
            if (success) {
                _qwen3AsrState.update {
                    it.copy(downloadedVariants = _qwen3AsrState.value.downloadedVariants - variant)
                }
                val savedPath = preferencesManager.qwen3AsrModelPath.first()
                if (savedPath != null && savedPath.contains(Qwen3AsrDownloader.getModelDirName(variant))) {
                    preferencesManager.clearQwen3AsrModelPath()
                    _uiState.update { it.copy(modelPath = "", modelName = "") }
                    shareTargetManager.onModelDeleted(Qwen3AsrBackend.BACKEND_ID)
                }
                val displayName = context.getString(variant.titleResId)
                _snackbarEvent.tryEmit(SnackbarEvent.Message(context.getString(R.string.qwen3_asr_deleted, displayName)))
            }
        }
    }

    // ==================== Nemotron Model Download ====================

    /**
     * Refreshes the Nemotron model state. Single-variant: checks whether the model
     * is downloaded and resolves its path.
     */
    fun refreshNemotronState() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = ctx
            val path = NemotronDownloader.getModelPath(context)
            _nemotronState.update { it.copy(modelPath = path) }
        }
    }

    // ==================== Nemotron Confirmation Dialogs ====================

    fun showNemotronDownloadDialog() {
        _nemotronState.update { it.copy(showDownloadDialog = true) }
    }

    fun dismissNemotronDownloadDialog() {
        _nemotronState.update { it.copy(showDownloadDialog = false) }
    }

    fun confirmNemotronDownload() {
        startNemotronDownload()
    }

    fun showNemotronDeleteDialog() {
        _nemotronState.update { it.copy(showDeleteDialog = true) }
    }

    fun dismissNemotronDeleteDialog() {
        _nemotronState.update { it.copy(showDeleteDialog = false) }
    }

    fun confirmNemotronDelete() {
        _nemotronState.update { it.copy(showDeleteDialog = false) }
        deleteNemotronModel()
    }

    // ==================== Nemotron Download Methods ====================

    fun startNemotronDownload() {
        _nemotronState.update {
            it.copy(
                showDownloadDialog = false,
                isDownloading = true,
                downloadProgress = 0f,
                downloadState = DownloadState.Connecting(""),
                errorMessage = null,
                partialDownload = null
            )
        }
        val context = ctx
        val intent = Intent(context, ExtractionService::class.java).apply {
            putExtra(ExtractionService.EXTRA_MODEL_TYPE, ExtractionService.ModelType.NEMOTRON.key)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun cancelNemotronDownload() {
        NemotronDownloader.cancel()
        stopExtractionService(ExtractionService.ModelType.NEMOTRON, null)
        viewModelScope.launch(Dispatchers.IO) {
            val partial = NemotronDownloader.detectPartialDownload(ctx)
            _nemotronState.update {
                it.copy(isDownloading = false, partialDownload = partial)
            }
        }
    }

    fun resumeNemotronDownload() {
        _nemotronState.update { it.copy(partialDownload = null) }
        startNemotronDownload()
    }

    fun clearNemotronPartialDownload() {
        viewModelScope.launch(Dispatchers.IO) {
            NemotronDownloader.clearPartialDownload(ctx)
            _nemotronState.update { it.copy(partialDownload = null) }
        }
    }

    fun useNemotronModel() {
        viewModelScope.launch {
            val context = ctx
            val modelPath = NemotronDownloader.getModelPath(context) ?: _nemotronState.value.modelPath
            if (modelPath != null) {
                preferencesManager.saveNemotronModelPath(modelPath)
                preferencesManager.saveTranscriptionBackend(NemotronStreamingBackend.BACKEND_ID)

                val displayName = context.getString(R.string.nemotron_name)
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
            }
        }
    }

    fun deleteNemotronModel() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = ctx
            val success = NemotronDownloader.deleteModel(context)
            if (success) {
                val savedPath = preferencesManager.nemotronModelPath.first()
                if (savedPath != null) {
                    preferencesManager.clearNemotronModelPath()
                    _uiState.update { it.copy(modelPath = "", modelName = "") }
                    shareTargetManager.onModelDeleted(NemotronStreamingBackend.BACKEND_ID)
                }
                _nemotronState.update { it.copy(modelPath = null) }
                _snackbarEvent.tryEmit(SnackbarEvent.Message(context.getString(R.string.nemotron_deleted, context.getString(R.string.nemotron_name))))
            }
        }
    }

    // ==================== GGUF: DISABLED ====================
    // Move files from app/src/gguf-disabled/ back to main and uncomment to re-enable
    /* GGUF disabled

    private fun handleServiceProgressGguf(progress: ExtractionService.ExtractionProgress) {
        val variant = Gemma4GgufModelManager.GgufVariant.entries
            .find { it.name.lowercase() == progress.variant }

        handleServiceProgress(
            state = progress.downloadState,
            updateFlow = { state, prog ->
                if (variant != null) {
                    _ggufState.update {
                        it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                            copy(downloadState = state, downloadProgress = prog ?: downloadProgress)
                        })
                    }
                }
            },
            onError = { msg, _ ->
                if (variant != null) {
                    _ggufState.update {
                        it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                            copy(isDownloading = false, errorMessage = msg)
                        })
                    }
                }
                detectPartialDownloads()
            },
            onComplete = { file ->
                _ggufState.update {
                    it.copy(
                        modelPath = file.absolutePath,
                        downloadedVariants = if (variant != null) it.downloadedVariants + variant else it.downloadedVariants,
                        variantDownloadStates = it.variantDownloadStates.removeVariant(variant)
                    )
                }
                if (variant != null) {
                    if (_uiState.value.modelName.isBlank()) useGgufModel(variant)
                    val displayName = ctx.getString(variant.titleResId)
                    viewModelScope.launch { _snackbarEvent.tryEmit(SnackbarEvent.Message(ctx.getString(R.string.gguf_downloaded, displayName))) }
                }
            },
            onCancelled = {
                if (variant != null) {
                    _ggufState.update {
                        it.copy(variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                            copy(isDownloading = false, errorMessage = null)
                        })
                    }
                }
                detectPartialDownloads()
            }
        )
    }

    fun refreshGgufState() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = ctx
            val downloadedVariants = Gemma4GgufModelManager.GgufVariant.entries
                .filter { Gemma4GgufModelManager.isDownloaded(context, it) }
                .toSet()
            _ggufState.update { it.copy(downloadedVariants = downloadedVariants) }
        }
    }

    // ==================== GGUF Confirmation Dialogs ====================

    fun showGgufDownloadDialog(variant: Gemma4GgufModelManager.GgufVariant) {
        _ggufState.update { it.copy(showDownloadDialog = true, selectedVariant = variant) }
    }

    fun dismissGgufDownloadDialog() {
        _ggufState.update { it.copy(showDownloadDialog = false) }
    }

    fun confirmGgufDownload() {
        val variant = _ggufState.value.selectedVariant ?: return
        startGgufDownload(variant)
    }

    fun showGgufDeleteDialog(variant: Gemma4GgufModelManager.GgufVariant) {
        _ggufState.update { it.copy(showDeleteDialog = true, variantToDelete = variant) }
    }

    fun dismissGgufDeleteDialog() {
        _ggufState.update { it.copy(showDeleteDialog = false, variantToDelete = null) }
    }

    fun confirmGgufDelete() {
        val variant = _ggufState.value.variantToDelete ?: return
        _ggufState.update { it.copy(showDeleteDialog = false) }
        deleteGgufModel(variant)
    }

    // ==================== GGUF Download Methods ====================

    fun startGgufDownload(variant: Gemma4GgufModelManager.GgufVariant) {
        _ggufState.update {
            it.copy(
                showDownloadDialog = false,
                selectedVariant = variant,
                variantDownloadStates = it.variantDownloadStates + (variant to VariantDownloadState(
                    downloadState = DownloadState.Connecting(""),
                    downloadProgress = 0f,
                    isDownloading = true,
                    errorMessage = null,
                    partialDownload = null
                ))
            )
        }
        val context = ctx
        val intent = Intent(context, ExtractionService::class.java).apply {
            putExtra(ExtractionService.EXTRA_MODEL_TYPE, ExtractionService.ModelType.GEMMA4_GGUF.key)
            putExtra(ExtractionService.EXTRA_VARIANT, variantKey(variant))
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun cancelGgufDownload(variant: Gemma4GgufModelManager.GgufVariant) = cancelVariantDownload(
        variant,
        cancelAction = { Gemma4GgufModelManager.cancel(variant) },
        detectPartial = { ctx, v -> Gemma4GgufModelManager.detectPartialDownload(ctx, v) },
        getCurrentStates = { _ggufState.value.variantDownloadStates },
        applyUpdatedStates = { states -> _ggufState.update { it.copy(variantDownloadStates = states) } },
        modelType = ExtractionService.ModelType.GEMMA4_GGUF,
        variantKey = variantKey(variant)
    )

    fun resumeGgufDownload(variant: Gemma4GgufModelManager.GgufVariant) {
        _ggufState.update { it.copy(
            variantDownloadStates = it.variantDownloadStates.updateVariant(variant) {
                copy(partialDownload = null)
            }
        ) }
        startGgufDownload(variant)
    }

    fun clearGgufPartialDownload(variant: Gemma4GgufModelManager.GgufVariant) {
        viewModelScope.launch(Dispatchers.IO) {
            Gemma4GgufModelManager.clearPartialDownload(ctx, variant)
            _ggufState.update {
                it.copy(variantDownloadStates = it.variantDownloadStates - variant)
            }
        }
    }
    */

    // GGUF: disabled — move files from gguf-disabled/ to re-enable
    // fun useGgufModel(variant: Gemma4GgufModelManager.GgufVariant) { ... }
    // fun deleteGgufModel(variant: Gemma4GgufModelManager.GgufVariant) { ... }

    /**
     * Sets a downloaded File as the active model.
     * Used internally after successful download.
     */
    private fun setDownloadedModel(file: File) {
        viewModelScope.launch {
            val modelPath = file.absolutePath
            preferencesManager.saveModelPath(modelPath)
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

            val config = when (backendId) {
                WhisperBackend.BACKEND_ID -> {
                    val lang = preferencesManager.transcriptionLanguage.first()
                    BackendConfig.SherpaOnnxConfig(
                        modelDir = modelPath,
                        numThreads = threadCount,
                        language = if (lang == "auto") "" else lang,
                        provider = resolvedProvider
                    )
                }
                SherpaOnnxBackend.BACKEND_ID, Qwen3AsrBackend.BACKEND_ID, NemotronStreamingBackend.BACKEND_ID -> BackendConfig.SherpaOnnxConfig(
                    modelDir = modelPath,
                    numThreads = threadCount,
                    provider = resolvedProvider
                )
                "gemma4_gguf" /* GGUF: Gemma4GgufBackend.BACKEND_ID */ -> BackendConfig.GgufConfig(
                    modelPath = modelPath,
                    threadCount = threadCount
                )
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
