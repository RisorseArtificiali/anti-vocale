package com.antivocale.app.service

import android.app.Notification
import android.app.NotificationManager
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.util.TranscriptSignature
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antivocale.app.R
import com.antivocale.app.MainActivity
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.ShareShortcutManager
import com.antivocale.app.data.TranscriptionCalibrator
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.transcription.DualRefinementPolicy
import com.antivocale.app.transcription.TimedSegment
import com.antivocale.app.transcription.TranscriptionBackendManager
import com.antivocale.app.transcription.TranscriptionOrchestrator
import com.antivocale.app.util.CrashReporter
import com.antivocale.app.util.ClipboardWriter
import com.antivocale.app.util.ProgressThrottler
import com.antivocale.app.util.SubtitleFormatter
import com.antivocale.app.util.TranscriptFileSaver
import com.antivocale.app.util.formatProcessingTime
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
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
    @Inject lateinit var backendManager: TranscriptionBackendManager
    @Inject lateinit var logDao: LogDao
    @Inject lateinit var shareShortcutManager: ShareShortcutManager

    companion object {
        const val TAG = "InferenceService"
        val CHANNEL_ID = AppNotificationChannel.INFERENCE.id
        val RESULT_CHANNEL_ID = AppNotificationChannel.TRANSCRIPTION_RESULT.id
        const val NOTIFICATION_ID = 1001

        private const val RC_LAUNCH_DEFAULT = 0
        private const val RC_LAUNCH_MODEL_TAB = 1
        private const val RC_LAUNCH_SETTINGS_ROW = 2
        private const val RC_NAV_PREV = 10
        private const val RC_NAV_NEXT = 11
        private const val RC_PAUSE_TASK = 12

        // TaskId-hash request codes live above the small-constant band: a raw
        // hash can land on a constant's value, and PendingIntent matching
        // ignores extras, so a collision silently overwrites the other intent
        // (the slot-band comment in InferenceEnqueue documents the same class).

        const val EXTRA_SOURCE = "source"

        /**
         * TASK-1010: the dictionary-change re-arm gate. The supplement pass
         * and the collector may unload the active backend to re-arm biasing;
         * unloading under a request that is still PREPROCESSING kills it (the
         * TASK-1008 class: the 789 drain safety brackets decodes, not the
         * audio-prep that precedes them). The service owns the truth about
         * in-flight work, so re-arm requests land here as a pending flag
         * while a service instance is alive, and the drain checkpoints
         * perform the unload only once the batch is idle. A flag left set by
         * a dying service is moot: the next construction reads the vocab
         * file from disk anyway.
         */
        @Volatile
        var alive: Boolean = false
            internal set

        private val pendingRearm = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Defer a backend re-arm unload to the next idle drain (TASK-1010). */
        fun requestRearm() {
            pendingRearm.set(true)
        }

        /** True when a re-arm was pending; the caller performs the unload under its own idle check. */
        fun takePendingRearm(): Boolean = pendingRearm.getAndSet(false)

        /** TASK-889: the cold-start catch-up sweep's flag (the per-note
         *  SUCCESS notification is suppressed; the digest replaces it). */
        const val EXTRA_CATCHUP = "catchup"
        const val EXTRA_SOURCE_PACKAGE = "source_package"
        /** TASK-736: the matched voice-note sender (share flow only). */
        const val EXTRA_SENDER_NAME = "sender_name"
        /** TASK-842: the intake content hash, from the interactive guard's
         *  share flow; rides the row so FUTURE intakes can match it. */
        const val EXTRA_CONTENT_HASH = "content_hash"
        /** TASK-1040: the original-message moment (see LogEntity's twin). */
        const val EXTRA_ORIGIN_TIMESTAMP_MS = "origin_timestamp_ms"

        /**
         * TASK-1037 (GH #190) phase 3: the resume marker. With it set the
         * intent carries only a task id ([TaskerRequestReceiver.EXTRA_TASK_ID]);
         * the drain hydrates every other field from the transcription-session
         * row and validates its frozen identity BEFORE any work starts.
         */
        const val EXTRA_RESUME_SESSION = "resume_session"
        const val SOURCE_SHARE = "share"

        /** TASK-500: the History browse FAB; not a share request. */
        const val SOURCE_BROWSE = "browse"

        /** TASK-741: the scheduled folder watch worker; not a share request. */
        const val SOURCE_FOLDER_WATCH = "folder_watch"

        /** The History re-run arm (chip and notification actions). */
        const val SOURCE_RETRANSCRIBE = "retranscribe"

        /** The on-device model benchmark screen. */
        const val SOURCE_BENCHMARK = "benchmark"

        /** TASK-271: the detected voice-note offer's Transcribe action; not a share request. */
        const val SOURCE_VOICE_NOTE = "voice_note"

        /**
         * Which completed requests post the result notification: shares
         * (TASK-688's design) and automatic voice notes. A voice note is
         * transcribed with zero taps, so without the notification the
         * result is only discoverable by opening History; the README and
         * FAQ promise it, and the automatic route is the one origin where
         * the user is not already looking at the app. Everything else
         * share-gated (auto-copy, partial saves, the filter seed) stays
         * share-only.
         *
         * TASK-889: a catch-up-sweep note suppresses its per-note SUCCESS
         * post (the burst's digest replaces N posts on a 3-slot shade);
         * the History row and the auto-save still happen, and a save
         * failure still surfaces through the !postsResult notice.
         */
        internal fun postsResultNotification(
            isShareRequest: Boolean,
            source: String?,
            catchup: Boolean = false,
        ): Boolean = (isShareRequest || source == SOURCE_VOICE_NOTE) && !catchup

        /** Sources that never get a Tasker-style reply broadcast: every
         *  in-app origin has no legitimate listener, so the reply would be
         *  a world-visible broadcast carrying the full transcript (the
         *  TASK-274(f) exfiltration argument, which holds for all of them).
         *  Tasker requests carry NO source (null), which is why null still
         *  replies: that is the one origin with a listener. */
        private val NON_REPLY_SOURCES = setOf(
            SOURCE_SHARE, SOURCE_BROWSE, SOURCE_FOLDER_WATCH,
            SOURCE_RETRANSCRIBE, SOURCE_BENCHMARK, SOURCE_VOICE_NOTE,
        )

        const val EXTRA_SHARED_URI = "shared_uri"
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_BACKEND_OVERRIDE = "backend_override"

        /**
         * TASK-546 AC3: transient per-request language override (the chip's
         * re-run arm). Speaks the preference vocabulary ("auto" or a concrete
         * code); never persists, unlike a Settings pin.
         */
        const val EXTRA_LANGUAGE_OVERRIDE = "language_override"
        /** TASK-274(f): the broadcast sender's package, pinned onto replies. */
        const val EXTRA_REQUESTER_PACKAGE = "requester_package"

        const val ACTION_CANCEL = "com.antivocale.app.CANCEL_TRANSCRIPTION"

        /** Per-task cancel (GH #52 follow-up): cancels one queued or in-flight request. */
        const val ACTION_CANCEL_TASK = "com.antivocale.app.CANCEL_TRANSCRIPTION_TASK"
        const val EXTRA_CANCEL_TASK_ID = "cancel_task_id"

        /**
         * TASK-1037 phase 4: per-task cooperative pause of a live session
         * stint. A control delivery in the ACTION_CANCEL_TASK family: it
         * carries no request identity, must never become a fallback
         * notification, and retires an idle revival. The extra is optional
         * (the notification's button sends none; the handler then pauses
         * whichever session stint is live).
         */
        const val ACTION_PAUSE_TASK = "com.antivocale.app.PAUSE_TRANSCRIPTION_TASK"
        const val EXTRA_PAUSE_TASK_ID = "pause_task_id"

        // Chunk navigation actions for the in-progress notification (TASK-242).
        // Target the service directly — navigation is stateful (mutates the live cursor the
        // producer also writes to), unlike the stateless copy/share actions in
        // NotificationActionReceiver. Mirrors how ACTION_CANCEL is wired.
        const val ACTION_NAV_PREV = "com.antivocale.app.NAV_CHUNK_PREV"
        const val ACTION_NAV_NEXT = "com.antivocale.app.NAV_CHUNK_NEXT"
        const val ACTION_NAV_LIVE = "com.antivocale.app.NAV_CHUNK_LIVE"

        /**
         * TASK-1030: the ONE delivery-kind predicate, for the failure arm's
         * preserve decision. It mirrors onStartCommand's control arms
         * exactly: anything that is not a control action falls through to
         * the request parse there, so here it is preservable. Keep it in
         * sync with those arms (the arms own the constants; this owns the
         * membership question).
         */
        fun isControlDelivery(intent: Intent): Boolean = intent.action in setOf(
            ACTION_CANCEL, ACTION_CANCEL_TASK, ACTION_PAUSE_TASK,
            ACTION_NAV_PREV, ACTION_NAV_NEXT, ACTION_NAV_LIVE,
        )

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
    /** TASK-1045: per-task Cancels that arrived while their target was
     *  neither current nor queued, i.e. inside the dispatch window (the
     *  request is already polled out of the queue, currentTaskId has not
     *  taken it yet; the resume hydration is the long stretch). The drain
     *  consumes the marker before launching the run. */
    private val pendingUserCancels = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Child job running the current task's processRequest; per-task cancel target. */
    @Volatile private var currentTaskJob: Job? = null
    @Volatile private var transcriptionStartTime: Long = 0
    private val pendingCount = AtomicInteger(0)
    // The drain's single-entry gate (see processQueue): compareAndSet, never a
    // check-then-act on _isTranscribing, which a suspend read can interleave.
    private val drainEntered = AtomicBoolean(false)

    // GH #67 core mechanism (scoped extraction): held while a batch runs,
    // released when the queue drains; see the class KDoc for why a silent
    // track is what an OEM freezer respects.
    // var for the service-level tests (reflection injection, the same seam
    // the race tests use for preferencesManager/orchestrator).
    private var silentKeepalive = SilentAudioKeepalive(TAG)

    /**
     * The ONE 'no batch remains' predicate (review: the invariant had
     * four call sites and no owner). Both teardown paths (the drain's
     * post-join stop and the cancel path's) route through it, so a
     * batch enqueued during a join window keeps its service, its
     * notification, and its keepalive.
     */
    private fun batchIdle(): Boolean =
        requestQueue.isEmpty() && pendingCount.get() == 0 && !_isTranscribing.value
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

    /**
     * TASK-1032 A: the startId of the most recent delivery, consulted by
     * every teardown stopSelf. The plain stopSelf() is UNCONDITIONAL in
     * ActiveServices (stopServiceTokenLocked with startId -1), so an IO
     * teardown could bring the service down while a startForegroundService
     * delivery that re-armed fgRequired was still in flight: the same
     * ForegroundServiceDidNotStartInTimeException, window of milliseconds,
     * no non-fatal alongside. stopSelf(lastStartId) makes the system's own
     * compare-and-stop close the race atomically: a newer delivery (its own
     * promotion and its own teardown duties) survives ours.
     */
    @Volatile private var lastStartId = -1
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
        /** TASK-736: the matched voice-note sender, written on the row at enqueue. */
        val senderName: String? = null,
        val backendOverride: String? = null,
        /** TASK-546 AC3: request-scoped language override, like [backendOverride]. */
        val languageOverride: String? = null,
        val trackIndex: Int = -1,
        /** TASK-274(f): reply-sink pin (null = pre-34 sender, unpinned reply). */
        val requesterPackage: String? = null,
        /** TASK-842: the intake content hash, onto the row at enqueue. */
        val contentHash: String? = null,
        /** TASK-889: the cold-start sweep origin (suppresses the per-note
         *  SUCCESS result notification; the digest covers the burst). */
        val catchup: Boolean = false,
        /** TASK-1040: the ORIGINAL message moment (null when the source
         *  has none); onto the row at enqueue, into the auto-save filename
         *  at completion. */
        val originTimestampMs: Long? = null,
        /** TASK-1037: a session-resume request. The placeholder fields are
         *  meaningless; the drain hydrates them from the session row
         *  ([TranscriptionOrchestrator.prepareSessionResume]) before the
         *  run starts. No logQueued call happens for such a request (the
         *  row exists in PAUSED). */
        val resumeSession: Boolean = false
    )

    // ---- Android Lifecycle ----

    /**
     * TASK-978/1014: satisfy the system's fgRequired expectation with the
     * best-shape notification. The NAV shape wins when a chunk view is live
     * (F3: a single-chunk job's file/progress/ETA/chronometer survives the
     * promotion instead of degrading to the bare form), then the last
     * foreground notification, then the bare "Processing audio" form.
     *
     * The failure arm records through CrashReporter (TASK-1014): the review
     * round's warning that "a silent swallow would turn an OEM FGS
     * restriction into an unattributable ForegroundServiceDidNotStartInTime
     * kill" proved out in the field (1.16.1, two Samsung issue groups,
     * TASK-1014), where Log.w alone left the swallow undiscriminable from a
     * pre-delivery process stall. A REQUIRED delivery that throws here still
     * ends in that kill class ten seconds later; the report is what makes
     * the next event attributable.
     */
    private fun promoteToForeground(origin: String): Boolean =
        runCatching {
            foregroundPromotion(
                deriveChunkNavView()?.let { assembleChunkNavNotification(it) }
                    ?: lastForegroundNotification
                    ?: createNotification(getString(R.string.processing_audio)))
        }.onFailure {
            Log.w(TAG, "startForeground promotion failed ($origin)", it)
            // The failure arm runs OUTSIDE the catch: reporting infra (the
            // playStore Firebase path during early process start) throwing
            // here must not convert a promotion failure into a service
            // crash from onCreate.
            runCatching { CrashReporter.report(it, "InferenceService.startForeground/$origin") }
        }.isSuccess

    /**
     * Test seam (the progressNotifyThrottler precedent): production always
     * calls the real [android.app.Service.startForeground]; tests substitute
     * a recorder (to observe the promotion's interleaving with the re-arm
     * drain) or a throwing form (to drive the failed-promotion arm).
     */
    internal var foregroundPromotion: (android.app.Notification) -> Unit = {
        startForeground(NOTIFICATION_ID, it)
    }

    override fun onCreate() {
        super.onCreate()
        alive = true
        createNotificationChannel()
        createResultNotificationChannel()
        // TASK-1014: promote before the delivery. The onStartCommand
        // promotion covers every delivery that RUNS; it cannot cover the gap
        // between service construction and that delivery, where the 1.16.1
        // events landed. The promotion below upgrades the shape when a live
        // chunk-nav view exists.
        promoteToForeground("onCreate")
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        Log.i(TAG, "onStartCommand called, action=${intent?.action}")

        // TASK-978: the promotion is the delivery's FIRST statement,
        // because every startForegroundService delivery re-arms the system's
        // fgRequired expectation and must be answered before ANY other work
        // or early return (the 1.16.0 crash:
        // LogsViewModel.continueBrowseIntake's re-entrant start; map
        // e0234b8f, deobfuscated). onCreate promotes too (TASK-1014), but a
        // re-delivery to a LIVE service re-arms without re-running onCreate,
        // so this call cannot be folded away. TASK-1030 restored the
        // absolute first position: TASK-1010 had parked the re-arm drain
        // ahead of it (the 1.17.0 recurrence, mechanism C). The
        // control actions below arrive from TWO sources (review round):
        // notification buttons through getService PendingIntents (no
        // expectation), and in-app callers through direct startService
        // (HistoryTab.cancelTask); both run with the app foreground, and a
        // lapsed-allowlist PendingIntent delivery on API 31+ only produces a
        // recorded failure (no fgRequired window exists for a startService
        // command). Promoting for them costs only a same-id repost; a control
        // tap that revived a dead service gets a brief honest notification
        // before the idle stop. PROMOTING WITH THE NAV SHAPE when one is live
        // (altitude round): the bare form would overwrite the pinned chunk
        // view, and no-op nav taps plus the signature dedup could never
        // restore it.
        if (!promoteToForeground("onStartCommand") && batchIdle()) {
            // TASK-1030 mechanism A, EMPTY instance only (fable review
            // round): AOSP sendServiceArgsLocked arms the per-delivery
            // fgRequired timeout only when the service is not already
            // foreground, so a failed promotion on a LIVE batch (an earlier
            // promotion answered the service-level expectation) is not a
            // doomed delivery; 1.17.0's report-and-carry-on stands there.
            // An idle instance has nothing that will ever answer the
            // expectation: see [preserveFailedDelivery].
            preserveFailedDelivery(intent, startId)
            return START_NOT_STICKY
        }

        // TASK-1010 intake serialization, BELOW the promotion since TASK-1030
        // (mechanism C: the unload is native model teardown on the intake
        // thread and can take seconds on a loaded device, so the fgRequired
        // answer must never sit behind it). The drain still runs BEFORE this
        // request joins the queue (main thread = the intake point; the
        // drain-tail check below can otherwise race a request enqueued
        // between its batchIdle read and its unload). Operand order is
        // load-bearing (fable review round): batchIdle() FIRST, exactly as
        // the drain tail orders it, because takePendingRearm() CONSUMES the
        // flag - the old order ate a pending re-arm whenever a delivery
        // arrived mid-batch and the stale vocabulary stayed loaded.
        if (batchIdle() && takePendingRearm()) {
            backendManager.unloadActiveBackend()
            Log.i(TAG, "deferred dictionary re-arm drained at intake")
        }

        if (intent?.action == ACTION_CANCEL) {
            handleCancelRequest()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_CANCEL_TASK) {
            val cancelTaskId = intent.getStringExtra(EXTRA_CANCEL_TASK_ID)
            if (cancelTaskId != null) {
                handleCancelTask(cancelTaskId)
            } else {
                // A malformed per-task cancel (action set, extra absent) fell
                // straight to the return after the promotion above, stranding
                // the foreground notification this very block just posted
                // (review round: the sibling arms all retire).
                retireIfIdle()
            }
            return START_NOT_STICKY
        }

        // TASK-1037 phase 4: the cooperative-pause delivery. The extra is
        // optional: the notification's Pause button carries none and the
        // handler pauses the live stint; a History-row Pause carries the
        // row's task id and the orchestrator gate rejects a mismatch. The
        // retire mirrors the nav arms: a Pause tap on a dead-process
        // notification must not leave the revived service stranded (the
        // TASK-978 class), and retireIfIdle's idleness re-check spares a
        // live batch.
        if (intent?.action == ACTION_PAUSE_TASK) {
            handlePauseTask(intent.getStringExtra(EXTRA_PAUSE_TASK_ID))
            retireIfIdle()
            return START_NOT_STICKY
        }

        // Chunk navigation: mutate the live cursor and re-post.
        val navAction = intent?.action
        // ACTION_NAV_LIVE is LEGACY-ONLY: no shipped notification carries
        // it (jump-to-live folded into Next, see the builder comment), but a
        // notification posted by a PREVIOUS install can survive an app
        // update and still deliver it, so the arm and handler stay.
        if (navAction == ACTION_NAV_PREV || navAction == ACTION_NAV_NEXT || navAction == ACTION_NAV_LIVE) {
            handleChunkNavAction(navAction)
            // TASK-978 review: a nav tap on a dead-process notification
            // (chunkNavState null, nothing rendered) would otherwise leave
            // the revived foreground service stranded like the cancel arms.
            retireIfIdle()
            return START_NOT_STICKY
        }

        // TASK-1037: the resume arm, BEFORE the normal parse (its intent
        // carries no file path; a fall-through would build a null-path
        // request). The inFlightTaskIds guard makes a double tap idempotent
        // (the plan's resume-request shape). No logQueued: the row already
        // exists in PAUSED; markProcessing's extended SQL re-promotes it
        // when the stint starts.
        if (intent?.getBooleanExtra(EXTRA_RESUME_SESSION, false) == true) {
            val resumeTaskId = intent.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID)
            if (resumeTaskId.isNullOrBlank()) {
                Log.w(TAG, "Resume delivery without a task id; nothing to do")
                retireIfIdle()
                return START_NOT_STICKY
            }
            // Simplify round: the same enqueue bookkeeping as the normal arm
            // (dedup guard, queue add, counter); the resume-specific parts
            // (no logQueued, the drain-time hydration) stay in their arms.
            if (enqueueRequest(PendingRequest(
                    taskId = resumeTaskId,
                    requestType = TaskerRequestReceiver.REQUEST_TYPE_AUDIO,
                    prompt = "",
                    filePath = null,
                    resumeSession = true,
                ), label = "Session resume enqueued")
            ) {
                processQueue()
            }
            return START_NOT_STICKY
        }

        val filePath = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH)

        val request = PendingRequest(
            taskId = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID)
                ?: "unknown_${System.currentTimeMillis()}",
            requestType = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE) ?: "text",
            prompt = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_PROMPT) ?: "",
            filePath = filePath,
            requesterPackage = intent?.getStringExtra(EXTRA_REQUESTER_PACKAGE),
            source = intent?.getStringExtra(EXTRA_SOURCE),
            sourcePackage = intent?.getStringExtra(EXTRA_SOURCE_PACKAGE),
            senderName = intent?.getStringExtra(EXTRA_SENDER_NAME),
            backendOverride = intent?.getStringExtra(EXTRA_BACKEND_OVERRIDE),
            languageOverride = intent?.getStringExtra(EXTRA_LANGUAGE_OVERRIDE),
            trackIndex = intent?.getIntExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, -1) ?: -1,
            contentHash = intent?.getStringExtra(EXTRA_CONTENT_HASH),
            catchup = intent?.getBooleanExtra(EXTRA_CATCHUP, false) ?: false,
            // TASK-1040: the extra cannot express null, so 0 is the
            // absent sentinel (never a legal epoch): the guard is the
            // raw-extra-to-request decode boundary. (The second ?. is
            // load-bearing: intent's nullability flows through the
            // safe-call, making the Long nullable before takeIf.)
            originTimestampMs = intent?.getLongExtra(EXTRA_ORIGIN_TIMESTAMP_MS, 0L)
                ?.takeIf { it > 0L },
        )
        rememberRequester(request)

        // Dedup by taskId: drop a duplicate before it enters the queue. This covers both the
        // queued case (a request with the same taskId is waiting) and the in-flight case (a
        // request with the same taskId is currently being processed). `inFlightTaskIds.add`
        // returns false when the id is already present — atomic against the drain loop's remove.
        if (!enqueueRequest(request)) {
            return START_NOT_STICKY
        }
        val queueSize = pendingCount.get()

        // GH #51: make the request visible in the Logs tab from enqueue time.
        // processRequest's markProcessing promotes the entry once work starts.
        serviceScope.launch {
            // TASK-899: the sweep gate is awaited inside logQueued (the
            // single insert point), so this launch needs no await of its own.
            runCatching {
                orchestrator.logQueued(
                    taskId = request.taskId,
                    requestType = request.requestType,
                    prompt = request.prompt,
                    filePath = request.filePath,
                    sourcePackageName = request.sourcePackage,
                    senderName = request.senderName,
                    contentHash = request.contentHash,
                    originTimestampMs = request.originTimestampMs,
                )
            }.onFailure { Log.w(TAG, "Failed to log queued request ${request.taskId}", it) }
        }

        if (_isTranscribing.value && queueSize > 1) {
            updateNotificationQueueHint(queueSize)
        }

        processQueue()
        return START_NOT_STICKY
    }

    /**
     * TASK-1030 mechanism A, reached only with the batch idle: the
     * delivery-time promotion failed (the OEM/OS restriction class TASK-1014
     * records) and nothing else will ever answer this delivery's fgRequired
     * expectation. The exception itself cannot be prevented (fable review,
     * from AOSP bringDownServiceLocked: a teardown with fgRequired set
     * raises it immediately, and the window expiry raises it otherwise; the
     * AOSP default window is 30s, our 1.16.1 field events measured about
     * 10s), so what is saved is the REQUEST: an identity-carrying delivery
     * rides the SAME fallback notification the enqueue path posts for
     * restricted starts ([InferenceEnqueue.postFallbackNotification]); its
     * tap is user-initiated, which satisfies the FGS restriction, and the
     * trampoline forwards the full extras set unchanged. Control deliveries
     * are dropped, as before. The failure itself was already reported by
     * [promoteToForeground]'s failure arm, with the origin key.
     *
     * The stop keeps the retireIfIdle discipline with the idleness re-check:
     * a tap on the just-posted fallback can legally re-promote and start a
     * batch during the join window, which the re-check then spares exactly
     * as retireIfIdle spares the burst-share race. The
     * nothing-tracked-and-idle case stops synchronously, so the common empty
     * case stays deterministic (both checks run on the delivery thread,
     * which serializes against any later delivery).
     */
    private fun preserveFailedDelivery(intent: Intent?, bailStartId: Int) {
        if (intent != null && !isControlDelivery(intent)) {
            when (val outcome = InferenceEnqueue.postFallbackNotification(this, intent)) {
                is InferenceEnqueue.Outcome.FallbackNotificationPosted ->
                    Log.w(TAG, "Promotion failed; delivery preserved via the fallback notification")
                else ->
                    Log.e(TAG, "Promotion failed and preservation failed too ($outcome); delivery lost")
            }
        }
        if (pendingResultNotifications.isEmpty()) {
            stopSelf(bailStartId)
        } else {
            // TASK-1032 A (simplify round): capture THIS delivery's startId,
            // not the live field. A delivery landing in the join window owns
            // the service; a stale stopSelf is ignored by the system, so the
            // captured form can never stop a newer delivery's work. (The
            // drain tail reads the field instead: its re-kick loop processes
            // any delivery that arrived mid-drain, so at true completion the
            // field equals the last processed id.)
            serviceScope.launch {
                joinPendingResultJobs()
                if (batchIdle()) stopSelf(bailStartId)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        alive = false
        // TASK-1010: an edit landing between the drain tail's check and the
        // stopSelf must not carry a stale recognizer into the next service
        // instance; at teardown nothing is in flight, so drain here.
        if (takePendingRearm()) {
            backendManager.unloadActiveBackend()
            Log.i(TAG, "deferred dictionary re-arm drained at teardown")
        }
        silentKeepalive.stop()
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
    }

    // ---- Queue Management ----

    /**
     * TASK-1037 simplify round: the ONE enqueue bookkeeping sequence (dedup
     * guard, queue add, pending counter, the enqueue log), shared by the
     * normal intake arm and the resume arm. Returns false when the task id
     * was already queued or in flight (the duplicate is dropped and the
     * drain re-kicked, exactly the old inline behavior; the plan's
     * double-tap idempotence). The caller owns everything arm-specific
     * (logQueued, the queue-size hint, the drain-time resume hydration).
     */
    private fun enqueueRequest(request: PendingRequest, label: String = "Request enqueued"): Boolean {
        if (!inFlightTaskIds.add(request.taskId)) {
            Log.w(TAG, "Duplicate taskId dropped (already queued or processing): ${request.taskId}")
            processQueue()
            return false
        }
        requestQueue.add(request)
        pendingCount.incrementAndGet()
        Log.i(TAG, "$label: ${request.taskId}, source=${request.source}, " +
            "sourcePackage=${request.sourcePackage}, filePath=${request.filePath}")
        return true
    }

    private fun processQueue() {
        currentProcessingJob = serviceScope.launch {
            // The atomic gate, not a check-then-act on _isTranscribing: the
            // suspend preference read inside the try used to sit between the
            // flag read and write, so two concurrent drains could both pass
            // the stale false flag and process tasks concurrently (double
            // model load, clobbered currentTaskId: the OOM class).
            if (!drainEntered.compareAndSet(false, true)) {
                Log.d(TAG, "Already processing, request will wait in queue")
                return@launch
            }

            _isTranscribing.value = true
            val totalInBatch = pendingCount.get()
            var currentIndex = 0

            try {
                // TASK-899: the startup sweep's "non-terminal = orphaned by
                // process death" classification is only safe while no run is
                // active; a request racing the (now async) sweep would have
                // its fresh row closed as interrupted underneath it. Await
                // the gate before this drain touches any row. INSIDE the try
                // (review round): a batch cancel while gated used to unwind
                // before the finally, leaving drainEntered stuck true for the
                // process lifetime, every later enqueue queueing forever.
                StartupSweepGate.await()
                // GH #67 (scoped): opt-in via Settings (DEFAULT OFF); the toggle is
                // the reliability setting for freezer-ROMs, not a default behavior.
                // Read here, on the batch-owning coroutine and inside the try, so
                // a cancellation during the read still clears the flag below.
                // Idempotent start: this pass is the single one that owns the
                // batch, so follow-up enqueues while it runs never re-start.
                if (preferencesManager.freezeProtectionEnabled.first()) silentKeepalive.start()

                while (requestQueue.isNotEmpty()) {
                    var request = requestQueue.poll() ?: continue
                    pendingCount.decrementAndGet()

                    // TASK-1037: the resume hydration, BEFORE the task's
                    // bookkeeping so a refused resume never claims a drain
                    // slot. The plan's resume-request shape: the intent
                    // carried only the task id; the session row supplies
                    // everything else, so the request cannot disagree with
                    // persisted state. The id is released on every skip path
                    // (the per-task finally below only covers taken runs).
                    if (request.resumeSession) {
                        // Simplify round: the notification settle is the
                        // orchestrator's one entry point (the service never
                        // touches the session store directly), shared by the
                        // taken and refused arms; the NoSession drop pays
                        // nothing.
                        suspend fun settlePausedNotification() {
                            runCatching { orchestrator.settlePausedNotification(applicationContext) }
                        }
                        when (val plan = orchestrator.prepareSessionResume(
                            request.taskId, applicationContext)) {
                            is TranscriptionOrchestrator.SessionResumePlan.Ready -> {
                                request = request.copy(
                                    filePath = plan.filePath,
                                    prompt = plan.prompt,
                                    source = plan.source,
                                    sourcePackage = plan.sourcePackage,
                                    senderName = plan.senderName,
                                    backendOverride = plan.backendOverride,
                                    languageOverride = plan.languageOverride,
                                )
                                // The requester record must carry the HYDRATED
                                // source: a stale null-source entry would let the
                                // success path broadcast a world-visible Tasker
                                // reply for an in-app-origin session (the
                                // TASK-274(f) exfiltration shape).
                                requesterByTask[request.taskId] = request
                                settlePausedNotification()
                                Log.i(TAG, "Resume hydrated: ${request.taskId} file=${request.filePath} backend=${request.backendOverride}")
                            }
                            is TranscriptionOrchestrator.SessionResumePlan.Closed -> {
                                Log.w(TAG, "Resume refused for ${request.taskId}: ${plan.reason}")
                                settlePausedNotification()
                                inFlightTaskIds.remove(request.taskId)
                                continue
                            }
                            TranscriptionOrchestrator.SessionResumePlan.NoSession -> {
                                Log.i(TAG, "Resume dropped for ${request.taskId}: no session (already terminal)")
                                inFlightTaskIds.remove(request.taskId)
                                continue
                            }
                        }
                    }

                    // TASK-1045: a Cancel that raced this dispatch (the
                    // request was polled out of the queue and currentTaskId
                    // had not taken it yet) recorded itself; the cancel's
                    // own arm already closed the row and the parked
                    // session, so here the run is dropped, never started.
                    if (pendingUserCancels.remove(request.taskId)) {
                        Log.i(TAG, "Per-task cancel raced the dispatch for ${request.taskId}; dropping the run")
                        inFlightTaskIds.remove(request.taskId)
                        continue
                    }

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
                        // Review round: without this, the NEXT job's early
                        // window promotes with the PREVIOUS job's final shape.
                        lastForegroundNotification = null
                        // Throttle carries no state across jobs: the first progress tick
                        // of each request must post promptly (TASK-266).
                        progressNotifyThrottler.reset()

                        // Per-task child job (GH #52): cancelling it (per-task cancel
                        // from the Logs menu) aborts only THIS request; the drain loop
                        // survives via join() and keeps processing the queue. A batch
                        // ACTION_CANCEL instead cancels the drain job itself, and join()
                        // rethrows, reaching the batch catch below.
                        val taskJob = launch {
                            // TASK-684 (GH #109): the phase-blind liveness heartbeat.
                            // Seeds immediately and ticks for the whole task lifetime
                            // (decode, summary, diarization alike), so the cold-start
                            // classifier can tell an OEM-freezer suspension (ticks
                            // stopped while the row lived on) from a run that was
                            // alive when killed (fresh tick at death). Cleared in the
                            // finally, AFTER processRequest closed the row, so a
                            // normally-ended run never leaves suspension evidence.
                            val heartbeat = launch {
                                while (isActive) {
                                    RunHeartbeat.touch(applicationContext, request.taskId)
                                    delay(RunHeartbeat.TICK_MS)
                                }
                            }
                            try {
                                orchestrator.processRequest(
                                    taskId = request.taskId,
                                    requestType = request.requestType,
                                    prompt = request.prompt,
                                    filePath = request.filePath,
                                    source = request.source,
                                    sourcePackage = request.sourcePackage,
                                    backendOverride = request.backendOverride,
                                    languageOverride = request.languageOverride,
                                    trackIndex = request.trackIndex,
                                    // TASK-1037: part of a session's frozen identity.
                                    senderName = request.senderName,
                                    queuePosition = currentIndex,
                                    queueTotal = totalInBatch,
                                    context = applicationContext,
                                    cacheDir = cacheDir,
                                    listener = this@InferenceService,
                                    coroutineScope = this
                                )
                            } finally {
                                heartbeat.cancel()
                                RunHeartbeat.clear(applicationContext, request.taskId)
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
                // The queued ids leave with the queue (review: without this
                // sweep they stay in inFlightTaskIds forever and a Tasker
                // request reusing one is dropped as a duplicate until the
                // process dies). Poll-drain, not forEach+clear: clearing the
                // whole queue atomically can delete a retry that re-enqueued
                // with the same id after its sweep, leaving that id poisoned
                // for the process lifetime.
                while (true) {
                    val queued = requestQueue.poll() ?: break
                    inFlightTaskIds.remove(queued.taskId)
                }
                pendingCount.set(0)
            } finally {
                currentTaskId = null
                currentTaskJob = null
                _isTranscribing.value = false
                // Code-review round (TASK-978 F6): retire the chunk-nav view
                // when the BATCH ends, not only when the next task starts.
                // Between the two, a delivery promoting with the nav shape
                // would re-post the finished job's chunk view; cleared here,
                // that promotion falls back to the bare notification for the
                // brief moment before the idle stop.
                chunkNavState = null
                lastNavSignature = null
                lastForegroundNotification = null
                drainEntered.set(false)
                // The finally (not the post-join path) owns the keepalive
                // stop: an escaped Error from native code skips everything
                // after the try, and a looping track must never outlive
                // its batch. The guard holds because a coroutine past
                // start() either still has its request in the queue (queue
                // clause) or set _isTranscribing before its first poll
                // (flag clause); a batch holding a started keepalive can
                // never see batchIdle() true here.
                if (batchIdle()) silentKeepalive.stop()
            }

            // TASK-1010: a dictionary-change re-arm deferred while the batch
            // ran (the supplement pass routes its unload here when a service
            // is alive). batchIdle re-checked: work enqueued during the join
            // below owns the backend until ITS drain.
            if (batchIdle() && takePendingRearm()) {
                backendManager.unloadActiveBackend()
                Log.i(TAG, "deferred dictionary re-arm: backend unloaded at batch drain")
            }

            // Wait for any pending result-notification jobs before teardown:
            // stopSelf -> onDestroy cancels serviceScope, which would kill a
            // not-yet-run notification coroutine (the TASK-336 race).
            joinPendingResultJobs()

            // The SAME idle guard as the cancel path: a batch enqueued
            // during the join window is live work; tearing down here
            // would kill it via onDestroy's scope cancel.
            if (batchIdle()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                // TASK-1032 A: conditional stop; a delivery that landed in
                // the join window owns the service now.
                stopSelf(lastStartId)
            }

            // Re-kick: a request landing in the window between the while
            // exit and the finally's gate release stranded before (its own
            // processQueue early-returned at the gate, and teardown was
            // skipped by the same non-idle queue); without this kick it
            // waits forever for the next onStartCommand.
            if (requestQueue.isNotEmpty()) processQueue()
        }
    }

    /**
     * TASK-336/820 (simplify round): the ONE snapshot-then-join over the
     * tracked result jobs (notifications and auto-saves). The snapshot
     * under the monitor is load-bearing: the list is mutated on job
     * completion, and iterating the live list is the naive fourth-copy
     * mistake. Both teardown paths (the drain loop's tail and
     * [retireIfIdle]) consume this.
     */
    private suspend fun joinPendingResultJobs() {
        val pending = synchronized(pendingResultNotifications) { pendingResultNotifications.toList() }
        pending.forEach { it.join() }
    }

    private fun handleCancelRequest() {
        Log.i(TAG, "Cancel request received")
        // Phase 4 review round 2: the batch cancel is a USER cancel for
        // the in-flight task too; without the marker cancelIfPending
        // would class it as a scope teardown and leave its session
        // ACTIVE for cold-start recovery (cancelled work resurrecting
        // as PAUSED_SYSTEM, the exact class the marker closed for the
        // per-task arm).
        currentTaskId?.let { orchestrator.markUserCancel(it) }
        currentProcessingJob?.cancel()
        failQueuedLogs("Cancelled")
        requestQueue.clear()
        pendingCount.set(0)
        // TASK-820/TASK-336: the shared retire owns the join-then-stop
        // discipline (a completed task's auto-save write must not die to
        // the teardown's scope cancel; the simplify round folded this
        // hand copy into [retireIfIdle] with the drain's path).
        retireIfIdle()
    }

    /**
     * TASK-1037 phase 4: the ACTION_PAUSE_TASK arm. A null id (the
     * notification's button) targets whichever session stint is live; an
     * explicit id (the History row) must match it. The orchestrator gate
     * makes every stale shape (nothing live, wrong task, between stints) a
     * logged no-op; only a live stint arms the cooperative pause that the
     * next chunk boundary honors.
     */
    private fun handlePauseTask(taskId: String?) {
        val target = taskId ?: orchestrator.activeSessionTaskId
        if (target == null) {
            Log.w(TAG, "Pause delivery with no live session stint; nothing to do")
            return
        }
        if (orchestrator.requestSessionPause(target)) {
            Log.i(TAG, "Session pause requested for $target (honored at the next chunk boundary)")
        } else {
            Log.w(TAG, "Session pause not armed for $target (no live stint for that task)")
        }
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
            // TASK-1037 phase 4 review: mark BEFORE the cancel, so the
            // CancellationException handler deletes the session instead
            // of leaving it ACTIVE for cold-start recovery (cancelled
            // work must not resurrect as a paused session; coroutine
            // activity cannot tell the two paths apart).
            orchestrator.markUserCancel(taskId)
            currentTaskJob?.cancel()
        } else {
            val waiting = requestQueue.firstOrNull { it.taskId == taskId }
            val removed = waiting != null && requestQueue.removeIf { it.taskId == taskId }
            if (!removed) {
                // TASK-1045: the task may be inside the dispatch window
                // (polled, not yet current): record the cancel so the drain
                // drops the run instead of starting cancelled work. The
                // marker is consumed at dispatch; ids are unique per
                // request, so an unconsumed marker is a stale tap's leftover.
                pendingUserCancels.add(taskId)
                // Code review: a PAUSED History row's Cancel lands here (the
                // session is neither in-flight nor queued). cancelSessionTask
                // is a safe probe: it no-ops when no session exists for the
                // id, and closeSessionAsPartial handles the row update
                // (ERROR isPartial with text kept), the session-row cleanup,
                // and the paused-notification settle.
                serviceScope.launch {
                    runCatching { orchestrator.cancelSessionTask(taskId, applicationContext) }
                }
                Log.i(TAG, "Per-task cancel for unknown/finished $taskId; nothing to do")
                // TASK-978 review: a control delivery that revived a dead
                // process lands here with an empty queue. The delivery
                // promotion (onStartCommand's first statement) put this
                // fresh instance in the FOREGROUND; without a stop it
                // would strand an unswipeable notification forever (the
                // pre-promotion code leaked a background service instead,
                // which the system reaps on its own).
                retireIfIdle()
                return
            }
            pendingCount.decrementAndGet()
            inFlightTaskIds.remove(taskId)
            Log.i(TAG, "Per-task cancel removed queued $taskId")
            // TASK-1037: a cancelled queued RESUME leaves the session
            // parked (only the dispatch was cancelled, not the session):
            // restore its notification, which the dispatch path had
            // cleared. A cancelled parked SESSION (History Cancel on a
            // PAUSED row) closes as partial with its assembled text kept
            // (the state machine's Cancel arm; failNonTerminal below
            // deliberately cannot touch a PAUSED row). Both go through the
            // orchestrator's entry points (the simplify round: the service
            // probed the session store directly here).
            if (waiting.resumeSession) {
                serviceScope.launch {
                    runCatching { orchestrator.settlePausedNotification(applicationContext) }
                }
            } else {
                serviceScope.launch {
                    runCatching { orchestrator.cancelSessionTask(taskId, applicationContext) }
                }
            }
            // The nav-notification restore after the delivery promotion
            // lives in onStartCommand itself (signature-forced repost),
            // which covers this arm: a queued cancel changes nothing the
            // nav signature tracks, so no further re-render is needed.
        }
        serviceScope.launch {
            runCatching { logDao.failNonTerminal(taskId, "Cancelled", durationMs = 0) }
                .onFailure { Log.w(TAG, "Failed to close cancelled row for $taskId", it) }
        }
    }

    /**
     * TASK-978/simplify: the ONE async retire, shared by the batch-cancel
     * path and the control-action arms (which can arrive on a service with
     * no work: a stale notification tap reviving a dead process, which the
     * delivery promotion made foreground; unretired it would strand).
     * The FULL teardown discipline (review finding): join the tracked
     * result jobs first (a mid-write auto-save dying to onDestroy's scope
     * cancel is the TASK-336/820 class), re-check idleness after the join
     * (a batch enqueued in the window owns the service AND the keepalive:
     * stopping it here would strip the freezer protection from a live
     * batch, the burst-share race), then retire.
     */
    private fun retireIfIdle() {
        // Entry guard (efficiency round): the common caller is a nav tap on
        // a LIVE service, where batchIdle() is false and the drain loop's
        // own finally already runs the identical join-then-stop discipline;
        // skipping the launch allocation there is free.
        if (!batchIdle()) return
        serviceScope.launch {
            joinPendingResultJobs()
            if (batchIdle()) {
                silentKeepalive.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                // TASK-1032 A: conditional stop, same as the drain tail.
                stopSelf(lastStartId)
            }
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

    /**
     * TASK-186: the early-preview interim. Renders the run's EXISTING
     * in-progress notification (the same surface the interim path updates)
     * with the preview badge, in the legacy non-chunk-nav shape: the preview
     * is not a completed chunk, so ChunkNavState stays untouched and no new
     * notification id is allocated (the reserved-range contract). The real
     * chunk 0 delivery replaces this render moments later.
     */
    override fun onPreviewResult(chunkText: String) {
        updateNotification(
            contentText = chunkText,
            bigText = chunkText,
            subText = getString(R.string.early_preview_badge),
            startTimeMillis = transcriptionStartTime
        )
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
        durationSeconds > 0 -> formatProcessingTime(durationSeconds * 1000L)
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
        val view = deriveChunkNavView() ?: return
        // Skip the rebuild + notify when nothing visible changed (e.g. successive 200ms progress
        // ticks while pinned and the ETA string is unchanged). chunkText is included so a retried
        // chunk's improved text refreshes even while pinned on it. The dedup lives HERE, on the
        // derived view, so an unchanged tick never builds a Notification (code-review round:
        // the previous Pair form built and discarded one per tick just to compare) and the
        // startForeground promotion (altitude round) is never skipped.
        if (view.signature == lastNavSignature) return
        lastNavSignature = view.signature
        postForeground(assembleChunkNavNotification(view))
    }

    /**
     * The nav view's derived, comparable form: the guards (no state, or the
     * live index not yet set), one atomic snapshot so action visibility,
     * displayed text, and the progress bar cannot disagree when chunk
     * completions (IO) and button taps (main) race, plus the visible-state
     * signature and every field the notification assembly needs. The
     * START-FOREGROUND PROMOTION and the posting wrapper both consume this
     * (TASK-978 altitude round: a delivery while a nav job is live promotes
     * WITH the nav shape instead of the bare "Processing audio" form), and
     * the signature dedup lives in the POSTING wrapper, so a caller that
     * hands the notification to startForeground is never skipped by dedup.
     */
    private fun deriveChunkNavView(): ChunkNavView? {
        val state = chunkNavState ?: return null
        val snap = state.snapshot()
        if (snap.liveIndex < 0) return null

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
        // chunkText is in the signature so a retried chunk's improved text
        // refreshes even while pinned on it.
        val signature = "$viewIndex|$pinned|$live|$queueText|$timingText|$chunkText"
        val subText = joinSubText(navSub, queueText, timingText) ?: navSub
        return ChunkNavView(signature, total, live, viewIndex, pinned, chunkText, subText)
    }

    /** The nav view's Notification, assembled only when something consumes it. */
    private fun assembleChunkNavNotification(view: ChunkNavView): android.app.Notification {
        val (_, total, live, viewIndex, pinned, chunkText, subText) = view
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

        return builder.build()
    }

    /** The comparable form of the chunk-nav notification (see deriveChunkNavView). */
    private data class ChunkNavView(
        val signature: String,
        val totalChunks: Int,
        val liveIndex: Int,
        val viewIndex: Int,
        val pinned: Boolean,
        val chunkText: String,
        val subText: String,
    )

    override fun onSuccess(
        taskId: String,
        resultText: String,
        isShareRequest: Boolean,
        sourcePackage: String?,
        durationMs: Long,
        confidence: Float?,
        detectedLanguage: String?,
        isPartial: Boolean,
        failedChunkCount: Int,
        streamedWithoutVad: Boolean,
        segments: List<TimedSegment>,
        refinementOutcome: String?,
        repetitionSuspected: Boolean,
    ) {
        // DRAFT-12 (TASK-598 boundary): the Tasker reply carries the same
        // annotated form every other surface delivers on diarized runs.
        val annotatedResult = SubtitleFormatter.annotatedOrStored(resultText, segments)
        // Read before forgetRequester drops the request.
        val request = requesterByTask[taskId]
        val postsResult =
            postsResultNotification(isShareRequest, request?.source, request?.catchup == true)
        val fromVoiceNote = request?.source == SOURCE_VOICE_NOTE
        sendSuccessReply(taskId, annotatedResult)
        forgetRequester(taskId)
        // Every completed task moves the model-recency source: re-derive the
        // launcher's dynamic share shortcuts. Metadata-only side effect on the
        // service scope (IO), must never reach the result path.
        serviceScope.launch { shareShortcutManager.refresh() }
        // Track the notification job: processQueue()'s finally must NOT stopSelf
        // while it is still pending. It used to be an untracked launch, and for
        // fast single-chunk transcriptions the queue teardown (stopForeground +
        // stopSelf -> onDestroy -> serviceScope.cancel) won the race, killing
        // the result notification before it was ever posted. In the background that
        // read as "the notification vanished and nothing arrived" (TASK-336).
        // TASK-820: the tracking now also spans the non-share auto-save below;
        // the teardown must not kill a save mid-write either.
        pendingResultNotifications.add(serviceScope.launch {
            try {
                // TASK-820 (GH #128): the auto-save runs for every completed
                // transcription of the app's own surfaces (the Export
                // setting's promise), not just shares; it used to live
                // inside the share branch, so browse/Tasker/retranscribe
                // origins never saved at all, silently. Deliberately OUT:
                // the Open Transcribe callback bridge (third-party requests
                // must not write into the user's folder) and partial runs on
                // non-share origins. The RESULT NOTIFICATION stays share-only
                // (TASK-688's design); shares also keep saving partials
                // exactly as before.
                val saveResult =
                    if (isShareRequest || !isPartial) {
                        saveTranscriptToFileIfEnabled(resultText, sourcePackage, segments, failedChunkCount, request?.originTimestampMs)
                    } else {
                        TranscriptFileSaver.SaveResult.NotConfigured
                    }
                if (saveResult !is TranscriptFileSaver.SaveResult.NotConfigured) {
                    logDao.setSaveOutcome(
                        taskId,
                        saveResult.savedFolderOrNull(),
                        saveResult.savedFileNameOrNull(),
                        saveResult.failureOrNull())
                    // A non-share failure has no result notification to ride
                    // the subtext of: a dedicated one names the reason.
                    val failureReason = saveResult.failureOrNull()
                    if (!postsResult && failureReason != null) {
                        resultNotificationFactory.postSaveFailure(failureReason)
                    }
                }
                if (postsResult) {
                    // TASK-598 review F3: auto-copy must deliver the same text
                    // the notification's Copy action delivers (the annotated
                    // form derived once above, reused by the Tasker reply).
                    // Auto-copy stays share-only: an automatic voice note
                    // arriving in the background must not overwrite the
                    // clipboard unasked.
                    val copied = isShareRequest && autoCopyIfEnabled(annotatedResult, sourcePackage)
                    val refinedFrom = refinementOutcome?.takeIf { it != DualRefinementPolicy.NOT_REFINED }
                    resultNotificationFactory.postResult(
                        preferencesManager, perAppPreferencesManager,
                        annotatedResult, segments, saveResult,
                        sourcePackage, taskId, confidence, detectedLanguage,
                        isPartial, failedChunkCount,
                        copiedToClipboard = copied,
                        streamedWithoutVad = streamedWithoutVad,
                        refinedFrom = refinedFrom,
                        notRefined = refinementOutcome == DualRefinementPolicy.NOT_REFINED,
                        repetitionSuspected = repetitionSuspected,
                        originTag = "InferenceService",
                        fromVoiceNote = fromVoiceNote,
                        senderName = request?.senderName.takeIf { fromVoiceNote },
                    )
                }
            } finally {
                pendingResultNotifications.remove(coroutineContext[Job])
            }
        })
    }

    override fun onError(
        taskId: String,
        errorCode: String,
        errorMessage: String,
        isShareRequest: Boolean,
        isNoModelError: Boolean,
        durationMs: Long,
        isMemoryFailure: Boolean,
        isDecodeError: Boolean
    ) {
        sendErrorReply(taskId, errorCode, errorMessage)
        forgetRequester(taskId)
        // TASK-307: in-app failures get the same notification as share failures.
        // The Logs row records the error either way, but a user actively waiting on
        // an in-app transcription had no immediate signal unless they expanded the row.
        if (isNoModelError) showNoModelNotification()
        else showErrorNotification(
            // TASK-631: the orchestrator now sends the localized message for
            // every failure path, including the OOM catch (its advice varies
            // with the memory-protection state), so errorMessage is final.
            errorMessage,
            isMemoryFailure = isMemoryFailure)
    }

    // ---- Broadcast Replies ----

    /**
     * TASK-274(f): the reply sink is PINNED point-to-point. The receiver
     * records the sending package (API 34+); replies go only there, and
     * share-flow requests are never replied to at all (their consumer is the
     * result notification; nothing legitimate listens for share_* taskIds, so
     * a world-visible reply was pure exfiltration surface). A null pin (pre-34
     * device or system-sent broadcast) keeps the historical unpinned reply,
     * which cannot be made worse than it already was.
     */
    private val requesterByTask = java.util.concurrent.ConcurrentHashMap<String, PendingRequest>()
    private fun rememberRequester(request: PendingRequest) {
        requesterByTask[request.taskId] = request
    }
    private fun forgetRequester(taskId: String) {
        requesterByTask.remove(taskId)
    }

    private fun sendSuccessReply(taskId: String, resultText: String) {
        val request = requesterByTask[taskId]
        if (request?.source in NON_REPLY_SOURCES) return
        val replyIntent = Intent(TaskerRequestReceiver.ACTION_TASKER_REPLY).apply {
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskerRequestReceiver.EXTRA_STATUS, TaskerRequestReceiver.STATUS_SUCCESS)
            putExtra(TaskerRequestReceiver.EXTRA_RESULT_TEXT, resultText)
            request?.requesterPackage?.let { setPackage(it) }
        }
        sendBroadcast(replyIntent)
    }

    private fun sendErrorReply(taskId: String, errorCode: String, errorMessage: String) {
        val request = requesterByTask[taskId]
        if (request?.source in NON_REPLY_SOURCES) return
        val replyIntent = Intent(TaskerRequestReceiver.ACTION_TASKER_REPLY).apply {
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskerRequestReceiver.EXTRA_STATUS, TaskerRequestReceiver.STATUS_ERROR)
            putExtra(TaskerRequestReceiver.EXTRA_ERROR_MESSAGE, "$errorCode: $errorMessage")
            request?.requesterPackage?.let { setPackage(it) }
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
            // TASK-647: the clipboard is an exit surface; the AI-disclaimer
            // signature (when enabled) rides exactly here.
            val sig = TranscriptSignature.effectiveSpec(
                preferencesManager, getString(R.string.signature_default_text))
            val signedText = TranscriptSignature.apply(transcriptionText, sig.text, sig.position)
            // TASK-688: the write itself is the shared ClipboardWriter; the
            // signature above stays here (its single owner).
            ClipboardWriter.copy(
                this@InferenceService,
                getString(R.string.clipboard_label_transcription),
                signedText)
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

    /** @return the auto-save [TranscriptFileSaver.SaveResult] for the result
     *  notification (TASK-722's failure reason and GH #128's destination
     *  folder ride it). */
    private suspend fun saveTranscriptToFileIfEnabled(
        text: String,
        sourcePackage: String?,
        segments: List<TimedSegment>,
        failedChunkCount: Int,
        /** TASK-1040: stamps the auto-save filename with the original
         *  message moment when the source carried one. */
        originTimestampMs: Long? = null,
    ): TranscriptFileSaver.SaveResult {
        return withContext(Dispatchers.IO) {
            // ONE resolution: two reads could pair a fresh signature text
            // with a stale position when a preference edit lands between them.
            val sig = TranscriptSignature.effectiveSpec(
                preferencesManager, getString(R.string.signature_default_text))
            TranscriptFileSaver.saveAuto(
                this@InferenceService,
                preferencesManager.outputFolderUri.first(),
                preferencesManager.transcriptExportFormat.first(),
                text, segments, failedChunkCount, sourcePackage,
                originTimestampMs = originTimestampMs,
                signature = sig.text,
                signaturePosition = sig.position,
            )
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

        // TASK-1037 phase 4: Pause rides the active-session stint's
        // notification (this builder is the pipelined path's; the interim
        // reposts refresh the button from the live tracker). Cancel stays
        // the fixed anchor and Pause is second, within the three-action
        // budget even expanded. The chunk-nav builder deliberately adds no
        // Pause: its Cancel + Prev + Next already fills the budget, and
        // the streaming live view is not a session surface. The
        // isInitialized guard: this builder also runs at onCreate's
        // promotion, where a reflection-driven test instance may not have
        // the seam injected yet; production always has it (Hilt), and the
        // degraded shape is simply no Pause button.
        val pauseTarget = if (::orchestrator.isInitialized) orchestrator.activeSessionTaskId else null
        if (pauseTarget != null) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.session_pause_action),
                pausePendingIntent
            )
        }

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
            builder.setSubText(formatProcessingTime(durationSeconds * 1000L))
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
        // postForeground (review round): the interim-text shape IS the
        // single-chunk view the promotion must preserve.
        postForeground(
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
        // postForeground (simplify round): this interim shape is exactly the
        // single-chunk view the promotion must preserve, so it rides the
        // last-shape cache like every other site on the foreground id.
        postForeground(
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

        postForeground(builder.build())
    }

    /**
     * F3 (review round): the LAST shape posted on the foreground id, so the
     * startForeground promotion can re-post it instead of degrading a live
     * single-chunk progress view to the bare "Processing audio" form (which
     * also resets setWhen/chronometer). Cleared at batch end beside the
     * chunk-nav state; a terminal notification replaces the shape anyway.
     */
    @Volatile private var lastForegroundNotification: android.app.Notification? = null

    @Synchronized
    private fun postForeground(notification: android.app.Notification) {
        lastForegroundNotification = notification
        notificationManager.notify(NOTIFICATION_ID, notification)
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

        postForeground(builder.build())
        Log.i(TAG, "Updated notification with queue hint: $queuedCount queued")
    }

    private fun showErrorNotification(errorMessage: String, isMemoryFailure: Boolean) {
        // TASK-625: composition lives in ResultNotificationFactory (both error
        // surfaces and the TEST_SPI simulate op share this one builder).
        val notification = resultNotificationFactory.errorNotification(errorMessage, isMemoryFailure)

        val id = ResultNotificationFactory.nextNotificationId()
        notificationManager.notify(id, notification)
        Log.i(TAG, "Showed error notification: $errorMessage (memoryAction=$isMemoryFailure, id=$id)")
    }

    private fun showNoModelNotification() {
        // TASK-328: composition lives in ResultNotificationFactory.
        val notification = resultNotificationFactory.noModelNotification()
        val id = ResultNotificationFactory.nextNotificationId()
        notificationManager.notify(id, notification)
        Log.i(TAG, "Showed no-model notification (id=$id)")
    }

    // ---- Notification Helpers ----


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

    /**
     * TASK-1037 phase 4: the active-session notification's Pause, the
     * extra-less control form ([navPendingIntent] builds exactly it).
     * Carries no task id on purpose: the handler resolves the live stint,
     * and the button is only built while one exists (the builder's gate).
     */
    private val pausePendingIntent by lazy { navPendingIntent(ACTION_PAUSE_TASK, RC_PAUSE_TASK) }

    private fun navPendingIntent(action: String, requestCode: Int) = android.app.PendingIntent.getService(
        this,
        requestCode,
        Intent(this, InferenceService::class.java).apply { this.action = action },
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )

    private val navPrevPendingIntent by lazy { navPendingIntent(ACTION_NAV_PREV, RC_NAV_PREV) }
    private val navNextPendingIntent by lazy { navPendingIntent(ACTION_NAV_NEXT, RC_NAV_NEXT) }
}
