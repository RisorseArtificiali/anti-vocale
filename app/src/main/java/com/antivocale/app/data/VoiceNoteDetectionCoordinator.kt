package com.antivocale.app.data

import android.util.Log
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.di.ApplicationScope
import com.antivocale.app.receiver.VoiceNoteIdentityCache
import com.antivocale.app.receiver.VoiceNoteSenderResolution
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
 * @property filePath Absolute path of the voice-note file (MediaStore DATA).
 * @property senderName Matched sender label, or null when the identity
 *   matcher found no unambiguous candidate (the graceful degradation).
 * @property ownerPackage Package that contributed the MediaStore row (API
 *   29+), threaded to the service as the source package so History grouping
 *   and per-app preferences behave exactly as for a share from that app.
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
 * WhatsApp voice-note folder listing (the bounded MediaStore query), and
 * everything stays on device.
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
    private val poster: VoiceNoteOfferPoster,
    private val identityCache: VoiceNoteIdentityCache,
    private val audioPreprocessor: AudioPreprocessor,
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
            ) { enabled, _ -> enabled }.collect { enabled ->
                // Contained per emission: a failed (un)subscribe must
                // not kill the collector, or a later toggle would
                // silently stop reaching the detector.
                runCatching { reconcile(enabled) }
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
     * Brings the detector's subscription in line with the preference and
     * the permission. Internal so the unit tests can drive transitions
     * directly without the preference collector in between.
     */
    internal suspend fun reconcile(prefEnabled: Boolean) {
        val shouldRun = prefEnabled && permissionChecker.audioReadGranted()
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
                // merely unregisters the observer (no more MediaChanged
                // emissions follow it).
                job?.cancel()
                Log.i(TAG, "detection inactive")
            }
        }
    }

    private suspend fun collectSignals() {
        detector.changeSignals().collect {
            runCatching { handleFreshEntries(detector.poll()) }
                .onFailure { Log.w(TAG, "voice-note poll failed", it) }
        }
    }

    /**
     * The NEWEST fresh entry becomes the offer: the single fixed
     * notification slot can only ever show the latest one, so resolving the
     * older entries of a burst (duration probe plus matcher per entry)
     * would be discarded work. An entry without an absolute path cannot be
     * handed to the service and yields no offer.
     */
    private suspend fun handleFreshEntries(entries: List<VoiceNoteEntry>) {
        if (!poster.canPost()) return
        val entry = entries.lastOrNull() ?: return
        val path = entry.path ?: return
        // Sender correlation at DETECTION time, not tap time: the same
        // duration probe + matcher the share path uses, over the cache
        // of the package that contributed the row.
        val resolution = VoiceNoteSenderResolution.match(identityCache, audioPreprocessor, entry.ownerPackage, path)
        poster.post(
            VoiceNoteOffer(
                filePath = path,
                senderName = resolution.senderName,
                ownerPackage = entry.ownerPackage,
                durationSeconds = resolution.durationSeconds,
            ),
        )
    }

    private companion object {
        const val TAG = "VoiceNoteDetection"
    }
}
