package com.antivocale.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.data.local.toEntity
import com.antivocale.app.data.local.toLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    val sourcePackageName: String? = null
) {
    enum class Type { TEXT, AUDIO }
    enum class Status { PENDING, SUCCESS, ERROR }
}

class LogsViewModel(private val logDao: LogDao) : ViewModel() {

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

    fun logSuccess(taskId: String, result: String, durationMs: Long) {
        updateLog(taskId) { log ->
            log.copy(
                status = LogEntry.Status.SUCCESS,
                result = result,
                durationMs = durationMs
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

    // One-shot highlight signal: set a taskId to scroll-to + expand, then cleared by the UI
    private val _highlightTaskId = MutableStateFlow<String?>(null)
    val highlightTaskId: StateFlow<String?> = _highlightTaskId.asStateFlow()

    fun highlightLogEntry(taskId: String) {
        _highlightTaskId.value = taskId
    }

    fun clearHighlight() {
        _highlightTaskId.value = null
    }
}
