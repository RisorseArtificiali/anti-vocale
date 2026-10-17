package com.antivocale.app.data

import android.util.Log
import com.antivocale.app.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The injectable runtime-permission probe (a seam for unit tests). */
fun interface VoiceNotePermissionChecker {
    fun audioReadGranted(): Boolean
}

/**
 * TASK-827: the arming conjunction's outcome, owned by the coordinator
 * and updated by every reconcile pass (preference changes, permission
 * pokes, tree-grant writes). The Settings grant card renders THIS state
 * instead of re-deriving the conjunction itself: one owner, no drift
 * between what arms detection and what the card claims.
 */
sealed interface VoiceNoteGateState {
    /** The preference is off; the privacy contract says nothing is read. */
    data object Inactive : VoiceNoteGateState

    /** The preference is on but the runtime audio permission is missing. */
    data object PermissionMissing : VoiceNoteGateState

    /** On + permitted, but the tree grant is absent or no longer live. */
    data object GrantMissing : VoiceNoteGateState

    /**
     * The full conjunction holds. This is the DESIRED state, published
     * pre-lock (see [VoiceNoteDetectionCoordinator.gateState]): the
     * subscribe/dispose transition follows under the lifecycle mutex, and
     * a thrown subscribe leaves [VoiceNoteDetectionCoordinator.armed]
     * false while this reads Active - the divergent pair is a signal the
     * SPI exposes, not an invariant violation.
     */
    data object Active : VoiceNoteGateState
}

/**
 * TASK-272 (automatic form; maintainer decision 2026-10-13) lifecycle
 * owner for voice-note transcription: observes the
 * `voiceNoteDetectionEnabled` preference and, while it is on AND the
 * read-media runtime permission is granted AND the tree grant is live,
 * keeps [VoiceNoteDetector] subscribed and feeds every detected note to
 * [VoiceNoteAutoTranscriber]: resolve through the grant, enqueue, zero
 * taps. The result surfaces through the existing result notification and
 * History rows.
 *
 * PRIVACY CONTRACT (the Settings card states it): while the preference is
 * off, nothing is read at all. While on, detection reads only the
 * WhatsApp voice-note folder listing (FileObserver events over the tree),
 * and the note's CONTENT is read only when it is transcribed, entirely on
 * device.
 *
 * Threading: [VoiceNoteDetector.subscribe] runs synchronous filesystem
 * work, so every call site here runs it on [Dispatchers.IO], never the
 * main thread. All coroutines live on the injected @ApplicationScope
 * (launch sites pass their own dispatcher; the scope carries none).
 *
 * Permission revocation needs no live observer of its own: Android kills
 * the process when a runtime permission is revoked, so the next cold start
 * re-evaluates the conjunction and stays unsubscribed until the permission
 * returns.
 *
 * Idempotent across rapid toggling: transitions serialize on one mutex and
 * short-circuit when the desired state already holds; dispose is the
 * detector's reversible teardown, so off/on cycles re-arm detection.
 */
