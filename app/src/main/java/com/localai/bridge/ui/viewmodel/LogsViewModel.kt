package com.localai.bridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    val audioDurationSeconds: Double = 0.0  // Original voice message duration
) {
    enum class Type { TEXT, AUDIO }
    enum class Status { PENDING, SUCCESS, ERROR }
}

class LogsViewModel : ViewModel() {

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredLogs: StateFlow<List<LogEntry>> = combine(_logs, _searchQuery) { logs, query ->
        if (query.isBlank()) logs
        else logs.filter { it.result.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val maxLogs = 10

    fun addLog(entry: LogEntry) {
        _logs.update { currentLogs ->
            (listOf(entry) + currentLogs).take(maxLogs)
        }
    }

    fun updateLog(taskId: String, update: (LogEntry) -> LogEntry) {
        _logs.update { currentLogs ->
            currentLogs.map { log ->
                if (log.taskId == taskId) update(log) else log
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // Convenience methods for common operations
    fun logRequest(
        taskId: String,
        type: LogEntry.Type,
        prompt: String,
        filePath: String? = null,
        audioDurationSeconds: Double = 0.0
    ) {
        addLog(
            LogEntry(
                taskId = taskId,
                type = type,
                status = LogEntry.Status.PENDING,
                prompt = prompt,
                filePath = filePath,
                audioDurationSeconds = audioDurationSeconds
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

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun clearSearch() {
        _searchQuery.value = ""
    }
}
