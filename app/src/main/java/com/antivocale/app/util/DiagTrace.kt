package com.antivocale.app.util

import android.os.Process
import android.util.Log

/**
 * TEMPORARY diagnostic tracer for the background share-flow stall investigation
 * (ColorOS-class freeze: share → home → transcription never finishes; result only
 * after re-opening the app; wake lock did not help).
 *
 * One uniform logcat tag ("DiagTrace") carries every pipeline stage transition plus a
 * periodic heartbeat while a batch is active. Reading the capture:
 *  - Heartbeat lines STOP arriving while the row is still PROCESSING → the whole
 *    process was frozen/killed by the OS (nothing app-side runs anymore).
 *  - Heartbeats continue but the last stage line never completes → the named call
 *    is genuinely stuck (and CPU-time delta in the heartbeat tells busy vs blocked:
 *    wall clock advancing with flat cpuMs = suspended/throttled threads).
 *  - Stage lines advance normally to listener-onSuccess but no notification →
 *    delivery problem, not compute.
 *
 * Safe in unit tests (Log is a no-op via returnDefaultValues); Process.getElapsedCpuTime
 * likewise returns 0. Remove once the root cause is pinned.
 */
object DiagTrace {

    const val TAG = "DiagTrace"

    /** Last stage name handed to [mark]; read by the service heartbeat. */
    @Volatile
    @JvmField
    var stage: String = "idle"

    @Volatile private var batchStartMs: Long = 0L

    /** Starts a new measurement window (called when a transcription batch begins). */
    fun beginBatch(context: String = "") {
        batchStartMs = System.currentTimeMillis()
        stage = "batch-begin"
        Log.w(TAG, "=== BATCH BEGIN $context pid=${Process.myPid()} ===")
    }

    /**
     * Records a stage transition: `[+<elapsed>s][thread] <stage> <detail>`.
     * Elapsed is measured from [beginBatch] so one capture reads as a timeline.
     */
    fun mark(stageName: String, detail: String = "") {
        val now = System.currentTimeMillis()
        if (batchStartMs == 0L) batchStartMs = now
        stage = stageName
        val elapsed = "%06.1f".format((now - batchStartMs) / 1000.0)
        val suffix = if (detail.isBlank()) "" else " | $detail"
        Log.i(TAG, "[+${elapsed}s][${Thread.currentThread().name}] $stageName$suffix")
    }

    /** Closes the window (batch end) and stops implicit elapsed growth until next beginBatch. */
    fun endBatch(context: String = "") {
        mark("BATCH-END", context)
        batchStartMs = 0L
        stage = "idle"
    }

    /**
     * One heartbeat sample: wall elapsed since batch start, process-wide CPU time,
     * current stage. Emitted every few seconds by InferenceService while a batch runs.
     */
    fun heartbeat(taskId: String?, queued: Int, extra: String = "") {
        val wallMs = if (batchStartMs == 0L) 0L else System.currentTimeMillis() - batchStartMs
        val cpuMs = Process.getElapsedCpuTime()
        val suffix = if (extra.isBlank()) "" else " $extra"
        Log.w(
            TAG,
            "HEARTBEAT task=$taskId stage=$stage wall=${wallMs / 1000.0}s cpu=${cpuMs / 1000.0}s queued=$queued$suffix"
        )
    }
}
