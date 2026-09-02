package com.antivocale.app.service

import android.app.Notification
import android.app.NotificationManager
import com.antivocale.app.util.AppNotificationChannel
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.antivocale.app.R
import com.antivocale.app.MainActivity
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.TranscriptionCalibrator
import com.antivocale.app.data.download.ProgressThrottler
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.transcription.TranscriptionBackendManager
import com.antivocale.app.transcription.TranscriptionOrchestrator
import com.antivocale.app.util.CrashReporter
import com.antivocale.app.util.TranscriptFileSaver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Foreground service for handling inference requests.
 *
 * Delegates all business logic to [TranscriptionOrchestrator] and handles
 * only Android lifecycle concerns: notifications, broadcasts, clipboard,
 * and foreground service management.
 */
@AndroidEntryPoint
class InferenceService : Service(), TranscriptionListener {

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var perAppPreferencesManager: PerAppPreferencesManager
    @Inject lateinit var orchestrator: TranscriptionOrchestrator
    @Inject lateinit var logDao: LogDao

    companion object {
        const val TAG = "InferenceService"
        val CHANNEL_ID = AppNotificationChannel.INFERENCE.id
        val RESULT_CHANNEL_ID = AppNotificationChannel.TRANSCRIPTION_RESULT.id
        const val NOTIFICATION_ID = 1001
        const val RESULT_NOTIFICATION_ID = 1002

        private const val RC_LAUNCH_DEFAULT = 0
        private const val RC_LAUNCH_MODEL_TAB = 1
        private const val RC_NAV_PREV = 10
        private const val RC_NAV_NEXT = 11
        private const val RC_NAV_LIVE = 12

        const val EXTRA_SOURCE = "source"
        const val EXTRA_SOURCE_PACKAGE = "source_package"
        const val SOURCE_SHARE = "share"

        const val EXTRA_SHARED_URI = "shared_uri"
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_BACKEND_OVERRIDE = "backend_override"

        const val ACTION_CANCEL = "com.antivocale.app.CANCEL_TRANSCRIPTION"

        /** Per-task cancel (GH #52 follow-up): cancels one queued or in-flight request. */
        const val ACTION_CANCEL_TASK = "com.antivocale.app.CANCEL_TRANSCRIPTION_TASK"
        const val EXTRA_CANCEL_TASK_ID = "cancel_task_id"

        // Chunk navigation actions for the in-progress notification (TASK-242).
        // Target the service directly — navigation is stateful (mutates the live cursor the
        // producer also writes to), unlike the stateless copy/share actions in
        // NotificationActionReceiver. Mirrors how ACTION_CANCEL is wired.
        const val ACTION_NAV_PREV = "com.antivocale.app.NAV_CHUNK_PREV"
        const val ACTION_NAV_NEXT = "com.antivocale.app.NAV_CHUNK_NEXT"
        const val ACTION_NAV_LIVE = "com.antivocale.app.NAV_CHUNK_LIVE"

        private val _isTranscribing = MutableStateFlow(false)
        val isTranscribing: kotlinx.coroutines.flow.StateFlow<Boolean> = _isTranscribing.asStateFlow()
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CrashReporter.handler)

    /**
     * In-flight result-notification jobs (onSuccess's auto-copy + save + notify
     * coroutine). processQueue() awaits these before tearing the service down;
     * see onSuccess for the race this closes.
     */
    private val pendingResultNotifications = java.util.Collections.synchronizedList(mutableListOf<Job>())
    private val requestQueue = ConcurrentLinkedQueue<PendingRequest>()
    private val inFlightTaskIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var currentProcessingJob: Job? = null
    /** taskId currently being processed (null while idle); per-task cancel targets this. */
    @Volatile private var currentTaskId: String? = null
    /** Child job running the current task's processRequest; per-task cancel target. */
    @Volatile private var currentTaskJob: Job? = null
    @Volatile private var transcriptionStartTime: Long = 0
    private val pendingCount = AtomicInteger(0)
    private val resultNotificationFactory: ResultNotificationFactory by lazy { ResultNotificationFactory(this) }

