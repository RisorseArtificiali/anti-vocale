package com.localai.bridge.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.localai.bridge.data.ModelDownloader
import com.localai.bridge.data.PreferencesManager
import com.localai.bridge.manager.LlmManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class ModelViewModel(
    private val preferencesManager: PreferencesManager
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
        val needsStoragePermission: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _filePickerEvent = MutableSharedFlow<Unit>()
    val filePickerEvent: SharedFlow<Unit> = _filePickerEvent.asSharedFlow()

    init {
        // Load saved model path on initialization
        loadSavedModelPath()
        // Check for Google AI Edge Gallery models
        checkForGalleryModels()
    }

    /**
     * Checks if a Gemma 3n model is available from Google AI Edge Gallery.
     */
    private fun checkForGalleryModels() {
        viewModelScope.launch {
            val variant = ModelDownloader.ModelVariant.GEMMA_3N_E2B
            val galleryPath = ModelDownloader.getGalleryModelPath(variant)

            if (galleryPath != null) {
                _uiState.update { it.copy(
                    galleryModelPath = galleryPath,
                    galleryModelName = "Gemma 3n E2B (from AI Gallery)",
                    isGalleryModelAvailable = true
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

    override fun onCleared() {
        super.onCleared()
        // Don't unload on config changes, only on actual destruction
    }

    /**
     * Factory for creating ModelViewModel with dependencies
     */
    class Factory(
        private val preferencesManager: PreferencesManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ModelViewModel::class.java)) {
                return ModelViewModel(preferencesManager) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
