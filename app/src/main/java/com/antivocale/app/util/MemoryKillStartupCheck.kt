package com.antivocale.app.util

import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import com.antivocale.app.R
import com.antivocale.app.service.ResultNotificationFactory

/**
 * TASK-426 (docs/research/2026-09-01-android-heap-limits-adaptive-behavior.md,
 * finding 3): Android 17 scales per-app total-memory caps to device RAM and
 * enforces them in two steps, forced zRAM swap then a process kill. The kill
 * lands in ApplicationExitInfo as REASON_OTHER with a description containing
 * the literal "MemoryLimiter"; anonymous native memory (ONNX arenas, the
 * Gemma working set), which the TASK-416 Java-heap work could not bound.
 *
 * SCOPE: this check owns ONLY the MemoryLimiter class. The classic LMK kill
 * (REASON_LOW_MEMORY) is already surfaced end-to-end by
 * [NativeCrashDetector.checkForRecentCrash] (the MainActivity banner) and
 * both classes reach Crashlytics through
 * [NativeCrashDetector.reportUnreportedDeaths], which this task extends to
 * the MemoryLimiter death (the incidence-measurability half of the task; no
 * new telemetry channel). Here we post ONE dismissable advisory naming the
 * smaller-model / shorter-file lever. Deliberately NO automatic fallback:
 * unlike the NNAPI precedent (issue #26) there is no crash loop to break
 * (a kill does not relaunch transcription by itself), and silently changing
 * the user's model would violate the consent-first pattern the memory
 * features follow (TASK-631's opt-in protection). Deduped per kill in the
 * same SharedPreferences file NativeCrashDetector uses, reason-scoped like
 * its keys: the advisory fires once per kill, not once per launch.
 */
object MemoryKillStartupCheck {

    private const val TAG = "MemoryKillCheck"
    private const val KEY_LAST_MEMORY_LIMITER_TS = "last_memory_limiter_ts"

    /**
     * A kill older than this does not advise: "your last transcription may
     * have been cut short" is stale advice weeks later (the banner sibling
     * uses 5 minutes for its just-crashed context; here the user may relaunch
     * much later, so the window is a day).
     */
    internal const val ADVISE_WINDOW_MS = 24 * 60 * 60 * 1000L

    /** Fixed id under the reserved-range base; see ResultNotificationFactory's table. */
    const val NOTIFICATION_ID = 1007

    /**
     * The pure discriminator, unit-testable without the system service: the
     * memory-cap kill masquerades as the catch-all REASON_OTHER and is only
     * separable by the literal the platform writes into the description.
     */
    fun isMemoryLimiterKill(reason: Int, description: String?): Boolean =
        reason == ApplicationExitInfo.REASON_OTHER &&
            description.orEmpty().contains("MemoryLimiter", ignoreCase = true)

    /**
     * The dedupe decision, pure: an advisory is due only when the exit is a
     * memory kill, NEWER than the last one handled, and recent enough that
     * the advice still means something.
     */
    fun adjudicate(
        isMemoryKill: Boolean,
        exitTimestamp: Long,
        handledTimestamp: Long,
        nowMs: Long,
    ): Boolean =
        isMemoryKill &&
            exitTimestamp > handledTimestamp &&
            nowMs - exitTimestamp <= ADVISE_WINDOW_MS

    fun run(context: Context, exits: List<NativeCrashDetector.ExitRecord>? = null) {
        runCatching {
            // The 5-record history, not the single most recent exit: a
            // limiter kill can be buried under a later exit (a service
            // restart that then died for any other reason), and telemetry's
            // reportUnreportedDeaths sees exactly those records too. The
            // caller may pass the snapshot it already read (MainActivity
            // reads it once for NativeCrashDetector too; review: two binder
            // reads per onCreate duplicated the cost on every rotation).
            val kills = (exits ?: NativeCrashDetector.recentExits(context))
                .filter { isMemoryLimiterKill(it.reason, it.description) }
            if (kills.isEmpty()) return
            val prefs = context.getSharedPreferences(
                NativeCrashDetector.DEDUPE_PREFS_NAME, Context.MODE_PRIVATE)
            val handled = prefs.getLong(KEY_LAST_MEMORY_LIMITER_TS, 0L)
            val now = System.currentTimeMillis()
            val due = kills.firstOrNull { adjudicate(true, it.timestamp, handled, now) } ?: return

            // The channel must exist before notify() or the system silently
            // drops the post (fresh installs can be killed before any result
            // notification ever created it). TASK-426 review: a post the
            // system DROPS (notifications disabled at the app or channel
            // level, the common fresh-install state on 13+) must not consume
            // the once-per-kill mark; nothing shown, nothing marked, and the
            // advisory retries after the user grants the permission.
            AppNotificationChannel.TRANSCRIPTION_RESULT.create(context)
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            val channel = nm.getNotificationChannel(AppNotificationChannel.TRANSCRIPTION_RESULT.id)
            if (!nm.areNotificationsEnabled() ||
                channel?.importance == android.app.NotificationManager.IMPORTANCE_NONE
            ) {
                Log.w(TAG, "Advisory skipped: notifications disabled; the kill stays unmarked")
                return
            }
            nm.notify(
                NOTIFICATION_ID,
                ResultNotificationFactory(context).alertNotification(
                    title = context.getString(R.string.memory_kill_warning_title),
                    text = context.getString(R.string.memory_kill_warning_body),
                ))
            prefs.edit().putLong(KEY_LAST_MEMORY_LIMITER_TS, kills.maxOf { it.timestamp }).apply()
            Log.w(TAG, "Previous process was killed by the memory limiter " +
                "(${due.description}); advisory posted")
        }.onFailure { Log.w(TAG, "Startup memory-kill check failed", it) }
    }
}
