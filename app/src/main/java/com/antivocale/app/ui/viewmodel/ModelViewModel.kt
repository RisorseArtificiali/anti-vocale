package com.antivocale.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.antivocale.app.data.HuggingFaceTokenManager
import com.antivocale.app.data.ModelDownloader
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.R
import com.antivocale.app.data.download.DownloadException
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.di.AppContainer
import com.antivocale.app.manager.LlmManager
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
import java.io.FileInputStream
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
        val partialDownload: DownloadState.PartiallyDownloaded? = null
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
        val partialDownloadVariant: WhisperModelManager.Variant? = null
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

    /**
     * Detects partial downloads across all downloaders and updates state.
     */
    private fun detectPartialDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = AppContainer.applicationContext

            // Check Gemma variants
            for (variant in ModelDownloader.ModelVariant.entries) {
                if (!ModelDownloader.isModelDownloaded(context, variant)) {
                    val partial = ModelDownloader.detectPartialDownload(context, variant)
                    if (partial != null) {
                        _downloadUiState.update { it.copy(partialDownload = partial, partialDownloadVariant = variant) }
                        break // Only show one partial at a time
                    }
                }
            }

            // Check Parakeet
            if (!ParakeetDownloader.isModelDownloaded(context)) {
                val partial = ParakeetDownloader.detectPartialDownload(context)
                if (partial != null) {
                    _parakeetState.update { it.copy(partialDownload = partial) }
                }
            }

            // Check Whisper variants
            for (variant in WhisperModelManager.Variant.entries) {
                if (!WhisperDownloader.isModelDownloaded(context, variant)) {
                    val partial = WhisperDownloader.detectPartialDownload(context, variant)
                    if (partial != null) {
                        _whisperState.update { it.copy(partialDownload = partial, partialDownloadVariant = variant) }
                        break
                    }
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
     * Starts a download for the selected model variant.
     *
     * Auth is optional: tries public access first, falls back to stored token if needed.
     * Supports resume for interrupted downloads and retry on transient errors.
     */
    fun startDownload(variant: ModelDownloader.ModelVariant) {
        viewModelScope.launch(Dispatchers.IO) {
            _downloadUiState.update { it.copy(
                selectedVariant = variant,
                isDownloading = true,
                downloadProgress = 0f,
                downloadState = DownloadState.Idle,
                downloadError = null,
                partialDownload = null
            )}

            val result = ModelDownloader.downloadModel(
                context = AppContainer.applicationContext,
                variant = variant,
                tokenManager = tokenManager,
                onProgress = { progress ->
                    _downloadUiState.update { it.copy(downloadProgress = progress) }
                },
                onStateChange = { state ->
                    _downloadUiState.update { current ->
                        current.copy(
                            downloadState = state,
                            downloadProgress = when (state) {
                                is DownloadState.Downloading -> state.progressPercent / 100f
                                is DownloadState.Complete -> 1f
                                else -> current.downloadProgress
                            }
                        )
                    }
                    when (state) {
                        is DownloadState.Error -> {
                            val error = when (state.throwable) {
                                is ModelDownloader.DownloadError -> state.throwable
                                else -> ModelDownloader.DownloadError.NetworkError(
                                    state.message,
                                    state.throwable
                                )
                            }
                            _downloadUiState.update { it.copy(
                                isDownloading = false,
                                downloadError = error
                            )}
                            val message = when (error) {
                                is ModelDownloader.DownloadError.AuthRequired -> "auth_required"
                                is ModelDownloader.DownloadError.AuthError -> "Invalid token. Check Settings."
                                is ModelDownloader.DownloadError.LicenseError -> "Accept license on HuggingFace"
                                is ModelDownloader.DownloadError.StorageError -> "Not enough storage"
                                is ModelDownloader.DownloadError.NetworkError -> "Network error: ${error.message}"
                            }
                            viewModelScope.launch { _snackbarEvent.send(message) }
                        }
                        is DownloadState.Complete -> {
                            _downloadUiState.update { it.copy(
                                isDownloading = false,
                                downloadProgress = 1f,
                                partialDownload = null
                            )}
                            refreshDownloadedModels()
                            if (_uiState.value.modelName.isBlank()) {
                                setDownloadedModel(state.file)
                            }
                        }
                        is DownloadState.Cancelled -> {
                            _downloadUiState.update { it.copy(isDownloading = false) }
                        }
                        else -> {}
                    }
                }
            )

            result.onFailure { error ->
                // Skip cancellation — cancelDownload() already cleared state
                if (error is DownloadException.Cancelled) return@onFailure
                if (error.message?.contains("cancelled", ignoreCase = true) == true) return@onFailure

                if (error is ModelDownloader.DownloadError) {
                    // Already handled via onStateChange (snackbar emitted there).
                    // Just ensure isDownloading is cleared.
                    _downloadUiState.update { it.copy(isDownloading = false) }
                    return@onFailure
                }

                _downloadUiState.update { it.copy(
                    isDownloading = false,
                    downloadError = ModelDownloader.DownloadError.NetworkError(
                        error.message ?: "Download failed",
                        error
                    )
                )}
            }
        }
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
                partialDownload = null,
                partialDownloadVariant = null,
                downloadError = null,
                downloadState = DownloadState.Idle
            )}
        }
    }

    /**
     * Cancels the ongoing download.
     */
    fun cancelDownload() {
        ModelDownloader.cancel()
        _downloadUiState.update { it.copy(
            isDownloading = false,
            downloadState = DownloadState.Cancelled("User cancelled"),
            downloadError = null
        )}
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
                isDownloading = false,
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
        _parakeetState.update { it.copy(showDownloadDialog = false) }
        startParakeetDownload()
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
     * Starts downloading the Parakeet model.
     */
    fun startParakeetDownload() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = AppContainer.applicationContext

            _parakeetState.update { it.copy(
                isDownloading = true,
                downloadProgress = 0f,
                downloadState = DownloadState.Connecting(""),
                errorMessage = null,
                partialDownload = null
            )}

            val result = ParakeetDownloader.downloadModel(
                context = context,
                onProgress = { progress ->
                    _parakeetState.update { it.copy(downloadProgress = progress) }
                },
                onStateChange = { state ->
                    _parakeetState.update { current ->
                        current.copy(
                            downloadState = state,
                            downloadProgress = when (state) {
                                is DownloadState.Downloading -> state.progressPercent / 100f
                                is DownloadState.Complete -> 1f
                                else -> current.downloadProgress
                            }
                        )
                    }
                    when (state) {
                        is DownloadState.Error -> {
                            _parakeetState.update { it.copy(
                                isDownloading = false,
                                errorMessage = state.message
                            )}
                            viewModelScope.launch { _snackbarEvent.send(state.message) }
                        }
                        is DownloadState.Complete -> {
                            _parakeetState.update { it.copy(
                                isDownloading = false,
                                modelPath = state.file.absolutePath,
                                partialDownload = null
                            )}
                            // Save to preferences
                            viewModelScope.launch {
                                preferencesManager.saveParakeetModelPath(state.file.absolutePath)
                                // Auto-select only if no model is currently active
                                if (_uiState.value.modelName.isBlank()) {
                                    useParakeetModel()
                                }
                            }
                            viewModelScope.launch { _snackbarEvent.send("Parakeet model downloaded successfully!") }
                        }
                        is DownloadState.Cancelled -> {
                            _parakeetState.update { it.copy(isDownloading = false) }
                        }
                        else -> {}
                    }
                }
            )

            result.fold(
                onSuccess = { modelDir ->
                    _parakeetState.update { it.copy(
                        isDownloading = false,
                        modelPath = modelDir.absolutePath
                    )}
                },
                onFailure = { error ->
                    if (error is DownloadException.Cancelled) return@fold
                    if (error.message?.contains("cancelled", ignoreCase = true) == true) return@fold
                    _parakeetState.update { it.copy(
                        isDownloading = false,
                        errorMessage = error.message
                    )}
                }
            )
        }
    }

    /**
     * Resumes a partial Parakeet download.
     */
    fun resumeParakeetDownload() {
        _parakeetState.update { it.copy(partialDownload = null) }
        startParakeetDownload()
    }

    /**
     * Clears a partial Parakeet download.
     */
    fun clearParakeetPartialDownload() {
        viewModelScope.launch {
            ParakeetDownloader.clearPartialDownload(AppContainer.applicationContext)
            _parakeetState.update { it.copy(partialDownload = null) }
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
            errorMessage = null
        )}
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
                _snackbarEvent.send("Parakeet model deleted")
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
                isDownloading = false,
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
        _whisperState.update { it.copy(showDownloadDialog = false) }
        startWhisperDownload(variant)
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
     * Starts downloading a Whisper model variant.
     */
    fun startWhisperDownload(variant: WhisperModelManager.Variant) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = AppContainer.applicationContext

            _whisperState.update { it.copy(
                selectedVariant = variant,
                isDownloading = true,
                downloadProgress = 0f,
                downloadState = DownloadState.Connecting(""),
                errorMessage = null,
                partialDownload = null
            )}

            val result = WhisperDownloader.downloadModel(
                context = context,
                variant = variant,
                onProgress = { progress ->
                    _whisperState.update { it.copy(downloadProgress = progress) }
                },
                onStateChange = { state ->
                    _whisperState.update { current ->
                        current.copy(
                            downloadState = state,
                            downloadProgress = when (state) {
                                is DownloadState.Downloading -> state.progressPercent / 100f
                                is DownloadState.Complete -> 1f
                                else -> current.downloadProgress
                            }
                        )
                    }
                    when (state) {
                        is DownloadState.Error -> {
                            _whisperState.update { it.copy(
                                isDownloading = false,
                                errorMessage = state.message
                            )}
                            viewModelScope.launch { _snackbarEvent.send(state.message) }
                        }
                        is DownloadState.Complete -> {
                            _whisperState.update { it.copy(
                                isDownloading = false,
                                modelPath = state.file.absolutePath,
                                downloadedVariants = _whisperState.value.downloadedVariants + variant,
                                partialDownload = null
                            )}
                            // Auto-select only if no model is currently active
                            if (_uiState.value.modelName.isBlank()) {
                                useWhisperModel(variant)
                            }
                            val displayName = AppContainer.applicationContext.getString(variant.titleResId)
                            viewModelScope.launch { _snackbarEvent.send("Whisper $displayName downloaded successfully!") }
                        }
                        is DownloadState.Cancelled -> {
                            _whisperState.update { it.copy(isDownloading = false) }
                        }
                        else -> {}
                    }
                }
            )

            result.fold(
                onSuccess = { modelDir ->
                    _whisperState.update { it.copy(
                        isDownloading = false,
                        modelPath = modelDir.absolutePath
                    )}
                },
                onFailure = { error ->
                    if (error is DownloadException.Cancelled) return@fold
                    if (error.message?.contains("cancelled", ignoreCase = true) == true) return@fold
                    _whisperState.update { it.copy(
                        isDownloading = false,
                        errorMessage = error.message
                    )}
                }
            )
        }
    }

    /**
     * Resumes a partial Whisper download.
     */
    fun resumeWhisperDownload(variant: WhisperModelManager.Variant) {
        _whisperState.update { it.copy(partialDownload = null, partialDownloadVariant = null) }
        startWhisperDownload(variant)
    }

    /**
     * Clears a partial Whisper download.
     */
    fun clearWhisperPartialDownload(variant: WhisperModelManager.Variant) {
        viewModelScope.launch {
            WhisperDownloader.clearPartialDownload(AppContainer.applicationContext, variant)
            _whisperState.update { it.copy(partialDownload = null, partialDownloadVariant = null) }
        }
    }

    /**
     * Cancels the Whisper download.
     */
    fun cancelWhisperDownload() {
        WhisperDownloader.cancel()
        _whisperState.update { it.copy(
            isDownloading = false,
            downloadState = DownloadState.Cancelled("User cancelled"),
            errorMessage = null
        )}
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
                _snackbarEvent.send("Whisper $displayName deleted")
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
