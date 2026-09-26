package com.antivocale.app.ui.viewmodel

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import com.antivocale.app.util.SubtitleFormatter
import kotlinx.coroutines.flow.distinctUntilChanged
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antivocale.app.R
import com.antivocale.app.audio.AudioDurationPolicy
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.audio.MemoryReadings
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.TimedSegmentsConverter
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.data.local.toEntity
import com.antivocale.app.data.local.toLogEntry
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.TranscriptionCalibrator
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.service.InferenceEnqueue
import com.antivocale.app.service.InferenceService
import com.antivocale.app.util.LocaleManager
import com.antivocale.app.util.SharedAudioHandler
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.TranscriptionBackendManager
import com.antivocale.app.transcription.variantAwareDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    /** TASK-276 AC3: raw ASR text pre-punctuation, when the pass changed it. */
    val rawTranscript: String? = null,
    /** TASK-121.4: the AI summary of a long transcript, when the pass produced one. */
    val summary: String? = null,
    /** TASK-494: stable token from the entity; rendered localized. */
    val summarySkipReason: String? = null,
    /** GH #92: JSON-serialized timed cues (raw passthrough; see
     *  TimedSegmentsConverter). WRITE-PATH ONLY (TASK-599): both list
     *  projections deliberately exclude this column, so it is structurally
     *  null on the UI side; reads go exclusively through
     *  LogsViewModel.speakerAnnotatedFlow / LogDao.getSegments. Do NOT
     *  "fix" it by adding the column back (pinned heap-churn regression,
     *  LogDaoProjectionTest). */
    val segments: String? = null,
    /** TASK-570: structured failure diagnostics JSON (raw passthrough; see
     *  FailureContextJson); present on ERROR rows written since v9. */
    val failureContext: String? = null,
    /** TASK-512: JSON processing context (raw passthrough; see
     *  ProcessingContextConverter); present on SUCCESS rows since v10. */
    val processingContext: String? = null,
    /** GH #43: the superseded fast first-pass transcript (two-pass runs).
     *  WRITE-PATH ONLY (TASK-595): excluded from both list projections
     *  (it duplicates the transcript and rides every interim re-emit), so
     *  structurally null on the UI side; reads go exclusively through
     *  LogsViewModel.firstPassFlow / LogDao.getFirstPass. It is load-bearing
     *  on the updateLog round-trip: getByTaskId is a full SELECT and the
     *  whole-row @Update must write the column back, so do NOT drop it, and
     *  do NOT add it back to the projections (pinned, LogDaoProjectionTest). */
    val firstPassTranscript: String? = null,
    /** TASK-546: backend-reported language (null on old rows and text entries). */
    val detectedLanguage: String? = null,
    /** TASK-546: the policy-resolved pin in force at transcription time. */
    val languagePin: String? = null,
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
    private val transcriptionCalibrator: TranscriptionCalibrator,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
) : ViewModel() {
    companion object {
        private const val TAG = "LogsViewModel"

        /**
         * F1 error-notification id: fixed, inside the free headroom of the
         * reserved-range contract (2501..2999; see
         * ResultNotificationFactory's band table).
         */
        internal const val HISTORY_ERROR_NOTIFICATION_ID = 2501
    }


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

    /**
     * TASK-500: user-facing errors on the History snackbar (browse FAB
     * failures, and the guarded FGS starts below), as EVENTS: a StateFlow
     * would conflate an identical consecutive error into silence while the
     * first snackbar is still showing.
     */
    private val _historyError = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val historyError: kotlinx.coroutines.flow.SharedFlow<String> = _historyError

    /**
     * F1 (code review): with no History tab composed (Crossfade disposes it)
     * or the app backgrounded, a SharedFlow event reaches nobody and the
     * failure would evaporate. When nobody is listening, the message rides
     * a notification instead; the snackbar path stays for the common case.
     */
    private fun reportHistoryError(message: String) {
        if (_historyError.subscriptionCount.value > 0) {
            _historyError.tryEmit(message)
        } else {
            postHistoryErrorNotification(message)
        }
    }

    private fun postHistoryErrorNotification(message: String) {
        // Sibling parity (code review): the result channel like the share
        // error path, a contentIntent opening the app, NOT the Tasker
        // fallback channel (a user silencing that channel would lose these).
        val nm = appContext.getSystemService(android.app.NotificationManager::class.java)
        com.antivocale.app.util.AppNotificationChannel.TRANSCRIPTION_RESULT.create(appContext)
        val contentIntent = android.app.PendingIntent.getActivity(
            appContext, 0,
            android.content.Intent(appContext, com.antivocale.app.MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = androidx.core.app.NotificationCompat.Builder(
            appContext, com.antivocale.app.util.AppNotificationChannel.TRANSCRIPTION_RESULT.id
        )
            .setContentTitle(appContext.getString(com.antivocale.app.R.string.app_name))
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        nm.notify(HISTORY_ERROR_NOTIFICATION_ID, notification)
    }

    /**
     * TASK-500: the History browse FAB. Copies the picked file through the
     * SAME shared-audio path as the share receiver (format-agnostic copy,
     * free-space pre-copy check, app-storage copy) and enqueues it on the
     * inference service with source "browse": not a share request, so no
     * share-back affordances, exactly like a Tasker-initiated local file.
     */
    fun transcribeLocalFile(context: Context, uri: Uri) {
        // The applicationContext, never the Activity: the copy is blocking IO
        // with no suspension points, so the coroutine can outlive the
        // Activity's teardown, and nothing here needs the Activity.
        val appContext = context.applicationContext
        // User-facing strings resolve through the in-app locale: on API 26-32
        // setApplicationLocales reaches Activity contexts only, so raw
        // applicationContext.getString would ignore a pinned app language
        // (code-review finding 4 on the browse flow).
        val localizedContext = LocaleManager.updateContextLocale(appContext)
        viewModelScope.launch(Dispatchers.IO) {
            // copyToAppStorage never throws: every path lands in a
            // CopyResult variant (the FGS start below is the throwing step).
            val result = SharedAudioHandler.copyToAppStorage(appContext, uri)
            val localPath = when (result) {
                is SharedAudioHandler.CopyResult.Success -> result.path
                // Every failure variant carries its own localized message
                // (single definition next to the sealed class).
                else -> {
                    reportHistoryError(result.userMessage(localizedContext))
                    return@launch
                }
            }

            // F5: the shared probe+offer; when a choice prompt is posted it
            // owns the request and this flow ends here.
            if (com.antivocale.app.receiver.SubtitleChoice.offerIfTracks(
                    appContext, UUID.randomUUID().toString(), localPath,
                    source = InferenceService.SOURCE_BROWSE,
                    sourcePackage = null,
                    backendOverride = null)) {
                return@launch
            }

            // F3: the long-audio advisory shared with the retranscribe
            // flow (TASK-432); above threshold the SAME dialog confirms
            // before hours of compute can start.
            // The in-memory active id is NULL while the model is idle-unloaded
            // (the common pick-time state); the PERSISTED preference keeps the
            // gate honest then (device-verified 2026-09-13: a null id fell to
            // the whole-file ceiling and skipped the advisory entirely).
            longAudioGate(
                transcriptionBackendManager.activeBackendId.value
                    ?: preferencesManager.transcriptionBackend.first(),
                localPath,
                localizedContext,
            ) { enqueueBrowse(appContext, localPath, localizedContext) }
        }
    }

    /**
     * TASK-432 long-audio gate, shared by retranscribe and browse (F-batch
     * reuse finding; the two inline copies had drifted on probe-failure
     * stance). Duration probe fails OPEN (0s, no advisory): the hard
     * ceilings inside preprocessing still protect every path.
     */
    private suspend fun longAudioGate(backendId: String?, filePath: String, context: Context, proceed: () -> Unit) {
        // Off-main ALWAYS: callers arrive on either dispatcher and the probe
        // is a container parse (code-review: the extraction had dropped the
        // retranscribe path's withContext(IO) wrapper).
        val duration = withContext(Dispatchers.IO) {
            runCatching { audioPreprocessor.getAudioDuration(filePath) }.getOrDefault(0.0)
        }
        val decodePath = backendId
            ?.let { transcriptionBackendManager.gateInputsFor(it) }
            ?.decodePath(preferencesManager.vadEnabled.first())
            ?: AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM
        val ceiling = AudioDurationPolicy.ceilingSeconds(
            decodePath, MemoryReadings.availableRamBytes(context), MemoryReadings.maxHeapBytes())
        val descriptor = backendId?.let { backendRegistry.byBackendId(it) }
        val modelPath = descriptor?.modelPathFlow(preferencesManager)?.first()
        val profile = backendId?.let { transcriptionCalibrator.getEstimate(it, modelPath ?: "") }
        val calibrated = profile?.hasEstimate == true
        val estimate = AudioDurationPolicy.resolveEstimateMsPerSec(
            profile?.msPerSecondOfAudio, calibrated, descriptor?.rtfEstimate ?: 1f)
        val decision = AudioDurationPolicy.warnDecision(
            duration.toLong(), ceiling, estimate, dialogCapable = true, calibrated = calibrated)
        if (!decision.showDialog) {
            proceed()
            return
        }
        val previous = _pendingLongAudioWarning.value
        _pendingLongAudioWarning.value = LongAudioWarning(
            durationMinutes = decision.durationMinutes.toInt(),
            estimateMinutes = decision.estimateMinutes,
            isRough = decision.isRough,
            modelDisplayName = backendId?.let { displayNameFor(it, context) } ?: "",
            onConfirm = {
                previous?.onConfirm?.invoke()
                proceed()
            },
        )
    }

    /** The browse enqueue, shared by the direct and the warn-confirmed paths. */
    private fun enqueueBrowse(appContext: Context, localPath: String, localizedContext: Context) {
        val intent = Intent(appContext, InferenceService::class.java).apply {
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, UUID.randomUUID().toString())
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, TaskerRequestReceiver.REQUEST_TYPE_AUDIO)
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, localPath)
            putExtra(InferenceService.EXTRA_SOURCE, InferenceService.SOURCE_BROWSE)
        }
        // F2: the unified enqueue owns the API 31+ restriction handling;
        // a restricted start posts the trampoline notification that
        // preserves the request instead of dropping it.
        when (InferenceEnqueue.start(appContext, intent)) {
            InferenceEnqueue.Outcome.Started,
            InferenceEnqueue.Outcome.FallbackNotificationPosted -> Unit
            is InferenceEnqueue.Outcome.Failed ->
                reportHistoryError(localizedContext.getString(R.string.failed_to_process_audio))
        }
    }

    fun updateLog(taskId: String, update: (LogEntry) -> LogEntry) {
        viewModelScope.launch {
            val entity = logDao.getByTaskId(taskId) ?: return@launch
            logDao.update(update(entity.toLogEntry()).toEntity())
        }
    }

    fun deleteLog(id: String) {
        // TASK-599 F3: the row's cached annotated transcript must not
        // outlive the row (long transcripts pin real memory).
        annotatedByRow.remove(id)
        firstPassByRow.remove(id)
        viewModelScope.launch {
            logDao.deleteById(id)
        }
    }

    fun clearLogs() {
        // TASK-662 review: the query must not survive the history it searched;
        // a forgotten active query on an empty list would hand the next
        // arrival a no-results view with a stale query in the field.
        _searchQuery.value = ""
        annotatedByRow.clear()
        firstPassByRow.clear()
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

    /** TASK-616: the technical processing-context line renders only when
     *  this is on (off by default); the data stays persisted either way. */
    val showTechnicalDetails: StateFlow<Boolean> = preferencesManager.showTechnicalDetails
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesManager.DEFAULT_SHOW_TECHNICAL_DETAILS)

    val showRetranscribeButton: StateFlow<Boolean> = preferencesManager.showRetranscribeButton
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesManager.DEFAULT_SHOW_RETRANSCRIBE_BUTTON)

    val compactResultActions: StateFlow<Boolean> = preferencesManager.compactResultActions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesManager.DEFAULT_COMPACT_RESULT_ACTIONS)

    /** TASK-546: the detected-language chip toggle (maintainer directive:
     *  the chip is conditional on a Settings flag). */
    val languageChipEnabled: StateFlow<Boolean> = preferencesManager.languageChipEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesManager.DEFAULT_LANGUAGE_CHIP_ENABLED)

    fun saveLanguageChip(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.saveLanguageChipEnabled(enabled) }
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

    /**
     * GH #83 / TASK-599: the expanded row's speaker-annotated transcript.
     * ONE StateFlow per row id, cached for the session: the cold Room flow
     * is built once (not per recomposition), deduped (any logs-table write
     * re-runs the DAO query; an unchanged String costs one equals), and
     * parsed + annotated OFF the composition thread. WhileSubscribed(5000)
     * stops the query when the row collapses; the cached StateFlow keeps
     * its last value across collapse/re-expand, so only the FIRST expand
     * can show the stored text for a frame before the annotated one lands.
     * Null while loading or when the row carries no cues; the caller falls
     * back to the stored text.
     */
    private val annotatedByRow = ConcurrentHashMap<String, StateFlow<String?>>()
    private val firstPassByRow = ConcurrentHashMap<String, StateFlow<String?>>()

    /** One cached per-row StateFlow per id: the flow is built once, late
     *  collectors share the running upstream, and eviction happens on
     *  delete/clear. Shared by the two lean per-row reads (TASK-595). */
    private fun <T> rowFlow(
        cache: ConcurrentHashMap<String, StateFlow<T?>>,
        id: String,
        build: () -> StateFlow<T?>,
    ): StateFlow<T?> = cache.getOrPut(id, build)

    /**
     * TASK-595 F5: the first-pass transcript of the expanded row, same lean
     * lifecycle as [speakerAnnotatedFlow] (the column left the list
     * projections: it duplicates the transcript and rides every re-emit).
     */
    fun firstPassFlow(id: String): StateFlow<String?> = rowFlow(firstPassByRow, id) {
        logDao.getFirstPass(id)
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    }

    fun speakerAnnotatedFlow(id: String): StateFlow<String?> = rowFlow(annotatedByRow, id) {
        logDao.getSegments(id)
            .distinctUntilChanged()
            .map { json ->
                withContext(Dispatchers.Default) {
                    json?.let {
                        SubtitleFormatter.speakerAnnotated(TimedSegmentsConverter.fromJson(it))
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    }

    /** TASK-601: the fast model's DISPLAY name for the first-pass header
     *  (the registry's contract for user surfaces; the observability
     *  metadata line still renders raw ids by design). Variant-blind: the
     *  fast side is the streaming entry; if a multi-variant streaming entry
     *  ever ships, resolve the saved path here too (review residue). Falls
     *  back to the id itself for unknown/external ids rather than blank. */
    fun fastBackendDisplayName(backendId: String?): String? = backendId?.let { id ->
        backendRegistry.byBackendId(id)
            ?.let { variantAwareDisplayName(appContext, it, null) }
            ?: id
    }

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
        val descriptor = backendRegistry.byBackendId(backendId)
            ?: return transcriptionBackendManager.getBackend(backendId)?.displayName ?: backendId
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
            // The shared TASK-432 gate (fail-open probe, dialogCapable);
            // retranscribe and browse run the identical decision.
            val appContext = context.applicationContext
            longAudioGate(backendId, filePath, appContext) {
                dispatchTranscription(originalEntry, backendId, filePath, appContext)
            }
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
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, TaskerRequestReceiver.REQUEST_TYPE_AUDIO)
            putExtra(TaskerRequestReceiver.EXTRA_PROMPT, originalEntry.prompt)
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, filePath)
            putExtra(InferenceService.EXTRA_SOURCE, "retranscribe")
            putExtra(InferenceService.EXTRA_BACKEND_OVERRIDE, backendId)
            originalEntry.sourcePackageName?.let {
                putExtra(InferenceService.EXTRA_SOURCE_PACKAGE, it)
            }
        }
        // F2: unified enqueue; a restricted start rides the fallback
        // notification (the request survives), a real failure surfaces on
        // the History error channel.
        when (InferenceEnqueue.start(context, intent)) {
            InferenceEnqueue.Outcome.Started,
            InferenceEnqueue.Outcome.FallbackNotificationPosted -> Unit
            is InferenceEnqueue.Outcome.Failed ->
                reportHistoryError(context.getString(R.string.failed_to_process_audio))
        }
    }
}