    // ---- Chunk navigation state (TASK-242) ----
    // Null outside a multi-chunk progressive job; created on the first interim chunk result
    // and cleared when the next job starts. Single-chunk jobs never create one (nav is a no-op).
    @Volatile private var chunkNavState: ChunkNavState? = null
    // Latest progress tick cached so renderChunkNavNotification() can refresh ETA/queue subtext
    // without wiping the user's pinned chunk view on a progress-bar update.
    @Volatile private var latestEtaText = ""
    @Volatile private var latestDurationSeconds = 0
    @Volatile private var latestQueuedCount = 0
    // Signature of the last rendered nav notification; when unchanged we skip the rebuild+notify
    // to avoid re-posting on every 200ms progress tick while the viewed chunk is stable.
    @Volatile private var lastNavSignature: String? = null
    // TASK-266: gate progress-bar notification posts to ~1/sec (the orchestrator ticks
    // every ~200ms), reusing the download ProgressThrottler. Only the smooth-progress
    // path consults it: terminal notifications (result/error/no-model) post on separate
    // ids, and chunk-nav rendering keeps its signature-based dedup. Swappable so tests
    // can inject a fake clock (same seam idea as orchestrator.throttleClock).
    internal var progressNotifyThrottler = ProgressThrottler()

    data class PendingRequest(
        val taskId: String,
        val requestType: String,
        val prompt: String,
        val filePath: String?,
        val startTime: Long = System.currentTimeMillis(),
        val source: String? = null,
        val sourcePackage: String? = null,
        val backendOverride: String? = null,
        val trackIndex: Int = -1
    )

    // ---- Android Lifecycle ----

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createResultNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand called, action=${intent?.action}")

        if (intent?.action == ACTION_CANCEL) {
            handleCancelRequest()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_CANCEL_TASK) {
            intent.getStringExtra(EXTRA_CANCEL_TASK_ID)?.let { handleCancelTask(it) }
            return START_NOT_STICKY
        }

