package com.antivocale.app.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.widget.Toast
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import com.antivocale.app.R
import com.antivocale.app.audio.AudioDurationPolicy
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.audio.MemoryReadings
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.data.local.toEntity
import com.antivocale.app.data.local.toLogEntry
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.TranscriptionCalibrator
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.service.InferenceService
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.TranscriptionBackendManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val taskId: String,
    val type: Type,
    val status: Status,
    val prompt: String = "",
    val result: String = "",
    val errorMessage: String? = null,
    val durationMs: Long = 0,
    val filePath: String? = null,
    val audioDurationSeconds: Double = 0.0,
    val sourcePackageName: String? = null,
    val isPartial: Boolean = false,
    val failedChunkCount: Int = 0,
    /** Display name of the model that produced this transcription (GH #45; null on old rows). */
    val modelName: String? = null,
) {
    enum class Type { TEXT, AUDIO }
    enum class Status { QUEUED, PROCESSING, SUCCESS, ERROR }

    /** A final, copyable transcript (mirrors the swipe/menu action gating). */
    val hasCompletedResult: Boolean
        get() = status == Status.SUCCESS && result.isNotEmpty()
}

/**
 * A partial-transcription state younger than this is treated as an IN-FLIGHT transcription
 * (progressive transcription re-saves it roughly every 5s), so the "was interrupted" recovery
 * dialog is suppressed to avoid a false alarm while a transcription is actively running (issue #11).
 * State older than this means the transcription stopped progressing (process likely died
 * mid-transcription) → a genuine interruption worth offering to recover.
 */
