package com.antivocale.app.service

import android.content.Context
import android.os.PowerManager

/**
 * Partial wake lock held for the duration of active transcription.
 *
 * Why this exists: with the app backgrounded (share-flow transcription), the CPU can
 * enter deep idle mid-inference and the process stalls until the user re-opens the
 * app — the stuck-PROCESSING-row / result-on-next-launch symptom confirmed on
 * ColorOS-class devices (Realme RMX3853). The foreground service keeps the process
 * alive but does not keep cores clocked; this lock does. It complements, not
 * replaces, the battery-optimization exemption (TASK-336 follow-up).
 *
 * Concurrency: non-reference-counted on purpose — one acquire per task start refreshes
 * the safety deadline, and exactly ONE release (in the drain-loop finally) drops it no
 * matter how many tasks ran in the batch. [release] is idempotent so every teardown
 * path (batch cancel, per-task cancel, scope teardown in onDestroy) can call it
 * unconditionally.
 */
class TranscriptionWakeLock(context: Context) {

    companion object {
        /**
         * Hard cap per acquire: bounds battery damage if a teardown path is ever
         * missed. Generous over the worst legitimate batch (model hard cap 400s of
         * audio × slow-model real-time factor); a batch longer than this re-arms at
         * its NEXT task start anyway.
         */
        const val TIMEOUT_MS = 30L * 60_000L
        const val WAKELOCK_TAG = "antivocale:inference"
    }

    private val wakeLock: PowerManager.WakeLock =
        context.applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            .also { it.setReferenceCounted(false) }

    /** True while the lock is currently held. */
    val isHeld: Boolean get() = wakeLock.isHeld

    /**
     * Takes the lock (or refreshes its deadline when already held by a previous task
     * of the same batch). Non-reference-counted: N acquires still need one release.
     */
    fun acquire() {
        wakeLock.acquire(TIMEOUT_MS)
    }

    /** Drops the lock if held; safe to call from any teardown path, any number of times. */
    fun release() {
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }
}
