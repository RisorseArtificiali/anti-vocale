package com.antivocale.app.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import androidx.core.content.edit

/**
 * Detects whether the app's previous process died from a native crash (exit 255,
 * sherpa-onnx invalid model) or a low-memory kill (lmkd).
 *
 * On next launch, checks [ActivityManager.getHistoricalProcessExitReasons] for
 * [ApplicationExitInfo.REASON_CRASH_NATIVE] and [ApplicationExitInfo.REASON_LOW_MEMORY],
 * and reports which (if any) the UI should warn about, with a distinct message per reason.
 *
 * API 30+ only; earlier versions silently return [CrashCheckResult.None].
 *
 * Related: GitHub #21, TASK-314.
 */
object NativeCrashDetector {
    private const val TAG = "NativeCrashDetector"
    private const val PREFS_NAME = "native_crash_detection"
    private const val KEY_LAST_NATIVE_CRASH_TS = "last_native_crash_ts"
    private const val KEY_LAST_LOW_MEMORY_TS = "last_low_memory_ts"
    private const val KEY_LAST_REPORTED_DEATH_TS = "last_reported_death_ts"
    private const val RECENT_WINDOW_MS = 5 * 60 * 1000L // 5 minutes

    /**
     * TASK-472b: the silent-death reasons worth telemetry. LMKD kills surface
     * as [ApplicationExitInfo.REASON_LOW_MEMORY]; OEM killers (MIUI
     * PowerKeeper, ColorOS UserAwareMgr) as [ApplicationExitInfo.REASON_SIGNALED];
     * native aborts (sherpa model-load deaths, TASK-479/GH #88) as
     * [ApplicationExitInfo.REASON_CRASH_NATIVE]. None reach Crashlytics
     * without this (no NDK SDK), which is why the 4GB crash investigation
     * (TASK-468) had to be reconstructed statically: these reports close
     * that blind spot.
     */
    @android.annotation.SuppressLint("InlinedApi") // compile-time-int constants, no runtime field access below API 30
    private val REPORTED_DEATH_REASONS = setOf(
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
    )

    /**
     * Sealed result so the caller can pick a distinct message per cause. Each non-None
     * variant carries the exit timestamp for testing/diagnostics.
     */
    sealed class CrashCheckResult {
        data object None : CrashCheckResult()
        data class NativeCrash(val timestamp: Long) : CrashCheckResult()
        data class LowMemory(val timestamp: Long) : CrashCheckResult()
    }

    /**
     * Checks the app's most recent process death. Call exactly once per process launch,
     * from the first Activity's `onCreate`. Each detected reason is recorded in
     * SharedPreferences with a reason-scoped key, so a relaunch within [RECENT_WINDOW_MS]
     * does not nag again, AND a native crash then a low-memory kill (or vice versa) within
     * the window each surface once.
     */
    fun checkForRecentCrash(context: Context): CrashCheckResult {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return CrashCheckResult.None // API 30+ only
        }

        return try {
            val am = context.getSystemService(ActivityManager::class.java)
            val exitInfos = am?.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                ?: return CrashCheckResult.None

            val mostRecent = exitInfos.firstOrNull() ?: return CrashCheckResult.None
            val crashTime = mostRecent.timestamp

            // Map the OS exit reason to our sealed variant. Only these two are actionable;
            // every other reason (ANR, user-initiated, etc.) maps to None.
            val matched: CrashCheckResult = when (mostRecent.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> CrashCheckResult.NativeCrash(crashTime)
                ApplicationExitInfo.REASON_LOW_MEMORY -> CrashCheckResult.LowMemory(crashTime)
                else -> return CrashCheckResult.None
            }

            val isRecent = (System.currentTimeMillis() - crashTime) < RECENT_WINDOW_MS
            Log.w(TAG, "Process exit detected: $matched (description=${mostRecent.description}, recent=$isRecent)")

            if (!isRecent) return CrashCheckResult.None

            // Reason-scoped dedup: each reason has its own last-shown timestamp key.
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val dedupKey = when (matched) {
                is CrashCheckResult.NativeCrash -> KEY_LAST_NATIVE_CRASH_TS
                is CrashCheckResult.LowMemory -> KEY_LAST_LOW_MEMORY_TS
                CrashCheckResult.None -> return CrashCheckResult.None
            }
            val lastShownTs = prefs.getLong(dedupKey, 0L)
            if (crashTime <= lastShownTs) return CrashCheckResult.None

            prefs.edit().putLong(dedupKey, crashTime).apply()
            matched
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for recent crash", e)
            CrashCheckResult.None
        }
    }

    /**
     * One silent death as the exit-info API saw it. Our own type (not the
     * final [ApplicationExitInfo]) so the reporting logic is testable
     * without shelling the platform.
     */
    data class ExitRecord(val reason: Int, val timestamp: Long, val description: String?)

    /**
     * TASK-472b cold-start telemetry: report every silent death since the
     * last report as a Crashlytics non-fatal (the fdroid flavor's
     * [CrashReporter] is a logcat no-op by design). Unlike the banner's
     * [checkForRecentCrash] there is no recency window: a death the user
     * never reopened the app for is exactly the one we must hear about.
     * Metadata-only; never throws.
     *
     * Known limits: the dedup mark is a wall-clock timestamp, so a clock set
     * backwards past a death hides it forever; the platform keeps at most the
     * last five exit records, so a burst of deaths with no launch in between
     * drops the oldest; and REASON_SIGNALED also covers `adb shell am kill`,
     * so device-automation sessions contribute dev kills to the reports.
     */
    fun reportUnreportedDeaths(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5) ?: return
            val records = exits.map { ExitRecord(it.reason, it.timestamp, it.description) }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastTs = prefs.getLong(KEY_LAST_REPORTED_DEATH_TS, 0L)
            val toReport = unreported(records, lastTs)
            toReport.forEach { record ->
                CrashReporter.report(
                    SilentProcessDeath(record),
                    "Silent process exit: ${reasonName(record.reason)} at ${record.timestamp}",
                )
            }
            val newMax = toReport.maxOfOrNull { it.timestamp } ?: lastTs
            if (newMax > lastTs) {
                prefs.edit { putLong(KEY_LAST_REPORTED_DEATH_TS, newMax) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report unreported deaths", e)
        }
    }

    /** Pure: deaths worth reporting, oldest first, past the last-reported mark. */
    internal fun unreported(records: List<ExitRecord>, lastReportedTs: Long): List<ExitRecord> =
        records
            .filter { it.timestamp > lastReportedTs && it.reason in REPORTED_DEATH_REASONS }
            .sortedBy { it.timestamp }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        else -> "reason=$reason"
    }

    /** Telemetry carrier rendered as a Crashlytics non-fatal record. */
    class SilentProcessDeath(record: ExitRecord) :
        RuntimeException("Silent process death: ${reasonName(record.reason)} (${record.description ?: "no description"})")
}
