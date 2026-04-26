package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.manager.LlmManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages transcription backends and coordinates between them.
 *
 * Only one backend can be active at a time due to memory constraints.
 * Backends are provided via Hilt multibinding ([TranscriptionModule]).
 */
@Singleton
class TranscriptionBackendManager @Inject constructor(
    injectedBackends: Set<@JvmSuppressWildcards TranscriptionBackend>
) {

    private val backends: Map<String, TranscriptionBackend> = injectedBackends.associateBy { it.id }
    private val _activeBackendId = MutableStateFlow<String?>(null)
    @Volatile
    private var _activeBackend: TranscriptionBackend? = null

    /**
     * Flow of the currently active backend ID.
     */
    val activeBackendId = _activeBackendId.asStateFlow()

    init {
        if (backends.size != injectedBackends.size) {
            Log.w(TAG, "Duplicate backend IDs detected: ${injectedBackends.map { it.id }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}")
        }
        backends.values.forEach { backend ->
            Log.i(TAG, "Registered backend: ${backend.id} (${backend.displayName})")
        }
    }

    /**
     * Sets the active backend by ID.
     *
     * This will unload any currently active backend before loading the new one.
     *
     * @param backendId The ID of the backend to activate
     * @param context Application context for initialization
     * @param config Configuration for the backend
     * @return Result indicating success or failure
     */
    suspend fun setActiveBackend(
        backendId: String,
        context: Context,
        config: BackendConfig
    ): Result<Unit> {
        val backend = backends[backendId]
            ?: return Result.failure(IllegalArgumentException("Unknown backend: $backendId"))

        // Unload current backend if different
        if (_activeBackend != null && _activeBackend?.id != backendId) {
            Log.i(TAG, "Unloading previous backend: ${_activeBackend?.id}")
            _activeBackend?.unload()
        }

        // Initialize new backend
        Log.i(TAG, "Initializing backend: $backendId")
        val result = backend.initialize(context, config)

        if (result.isSuccess) {
            _activeBackend = backend
            _activeBackendId.value = backendId
            Log.i(TAG, "Backend activated: $backendId")
        } else {
            Log.e(TAG, "Failed to initialize backend: $backendId", result.exceptionOrNull())
        }

        return result
    }

    /**
     * Gets the currently active backend.
     *
     * @return The active backend, or null if none is active
     */
    fun getActiveBackend(): TranscriptionBackend? = _activeBackend

    /**
     * Gets all registered backends.
     *
     * @return List of available backends
     */
    fun getAvailableBackends(): List<TranscriptionBackend> = backends.values.toList()

    /**
     * Gets a specific backend by ID.
     *
     * @param backendId The backend ID
     * @return The backend, or null if not found
     */
    fun getBackend(backendId: String): TranscriptionBackend? = backends[backendId]

    /**
     * Checks if any backend is currently active.
     *
     * @return true if a backend is active
     */
    fun hasActiveBackend(): Boolean = _activeBackend != null

    /**
     * Unloads all loaded models (both LLM and transcription backends).
     * Synchronous — safe to call from any thread.
     */
    fun unloadAll() {
        if (LlmManager.isReady()) {
            LlmManager.unload()
        }
        unloadActiveBackend()
    }

    /**
     * Unloads the active backend.
     */
    fun unloadActiveBackend() {
        _activeBackend?.unload()
        _activeBackend = null
        _activeBackendId.value = null
        Log.i(TAG, "Active backend unloaded")
    }

    /**
     * Sets the keep-alive timeout for the active backend.
     *
     * @param minutes Timeout in minutes
     */
    fun setKeepAliveTimeout(minutes: Int) {
        _activeBackend?.setKeepAliveTimeout(minutes)
    }

    companion object {
        private const val TAG = "TranscriptionBackendManager"
    }
}
