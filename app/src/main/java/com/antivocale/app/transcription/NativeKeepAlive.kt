package com.antivocale.app.transcription

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Idle-unload timer for backends that hold native memory (sherpa-onnx sessions).
 *
 * Why this exists (TASK-344 / issue #42): the ORT CPU arena backing a loaded
 * sherpa model never shrinks while the session lives, and a loaded Parakeet
 * session retains ~2.3GB of native heap after transcription. OfflineRecognizer
 * .release() provably frees the arena, so unloading when idle returns the
 * process to baseline. The keep-alive timeout used to be a no-op for every
 * sherpa backend; this timer is that missing implementation, mirroring the
 * one LlmManager has always had for the Gemma backend.
 *
 * Concurrency: [beginWork]/[endWork] bracket a native call; the timer never
 * fires while work is in flight (the flag is re-checked after the delay too,
 * so a fire that races with new work aborts without unloading).
 */
class NativeKeepAlive(
    private val scope: CoroutineScope,
    private val tag: String,
    private val defaultTimeoutMinutes: Int,
    private val onIdleUnload: () -> Unit,
) {
    private val timeoutMinutes = AtomicInteger(defaultTimeoutMinutes)
    private val workInFlight = AtomicInteger(0)
    private val timerActive = AtomicBoolean(false)
    private val lock = Any()
    private var job: Job? = null

    /**
     * TEST SEAM (TASK-388): invoked inside the idle-unload window, AFTER the
     * workInFlight check has passed and BEFORE [onIdleUnload] runs, while
     * [lock] is held. Null in production (one branch on a once-per-idle-period
     * cold path; behavior identical). Race tests install a hook that freezes
     * here so a work-start landing in the window becomes a deterministic
     * interleaving instead of scheduler luck. Must not call back into this
     * class from the hook: the monitor is reentrant so it would not deadlock,
     * but an unpaired beginWork() would corrupt workInFlight.
     */
    @VisibleForTesting
    @Volatile
    internal var idleUnloadWindowHook: (() -> Unit)? = null

    /** Stores the timeout; a running timer restarts with the new value. */
    fun setTimeout(minutes: Int) {
        timeoutMinutes.set(if (minutes > 0) minutes else defaultTimeoutMinutes)
        synchronized(lock) {
            if (timerActive.get()) restartLocked()
        }
    }

    /** Starts the idle timer (call once after the backend initializes). */
    fun start() {
        synchronized(lock) {
            timerActive.set(true)
            restartLocked()
        }
    }

    /** Stops the timer and forgets it (call on [TranscriptionBackend.unload]). */
    fun stop() {
        synchronized(lock) {
            timerActive.set(false)
            job?.cancel()
            job = null
        }
    }

    /** Must wrap every native inference call: pauses the idle timer. */
    inline fun <R> withWork(block: () -> R): R {
        beginWork()
        try {
            return block()
        } finally {
            endWork()
        }
    }

    fun beginWork() {
        synchronized(lock) {
            workInFlight.incrementAndGet()
            job?.cancel()
        }
    }

    fun endWork() {
        synchronized(lock) {
            workInFlight.decrementAndGet()
            if (timerActive.get()) restartLocked()
        }
    }

    private fun restartLocked() {
        job?.cancel()
        job = scope.launch {
            val minutes = timeoutMinutes.get()
            delay(minutes * 60_000L)
            android.util.Log.i(tag, "Idle timeout (${minutes}m) reached, unloading native backend")
            // The unload runs UNDER the lock: a beginWork arriving meanwhile
            // blocks here instead of racing the native release (synchronized
            // is reentrant on the same thread, so the unload path's own
            // stop() call does not deadlock).
            synchronized(lock) {
                if (timerActive.get() && workInFlight.get() == 0) {
                    idleUnloadWindowHook?.invoke()
                    onIdleUnload()
                    if (workInFlight.get() > 0) {
                        // Work that queued while we held the lock started on an
                        // unloaded backend (its read saw the post-unload null);
                        // re-arm so the NEXT idle period still fires.
                        restartLocked()
                    } else {
                        // Disarm: no no-op refires every timeout while idle.
                        // The next initialize() re-arms via start().
                        timerActive.set(false)
                    }
                }
            }
        }
    }
}
