package com.antivocale.app.util

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Idle-unload timer for engines that hold native memory. Born in TASK-344
 * for the sherpa-onnx sessions; since TASK-451 the Gemma/LiteRT engine runs
 * on it too (LlmManager), which is why it lives in util rather than
 * transcription.
 *
 * Why this exists (TASK-344 / issue #42): the ORT CPU arena backing a loaded
 * sherpa model never shrinks while the session lives, and a loaded Parakeet
 * session retains ~2.3GB of native heap after transcription. OfflineRecognizer
 * .release() provably frees the arena, so unloading when idle returns the
 * process to baseline. The keep-alive timeout used to be a no-op for every
 * sherpa backend; this timer is that missing implementation.
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
    // TASK-574: elapsedRealtime by default; tests inject a controllable clock.
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val timeoutMinutes = AtomicInteger(defaultTimeoutMinutes)
    /** TASK-665: base window (cold). Defaults to the flat window: the
     * adaptive pair is OPT-IN via [setAdaptiveTimeouts]. */
    private val baseTimeoutMinutes = AtomicInteger(defaultTimeoutMinutes)
    /** TASK-665: warm window after a served request. */
    private val warmTimeoutMinutes = AtomicInteger(defaultTimeoutMinutes)
    /** TASK-665: the backend served within the current window. */
    private val warm = AtomicBoolean(false)
    private val workInFlight = AtomicInteger(0)
    private val timerActive = AtomicBoolean(false)
    private val lock = Any()
    private var job: Job? = null

    // TASK-574: the moment the running idle timer will fire; null while the
    // timer is paused by in-flight work, disarmed, or never started. Written
    // under [lock], read racily by [remainingSeconds] (a stale read shows an
    // already-restarted deadline, never a wrong direction).
    @Volatile
    private var idleDeadline: Long? = null

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

    /** TASK-665: the adaptive pair. Null restores the flat pre-665 window. */
    fun setAdaptiveTimeouts(baseMinutes: Int?, warmMinutes: Int?) {
        baseTimeoutMinutes.set(baseMinutes ?: defaultTimeoutMinutes)
        warmTimeoutMinutes.set(warmMinutes ?: defaultTimeoutMinutes)
        userOverride.set(false)
    }

    /** Stores the timeout; a running timer restarts with the new value.
     *  TASK-665: an explicit setTimeout is the USER taking control: the flat
     *  window wins and the adaptive pair stands down until re-armed. When
     *  the FIRE wins instead, work racing into the window starts on the
     *  already-unloaded backend and fails cleanly (NotInitialized); the
     *  next initialize() re-arms. */
    private val userOverride = AtomicBoolean(false)

    fun setTimeout(minutes: Int) {
        timeoutMinutes.set(if (minutes > 0) minutes else defaultTimeoutMinutes)
        userOverride.set(true)
        synchronized(lock) {
            if (timerActive.get()) restartLocked()
        }
    }

    /**
     * TASK-665 review: the SYSTEM preference sync (orchestrator -> backend
     * manager -> here on every load) must NOT arm the user-override flag,
     * or the adaptive pair is permanently disarmed. Use this from system
     * sync paths; [setTimeout] stays the explicit-user arm.
     */
    fun setTimeoutSystemSync(minutes: Int) {
        timeoutMinutes.set(if (minutes > 0) minutes else defaultTimeoutMinutes)
        // TASK-665 review CR3: warm tracks the preference (the ceiling IS
        // timeoutMinutes; warm = "served in the previous window" restores the
        // preference, whatever the user set it to).
        warmTimeoutMinutes.set(timeoutMinutes.get())
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

    /** TASK-451: the effective timeout (user pref or the default fallback). */
    fun currentTimeoutMinutes(): Int = timeoutMinutes.get()

    /** TASK-451: state reads for LlmManager.getRemainingTimeSeconds and tests. */
    fun isTimerActiveForTest(): Boolean = timerActive.get()

    /**
     * TASK-574: seconds until the idle timer fires, or null when there is no
     * live countdown (never started, paused by in-flight work, stopped, or
     * disarmed after an unload). This is the real remaining idle time, not
     * the configured timeout.
     */
    fun remainingSeconds(): Long? {
        if (!timerActive.get()) return null
        val deadline = idleDeadline ?: return null
        return ((deadline - clock()) / 1000L).coerceAtLeast(0L)
    }

    /** TASK-644: live in-flight native-call count (production read for
     *  [com.antivocale.app.transcription.TranscriptionBackend.isBusy]). */
    fun workInFlightCount(): Int = workInFlight.get()

    /** TASK-451: in-flight generation count, for the bracket tests. */
    fun workInFlightForTest(): Int = workInFlight.get()

    /** Stops the timer and forgets it (call on the owning backend's unload). */
    fun stop() {
        synchronized(lock) {
            timerActive.set(false)
            idleDeadline = null
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
            // TASK-574: countdown pauses while work runs (endWork re-arms it).
            idleDeadline = null
            job?.cancel()
            // TASK-665: the adaptive warm window. Base is 2 minutes; a
            // backend that SERVED in the previous window is warm and gets 5
            // (configurable via [warmTimeoutMinutes]). The heuristic is
            // serve-again-within-one-window: every beginWork while the timer
            // still counts marks the backend exercised, and endWork re-arms
            // with the warm value. A cold gap (idle unload fired) resets to
            // the base. The residency identity semantics (TASK-626) are
            // untouched: only WHEN the idle unload fires changes.
            warm.set(true)
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
        // TASK-665: the effective window. The user preference (setTimeout)
        // stays the ceiling: adaptive only SHORTENS a cold backend (base) or
        // restores the preference for a warm one, never exceeds the user's
        // choice.
        val effective = when {
            userOverride.get() -> timeoutMinutes.get()
            warm.get() -> warmTimeoutMinutes.get().coerceAtMost(timeoutMinutes.get())
            else -> baseTimeoutMinutes.get().coerceAtMost(timeoutMinutes.get())
        }
        idleDeadline = clock() + effective * 60_000L
        job = scope.launch {
            val minutes = effective
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
                    // TASK-439: no post-unload re-check of workInFlight.
                    // Every counter mutation happens under [lock] (beginWork,
                    // endWork), so work ARRIVING during the unload blocks at
                    // beginWork's monitor entry, increments only after this
                    // section exits (by then disarmed), reads the post-unload
                    // null, and fails cleanly with NotInitialized; the live
                    // re-arm is the next initialize() calling start().
                    // Disarm: no no-op refires every timeout while idle.
                    // The next initialize() re-arms via start(). TASK-665:
                    // the idle unload fired: the next window is COLD.
                    timerActive.set(false)
                    idleDeadline = null
                    warm.set(false)
                }
            }
        }
    }
}
