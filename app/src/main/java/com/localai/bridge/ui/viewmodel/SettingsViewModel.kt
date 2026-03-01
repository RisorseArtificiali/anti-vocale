package com.localai.bridge.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.localai.bridge.data.PreferencesManager
import com.localai.bridge.di.AppContainer
import com.localai.bridge.manager.LlmManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Settings screen.
 *
 * Manages:
 * - Keep-alive timeout configuration
 * - Model auto-unload settings
 */
class SettingsViewModel(
    application: Application,
    private val preferencesManager: PreferencesManager
) : AndroidViewModel(application) {

    // Keep-alive timeout options in minutes
    val timeoutOptions = listOf(1, 2, 5, 10, 15, 30, 60)

    // Current keep-alive timeout from preferences
    val keepAliveTimeout: StateFlow<Int> = preferencesManager.keepAliveTimeout
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 5
        )

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    data class SettingsUiState(
        val isSaving: Boolean = false,
        val saveSuccess: Boolean? = null,
        val errorMessage: String? = null
    )

    /**
     * Saves the keep-alive timeout.
     * Also applies it to the current LlmManager if a model is loaded.
     */
    fun saveKeepAliveTimeout(minutes: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveSuccess = null, errorMessage = null) }

            try {
                // Save to preferences
                preferencesManager.saveKeepAliveTimeout(minutes)

                // Apply to LlmManager if model is loaded
                if (LlmManager.isReady()) {
                    LlmManager.setKeepAliveTimeout(minutes)
                }

                _uiState.update { it.copy(isSaving = false, saveSuccess = true) }

                // Clear success message after delay
                kotlinx.coroutines.delay(2000)
                _uiState.update { it.copy(saveSuccess = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isSaving = false,
                    saveSuccess = false,
                    errorMessage = e.message ?: "Failed to save settings"
                )}
            }
        }
    }

    /**
     * Clears any error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Factory for creating SettingsViewModel with dependencies.
     */
    class Factory(
        private val application: Application,
        private val preferencesManager: PreferencesManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                return SettingsViewModel(application, preferencesManager) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