@Singleton
class VoiceNoteDetectionCoordinator @Inject constructor(
    private val detector: VoiceNoteDetector,
    private val preferencesManager: PreferencesManager,
    private val voiceNoteTreeGrant: VoiceNoteTreeGrant,
    private val autoTranscriber: VoiceNoteAutoTranscriber,
    private val permissionChecker: VoiceNotePermissionChecker,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val lifecycleMutex = Mutex()
    private var started = false

    // @Volatile + a public read for the same cross-thread reason as
    // rearmIntervalMs: the test SPI reads it from a binder thread while
    // reconcile writes it under the mutex (TASK-826).
    @Volatile
    private var active = false

    /** Whether the detector is subscribed right now (the reconcile outcome). */
    val armed: Boolean get() = active

    /**
     * TASK-832: the tree URI from the collector's OWN latest combine
     * emission, read by transcribe so the resolve hop draws from the same
     * FLOW that armed it (the DataStore flow is consumed once per
     * emission here; notes read only this field). LATEST-WINS: a mid-burst
     * emission swaps the URI under the running collector; the resolve
     * fails safe on a dead grant, and capturing at arm time would be the
     * bug (the released-grant stranding in VoiceNoteTreeGrant.resolve's
     * KDoc). Like _gateState, this write assumes reconcile's ONE
     * production caller (the combine collector); a second concurrent
     * caller would require moving the write under the lifecycle mutex.
     * @Volatile: written on the collector's Default thread, read on the
     * collector's IO worker inside collectSignals' transcribe.
     */
    @Volatile
    private var liveTreeUri: String? = null

    /**
     * TASK-827: the gate's current state for UI rendering; the Settings
     * card renders this flow and never re-derives the conjunction.
     * Published BEFORE the lifecycle mutex on purpose: the desired-state
     * derivation must publish even when the transition short-circuits at
     * `if (shouldRun == active) return`, which a post-check placement
     * inside the lock would skip. Thread safety is StateFlow's own; the
     * mutex still serializes the actual subscribe/dispose transitions
     * ([armed] is the completed-transition flag, and the SPI deliberately
     * reads THAT, so a divergent pair is a catchable signal, not a bug).
     * Reconcile has exactly one production caller (the combine collector);
     * a second concurrent caller would require moving publication under
     * the lock.
     */
    private val _gateState = MutableStateFlow<VoiceNoteGateState>(VoiceNoteGateState.Inactive)
    val gateState: StateFlow<VoiceNoteGateState> = _gateState.asStateFlow()
    private var collectJob: Job? = null
    private var rearmJob: Job? = null

    /**
     * TASK-830: the blind re-subscribe cadence. A var (not a constructor
     * param: a defaulted Long param breaks Dagger's provider resolution,
     * the ExternalModelStore lesson) settable by the tests before the
     * first arm; read at arm time so a change while armed takes effect on
     * the next arm. Volatile for the same cross-thread reason as
     * LlmManager.generationTimeoutMs (the write is not under the mutex).
     */
    @androidx.annotation.VisibleForTesting
    @Volatile
    internal var rearmIntervalMs = DEFAULT_REARM_INTERVAL_MS

    // The runtime permission has no observable state on Android, so grant
    // events arrive as pokes: each one re-evaluates the conjunction with the
    // CURRENT preference value. Without this, a grant made while the
    // preference is already on would save an unchanged value that
    // distinctUntilChanged would drop, and detection would stay down until
    // the next process restart (review F1).
    private val permissionPokes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Starts observing the preference and the permission pokes. Called once
     * from BridgeApplication.onCreate; further calls are no-ops.
     */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.Default) {
            // No distinctUntilChanged: a poke must re-fire even when the
            // preference value is unchanged; reconcile short-circuits when
            // the desired state already holds.
            combine(
                preferencesManager.voiceNoteDetectionEnabled,
                permissionPokes.onStart { emit(Unit) },
                preferencesManager.voiceNoteTreeUri,
            ) { enabled, _, uri -> enabled to uri }.collect { (enabled, uri) ->
                // Contained per emission: a failed (un)subscribe must
                // not kill the collector, or a later toggle would
                // silently stop reaching the detector.
                runCatching { reconcile(enabled, uri) }
                    .onFailure { Log.e(TAG, "detection reconcile failed for enabled=$enabled", it) }
            }
        }
    }

    /**
     * Re-evaluates the permission gate with the current preference value.
     * Called when a permission grant may have happened outside the
     * preference flow (the Settings grant callback, the ON_RESUME probe).
     */
    fun pokePermissionState() {
        permissionPokes.tryEmit(Unit)
    }

    /**
     * Brings the detector's subscription in line with the preference, the
     * permission, and the tree grant. Internal so the unit tests can drive
     * transitions directly without the preference collector in between.
     */
    internal suspend fun reconcile(prefEnabled: Boolean, treeUri: String?) {
        // The tree grant is the READ route (the spike verdict: direct opens
        // are EACCES and on-demand indexing lands in FILES, denied too);
        // arming without it would enqueue notes that can never resolve. The
        // LIVE grant is what gates (a saved URI whose grant died arms
        // nothing), the folder-watch precedent. TASK-827: the state is
        // derived ONCE here and published to [gateState]; shouldRun is a
        // projection, so the card and the arming can never disagree.
        liveTreeUri = treeUri
        val state = when {
            !prefEnabled -> VoiceNoteGateState.Inactive
            !permissionChecker.audioReadGranted() -> VoiceNoteGateState.PermissionMissing
            treeUri == null || !voiceNoteTreeGrant.holdsGrant(treeUri) ->
                VoiceNoteGateState.GrantMissing
            else -> VoiceNoteGateState.Active
        }
        _gateState.value = state
        val shouldRun = state == VoiceNoteGateState.Active
        lifecycleMutex.withLock {
            if (shouldRun == active) return
            if (shouldRun) {
                withContext(Dispatchers.IO) { detector.subscribe() }
                active = true
                collectJob = scope.launch(Dispatchers.IO) { collectSignals() }
                // TASK-830: the watches can die silently under storage churn
                // while every internal read still says armed (the 2026-10-14
                // overnight evidence), so while armed a blind re-subscribe
                // runs on a cadence: milliseconds of work, the same
                // ms-scale blind window an arm cycle has. Serialized on the
                // lifecycle mutex so a refresh can never race a disarm.
                val interval = rearmIntervalMs
                rearmJob = scope.launch(Dispatchers.IO) {
                    while (true) {
                        delay(interval)
                        // Contained per iteration (review F1: this loop
                        // exists to survive storage churn, the condition
                        // most likely to make a refresh throw; one failure
                        // must not kill the cadence while active stays
                        // true). The collector's runCatching states the
                        // same principle for reconcile; cancellation is
                        // control flow and rethrows.
                        try {
                            lifecycleMutex.withLock {
                                if (active) detector.refresh()
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "detection rearm failed", e)
                        }
                    }
                }
                Log.i(TAG, "detection active")
            } else {
                active = false
                val job = collectJob
                collectJob = null
                rearmJob?.cancel()
                rearmJob = null
                withContext(Dispatchers.IO) { detector.dispose() }
                // The signal flow is a hot SharedFlow that never completes,
                // so cancelling the collector job is what ends it; dispose
                // merely unregisters the observer (no more FilesChanged
                // emissions follow it).
                job?.cancel()
                Log.i(TAG, "detection inactive")
            }
        }
    }

    private suspend fun collectSignals() {
        detector.detections.collect { entry ->
            try {
                transcribe(entry)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Disarm cancels this job mid-enqueue: propagate, never
                // log as a failure or keep draining the queue (review F3).
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "voice-note auto transcription failed", e)
            }
        }
    }

    /**
     * One detected note becomes one enqueued transcription; an entry without
     * an absolute path cannot be resolved and is skipped. The EVENT's
     * timestamp travels with the path: the sender match bounds its
     * candidates to notifications posted near it (TASK-821), and the
     * collector's serialization must not shift that anchor. Runs on the
     * collector's IO dispatcher, never the main thread.
     */
    private suspend fun transcribe(entry: VoiceNoteEntry) {
        val path = entry.path ?: return
        autoTranscriber.enqueue(path, entry.detectedAtMs, liveTreeUri)
    }

    private companion object {
        const val TAG = "VoiceNoteDetection"

        /**
         * The re-subscribe cadence: short enough that an overnight channel
         * death costs at most one interval, long enough that the
         * re-enumeration (a listFiles over the counter folders) stays
         * negligible. Design note (TASK-830): 20 minutes was picked inside
         * the 15-30 band the maintainer discussion settled on.
         */
        const val DEFAULT_REARM_INTERVAL_MS = 20 * 60 * 1000L
    }
}
