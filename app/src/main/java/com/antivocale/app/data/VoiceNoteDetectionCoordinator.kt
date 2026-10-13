package com.antivocale.app.data

import android.util.Log
import com.antivocale.app.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    private var active = false
    private var collectJob: Job? = null

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
        // nothing), the folder-watch precedent.
        val shouldRun = prefEnabled && permissionChecker.audioReadGranted() &&
            treeUri != null && voiceNoteTreeGrant.holdsGrant(treeUri)
        lifecycleMutex.withLock {
            if (shouldRun == active) return
            if (shouldRun) {
                withContext(Dispatchers.IO) { detector.subscribe() }
                active = true
                collectJob = scope.launch(Dispatchers.IO) { collectSignals() }
                Log.i(TAG, "detection active")
            } else {
                active = false
                val job = collectJob
                collectJob = null
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
        autoTranscriber.enqueue(path, entry.detectedAtMs)
    }

    private companion object {
        const val TAG = "VoiceNoteDetection"
    }
}
