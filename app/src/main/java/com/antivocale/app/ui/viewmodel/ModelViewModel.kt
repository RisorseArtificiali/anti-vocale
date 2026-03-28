package com.antivocale.app.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.antivocale.app.data.HuggingFaceTokenManager
import com.antivocale.app.data.ModelDownloader
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.R
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.di.AppContainer
import com.antivocale.app.manager.LlmManager
import com.antivocale.app.service.ExtractionService
import com.antivocale.app.transcription.ParakeetDownloader
import com.antivocale.app.transcription.ParakeetModelManager
import com.antivocale.app.transcription.WhisperDownloader
import com.antivocale.app.transcription.WhisperModelManager
import com.antivocale.app.transcription.TranscriptionBackendManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class ModelViewModel(
    private val preferencesManager: PreferencesManager,
    private val tokenManager: HuggingFaceTokenManager
) : ViewModel() {

    // Set up auto-unload callback
    init {
        LlmManager.setOnAutoUnloadCallback {
            onModelAutoUnloaded()
        }
    }

    enum class ModelStatus {
        UNLOADED, LOADING, READY, ERROR
    }

    /**
     * Download UI state for model download management
     */
    data class DownloadUiState(
        val selectedVariant: ModelDownloader.ModelVariant? = null,
        val isDownloading: Boolean = false,
        val downloadProgress: Float = 0f,
        val downloadState: DownloadState = DownloadState.Idle,
        val downloadError: ModelDownloader.DownloadError? = null,
        val downloadedModels: Set<ModelDownloader.ModelVariant> = emptySet(),
        val hasToken: Boolean = false,
        val modelToDelete: ModelDownloader.ModelVariant? = null,
        val partialDownload: DownloadState.PartiallyDownloaded? = null,
        val partialDownloadVariant: ModelDownloader.ModelVariant? = null,
        val showDownloadDialog: Boolean = false
    )

    /**
     * Parakeet download UI state
     */
    data class ParakeetUiState(
        val isDownloaded: Boolean = false,
        val isDownloading: Boolean = false,
        val downloadProgress: Float = 0f,
        val downloadState: DownloadState = DownloadState.Idle,
        val modelPath: String? = null,
        val errorMessage: String? = null,
        // Confirmation dialogs
        val showDownloadDialog: Boolean = false,
        val showDeleteDialog: Boolean = false,
        val partialDownload: DownloadState.PartiallyDownloaded? = null,
        val needsExtraction: Boolean = false,
        val hasOrphanedFiles: Boolean = false
    )

    /**
     * Whisper download UI state
     */
    data class WhisperUiState(
        val selectedVariant: WhisperModelManager.Variant? = null,
        val downloadedVariants: Set<WhisperModelManager.Variant> = emptySet(),
        val isDownloading: Boolean = false,
        val downloadProgress: Float = 0f,
        val downloadState: DownloadState = DownloadState.Idle,
        val modelPath: String? = null,
        val errorMessage: String? = null,
        // Confirmation dialogs
        val showDownloadDialog: Boolean = false,
        val showDeleteDialog: Boolean = false,
        val variantToDelete: WhisperModelManager.Variant? = null,
        val partialDownload: DownloadState.PartiallyDownloaded? = null,
        val partialDownloadVariant: WhisperModelManager.Variant? = null,
        val variantsNeedingExtraction: Set<WhisperModelManager.Variant> = emptySet(),
        val orphanedVariants: Set<WhisperModelManager.Variant> = emptySet()
    )

    data class UiState(
        val status: ModelStatus = ModelStatus.UNLOADED,
        val statusMessage: String = "",
        val modelPath: String = "",
        val modelName: String = "",
        val memoryUsage: Long = 0,
        val isModelPathValid: Boolean = false
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

    // Channel for one-time Snackbar events (guarantees delivery)
    private val _snackbarEvent = Channel<String>()
    val snackbarEvent: kotlinx.coroutines.flow.Flow<String> = _snackbarEvent.receiveAsFlow()

    init {
        // Set up auto-unload callback
        LlmManager.setOnAutoUnloadCallback {
            onModelAutoUnloaded()
        }
        // Set up external load callback (e.g., from ModelPreloadReceiver)
        LlmManager.setOnExternalLoadCallback { modelPath ->
            onModelExternallyLoaded(modelPath)
        }
        // Observe ExtractionService progress state
        viewModelScope.launch {
            ExtractionService.progressState.collect { progress ->
                if (progress == null) return@collect
                when (progress.modelType) {
                    ExtractionService.ModelType.PARAKEET -> handleServiceProgressParakeet(progress)
                    ExtractionService.ModelType.WHISPER -> handleServiceProgressWhisper(progress)
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
        // Detect partial downloads
        detectPartialDownloads()
    }

    // ==================== Service progress handlers ====================

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
        val progress: Float? = when (state) {
            is DownloadState.Downloading -> state.progressPercent / 100f
            is DownloadState.Complete -> 1f
            else -> null
        }
        // Always update the flow so the UI reflects every state transition
        // (e.g., Downloading → Extracting → Complete). When progress is null,
        // the caller preserves the existing progress value.
        updateFlow(state, progress)

        when (state) {
            is DownloadState.Error -> {
                onError(state.message, state.throwable)
                viewModelScope.launch { _snackbarEvent.send(state.message) }
                ExtractionService.clearProgress()
            }
            is DownloadState.Complete -> {
                onComplete(state.file)
                ExtractionService.clearProgress()
            }
            is DownloadState.Cancelled -> {
                onCancelled()
                ExtractionService.clearProgress()
            }
            else -> {}
        }
    }

    private fun handleServiceProgressParakeet(progress: ExtractionService.ExtractionProgress) {
        handleServiceProgress(
            state = progress.downloadState,
            updateFlow = { state, prog -> _parakeetState.update { it.copy(downloadState = state, downloadProgress = prog ?: it.downloadProgress) } },
            onError = { msg, _ ->
                _parakeetState.update { it.copy(isDownloading = false, errorMessage = msg) }
            },
            onComplete = { file ->
                _parakeetState.update { it.copy(isDownloading = false, modelPath = file.absolutePath, partialDownload = null) }
                viewModelScope.launch {
                    preferencesManager.saveParakeetModelPath(file.absolutePath)
                    if (_uiState.value.modelName.isBlank()) useParakeetModel()
                }
                viewModelScope.launch { _snackbarEvent.send(AppContainer.applicationContext.getString(R.string.parakeet_downloaded)) }
            },
            onCancelled = {
                _parakeetState.update { it.copy(
                    isDownloading = false,
                    needsExtraction = ParakeetDownloader.needsExtraction(AppContainer.applicationContext)
                )}
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
            updateFlow = { state, prog -> _whisperState.update { it.copy(downloadState = state, downloadProgress = prog ?: it.downloadProgress) } },
            onError = { msg, _ ->
                _whisperState.update { it.copy(isDownloading = false, errorMessage = msg) }
                detectPartialDownloads()
            },
            onComplete = { file ->
                _whisperState.update {
                    it.copy(
                        isDownloading = false,
                        modelPath = file.absolutePath,
                        downloadedVariants = if (variant != null) it.downloadedVariants + variant else it.downloadedVariants,
                        partialDownload = null
                    )
                }
                if (variant != null) {
                    if (_uiState.value.modelName.isBlank()) useWhisperModel(variant)
                    val displayName = AppContainer.applicationContext.getString(variant.titleResId)
                    viewModelScope.launch { _snackbarEvent.send(AppContainer.applicationContext.getString(R.string.whisper_downloaded, displayName)) }
                }
            },
            onCancelled = {
                val context = AppContainer.applicationContext
                val eagerNeedsExtraction = WhisperModelManager.Variant.entries
                    .filter { WhisperDownloader.needsExtraction(context, it) }
                    .toSet()
                val eagerOrphaned = WhisperModelManager.Variant.entries
                    .filter { v ->
                        val modelDir = File(WhisperModelManager.getModelStorageDir(context), WhisperDownloader.getModelDirName(v))
                        modelDir.exists() && !WhisperDownloader.isModelDownloaded(context, v)
                    }
                    .toSet()
                _whisperState.update { it.copy(
                    isDownloading = false,
                    variantsNeedingExtraction = eagerNeedsExtraction,
                    orphanedVariants = eagerOrphaned
                )}
                detectPartialDownloads()
            }
        )
    }

    private fun handleServiceProgressGemma(progress: ExtractionService.ExtractionProgress) {
        handleServiceProgress(
            state = progress.downloadState,
            updateFlow = { state, prog -> _downloadUiState.update { it.copy(downloadState = state, downloadProgress = prog ?: it.downloadProgress) } },
            onError = { msg, throwable ->
                val error = when (throwable) {
                    is ModelDownloader.DownloadError -> throwable
                    else -> ModelDownloader.DownloadError.NetworkError(msg, throwable)
                }
                _downloadUiState.update { it.copy(isDownloading = false, downloadError = error) }
                val message = when (error) {
                    is ModelDownloader.DownloadError.AuthRequired -> "auth_required"
                    is ModelDownloader.DownloadError.AuthError -> "Invalid token. Check Settings."
                    is ModelDownloader.DownloadError.LicenseError -> "Accept license on HuggingFace"
                    is ModelDownloader.DownloadError.StorageError -> "Not enough storage"
                    is ModelDownloader.DownloadError.NetworkError -> "Network error: ${error.message}"
                }
                viewModelScope.launch { _snackbarEvent.send(message) }
            },
            onComplete = { file ->
                _downloadUiState.update { it.copy(isDownloading = false, downloadProgress = 1f, partialDownload = null) }
                refreshDownloadedModels()
                if (_uiState.value.modelName.isBlank()) setDownloadedModel(file)
            }
        )
    }

    // ==================== Service helpers ====================

    /**
     * Sends a cancel intent to [ExtractionService].
     */
    private fun stopExtractionService() {
        val context = AppContainer.applicationContext
        val intent = Intent(context, ExtractionService::class.java).apply {
            action = ExtractionService.ACTION_CANCEL
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Maps a [WhisperModelManager.Variant] to the string key used by [ExtractionService].
     */
    private fun whisperVariantKey(variant: WhisperModelManager.Variant): String {
        return variant.name.lowercase()
    }

    /**
     * Maps a [ModelDownloader.ModelVariant] to the string key used by [ExtractionService].
     */
    private fun gemmaVariantKey(variant: ModelDownloader.ModelVariant): String {
        return variant.name.lowercase()
    }

    /**
     * Detects partial downloads across all downloaders and updates state.
     */
    private fun detectPartialDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = AppContainer.applicationContext

            // Check Gemma variants
            var gemmaPartial: DownloadState.PartiallyDownloaded? = null
            var gemmaPartialVariant: ModelDownloader.ModelVariant? = null
            for (variant in ModelDownloader.ModelVariant.entries) {
                if (!ModelDownloader.isModelDownloaded(context, variant)) {
                    if (gemmaPartial == null) {
                        val partial = ModelDownloader.detectPartialDownload(context, variant)
                        if (partial != null) {
                            gemmaPartial = partial
                            gemmaPartialVariant = variant
                        }
                    }
                }
            }
            if (gemmaPartial != null && gemmaPartialVariant != null) {
                _downloadUiState.update { it.copy(partialDownload = gemmaPartial, partialDownloadVariant = gemmaPartialVariant) }
            }

            // Check Parakeet
            if (!ParakeetDownloader.isModelDownloaded(context)) {
                val partial = ParakeetDownloader.detectPartialDownload(context)
                val needsExtraction = ParakeetDownloader.needsExtraction(context)
                val parakeetModelDir = java.io.File(ParakeetModelManager.getModelStorageDir(context), "parakeet-tdt-0.6b-v3-int8")
                _parakeetState.update {
                    it.copy(
                        partialDownload = partial,
                        needsExtraction = needsExtraction,
                        hasOrphanedFiles = parakeetModelDir.exists()
                    )
                }
            } else {
                _parakeetState.update { it.copy(needsExtraction = false, hasOrphanedFiles = false) }
            }

            // Check Whisper variants
            val whisperNeedsExtraction = mutableSetOf<WhisperModelManager.Variant>()
            val whisperOrphaned = mutableSetOf<WhisperModelManager.Variant>()
            var firstPartial: DownloadState.PartiallyDownloaded? = null
            var firstPartialVariant: WhisperModelManager.Variant? = null
            for (variant in WhisperModelManager.Variant.entries) {
                if (!WhisperDownloader.isModelDownloaded(context, variant)) {
                    // Track first partial download only (for UI display)
                    if (firstPartial == null) {
                        val partial = WhisperDownloader.detectPartialDownload(context, variant)
                        if (partial != null) {
                            firstPartial = partial
                            firstPartialVariant = variant
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
            if (firstPartial != null && firstPartialVariant != null) {
                _whisperState.update {
                    it.copy(
                        partialDownload = firstPartial,
                        partialDownloadVariant = firstPartialVariant,
                        variantsNeedingExtraction = whisperNeedsExtraction,
                        orphanedVariants = whisperOrphaned
                    )
                }
            } else {
                _whisperState.update {
                    it.copy(
                        partialDownload = null,
                        partialDownloadVariant = null,
                        variantsNeedingExtraction = whisperNeedsExtraction,
                        orphanedVariants = whisperOrphaned
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

    private fun loadSavedModelPath() {
        viewModelScope.launch {
            // Check which backend is selected
            val backend = preferencesManager.transcriptionBackend.first()

            when (backend) {
                "sherpa-onnx" -> {
                    // Load Parakeet model path
                    val parakeetPath = preferencesManager.parakeetModelPath.first()
                    if (!parakeetPath.isNullOrBlank()) {
                        val isValid = File(parakeetPath).exists()
                        _uiState.update { it.copy(
                            modelPath = parakeetPath,
                            modelName = "Parakeet TDT",
                            isModelPathValid = isValid,
                            statusMessage = if (isValid) "Parakeet TDT ready" else "Parakeet model not found"
                        )}
                    }
                }
                "whisper" -> {
                    // Load Whisper model path
                    val whisperPath = preferencesManager.whisperModelPath.first()
                    if (!whisperPath.isNullOrBlank()) {
                        val modelDir = File(whisperPath)
                        val isValid = modelDir.exists() && modelDir.isDirectory
                        val model = WhisperModelManager.validateModelDirectory(modelDir)
                        _uiState.update { it.copy(
                            modelPath = whisperPath,
                            modelName = model?.variant?.let { v ->
                                AppContainer.applicationContext.getString(v.titleResId)
                            } ?: whisperPath.substringAfterLast("/"),
                            isModelPathValid = isValid,
                            statusMessage = if (isValid) "Whisper model ready" else "Whisper model not found"
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
                            isModelPathValid = isValid,
                            statusMessage = if (isValid) "Saved model found" else "Saved model not found"
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
                    isModelPathValid = true,
                    status = ModelStatus.UNLOADED,
                    statusMessage = "Model selected: $fileName"
                )}
            } else {
                _uiState.update { it.copy(
                    status = ModelStatus.ERROR,
                    statusMessage = "Failed to copy model file"
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
                statusMessage = "No model selected"
            )}
            return
        }

        if (!validateModelPath(modelPath)) {
            _uiState.update { it.copy(
                status = ModelStatus.ERROR,
                statusMessage = "Model file not found at: $modelPath"
            )}
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(
                status = ModelStatus.LOADING,
                statusMessage = "Loading model into memory..."
            )}

            val result = LlmManager.initialize(context, modelPath)

            result.fold(
                onSuccess = {
                    val memoryUsage = estimateMemoryUsage(modelPath)

                    _uiState.update { it.copy(
                        status = ModelStatus.READY,
                        statusMessage = "Model ready for inference",
                        memoryUsage = memoryUsage
                    )}
                },
                onFailure = { error ->
                    _uiState.update { it.copy(
                        status = ModelStatus.ERROR,
                        statusMessage = "Load failed: ${error.message}"
                    )}
                }
            )
        }
    }

    fun unloadModel() {
        LlmManager.unload()
        _uiState.update { it.copy(
            status = ModelStatus.UNLOADED,
            statusMessage = "Model unloaded",
            memoryUsage = 0
        )}
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

    private fun estimateMemoryUsage(modelPath: String): Long {
        // Estimate memory usage based on model file size
        // Gemma 3n models are typically 2-4GB
        val fileSize = File(modelPath).length()
        // Model in memory is roughly 1.2x the file size due to KV cache
        return (fileSize * 1.2 / (1024 * 1024)).toLong()
    }

    /**
     * Called when the model is automatically unloaded due to inactivity timeout.
     */
    private fun onModelAutoUnloaded() {
        _uiState.update { it.copy(
            status = ModelStatus.UNLOADED,
            statusMessage = "Model unloaded due to inactivity",
            memoryUsage = 0
        )}
    }

    /**
     * Called when the model is loaded externally (e.g., via ModelPreloadReceiver).
     * Updates the UI state to reflect the model is ready for inference.
     */
    private fun onModelExternallyLoaded(modelPath: String) {
        val modelName = extractFileName(modelPath)
        val memoryUsage = estimateMemoryUsage(modelPath)
        _uiState.update { it.copy(
            modelPath = modelPath,
            modelName = modelName,
            isModelPathValid = true,
            status = ModelStatus.READY,
            statusMessage = "Model ready (externally loaded)",
            memoryUsage = memoryUsage
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
                        AppContainer.applicationContext,
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
            isDownloading = true,
            downloadProgress = 0f,
            downloadState = DownloadState.Idle,
            downloadError = null,
            partialDownload = null
        )}

        val context = AppContainer.applicationContext
        val intent = Intent(context, ExtractionService::class.java).apply {
            putExtra(ExtractionService.EXTRA_MODEL_TYPE, ExtractionService.ModelType.GEMMA.key)
            putExtra(ExtractionService.EXTRA_VARIANT, gemmaVariantKey(variant))
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Resumes a partial Gemma download.
     */
    fun resumeDownload(variant: ModelDownloader.ModelVariant) {
        _downloadUiState.update { it.copy(partialDownload = null, partialDownloadVariant = null) }
        startDownload(variant)
    }

    /**
     * Clears a partial Gemma download.
     */
    fun clearPartialDownload(variant: ModelDownloader.ModelVariant) {
        viewModelScope.launch {
            ModelDownloader.clearPartialDownload(AppContainer.applicationContext, variant)
            _downloadUiState.update { it.copy(
                isDownloading = false,
                downloadProgress = 0f,
                partialDownload = null,
                partialDownloadVariant = null,
                downloadError = null,
                downloadState = DownloadState.Idle
            )}
        }
    }

    /**
     * Cancels the ongoing Gemma download.
     */
    fun cancelDownload() {
        ModelDownloader.cancel()
        _downloadUiState.update { it.copy(
            isDownloading = false,
            downloadState = DownloadState.Cancelled("User cancelled"),
            downloadError = null
        )}
        stopExtractionService()
        detectPartialDownloads()
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
                AppContainer.applicationContext,
                variant
            )
            if (modelPath != null) {
                preferencesManager.saveModelPath(modelPath)
                // Switch to LLM backend when selecting an LLM model
                preferencesManager.saveTranscriptionBackend("llm")
                val message = AppContainer.applicationContext.getString(R.string.model_selected_message, variant.displayName)
                _uiState.update { it.copy(
                    modelPath = modelPath,
                    modelName = variant.displayName,
                    isModelPathValid = true,
                    status = ModelStatus.UNLOADED,
                    statusMessage = message
                )}
                _snackbarEvent.send(message)
            }
        }
    }


    /**
     * Deletes a downloaded model and refreshes the state.
     */
    fun deleteModel(variant: ModelDownloader.ModelVariant) {
        viewModelScope.launch {
            val success = ModelDownloader.deleteModel(AppContainer.applicationContext, variant)
            if (success) {
                refreshDownloadedModels()
                // Clear model path if this was the selected model
                if (_uiState.value.modelPath.contains(variant.fileName)) {
                    preferencesManager.saveModelPath("")
                    _uiState.update { it.copy(
                        modelPath = "",
                        modelName = "",
                        isModelPathValid = false,
                        status = ModelStatus.UNLOADED,
                        statusMessage = "Model deleted"
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
     * Refreshes the Parakeet model state.
     */
    fun refreshParakeetState() {
        viewModelScope.launch {
            val context = AppContainer.applicationContext
            val isDownloaded = ParakeetDownloader.isModelDownloaded(context)
            val modelPath = if (isDownloaded) ParakeetDownloader.getModelPath(context) else null

            _parakeetState.update { it.copy(
                modelPath = modelPath
            )}
        }
    }

    // ==================== Parakeet Confirmation Dialogs ====================

    /**
     * Shows the Parakeet download confirmation dialog.
     */
    fun showParakeetDownloadDialog() {
        _parakeetState.update { it.copy(showDownloadDialog = true) }
    }

    /**
     * Dismisses the Parakeet download confirmation dialog.
     */
    fun dismissParakeetDownloadDialog() {
        _parakeetState.update { it.copy(showDownloadDialog = false) }
    }

    /**
     * Confirms and starts the Parakeet download.
     */
    fun confirmParakeetDownload() {
        val needsExtraction = _parakeetState.value.needsExtraction
        startParakeetDownload(
            dismissDialog = true,
            initialState = if (needsExtraction) DownloadState.Extracting(0, 0) else DownloadState.Connecting("")
        )
    }

    /**
     * Shows the Parakeet delete confirmation dialog.
     */
    fun showParakeetDeleteDialog() {
        _parakeetState.update { it.copy(showDeleteDialog = true) }
    }

    /**
     * Dismisses the Parakeet delete confirmation dialog.
     */
    fun dismissParakeetDeleteDialog() {
        _parakeetState.update { it.copy(showDeleteDialog = false) }
    }

    /**
     * Confirms and deletes the Parakeet model.
     */
    fun confirmParakeetDelete() {
        _parakeetState.update { it.copy(showDeleteDialog = false) }
        deleteParakeetModel()
    }

    /**
     * Starts downloading the Parakeet model via [ExtractionService].
     */
    fun startParakeetDownload(
        dismissDialog: Boolean = false,
        initialState: DownloadState = DownloadState.Connecting("")
    ) {
        _parakeetState.update { it.copy(
            showDownloadDialog = if (dismissDialog) false else it.showDownloadDialog,
            isDownloading = true,
            downloadProgress = 0f,
            downloadState = initialState,
            errorMessage = null,
            partialDownload = null
        )}

        val context = AppContainer.applicationContext
        val intent = Intent(context, ExtractionService::class.java).apply {
            putExtra(ExtractionService.EXTRA_MODEL_TYPE, ExtractionService.ModelType.PARAKEET.key)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Resumes a partial Parakeet download.
     */
    fun resumeParakeetDownload() {
        _parakeetState.update { it.copy(partialDownload = null) }
        startParakeetDownload()
    }

    /**
     * Clears a partial Parakeet download (tar file only).
     * The model directory is preserved — use [clearOrphanedParakeetFiles] to remove it.
     */
    fun clearParakeetPartialDownload() {
        viewModelScope.launch {
            val context = AppContainer.applicationContext
            ParakeetDownloader.clearPartialDownload(context)
            _parakeetState.update { it.copy(
                isDownloading = false,
                downloadState = DownloadState.Idle,
                downloadProgress = 0f,
                partialDownload = null,
                needsExtraction = false
            )}
            detectPartialDownloads()
        }
    }

    /**
     * Clears an orphaned Parakeet model directory (partial extraction leftovers).
     * The tar file is preserved so extraction can be retried.
     */
    fun clearOrphanedParakeetFiles() {
        viewModelScope.launch {
            val context = AppContainer.applicationContext
            ParakeetDownloader.deleteModel(context)
            _parakeetState.update { it.copy(hasOrphanedFiles = false) }
            detectPartialDownloads()
        }
    }

    /**
     * Cancels the Parakeet download.
     */
    fun cancelParakeetDownload() {
        ParakeetDownloader.cancel()
        _parakeetState.update { it.copy(
            isDownloading = false,
            downloadState = DownloadState.Cancelled("User cancelled"),
            errorMessage = null,
            // Eagerly set extraction state so the button shows "Extract" immediately
            // instead of flickering to "Download" while detectPartialDownloads() runs on IO
            needsExtraction = ParakeetDownloader.needsExtraction(AppContainer.applicationContext)
        )}
        stopExtractionService()
        detectPartialDownloads()
    }

    /**
     * Uses the Parakeet model (switches backend to sherpa-onnx).
     */
    fun useParakeetModel() {
        viewModelScope.launch {
            val modelPath = _parakeetState.value.modelPath
            if (modelPath != null) {
                // Save Parakeet model path and switch backend preference
                preferencesManager.saveParakeetModelPath(modelPath)
                preferencesManager.saveTranscriptionBackend("sherpa-onnx")

                val context = AppContainer.applicationContext
                val message = context.getString(R.string.model_selected_message, "Parakeet TDT")
                _uiState.update { it.copy(
                    modelName = "Parakeet TDT",
                    status = ModelStatus.UNLOADED,
                    statusMessage = message
                )}

                _snackbarEvent.send(message)
            }
        }
    }

    /**
     * Deletes the Parakeet model.
     */
    fun deleteParakeetModel() {
        viewModelScope.launch {
            val context = AppContainer.applicationContext
            val success = ParakeetDownloader.deleteModel(context)
            if (success) {
                preferencesManager.saveParakeetModelPath("")
                _parakeetState.update { it.copy(modelPath = null) }
                _snackbarEvent.send(context.getString(R.string.parakeet_deleted))
            }
        }
    }

    // ==================== Whisper Model Download ====================

    /**
     * Refreshes the Whisper model state.
     */
    fun refreshWhisperState() {
        viewModelScope.launch {
            val context = AppContainer.applicationContext
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
            isDownloading = true,
            downloadProgress = 0f,
            downloadState = initialState,
            errorMessage = null,
            partialDownload = null
        )}

        val context = AppContainer.applicationContext
        val intent = Intent(context, ExtractionService::class.java).apply {
            putExtra(ExtractionService.EXTRA_MODEL_TYPE, ExtractionService.ModelType.WHISPER.key)
            putExtra(ExtractionService.EXTRA_VARIANT, whisperVariantKey(variant))
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Resumes a partial Whisper download.
     */
    fun resumeWhisperDownload(variant: WhisperModelManager.Variant) {
        _whisperState.update { it.copy(partialDownload = null, partialDownloadVariant = null) }
        startWhisperDownload(variant)
    }

    /**
     * Clears a partial Whisper download (tar file only).
     * The model directory is preserved — use [clearOrphanedWhisperFiles] to remove it.
     */
    fun clearWhisperPartialDownload(variant: WhisperModelManager.Variant) {
        viewModelScope.launch {
            val context = AppContainer.applicationContext
            WhisperDownloader.clearPartialDownload(context, variant)
            _whisperState.update {
                it.copy(
                    isDownloading = false,
                    downloadState = DownloadState.Idle,
                    downloadProgress = 0f,
                    partialDownload = null,
                    partialDownloadVariant = null,
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
            val context = AppContainer.applicationContext
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
    fun cancelWhisperDownload() {
        WhisperDownloader.cancel()
        // Eagerly compute extraction state so the button shows "Extract" immediately
        // instead of flickering to "Download" while detectPartialDownloads() runs on IO
        val context = AppContainer.applicationContext
        val eagerNeedsExtraction = WhisperModelManager.Variant.entries
            .filter { WhisperDownloader.needsExtraction(context, it) }
            .toSet()
        val eagerOrphaned = WhisperModelManager.Variant.entries
            .filter { variant ->
                val modelDir = File(WhisperModelManager.getModelStorageDir(context), WhisperDownloader.getModelDirName(variant))
                modelDir.exists() && !WhisperDownloader.isModelDownloaded(context, variant)
            }
            .toSet()
        _whisperState.update { it.copy(
            isDownloading = false,
            downloadState = DownloadState.Cancelled("User cancelled"),
            errorMessage = null,
            variantsNeedingExtraction = eagerNeedsExtraction,
            orphanedVariants = eagerOrphaned
        )}
        stopExtractionService()
        detectPartialDownloads()
    }

    /**
     * Uses the Whisper model (switches backend to whisper).
     */
    fun useWhisperModel(variant: WhisperModelManager.Variant) {
        viewModelScope.launch {
            val context = AppContainer.applicationContext
            val modelPath = WhisperDownloader.getModelPath(context, variant)
            if (modelPath != null) {
                val displayName = context.getString(variant.titleResId)
                // Save Whisper model path and switch backend preference
                preferencesManager.saveWhisperModelPath(modelPath)
                preferencesManager.saveTranscriptionBackend("whisper")

                val message = context.getString(R.string.model_selected_message, displayName)
                _uiState.update { it.copy(
                    modelName = displayName,
                    status = ModelStatus.UNLOADED,
                    statusMessage = message
                )}

                _snackbarEvent.send(message)
            }
        }
    }

    /**
     * Deletes a Whisper model variant.
     */
    fun deleteWhisperModel(variant: WhisperModelManager.Variant) {
        viewModelScope.launch {
            val context = AppContainer.applicationContext
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
                }
                _snackbarEvent.send(context.getString(R.string.whisper_deleted, displayName))
            }
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
            _uiState.update { it.copy(
                modelPath = modelPath,
                modelName = file.name,
                isModelPathValid = true,
                status = ModelStatus.UNLOADED,
                statusMessage = "Downloaded model selected"
            )}
        }
    }

    override fun onCleared() {
        super.onCleared()
        LlmManager.setOnAutoUnloadCallback(null)
        LlmManager.setOnExternalLoadCallback(null)
    }

    /**
     * Factory for creating ModelViewModel with dependencies
     */
    class Factory(
        private val preferencesManager: PreferencesManager,
        private val tokenManager: HuggingFaceTokenManager = AppContainer.huggingFaceTokenManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ModelViewModel::class.java)) {
                return ModelViewModel(preferencesManager, tokenManager) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
