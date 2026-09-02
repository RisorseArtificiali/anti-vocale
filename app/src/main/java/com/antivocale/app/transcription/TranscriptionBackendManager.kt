package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.audio.AudioDurationPolicy
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelRecordsProvider
import com.antivocale.app.manager.LlmManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages transcription backends and coordinates between them.
 *
 * Only one backend can be active at a time due to memory constraints.
 * Static backends are provided via Hilt multibinding ([TranscriptionModule]).
 *
 * External models (spec: external models platform v2a) are NOT in the injected set:
 * the "external:" prefix routes to the single [ExternalSherpaBackend] engine, configured
 * per record via [BackendConfig.ExternalConfig], so no consumer can address an
 * unconfigured engine (its placeholder id is "external" and it is never registered
 * under that value). [getAvailableBackends] additionally appends one inert
 * [ExternalBackendHandle] per provider record so instance-enumerating consumers
 * (the re-transcribe picker) see every imported model; handles are enumeration-only,
 * the engine is the single loadable instance. For external ids, "known" in
 * [getBackend] means the provider snapshot holds the record, not engine state.
 */
@Singleton
class TranscriptionBackendManager @Inject constructor(
    private val llmManager: LlmManager,
    injectedBackends: Set<@JvmSuppressWildcards TranscriptionBackend>,
    private val externalRecordsProvider: ExternalModelRecordsProvider,
    // Not multibound into the backend set; Hilt satisfies this parameter via the engine's
    // own @Singleton @Inject constructor (Kotlin defaults are invisible to Dagger, so the
    // @Inject must stay). The default exists for direct test construction with a mock.
    private val externalEngine: ExternalSherpaBackend = ExternalSherpaBackend()
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
    /** True when the id is external AND the provider snapshot holds its record ("known" = store resolution, not engine state). */
    private fun isKnownExternal(backendId: String): Boolean =
        backendId.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX) &&
            externalRecordsProvider.records.value.any { it.backendId == backendId }

    suspend fun setActiveBackend(
        backendId: String,
        context: Context,
        config: BackendConfig
    ): Result<Unit> {
        val backend: TranscriptionBackend
        val effectiveConfig: BackendConfig
        if (backendId.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX)) {
            if (!isKnownExternal(backendId)) {
                return Result.failure(IllegalArgumentException("Unknown backend: $backendId"))
            }
            // Threads/provider are resolved by the orchestrator; inventing defaults here
            // would silently ignore the user's inference preferences.
            effectiveConfig = config as? BackendConfig.ExternalConfig
                ?: return Result.failure(IllegalArgumentException(
                    "External backend requires ExternalConfig (threads/provider are resolved by the orchestrator): $backendId"))
            backend = externalEngine
        } else {
            backend = backends[backendId]
                ?: return Result.failure(IllegalArgumentException("Unknown backend: $backendId"))
            effectiveConfig = config
        }

        // Unload current backend if different
        if (_activeBackend != null && _activeBackend?.id != backendId) {
            Log.i(TAG, "Unloading previous backend: ${_activeBackend?.id}")
            _activeBackend?.unload()
        }

        // Initialize new backend
        Log.i(TAG, "Initializing backend: $backendId")
        val result = backend.initialize(context, effectiveConfig)

        if (result.isSuccess) {
            _activeBackend = backend
            _activeBackendId.value = backendId
            // Backends with a self-managed idle timer (sherpa keep-alive) unload
            // themselves; clear our bookkeeping so the next request reloads.
            backend.setOnAutoUnloadCallback {
                if (_activeBackend === backend) {
                    _activeBackend = null
                    _activeBackendId.value = null
                    Log.i(TAG, "Active backend self-unloaded (idle timeout): $backendId")
                }
            }
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
     * Gets all registered backends: the injected static set plus one inert handle per
     * external-model record in the provider snapshot.
     *
     * @return List of available backends
     */
    fun getAvailableBackends(): List<TranscriptionBackend> =
        backends.values.toList() + externalRecordsProvider.records.value.map(::ExternalBackendHandle)

    /**
     * Gets a specific backend by ID. For external ids, returns the engine when the
     * provider snapshot holds the record ("known" = store resolution, not engine state).
     *
     * @param backendId The backend ID
     * @return The backend, or null if not found
     */
    fun getBackend(backendId: String): TranscriptionBackend? =
        if (isKnownExternal(backendId)) externalEngine
        else backends[backendId]

    /** Decode-path inputs the long-audio dialog gate needs for one backend (TASK-432). */
    data class GateInputs(
        /** Pre-tightening chunk cap; the path decision keys on null-ness only. */
        val maxChunkDurationSeconds: Int?,
        /** Backends that force VAD-aligned chunking flip the path to whole-file. */
        val forcesVadAlignedChunking: Boolean,
    ) {
        /**
         * The effective-VAD rule lives at this seam, not at each caller: the
         * backend's forced VAD ORs with the user preference (mirroring what the
         * orchestrator resolves for the live backend), then the policy decides
         * the path.
         */
        fun decodePath(vadPreference: Boolean): AudioDurationPolicy.DecodePath =
            AudioDurationPolicy.decodePathFor(
                vadPreference || forcesVadAlignedChunking, maxChunkDurationSeconds)
    }

    /**
     * Cold query for the long-audio dialog gate: predicts the decode path the
     * orchestrator will take for [backendId] WITHOUT loading anything. External
     * ids are resolved from their RECORD (the shared engine's
     * maxChunkDurationSeconds reads the last LOADED family and would mispredict
     * any other record); static ids read the backend instance.
     */
    fun gateInputsFor(backendId: String): GateInputs? {
        if (isKnownExternal(backendId)) {
            val record = externalRecordsProvider.records.value.firstOrNull { it.backendId == backendId }
            return record?.let {
                GateInputs(
                    maxChunkDurationSeconds = ExternalSherpaBackend.familyChunkCapSeconds(it.family),
                    forcesVadAlignedChunking = ExternalSherpaBackend.familyForcesVadAlignedChunking(it.family),
                )
            }
        }
        return getBackend(backendId)?.let {
            GateInputs(it.maxChunkDurationSeconds, it.requiresVadAlignedChunking)
        }
    }

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
        if (llmManager.isReady()) {
            llmManager.unload()
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

    /**
     * Inert enumeration handle for one external-model record (pickers and lists).
     * Not loadable: [initialize] always fails and [transcribeAudio] reports
     * NotInitialized; the [ExternalSherpaBackend] engine is the loadable instance.
     */
    private class ExternalBackendHandle(val record: ExternalModelRecord) : TranscriptionBackend {
        override val id: String get() = record.backendId
        override val displayName: String get() = record.displayName
        override val supportsAudio: Boolean get() = true
        override val supportsText: Boolean get() = false
        override val maxChunkDurationSeconds: Int? get() = null
        override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> =
            Result.failure(IllegalStateException(
                "External backend handles are enumeration-only; route ${record.backendId} through TranscriptionBackendManager"))
        override suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String): Result<TranscriptionResult> =
            Result.failure(TranscriptionException.NotInitialized())
        override suspend fun generateText(prompt: String): Result<String> =
            Result.failure(UnsupportedOperationException("Enumeration-only external handle"))
        override fun isReady(): Boolean = File(record.dir).exists()
        override fun isAudioSupported(): Boolean = true
        override fun unload() { /* enumeration-only */ }
        override fun setKeepAliveTimeout(minutes: Int) { /* enumeration-only */ }
        override fun getModelPath(): String? = record.dir
    }
}
