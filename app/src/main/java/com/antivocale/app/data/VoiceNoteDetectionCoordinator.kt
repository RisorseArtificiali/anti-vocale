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

/**
 * TASK-271: one pending offer to transcribe a detected voice note. This is
 * the payload the notification action's PendingIntent carries; tapping
 * Transcribe hands exactly these fields to the offer receiver.
 *
 * @property filePath Absolute path of the voice-note file as observed
 *   (unreadable directly: the tap resolves it through the SAF tree grant).
 * @property senderName Matched sender label, or null when the identity
 *   matcher found no unambiguous candidate (the graceful degradation).
 * @property ownerPackage Always com.whatsapp on this route, threaded to the
 *   service as the source package so History grouping and per-app preferences
 *   behave exactly as for a share from that app.
 * @property durationSeconds Probed audio duration in seconds, when readable.
 */
data class VoiceNoteOffer(
    val filePath: String,
    val senderName: String?,
    val ownerPackage: String?,
    val durationSeconds: Long?,
)

/** The injectable notification poster the coordinator hands offers to. */
fun interface VoiceNoteOfferPoster {
    /**
     * Pre-flight: whether an offer could post at all right now (app-level
     * denial or a blocked channel). Consulted before any per-entry work, so
     * a suppressed configuration pays no file probes.
     */
    fun canPost(): Boolean = true

    fun post(offer: VoiceNoteOffer)
}

/** The injectable runtime-permission probe (a seam for unit tests). */
fun interface VoiceNotePermissionChecker {
    fun audioReadGranted(): Boolean
}

/**
 * TASK-271 lifecycle owner for voice-note detection: observes the
 * `voiceNoteDetectionEnabled` preference and, while it is on AND the
 * read-media runtime permission is granted, keeps [VoiceNoteDetector]
 * subscribed, polls on every change signal, correlates the newest fresh
 * entry with the identity cache, and posts it as the offer notification
 * (the fixed offer id means only the newest entry's offer is visible).
 *
 * PRIVACY CONTRACT (the Settings card states it): while the preference is
 * off, nothing is read at all. While on, detection reads only the
 * WhatsApp voice-note folder listing (FileObserver events over the tree,
 * no content read), and everything stays on device.
 *
 * Threading: [VoiceNoteDetector.subscribe] runs a synchronous baseline
 * query, so every call site here runs it on [Dispatchers.IO], never the
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
 * detector's reversible teardown, so off/on cycles re-arm detection from
 * the delta since the watermark.
 */
@Singleton
class VoiceNoteDetectionCoordinator @Inject constructor(
    private val detector: VoiceNoteDetector,
    private val preferencesManager: PreferencesManager,
    private val voiceNoteTreeGrant: VoiceNoteTreeGrant,
    private val poster: VoiceNoteOfferPoster,
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
        // arming without it would post offers whose tap always fails. The
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
            runCatching { handleFreshEntry(entry) }
                .onFailure { Log.w(TAG, "voice-note offer failed", it) }
        }
    }

    /**
     * One detected entry becomes the offer. The detector's flow already
     * conflates bursts to the newest (DROP_OLDEST; the single fixed
     * notification slot can only ever show the latest one). An entry
     * without an absolute path cannot be handed to the service and yields
     * no offer.
     *
     * The offer is sender-less and duration-less BY DESIGN on this route
     * (code review F1, 2026-10-13): the observed path is FUSE-hidden and
     * EACCES (the spike's probe 3), so a detection-time duration probe
     * always fails and the matcher labels nothing without a duration (its
     * own contract). Probing through the tree grant is a content read at
     * detection time, which the Settings privacy copy rules out; the
     * enrichment task (backlog) owns that trade-off. The identity leg is
     * NOT dead: the share path matches over its readable sandbox copy.
     */
    private suspend fun handleFreshEntry(entry: VoiceNoteEntry) {
        if (!poster.canPost()) return
        val path = entry.path ?: return
        poster.post(
            VoiceNoteOffer(
                filePath = path,
                senderName = null,
                ownerPackage = entry.ownerPackage,
                durationSeconds = null,
            ),
        )
    }

    private companion object {
        const val TAG = "VoiceNoteDetection"
    }
}