private const val RECOVERY_STALE_THRESHOLD_MS = 15_000L

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val transcriptionBackendManager: TranscriptionBackendManager,
    private val logDao: LogDao,
    private val preferencesManager: PreferencesManager,
    private val backendRegistry: BackendRegistry,
    private val audioPreprocessor: AudioPreprocessor,
    private val transcriptionCalibrator: TranscriptionCalibrator
) : ViewModel() {

    /**
     * Pending long-audio warning (TASK-432): non-null while the advisory dialog
     * is showing; [onConfirm] carries the deferred dispatch. Once per request,
     * never persisted.
     */
    data class LongAudioWarning(
        val durationMinutes: Int,
        val estimateMinutes: Long,
        val isRough: Boolean,
        val modelDisplayName: String,
        val onConfirm: () -> Unit,
    )

    private val _pendingLongAudioWarning = MutableStateFlow<LongAudioWarning?>(null)
    val pendingLongAudioWarning: StateFlow<LongAudioWarning?> = _pendingLongAudioWarning.asStateFlow()

    fun confirmLongAudioWarning() {
        val warning = _pendingLongAudioWarning.value ?: return
        _pendingLongAudioWarning.value = null
        warning.onConfirm()
    }

    fun cancelLongAudioWarning() {
        _pendingLongAudioWarning.value = null
    }

    val logs: StateFlow<List<LogEntry>> = logDao.getAll()
        .map { entities -> entities.map { it.toLogEntry() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Search hits the DAO (SQL LIKE over FULL history, TASK-340 review note): the
    // in-memory list is now a bounded window, so filtering it in place would have
    // silently limited search to the newest 500 entries.
    val filteredLogs: StateFlow<List<LogEntry>> =
        _searchQuery.flatMapLatest { query ->
            if (query.isBlank()) logDao.getAll()
            else logDao.searchAll(query)
        }.map { entities -> entities.map { it.toLogEntry() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The currently active transcription entry (PROCESSING, falling back to QUEUED).
     * Prioritises entries that already have interim text from progressive transcription.
     * Used by the PiP view to efficiently observe only the relevant entry.
     */
    val activeTranscription: StateFlow<LogEntry?> = logs.map { logList ->
        logList.firstOrNull { it.status == LogEntry.Status.PROCESSING && it.result.isNotEmpty() }
            ?: logList.firstOrNull { it.status == LogEntry.Status.PROCESSING || it.status == LogEntry.Status.QUEUED }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _interruptedTranscription = MutableStateFlow<String?>(null)
    val interruptedTranscription: StateFlow<String?> = _interruptedTranscription.asStateFlow()

    init {
        viewModelScope.launch {
            val text = preferencesManager.partialTranscriptionText.first()
            if (text != null) {
                // Gate on staleness (issue #11): a fresh partial state means a transcription is
                // still in flight (it re-saves every ~5s), not interrupted — raising the "was
                // interrupted" dialog here was a false positive on the happy path. Only treat it
                // as a genuine interruption when no save has happened for a while.
                val timestamp = preferencesManager.partialTranscriptionTimestamp.first()
                val ageMs = timestamp?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE
                if (ageMs >= RECOVERY_STALE_THRESHOLD_MS) {
                    _interruptedTranscription.value = text
                    preferencesManager.clearPartialTranscriptionState()
                }
                // else: fresh → leave the state; logSuccess/logError clears it on completion,
                // or a later init re-evaluates it as stale.
            }
        }
    }

    fun dismissInterruptedTranscription() {
        _interruptedTranscription.value = null
    }

    fun addLog(entry: LogEntry) {
        viewModelScope.launch {
            logDao.insert(entry.toEntity())
        }
    }

    fun updateLog(taskId: String, update: (LogEntry) -> LogEntry) {
        viewModelScope.launch {
            val entity = logDao.getByTaskId(taskId) ?: return@launch
            logDao.update(update(entity.toLogEntry()).toEntity())
        }
    }

    fun deleteLog(id: String) {
        viewModelScope.launch {
            logDao.deleteById(id)
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            logDao.deleteAll()
        }
    }

    fun logRequest(
        taskId: String,
        type: LogEntry.Type,
        prompt: String,
        filePath: String? = null,
        audioDurationSeconds: Double = 0.0,
        sourcePackageName: String? = null
    ) {
        addLog(
            LogEntry(
                taskId = taskId,
                type = type,
                status = LogEntry.Status.PROCESSING,
                prompt = prompt,
                filePath = filePath,
                audioDurationSeconds = audioDurationSeconds,
                sourcePackageName = sourcePackageName
            )
        )
    }

    fun logSuccess(
        taskId: String,
        result: String,
        durationMs: Long,
        isPartial: Boolean = false,
        failedChunkCount: Int = 0
    ) {
        updateLog(taskId) { log ->
            log.copy(
                status = LogEntry.Status.SUCCESS,
                result = result,
                durationMs = durationMs,
                isPartial = isPartial,
                failedChunkCount = failedChunkCount
            )
        }
    }

    fun updateAudioDuration(taskId: String, audioDurationSeconds: Double) {
        updateLog(taskId) { log ->
            log.copy(audioDurationSeconds = audioDurationSeconds)
        }
    }

    fun logError(taskId: String, errorMessage: String, durationMs: Long = 0) {
        updateLog(taskId) { log ->
            log.copy(
                status = LogEntry.Status.ERROR,
                errorMessage = errorMessage,
                durationMs = durationMs
            )
        }
    }

    /**
     * Marks a log entry as ERROR only if it is still in a non-terminal state
     * (QUEUED or PROCESSING). Used in finally/cancellation paths to avoid
     * overwriting a completed result.
     */
    suspend fun cancelIfPending(taskId: String, errorMessage: String, durationMs: Long) {
        logDao.failNonTerminal(taskId, errorMessage, durationMs)
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun clearSearch() {
        _searchQuery.value = ""
    }

    val swipeActionMode: StateFlow<String> = preferencesManager.swipeActionMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesManager.DEFAULT_SWIPE_ACTION_MODE)

    val groupLogsByConversation: StateFlow<Boolean> = preferencesManager.groupLogsByConversation
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesManager.DEFAULT_GROUP_LOGS_BY_CONVERSATION)

    val showRetranscribeButton: StateFlow<Boolean> = preferencesManager.showRetranscribeButton
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesManager.DEFAULT_SHOW_RETRANSCRIBE_BUTTON)

    val compactResultActions: StateFlow<Boolean> = preferencesManager.compactResultActions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesManager.DEFAULT_COMPACT_RESULT_ACTIONS)

    /** GH #45 follow-up: reveal the task-id detail line. Default off. */
    val showTaskDetails: StateFlow<Boolean> = preferencesManager.showTaskDetails
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesManager.DEFAULT_SHOW_TASK_DETAILS)

    fun saveShowTaskDetails(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveShowTaskDetails(enabled)
        }
    }

    fun saveGroupLogsByConversation(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveGroupLogsByConversation(enabled)
        }
    }

    val showVadAdvisory: StateFlow<Boolean> = combine(
        transcriptionBackendManager.activeBackendId,
        preferencesManager.vadEnabled,
        preferencesManager.vadAdvisoryDismissed
    ) { backendId, vadEnabled, dismissed ->
        backendId == BuiltInBackendIds.PARAKEET && vadEnabled && !dismissed
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun dismissVadAdvisory() {
        viewModelScope.launch {
            preferencesManager.saveVadAdvisoryDismissed(true)
        }
    }

    // One-shot highlight signal: set a taskId to scroll-to + expand, then cleared by the UI
    private val _highlightTaskId = MutableStateFlow<String?>(null)
    val highlightTaskId: StateFlow<String?> = _highlightTaskId.asStateFlow()

    fun highlightLogEntry(taskId: String) {
        _highlightTaskId.value = taskId
    }

    fun clearHighlight() {
        _highlightTaskId.value = null
    }

    data class BackendOption(
        val backendId: String,
        val displayName: String,
        val isCurrentBackend: Boolean
    )

    suspend fun getAvailableAudioBackendsWithModels(context: Context): List<BackendOption> {
        val currentBackendId = transcriptionBackendManager.activeBackendId.first()
        val backends = transcriptionBackendManager.getAvailableBackends()
            .filter { it.supportsAudio }

        return backends
            .filter { backend ->
                backendRegistry.byBackendId(backend.id)
                    ?.modelPathFlow(preferencesManager)?.first()
                    ?.isNotBlank() == true
            }
            .map { backend ->
                BackendOption(
                    backendId = backend.id,
                    displayName = displayNameFor(backend.id, context),
                    isCurrentBackend = backend.id == currentBackendId
                )
            }
    }

    /**
     * Derives a user-visible backend label through the registry descriptor display-name
     * contract (fixed localized resource, else path-derived variant title), falling back
     * to the backend's own [com.antivocale.app.transcription.TranscriptionBackend.displayName]
     * for ids the registry does not know. Raw backend ids ("sherpa-onnx",
     * "nemotron-streaming") must never reach the picker (the "missed one dispatch site"
     * bug class): every display label routes through the registry.
     */
    private suspend fun displayNameFor(backendId: String, context: Context): String {
        val descriptor = backendRegistry.byBackendId(backendId) ?: return backendId
        val path = descriptor.modelPathFlow(preferencesManager).first()
        return when {
            descriptor.displayNameResId != null -> context.getString(descriptor.displayNameResId)
            else -> descriptor.deriveDisplayName(context, path ?: "")
        }
    }

    fun reTranscribeWithBackend(
        originalEntry: LogEntry,
        backendId: String,
        context: android.content.Context
    ) {
        val filePath = originalEntry.filePath ?: return
        if (!File(filePath).exists()) {
            Toast.makeText(context, context.getString(R.string.retranscribe_file_not_found), Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch {
            // dialogCapable is true here by construction: this is the interactive
            // in-app flow. Every headless dispatch site never calls the gate.
            val duration = withContext(Dispatchers.IO) { audioPreprocessor.getAudioDuration(filePath) }
            // Mirror the orchestrator's VAD resolution exactly: the preference OR
            // the backend's forced VAD (Canary/Gemma). Missing the forced term
            // would promise a streaming transcription that is then refused
            // whole-file (review finding 1).
            val gateInputs = transcriptionBackendManager.gateInputsFor(backendId)
            val vadEnabled = preferencesManager.vadEnabled.first() ||
                (gateInputs?.forcesVadAlignedChunking == true)
            val decodePath = AudioDurationPolicy.decodePathFor(vadEnabled, gateInputs?.maxChunkDurationSeconds)
            // applicationContext, not the Activity: the pending warning outlives
            // rotation inside the ViewModel and must not hold a destroyed Activity
            // (review finding 4).
            val appContext = context.applicationContext
            val ceiling = AudioDurationPolicy.ceilingSeconds(
                decodePath, MemoryReadings.availableRamBytes(appContext), MemoryReadings.maxHeapBytes())
            val descriptor = backendRegistry.byBackendId(backendId)
            val modelPath = descriptor?.modelPathFlow(preferencesManager)?.first()
            val profile = transcriptionCalibrator.getEstimate(backendId, modelPath ?: "")
            val estimate = AudioDurationPolicy.resolveEstimateMsPerSec(
                profile?.msPerSecondOfAudio, profile?.sampleCount ?: 0, descriptor?.rtfEstimate ?: 1f)
            val decision = AudioDurationPolicy.warnDecision(
                duration.toLong(), ceiling, estimate, dialogCapable = true,
                calibrated = profile?.hasEstimate == true)
            if (!decision.showDialog) {
                // below threshold or over ceiling: the pre-read refusal carries the message
                dispatchTranscription(originalEntry, backendId, filePath, appContext)
                return@launch
            }
            val displayName = when {
                descriptor == null ->
                    transcriptionBackendManager.getBackend(backendId)?.displayName ?: backendId
                descriptor.displayNameResId != null -> appContext.getString(descriptor.displayNameResId)
                else -> descriptor.deriveDisplayName(appContext, modelPath ?: filePath)
            }
            // A second long retranscribe while a warning is pending must not drop
            // the first request: the new confirm dispatches both, in order.
            val previous = _pendingLongAudioWarning.value
            _pendingLongAudioWarning.value = LongAudioWarning(
                durationMinutes = kotlin.math.ceil(duration / 60.0).toInt(),
                estimateMinutes = decision.estimateMinutes,
                isRough = decision.isRough,
                modelDisplayName = displayName,
                onConfirm = {
                    previous?.onConfirm?.invoke()
                    dispatchTranscription(originalEntry, backendId, filePath, appContext)
                }
            )
        }
    }

    private fun dispatchTranscription(
        originalEntry: LogEntry,
        backendId: String,
        filePath: String,
        context: android.content.Context
    ) {
        val newTaskId = UUID.randomUUID().toString()

        val intent = Intent(context, InferenceService::class.java).apply {
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, newTaskId)
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "audio")
            putExtra(TaskerRequestReceiver.EXTRA_PROMPT, originalEntry.prompt)
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, filePath)
            putExtra(InferenceService.EXTRA_SOURCE, "retranscribe")
            putExtra(InferenceService.EXTRA_BACKEND_OVERRIDE, backendId)
            originalEntry.sourcePackageName?.let {
                putExtra(InferenceService.EXTRA_SOURCE_PACKAGE, it)
            }
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