        // Chunk navigation: mutate the live cursor and re-post. The service is already
        // foreground for the active job, so this delivers a command rather than starting work.
        val navAction = intent?.action
        if (navAction == ACTION_NAV_PREV || navAction == ACTION_NAV_NEXT || navAction == ACTION_NAV_LIVE) {
            handleChunkNavAction(navAction)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.processing_audio)))

        val filePath = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH)

        val request = PendingRequest(
            taskId = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID)
                ?: "unknown_${System.currentTimeMillis()}",
            requestType = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE) ?: "text",
            prompt = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_PROMPT) ?: "",
            filePath = filePath,
            source = intent?.getStringExtra(EXTRA_SOURCE),
            sourcePackage = intent?.getStringExtra(EXTRA_SOURCE_PACKAGE),
            backendOverride = intent?.getStringExtra(EXTRA_BACKEND_OVERRIDE),
            trackIndex = intent?.getIntExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, -1) ?: -1
        )

        // Dedup by taskId: drop a duplicate before it enters the queue. This covers both the
        // queued case (a request with the same taskId is waiting) and the in-flight case (a
        // request with the same taskId is currently being processed). `inFlightTaskIds.add`
        // returns false when the id is already present — atomic against the drain loop's remove.
        if (!inFlightTaskIds.add(request.taskId)) {
            Log.w(TAG, "Duplicate taskId dropped (already queued or processing): ${request.taskId}")
            processQueue()
            return START_NOT_STICKY
        }

        requestQueue.add(request)
        val queueSize = pendingCount.incrementAndGet()
        Log.i(TAG, "Request enqueued: ${request.taskId}, source=${request.source}, sourcePackage=${request.sourcePackage}, filePath=$filePath")

        // GH #51: make the request visible in the Logs tab from enqueue time.
        // processRequest's markProcessing promotes the entry once work starts.
        serviceScope.launch {
            runCatching {
                orchestrator.logQueued(
                    taskId = request.taskId,
                    requestType = request.requestType,
                    prompt = request.prompt,
                    filePath = request.filePath,
                    sourcePackageName = request.sourcePackage,
                )
            }.onFailure { Log.w(TAG, "Failed to log queued request ${request.taskId}", it) }
        }

        if (_isTranscribing.value && queueSize > 1) {
            updateNotificationQueueHint(queueSize)
        }

        processQueue()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
    }

    // ---- Queue Management ----

    private fun processQueue() {
        currentProcessingJob = serviceScope.launch {
            if (_isTranscribing.value) {
                Log.d(TAG, "Already processing, request will wait in queue")
                return@launch
            }

            _isTranscribing.value = true
            val totalInBatch = pendingCount.get()
            var currentIndex = 0

            try {
                while (requestQueue.isNotEmpty()) {
                    val request = requestQueue.poll() ?: continue
                    pendingCount.decrementAndGet()
                    currentIndex++
                    currentTaskId = request.taskId

                    try {
                        // Show queue-aware initial notification
                        val initialText = if (totalInBatch > 1) {
                            getString(R.string.processing_queue_item, currentIndex, totalInBatch)
                        } else {
                            getString(R.string.processing_request, request.requestType)
                        }
                        updateNotification(initialText)

                        transcriptionStartTime = System.currentTimeMillis()
                        // Reset chunk-nav state for the new job (TASK-242).
                        chunkNavState = null
                        latestEtaText = ""
                        latestDurationSeconds = 0
                        latestQueuedCount = 0
                        lastNavSignature = null
                        // Throttle carries no state across jobs: the first progress tick
                        // of each request must post promptly (TASK-266).
                        progressNotifyThrottler.reset()

                        // Per-task child job (GH #52): cancelling it (per-task cancel
                        // from the Logs menu) aborts only THIS request; the drain loop
                        // survives via join() and keeps processing the queue. A batch
                        // ACTION_CANCEL instead cancels the drain job itself, and join()
                        // rethrows, reaching the batch catch below.
                        val taskJob = launch {
                            try {
                                orchestrator.processRequest(
                                    taskId = request.taskId,
                                    requestType = request.requestType,
                                    prompt = request.prompt,
                                    filePath = request.filePath,
                                    source = request.source,
                                    sourcePackage = request.sourcePackage,
                                    backendOverride = request.backendOverride,
                                    trackIndex = request.trackIndex,
                                    queuePosition = currentIndex,
                                    queueTotal = totalInBatch,
                                    context = applicationContext,
                                    cacheDir = cacheDir,
                                    listener = this@InferenceService,
                                    coroutineScope = this
                                )
                            } finally {
                                // Always release the taskId so a future request with the
                                // same id is accepted. Runs on success, error, cancel.
                                inFlightTaskIds.remove(request.taskId)
                            }
                        }
                        currentTaskJob = taskJob
                        taskJob.join()
                        currentTaskJob = null
                    } finally {
                        // Belt for the case where we never launched (notification etc.)
                        inFlightTaskIds.remove(request.taskId)
                    }
                }
            } catch (e: CancellationException) {
                // Batch cancel (ACTION_CANCEL) or scope teardown: per-task cancels
                // never reach here anymore, they only cancel the child task job.
                Log.i(TAG, "Processing cancelled by user")
                failQueuedLogs("Cancelled")
                requestQueue.clear()
                pendingCount.set(0)
            } finally {
                currentTaskId = null
                currentTaskJob = null
                _isTranscribing.value = false
            }

            // Wait for any pending result-notification jobs before teardown:
            // stopSelf -> onDestroy cancels serviceScope, which would kill a
            // not-yet-run notification coroutine (the TASK-336 race).
            val pending = synchronized(pendingResultNotifications) { pendingResultNotifications.toList() }
            pending.forEach { it.join() }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleCancelRequest() {
        Log.i(TAG, "Cancel request received")
        currentProcessingJob?.cancel()
        failQueuedLogs("Cancelled")
        requestQueue.clear()
        pendingCount.set(0)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Per-task cancel (GH #52 follow-up): removes one queued request or cancels
     * the in-flight task job. The drain loop keeps processing the rest of the
     * queue, unlike the batch [handleCancelRequest]. The log row is closed from
     * the (never cancelled) service scope: a Room suspend write from inside the
     * cancelled task job would itself be cancelled and never land.
     * failNonTerminal only touches non-terminal rows, so a racing terminal write
     * by the orchestrator makes the close a harmless no-op.
     */
    private fun handleCancelTask(taskId: String) {
        val isCurrent = taskId == currentTaskId
        if (isCurrent) {
            Log.i(TAG, "Per-task cancel for in-flight $taskId")
            currentTaskJob?.cancel()
        } else {
            val removed = requestQueue.removeIf { it.taskId == taskId }
            if (!removed) {
                Log.i(TAG, "Per-task cancel for unknown/finished $taskId; nothing to do")
                return
            }
            pendingCount.decrementAndGet()
            inFlightTaskIds.remove(taskId)
            Log.i(TAG, "Per-task cancel removed queued $taskId")
        }
        serviceScope.launch {
            runCatching { logDao.failNonTerminal(taskId, "Cancelled", durationMs = 0) }
                .onFailure { Log.w(TAG, "Failed to close cancelled row for $taskId", it) }
        }
    }

    /**
     * Closes the log rows of requests still waiting in the queue when the whole
     * batch is cancelled (GH #51: queued items have rows; without this they
     * would show "Queued" forever). Runs before the queue is cleared.
     */
    private fun failQueuedLogs(reason: String) {
        val waiting = requestQueue.map { it.taskId }
        if (waiting.isEmpty()) return
        serviceScope.launch {
            runCatching { logDao.failNonTerminalForTaskIds(waiting, reason) }
                .onFailure { Log.w(TAG, "Failed to close queued log rows", it) }
        }
    }

    // ---- TranscriptionListener Implementation ----

    override fun onStatusUpdate(message: String) {
        updateNotification(message)
    }

    override fun onIndeterminateProgress(message: String) {
        updateNotificationWithProgress(message, indeterminate = true)
    }

    override fun onProgress(
        contentText: String,
        progressPercent: Int,
        etaText: String,
        durationSeconds: Int,
        startTimeMillis: Long,
        queuedCount: Int
    ) {
        latestEtaText = etaText
        latestDurationSeconds = durationSeconds
        latestQueuedCount = queuedCount
        // If chunk navigation is active, preserve the (possibly pinned) chunk view and only
        // refresh the progress subtext — do not overwrite it with a progress-bar notification.
        val state = chunkNavState
        if (state != null && state.hasAnyChunk) {
            renderChunkNavNotification()
        } else if (progressNotifyThrottler.shouldReport()) {
            updateNotificationWithSmoothProgress(
                contentText, progressPercent, etaText, durationSeconds, startTimeMillis, pendingCount.get()
            )
        }
    }

    override fun onInterimResult(
        contentText: String,
        bigText: String,
        subText: String,
        chunkIndex: Int,
        chunkText: String?,
        totalChunks: Int
    ) {
        // Multi-chunk progressive job: maintain nav state and render the chunk view.
        if (totalChunks >= 2 && chunkIndex >= 0 && chunkText != null) {
            // takeIf guards against a stale state if the per-job reset didn't fire.
            val active = chunkNavState?.takeIf { it.totalChunks == totalChunks }
                ?: ChunkNavState(totalChunks).also { chunkNavState = it }
            active.onChunkCompleted(chunkIndex, chunkText)
            renderChunkNavNotification()
        } else {
            // Single-chunk / non-chunk interim: legacy behavior, no navigation.
            updateNotification(
                contentText = contentText,
                bigText = bigText,
                subText = subText,
                startTimeMillis = transcriptionStartTime
            )
        }
    }

    private fun handleChunkNavAction(action: String) {
        val state = chunkNavState ?: return
        when (action) {
            ACTION_NAV_PREV -> state.prev()
            ACTION_NAV_NEXT -> state.next()
            ACTION_NAV_LIVE -> state.jumpToLive()
        }
        renderChunkNavNotification()
    }

    private fun formatTimingText(etaText: String, durationSeconds: Int): String = when {
        etaText.isNotEmpty() -> etaText
        durationSeconds > 0 -> formatDuration(durationSeconds)
        else -> ""
    }

    /** Joins non-empty parts with " · "; null when all parts are empty. */
    private fun joinSubText(vararg parts: String): String? =
        parts.filter { it.isNotEmpty() }.joinToString(" · ").takeIf { it.isNotEmpty() }

    /**
     * Renders the in-progress notification from [chunkNavState]: shows the viewed chunk's text
     * (pinned or live), a chunks-completed progress bar, prev/next/jump-to-live actions with
     * progressive disclosure, and Cancel always last (so it is the one elided when collapsed
     * while navigating — still reachable by expanding or swiping). Per TASK-242.
     */
    private fun renderChunkNavNotification() {
        val state = chunkNavState ?: return
        // One atomic snapshot so action visibility, displayed text, and progress bar cannot
        // disagree when chunk completions (IO) and button taps (main) race.
        val snap = state.snapshot()
        if (snap.liveIndex < 0) return

        val total = snap.totalChunks
        val live = snap.liveIndex
        val viewIndex = snap.viewIndex
        val pinned = snap.pinned
        val chunkText = snap.text?.takeIf { it.isNotBlank() }
            ?: getString(R.string.chunk_nav_empty)

        val navSub = if (pinned) {
            getString(R.string.chunk_nav_pinned_subtext, viewIndex + 1, total, live + 1)
        } else {
            getString(R.string.chunk_nav_live_subtext, live + 1, total)
        }
        val timingText = formatTimingText(latestEtaText, latestDurationSeconds)
        val queueText = if (latestQueuedCount > 0) resources.getQuantityString(R.plurals.queued_count, latestQueuedCount, latestQueuedCount) else ""
        // Skip the rebuild + notify when nothing visible changed (e.g. successive 200ms progress
        // ticks while pinned and the ETA string is unchanged). chunkText is included so a retried
        // chunk's improved text refreshes even while pinned on it.
        val signature = "$viewIndex|$pinned|$live|$queueText|$timingText|$chunkText"
        if (signature == lastNavSignature) return
        lastNavSignature = signature
        val subText = joinSubText(navSub, queueText, timingText) ?: navSub

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(chunkText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(chunkText))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setSubText(subText)
            .setProgress(total, live + 1, false)

        if (transcriptionStartTime > 0) {
            builder.setWhen(transcriptionStartTime)
            builder.setUsesChronometer(true)
        }

        // Cancel is added FIRST so it stays a fixed anchor — it never moves; only the nav
        // buttons appear and disappear to its right.
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.action_cancel),
            cancelPendingIntent
        )
        if (viewIndex > 0) {
            builder.addAction(
                android.R.drawable.ic_media_previous,
                getString(R.string.chunk_nav_prev),
                navPrevPendingIntent
            )
        }
        if (pinned && viewIndex < live) {
            builder.addAction(
                android.R.drawable.ic_media_next,
                getString(R.string.chunk_nav_next),
                navNextPendingIntent
            )
        }
        // 'Jump to live' is deliberately not its own button: collapsed notifications cap at 3
        // actions, and pressing Next until the tail already clears the pin (returns to live).

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    override fun onSuccess(
        taskId: String,
        resultText: String,
        isShareRequest: Boolean,
        sourcePackage: String?,
        durationMs: Long,
        confidence: Float?,
        detectedLanguage: String?,
        isPartial: Boolean,
        failedChunkCount: Int
    ) {
        sendSuccessReply(taskId, resultText)
        if (isShareRequest) {
            // Track the notification job: processQueue()'s finally must NOT stopSelf
            // while it is still pending. It used to be an untracked launch, and for
            // fast single-chunk transcriptions the queue teardown (stopForeground +
            // stopSelf -> onDestroy -> serviceScope.cancel) won the race, killing the
            // result notification before it was ever posted. In the background that
            // read as "the notification vanished and nothing arrived" (TASK-336).
            pendingResultNotifications.add(serviceScope.launch {
                try {
                    val copied = autoCopyIfEnabled(resultText, sourcePackage)
                    saveTranscriptToFileIfEnabled(resultText, sourcePackage)
                    showResultNotification(resultText, sourcePackage, taskId, confidence, detectedLanguage, isPartial, failedChunkCount, copiedToClipboard = copied)
                } finally {
                    pendingResultNotifications.remove(coroutineContext[Job])
                }
            })
        }
    }

    override fun onError(
        taskId: String,
        errorCode: String,
        errorMessage: String,
        isShareRequest: Boolean,
        isNoModelError: Boolean,
        durationMs: Long
    ) {
        sendErrorReply(taskId, errorCode, errorMessage)
        // TASK-307: in-app failures get the same notification as share failures.
        // The Logs row records the error either way, but a user actively waiting on
        // an in-app transcription had no immediate signal unless they expanded the row.
        if (isNoModelError) showNoModelNotification()
        else showErrorNotification(
            // TASK-396 pt.1: the orchestrator's OOM catch reports the technical
            // class name as the message; the notification must carry the localized
            // mitigation advice, not "OutOfMemoryError".
            if (errorCode == "OUT_OF_MEMORY") getString(R.string.error_oom_transcription)
            else errorMessage)
    }

    // ---- Broadcast Replies ----

    private fun sendSuccessReply(taskId: String, resultText: String) {
        val replyIntent = Intent(TaskerRequestReceiver.ACTION_TASKER_REPLY).apply {
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskerRequestReceiver.EXTRA_STATUS, TaskerRequestReceiver.STATUS_SUCCESS)
            putExtra(TaskerRequestReceiver.EXTRA_RESULT_TEXT, resultText)
        }
        sendBroadcast(replyIntent)
    }

    private fun sendErrorReply(taskId: String, errorCode: String, errorMessage: String) {
        val replyIntent = Intent(TaskerRequestReceiver.ACTION_TASKER_REPLY).apply {
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskerRequestReceiver.EXTRA_STATUS, TaskerRequestReceiver.STATUS_ERROR)
            putExtra(TaskerRequestReceiver.EXTRA_ERROR_MESSAGE, "$errorCode: $errorMessage")
        }
        sendBroadcast(replyIntent)
    }

    // ---- Auto-Copy ----

    /** @return true when the text was copied (TASK-385: rides the result notification subText). */
    private suspend fun autoCopyIfEnabled(transcriptionText: String, sourcePackage: String?): Boolean {
        // Effective auto-copy = global toggle OR per-app preference (issue #13). The global
        // "Auto-Copy Transcription" toggle is the master enable; per-app preferences add their
        // own defaults/overrides on top. Previously the per-app value shadowed the global for
        // app-shared audio, so enabling the global toggle had no effect except for WhatsApp.
        val globalAutoCopy = preferencesManager.autoCopyEnabled.first()
        val perAppAutoCopy = sourcePackage?.let { pkg ->
            try {
                perAppPreferencesManager.getCurrentPreferences(pkg).autoCopy
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get per-app preferences for $pkg", e)
                false
            }
        } ?: false

        if (globalAutoCopy || perAppAutoCopy) {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.clipboard_label_transcription), transcriptionText)
            clipboardManager.setPrimaryClip(clip)
            Log.i(TAG, "Auto-copied transcription to clipboard (${transcriptionText.length} chars), source=$sourcePackage, global=$globalAutoCopy, perApp=$perAppAutoCopy")

            Handler(Looper.getMainLooper()).post {
                com.antivocale.app.util.ToastCompat.show(
                    this@InferenceService,
                    R.string.copied_to_clipboard
                )
            }
            return true
        }
        return false
    }

    // ---- Auto-save to folder (issue #14) ----

    private suspend fun saveTranscriptToFileIfEnabled(text: String, sourcePackage: String?) {
        val treeUriStr = preferencesManager.outputFolderUri.first() ?: return
        val treeUri = Uri.parse(treeUriStr)
        val name = withContext(Dispatchers.IO) {
            TranscriptFileSaver.save(this@InferenceService, treeUri, text, sourcePackage)
        }
        if (name != null) {
            Log.i(TAG, "Saved transcript to output folder: $name")
        }
    }

    // ---- Notifications ----

    private fun createNotificationChannel() {
        AppNotificationChannel.INFERENCE.create(this)
    }

    private fun createResultNotificationChannel() {
        AppNotificationChannel.TRANSCRIPTION_RESULT.create(this)
    }

    private fun createNotification(
        contentText: String,
        progress: Int = 0,
        maxProgress: Int = 0,
        indeterminate: Boolean = false,
        durationSeconds: Int = 0,
        startTimeMillis: Long = 0,
        bigText: String? = null,
        subText: String? = null
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_cancel),
                cancelPendingIntent
            )

        if (bigText != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        } else {
            builder.setProgress(maxProgress, progress, indeterminate)
        }

        if (startTimeMillis > 0) {
            builder.setWhen(startTimeMillis)
            builder.setUsesChronometer(true)
        }

        if (subText != null) {
            builder.setSubText(subText)
        } else if (durationSeconds > 0) {
            builder.setSubText(formatDuration(durationSeconds))
        }

        return builder.build()
    }

    private fun updateNotification(
        contentText: String,
        durationSeconds: Int = 0,
        startTimeMillis: Long = 0,
        bigText: String? = null,
        subText: String? = null
    ) {
        notificationManager.notify(
            NOTIFICATION_ID,
            createNotification(contentText, durationSeconds = durationSeconds, startTimeMillis = startTimeMillis, bigText = bigText, subText = subText)
        )
    }

    private fun updateNotificationWithProgress(
        contentText: String,
        progress: Int = 0,
        maxProgress: Int = 0,
        indeterminate: Boolean = false,
        durationSeconds: Int = 0,
        startTimeMillis: Long = 0
    ) {
        notificationManager.notify(
            NOTIFICATION_ID,
            createNotification(contentText, progress, maxProgress, indeterminate, durationSeconds, startTimeMillis)
        )
    }

    private fun updateNotificationWithSmoothProgress(
        contentText: String,
        progressPercent: Int,
        etaText: String,
        durationSeconds: Int,
        startTimeMillis: Long,
        queuedCount: Int = 0
    ) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, progressPercent, false)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_cancel), cancelPendingIntent
            )

        if (startTimeMillis > 0) {
            builder.setWhen(startTimeMillis)
            builder.setUsesChronometer(true)
        }

        val queueText = if (queuedCount > 0) resources.getQuantityString(R.plurals.queued_count, queuedCount, queuedCount) else ""
        val timingText = formatTimingText(etaText, durationSeconds)
        joinSubText(queueText, timingText)?.let { builder.setSubText(it) }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun updateNotificationQueueHint(queuedCount: Int) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(resources.getQuantityString(R.plurals.queued_count, queuedCount, queuedCount))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, 0, true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_cancel),
                cancelPendingIntent
            )

        notificationManager.notify(NOTIFICATION_ID, builder.build())
        Log.i(TAG, "Updated notification with queue hint: $queuedCount queued")
    }

    private suspend fun showResultNotification(
        transcriptionText: String,
        sourcePackage: String?,
        taskId: String,
        confidence: Float?,
        detectedLanguage: String?,
        isPartial: Boolean = false,
        failedChunkCount: Int = 0,
        copiedToClipboard: Boolean = false,
    ) {
        val prefs = if (sourcePackage != null) {
            try {
                perAppPreferencesManager.getCurrentPreferences(sourcePackage)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get per-app preferences for $sourcePackage, using defaults", e)
                com.antivocale.app.data.AppNotificationPreferences.default()
            }
        } else {
            com.antivocale.app.data.AppNotificationPreferences.default()
        }

        val id = ResultNotificationFactory.nextNotificationId()
        val spec = ResultNotificationSpec(
            transcriptionText = transcriptionText,
            taskId = taskId,
            sourcePackage = sourcePackage,
            confidence = confidence,
            detectedLanguage = detectedLanguage,
            isPartial = isPartial,
            failedChunkCount = failedChunkCount,
            notificationId = id,
            copiedToClipboard = copiedToClipboard,
            firstPostedAt = System.currentTimeMillis()
        )
        val notification = resultNotificationFactory.build(spec, prefs)
        notificationManager.notify(id, notification)
        Log.i(TAG, "Showed result notification (${transcriptionText.length} chars), source=$sourcePackage, showShare=${prefs.showShareAction} (id=$id)")
    }

    private fun showErrorNotification(errorMessage: String) {
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle(getString(R.string.transcription_failed))
            .setContentText(errorMessage)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(buildLaunchPendingIntent())
            .setAutoCancel(true)
            .build()

        val id = ResultNotificationFactory.nextNotificationId()
        notificationManager.notify(id, notification)
        Log.i(TAG, "Showed error notification: $errorMessage (id=$id)")
    }

    private fun showNoModelNotification() {
        val openPendingIntent = buildLaunchPendingIntent(navigateToModelTab = true)

        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_no_model_title))
            .setContentText(getString(R.string.notification_no_model_message))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_set_as,
                getString(R.string.notification_no_model_action),
                openPendingIntent
            )
            .build()

        val id = ResultNotificationFactory.nextNotificationId()
        notificationManager.notify(id, notification)
        Log.i(TAG, "Showed no-model notification (id=$id)")
    }

    // ---- Notification Helpers ----

    private fun buildLaunchPendingIntent(
        navigateToModelTab: Boolean = false,
        highlightTaskId: String? = null
    ): android.app.PendingIntent {
        val requestCode = when {
            highlightTaskId != null -> highlightTaskId.hashCode()
            navigateToModelTab -> RC_LAUNCH_MODEL_TAB
            else -> RC_LAUNCH_DEFAULT
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            if (highlightTaskId != null) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_HIGHLIGHT_TASK_ID, highlightTaskId)
            } else {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            if (navigateToModelTab) {
                putExtra(MainActivity.EXTRA_NAVIGATE_TO_MODEL_TAB, true)
            }
        }
        return android.app.PendingIntent.getActivity(
            this, requestCode, openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }

    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    private val cancelPendingIntent by lazy {
        val cancelIntent = Intent(this, InferenceService::class.java).apply {
            action = ACTION_CANCEL
        }
        android.app.PendingIntent.getService(
            this, 0, cancelIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun navPendingIntent(action: String, requestCode: Int) = android.app.PendingIntent.getService(
        this,
        requestCode,
        Intent(this, InferenceService::class.java).apply { this.action = action },
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )

    private val navPrevPendingIntent by lazy { navPendingIntent(ACTION_NAV_PREV, RC_NAV_PREV) }
    private val navNextPendingIntent by lazy { navPendingIntent(ACTION_NAV_NEXT, RC_NAV_NEXT) }
    private val navLivePendingIntent by lazy { navPendingIntent(ACTION_NAV_LIVE, RC_NAV_LIVE) }
}
