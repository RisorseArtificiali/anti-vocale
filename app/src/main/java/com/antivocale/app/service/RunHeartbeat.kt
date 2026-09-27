package com.antivocale.app.service

import android.content.Context
import android.util.Log

/**
 * TASK-684 (GH #109): the persisted liveness heartbeat of the in-flight
 * transcription run.
 *
 * The OEM freezer (Realme/Oplus class) suspends the whole process mid-run;
 * on the process's return the only honest way to tell "the system suspended
 * the app" from "the app was alive but slow" is a tick that fires while the
 * process executes, regardless of phase. The interim-partial save cannot do
 * this job: phases with no partials (preprocessing, the summary pass) stop
 * refreshing it while the run is perfectly alive, which is exactly the
 * 2026-09-25 misdiagnosis class (TASK-521: a run read as a freezer stall was
 * the slow summary phase). This heartbeat is phase-blind: [InferenceService]
 * touches it at task start and every [TICK_MS] for the whole task lifetime,
 * and clears it when the task ends on any path.
 *
 * Lifecycle contract (what makes the classifier's gap evidence sound):
 * - touch only while a run is in flight; the clear rides the task job's
 *   finally, AFTER the row reached a terminal state.
 * - a process death mid-run leaves the heartbeat set (the run never ended);
 *   a run that ended normally leaves it cleared, so a later process death
 *   cannot be misread as a suspension of an already-finished run.
 *
 * SharedPreferences, not DataStore: one tiny keypair at a 5s cadence, read
 * synchronously at cold start. No DI: an object like
 * [com.antivocale.app.util.NativeCrashDetector].
 */
object RunHeartbeat {
    private const val TAG = "RunHeartbeat"
    private const val PREFS_NAME = "run_heartbeat"
    private const val KEY_TASK_ID = "task_id"
    private const val KEY_TIMESTAMP = "ts"

    /** The tick cadence; shared by the ticker and the classifier's threshold derivation. */
    const val TICK_MS = 5_000L

    data class Snapshot(val taskId: String, val timestampMs: Long)

    /**
     * Arms or refreshes the heartbeat (same write: stamp the owning task and
     * now). The ticker calls it immediately at task start and then every
     * [TICK_MS]; a new task id simply takes over the slot.
     */
    fun touch(context: Context, taskId: String) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TASK_ID, taskId)
                .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                .apply()
        }.onFailure { Log.w(TAG, "Failed to write run heartbeat", it) }
    }

    /** Clears the heartbeat when the run ended normally; a mismatched task id is left alone. */
    fun clear(context: Context, taskId: String) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_TASK_ID, null) == taskId) {
                prefs.edit().clear().apply()
            }
        }.onFailure { Log.w(TAG, "Failed to clear run heartbeat", it) }
    }

    /** The live-run evidence for the cold-start classifier; null when no run was in flight. */
    fun read(context: Context): Snapshot? = try {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val taskId = prefs.getString(KEY_TASK_ID, null) ?: return null
        val ts = prefs.getLong(KEY_TIMESTAMP, 0L)
        if (ts <= 0L) null else Snapshot(taskId, ts)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read run heartbeat", e)
        null
    }
}
