package com.localai.bridge.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.localai.bridge.data.HuggingFaceTokenManager
import com.localai.bridge.data.ModelDownloader
import com.localai.bridge.data.ModelDownloader.DownloadError
import com.localai.bridge.data.PreferencesManager
import com.localai.bridge.di.AppContainer
import com.localai.bridge.manager.LlmManager
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
import kotlinx.coroutines.withContext
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
        val downloadState: ModelDownloader.DownloadState = ModelDownloader.DownloadState.Idle,
        val downloadError: ModelDownloader.DownloadError? = null,
        val downloadedModels: Set<ModelDownloader.ModelVariant> = emptySet(),
        val hasToken: Boolean = false,
        val modelToDelete: ModelDownloader.ModelVariant? = null
    )

    data class UiState(
        val status: ModelStatus = ModelStatus.UNLOADED,
        val statusMessage: String = "",
        val modelPath: String = "",
        val modelName: String = "",
        val memoryUsage: Long = 0,
        val isModelPathValid: Boolean = false,
        // Gallery model detection
        val galleryModelPath: String? = null,
        val galleryModelName: String? = null,
        val isGalleryModelAvailable: Boolean = false,
        val needsStoragePermission: Boolean = false,
        // Download state
        val downloadState: DownloadUiState = DownloadUiState()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _filePickerEvent = MutableSharedFlow<Unit>()
    val filePickerEvent: SharedFlow<Unit> = _filePickerEvent.asSharedFlow()

    // Download state
    private val _downloadUiState = MutableStateFlow(DownloadUiState())
    val downloadUiState: StateFlow<DownloadUiState> = _downloadUiState.asStateFlow()

    // Channel for one-time Snackbar events (guarantees delivery)
    private val _snackbarEvent = Channel<String>()
    val snackbarEvent: kotlinx.coroutines.flow.Flow<String> = _snackbarEvent.receiveAsFlow()

    init {
        // Set up auto-unload callback
        LlmManager.setOnAutoUnloadCallback {
            onModelAutoUnloaded()
        }
        // Load saved model path on initialization
        loadSavedModelPath()
        // Check for Google AI Edge Gallery models
        checkForGalleryModels()
        // Check for downloaded models
        refreshDownloadedModels()
        // Check for HuggingFace token
        refreshTokenState()
    }

    /**
     * Refreshes the token state from the token manager.
     */
    fun refreshTokenState() {
        _downloadUiState.update { it.copy(hasToken = tokenManager.hasToken()) }
    }

    /**
     * Checks if a Gemma 3n model is available from Google AI Edge Gallery.
     */
    private fun checkForGalleryModels() {
        viewModelScope.launch {
            android.util.Log.d("ModelViewModel", "checkForGalleryModels: Starting check...")
            
            val variant = ModelDownloader.ModelVariant.GEMMA_3N_E2B
            val galleryPath = ModelDownloader.getGalleryModelPath(variant)

            android.util.Log.d("ModelViewModel", "checkForGalleryModels: Result path = $galleryPath")

            if (galleryPath != null) {
                android.util.Log.i("ModelViewModel", "checkForGalleryModels: ✅ Gallery model FOUND at $galleryPath")
                _uiState.update { it.copy(
                    galleryModelPath = galleryPath,
                    galleryModelName = "Gemma 3n E2B (from AI Gallery)",
                    isGalleryModelAvailable = true
                )}
            } else {
                android.util.Log.w("ModelViewModel", "checkForGalleryModels: ❌ Gallery model NOT found")
                // Ensure state is updated even when not found
                _uiState.update { it.copy(
                    isGalleryModelAvailable = false,
                    galleryModelPath = null,
                    galleryModelName = null
                )}
            }
        }
    }

    /**
     * Refreshes the Gallery model detection (call after permission is granted).
     */
    fun refreshGalleryModels() {
        checkForGalleryModels()
    }

    /**
     * Uses the Google AI Edge Gallery model directly.
     */
    fun useGalleryModel() {
        val galleryPath = _uiState.value.galleryModelPath
        if (galleryPath == null) {
            // Try to refresh first
            checkForGalleryModels()
            return
        }

        viewModelScope.launch {
            // Verify the file exists
            val file = java.io.File(galleryPath)
            if (!file.exists()) {
                _uiState.update { it.copy(
                    status = ModelStatus.ERROR,
                    statusMessage = "Gallery model not accessible. Grant storage permission."
                )}
                return@launch
            }

            preferencesManager.saveModelPath(galleryPath)

            _uiState.update { it.copy(
                modelPath = galleryPath,
                modelName = "Gemma 3n E2B (Gallery)",
                isModelPathValid = true,
                status = ModelStatus.UNLOADED,
                statusMessage = "Gallery model selected"
            )}
        }
    }

    private fun loadSavedModelPath() {
        viewModelScope.launch {
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
     * Starts an authenticated download for the selected model variant.
     */
    fun startDownload(variant: ModelDownloader.ModelVariant) {
        viewModelScope.launch(Dispatchers.IO) {
            // Check if token is available (OAuth or manual PAT)
            val token = tokenManager.getEffectiveToken()
            if (token.isNullOrEmpty()) {
                _downloadUiState.update { it.copy(
                    downloadError = ModelDownloader.DownloadError.AuthError(
                        "No HuggingFace token. Please add your token in Settings."
                    )
                )}
                _snackbarEvent.send("Invalid token. Check Settings.")
                return@launch
            }

            _downloadUiState.update { it.copy(
                selectedVariant = variant,
                isDownloading = true,
                downloadProgress = 0f,
                downloadState = ModelDownloader.DownloadState.Connecting(""),
                downloadError = null
            )}

            val result = ModelDownloader.downloadModel(
                context = AppContainer.applicationContext,
                variant = variant,
                tokenManager = tokenManager,
                onProgress = { progress ->
                    _downloadUiState.update { it.copy(downloadProgress = progress) }
                    // Also update main UI state for progress display
                    _uiState.update { it.copy(
                        downloadState = _downloadUiState.value.copy(
                            isDownloading = true,
                            downloadProgress = progress
                        )
                    )}
                },
                onStateChange = { state ->
                    _downloadUiState.update { it.copy(downloadState = state) }
                    when (state) {
                        is ModelDownloader.DownloadState.Error -> {
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
                            // Emit user-friendly message to Snackbar
                            val message = when (error) {
                                is DownloadError.AuthError -> "Invalid token. Check Settings."
                                is DownloadError.LicenseError -> "Accept license on HuggingFace"
                                is DownloadError.StorageError -> "Not enough storage"
                                is DownloadError.NetworkError -> "Network error: ${error.message}"
                            }
                            viewModelScope.launch { _snackbarEvent.send(message) }
                        }
                        is ModelDownloader.DownloadState.Complete -> {
                            _downloadUiState.update { it.copy(
                                isDownloading = false,
                                downloadProgress = 1f
                            )}
                            // Refresh downloaded models list
                            refreshDownloadedModels()
                            // Auto-select the newly downloaded model by path
                            setDownloadedModel(state.file)
                        }
                        is ModelDownloader.DownloadState.Cancelled -> {
                            _downloadUiState.update { it.copy(isDownloading = false) }
                        }
                        else -> {}
                    }
                }
            )

            result.fold(
                onSuccess = { file ->
                    // Auto-select newly downloaded model
                    setDownloadedModel(file)
                },
                onFailure = { error ->
                    // Only update state here - Snackbar is already emitted via onStateChange
                    _downloadUiState.update { it.copy(
                        isDownloading = false,
                        downloadError = if (error is ModelDownloader.DownloadError) {
                            error
                        } else {
                            ModelDownloader.DownloadError.NetworkError(
                                error.message ?: "Download failed",
                                error
                            )
                        }
                    )}
                    // Note: Snackbar event is emitted in onStateChange callback, not here
                    // to avoid double emission
                }
            )
        }
    }

    /**
     * Cancels the ongoing download.
     */
    fun cancelDownload() {
        ModelDownloader.cancel()
        _downloadUiState.update { it.copy(
            isDownloading = false,
            downloadState = ModelDownloader.DownloadState.Cancelled("User cancelled")
        )}
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
                _uiState.update { it.copy(
                    modelPath = modelPath,
                    modelName = variant.displayName,
                    isModelPathValid = true,
                    status = ModelStatus.UNLOADED,
                    statusMessage = "${variant.displayName} selected"
                )}
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
                        isModelPathValid = false
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
        // Don't unload on config changes, only on actual destruction
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
