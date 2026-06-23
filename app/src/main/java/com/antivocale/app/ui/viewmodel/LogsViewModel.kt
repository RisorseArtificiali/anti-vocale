package com.antivocale.app.ui.viewmodel

import android.content.Intent
import android.widget.Toast
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import com.antivocale.app.R
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.data.local.toEntity
import com.antivocale.app.data.local.toLogEntry
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.service.InferenceService
import com.antivocale.app.transcription.LlmTranscriptionBackend
import com.antivocale.app.transcription.Qwen3AsrBackend
import com.antivocale.app.transcription.SherpaOnnxBackend
import com.antivocale.app.transcription.TranscriptionBackendManager
import com.antivocale.app.transcription.WhisperBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    val failedChunkCount: Int = 0
) {
    enum class Type { TEXT, AUDIO }
    enum class Status { PENDING, SUCCESS, ERROR }
}

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val transcriptionBackendManager: TranscriptionBackendManager,
    private val logDao: LogDao,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    val logs: StateFlow<List<LogEntry>> = logDao.getAll()
        .map { entities -> entities.map { it.toLogEntry() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredLogs: StateFlow<List<LogEntry>> = combine(logs, _searchQuery) { logs, query ->
        if (query.isBlank()) logs
        else logs.filter { it.result.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The currently active (PENDING) transcription entry.
     * Prioritises entries that already have interim text from progressive transcription.
     * Used by the PiP view to efficiently observe only the relevant entry.
     */
    val activeTranscription: StateFlow<LogEntry?> = logs.map { logList ->
        logList.firstOrNull { it.status == LogEntry.Status.PENDING && it.result.isNotEmpty() }
            ?: logList.firstOrNull { it.status == LogEntry.Status.PENDING }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _interruptedTranscription = MutableStateFlow<String?>(null)
    val interruptedTranscription: StateFlow<String?> = _interruptedTranscription.asStateFlow()

    init {
        viewModelScope.launch {
            val text = preferencesManager.partialTranscriptionText.first()
            if (text != null) {
                _interruptedTranscription.value = text
                preferencesManager.clearPartialTranscriptionState()
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
                status = LogEntry.Status.PENDING,
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

    fun updateInterimResult(taskId: String, accumulatedText: String) {
        updateLog(taskId) { log ->
            log.copy(result = accumulatedText)
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
     * Marks a log entry as ERROR only if it is still PENDING.
     * Used in finally/cancellation paths to avoid overwriting a completed result.
     */
    suspend fun cancelIfPending(taskId: String, errorMessage: String, durationMs: Long) {
        val entity = logDao.getByTaskId(taskId) ?: return
        if (entity.status == LogEntry.Status.PENDING.name) {
            logDao.update(
                entity.toLogEntry().copy(
                    status = LogEntry.Status.ERROR,
                    errorMessage = errorMessage,
                    durationMs = durationMs
                ).toEntity()
            )
        }
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
        backendId == SherpaOnnxBackend.BACKEND_ID && vadEnabled && !dismissed
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

    suspend fun getAvailableAudioBackendsWithModels(): List<BackendOption> {
        val currentBackendId = transcriptionBackendManager.activeBackendId.first()
        val backends = transcriptionBackendManager.getAvailableBackends()
            .filter { it.supportsAudio }

        val modelPaths = mapOf(
            WhisperBackend.BACKEND_ID to preferencesManager.whisperModelPath.first(),
            SherpaOnnxBackend.BACKEND_ID to preferencesManager.parakeetModelPath.first(),
            Qwen3AsrBackend.BACKEND_ID to preferencesManager.qwen3AsrModelPath.first(),
            LlmTranscriptionBackend.BACKEND_ID to preferencesManager.modelPath.first()
        )

        return backends
            .filter { backend -> modelPaths[backend.id]?.isNotBlank() == true }
            .map { backend ->
                BackendOption(
                    backendId = backend.id,
                    displayName = backend.displayName,
                    isCurrentBackend = backend.id == currentBackendId
                )
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
