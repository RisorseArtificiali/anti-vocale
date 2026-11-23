package com.antivocale.app.service

import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.antivocale.app.R
import com.antivocale.app.data.local.FailureContext
import com.antivocale.app.data.local.FailureContextJson
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.data.local.TranscriptionSessionEntity
import com.antivocale.app.util.NativeCrashDetector
import com.antivocale.app.util.formatProcessingTime

/**
 * TASK-684 (GH #109): honest recovery when the OEM freezer suspends a
 * background transcription.
 *
 * The pure [classify] function decides, from the evidence a process death
 * leaves behind, whether an orphaned PROCESSING row was a freezer suspension,
 * a native crash (the [NativeCrashDetector] lineage wins), a run that was
 * alive when killed, or unprovable. The classifier errs to no-label: a
 * suspension is claimed ONLY on the wall-clock gap between the run heartbeat
 * and the process death, never on "no completion" (the 2026-09-25 lesson in
 * TASK-521: a run read as a freezer stall was the slow summary phase, the
 * process alive at 0% CPU; absence of output is not evidence of suspension).
 *
 * The gap is sound because the heartbeat ([RunHeartbeat]) is phase-blind: it
 * ticks while the process executes, so a slow-but-alive phase keeps it fresh
 * (no false freezer claim) while a frozen process stops it entirely. The gap
 * is measured against the DEATH timestamp, never "now": a user who reopens
 * the app hours after an outright kill must not read as suspended.
 */
object SuspendedRunClassifier {

    /**
     * A suspension is claimed only when the heartbeat went silent at least
     * this long BEFORE the process died: 24 tick intervals, far beyond any
     * alive-phase delta (a runnable coroutine missing 24 scheduled 5s ticks
     * while alive would itself be a system-level watchdog event). OEM
     * suspensions last minutes or more; anything shorter reads as
     * [Verdict.Interrupted], which claims nothing.
     */
    const val SUSPENDED_MIN_GAP_MS: Long = 24 * RunHeartbeat.TICK_MS

    /** What the evidence at a process death supports. Anything unprovable is [Verdict.Unknown]. */
    sealed class Verdict {
        /** The freezer class: the run went silent long before the death; [atLeastMs] is a lower bound on the suspension. */
        data class Suspended(val taskId: String, val atLeastMs: Long) : Verdict()

        /** [NativeCrashDetector]'s own signals win: the crash/lmk paths own the user-facing outcome. */
        data object NativeCrashLineage : Verdict()

        /** The heartbeat was fresh at death: the process was alive and working when killed. No suspension claim. */
        data object Interrupted : Verdict()

        /** Insufficient or contradictory evidence: no label (the generic honest close applies). */
        data object Unknown : Verdict()
    }

    /** The evidence snapshot; every field is nullable because every source can be absent. */
    data class Evidence(
        /** The orphaned PROCESSING row's task id; null when no such row exists. */
        val processingTaskId: String?,
        /** Simplify F1: the inseparable pairs arrive as their own types. */
        val heartbeat: RunHeartbeat.Snapshot?,
        val exit: com.antivocale.app.util.NativeCrashDetector.ExitRecord?,
    )

    /**
     * The state machine. Check order is the decision table: every unprovable
     * input short-circuits to [Verdict.Unknown] before any claim is made, the
     * crash lineage wins over any gap, and only then does the gap decide
     * between suspension and plain interruption.
     */
    fun classify(evidence: Evidence): Verdict {
        // No live-run evidence: the run never ticked, or it ended normally and
        // the heartbeat was cleared. Nothing separates freezer from anything.
        val heartbeat = evidence.heartbeat ?: return Verdict.Unknown
        // The heartbeat must name the orphaned row; a mismatch (or no
        // PROCESSING row at all) means the evidence describes another run.
        val processingTaskId = evidence.processingTaskId ?: return Verdict.Unknown
        if (heartbeat.taskId != processingTaskId) return Verdict.Unknown
        // No death record (below API 30, or empty history): without the death
        // timestamp the gap cannot be measured against anything but "now",
        // which would blame a user who simply reopened the app late.
        val exit = evidence.exit ?: return Verdict.Unknown
        val exitMs = exit.timestamp
        val exitReason = exit.reason
        // Contradictory ordering (heartbeat after death): the clock moved; no label.
        if ((evidence.heartbeat?.timestampMs ?: Long.MIN_VALUE) > exitMs) return Verdict.Unknown
        // NativeCrashDetector's own signals win: the crash banner and the OOM
        // sweep advice own those deaths, whatever the gap says.
        if (exitReason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            exitReason == ApplicationExitInfo.REASON_LOW_MEMORY
        ) return Verdict.NativeCrashLineage
        val silentBeforeDeathMs = exitMs - heartbeat.timestampMs
        return if (silentBeforeDeathMs >= SUSPENDED_MIN_GAP_MS) {
            Verdict.Suspended(processingTaskId, silentBeforeDeathMs)
        } else {
            // Fresh heartbeat at death: the process was executing the run when
            // it died (the alive-slow class lands here too: its heartbeat
            // stays fresh, so no freezer claim is made).
            Verdict.Interrupted
        }
    }
}

/**
 * The cold-start pass that replaces BridgeApplication's blind
 * [LogDao.failAllNonTerminal] sweep: same guarantee (no row renders as
 * in-flight forever) plus the honest per-cause labels. Runs once per process
 * start, before the transcription service can exist in this process (the
 * sweep's standing invariant).
 */
object SuspendedRunRecovery {

    private const val TAG = "SuspendedRunRecovery"

    /** Phase 6: 30 days of parked life before the expiry sweep closes a session. */
    private const val PAUSED_SESSION_EXPIRY_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * Fixed id below the result allocator's 3000 base (reserved-range
     * contract, see [ResultNotificationFactory]): a later suspension replaces
     * an earlier one instead of stacking stale re-run prompts.
     */
    const val NOTIFICATION_ID = 2502

    /**
     * TASK-684: the generic-interrupted summary (one above the suspension
     * id, same reserved-range contract): a later summary replaces an
     * earlier one, never stacks.
     */
    const val INTERRUPTED_NOTIFICATION_ID = 2503

    /** The stable [FailureContext.errorClass] token marking a suspension row (matched by the DAO's SQL). */
    const val SUSPENDED_ERROR_CLASS = "SystemSuspended"

    /**
     * Closes the rows a process death orphaned, classifying the freezer case.
     * Order matters: the suspension marker writes first (non-terminal rows
     * only), then the generic sweep closes whatever is left, so the two can
     * never disagree on a row.
     *
     * TASK-1037 (GH #190) phase 3: BEFORE the suspension arm and the generic
     * sweep, every ACTIVE/COMPLETING session row is parked: the session
     * becomes PAUSED_SYSTEM (the classifier still runs, its verdict naming
     * the cause and driving the crash-loop counter), the log row becomes
     * PAUSED, and the paused notification carries the Resume action. The
     * generic sweep cannot touch a PAUSED row (its SQL list excludes it, the
     * phase-2 pin), and the suspension arm below must skip the parked task
     * id: the paused notification REPLACES the suspension notification for a
     * session run (same honest cause, plus the resume path a bare re-run
     * would not have).
     */
    suspend fun closeInterruptedRuns(
        context: Context,
        logDao: LogDao,
        sessionDao: com.antivocale.app.data.local.SessionDao,
        wasOOMCrash: Boolean,
        notifyGenericInterrupted: Boolean = true,
    ) {
        val heartbeat = RunHeartbeat.read(context)
        // No live-run evidence (the run never ticked, or it ended normally
        // and the heartbeat was cleared): exit stays null, the classifier
        // answers Unknown, and the generic interrupted sweep closes the rows.
        val exit = heartbeat?.let {
            NativeCrashDetector.earliestExitAtOrAfter(context, it.timestampMs)
        }
        val rows = runCatching { logDao.getNonTerminal() }.getOrElse { e ->
            Log.w(TAG, "Non-terminal row read failed; generic sweep only", e)
            emptyList()
        }
        // The classifier names the run the heartbeat belongs to: the single
        // PROCESSING row whose task id matches. Queued rows never ticked and
        // can only be interrupted.
        val processingTaskId = rows
            .firstOrNull { it.status == "PROCESSING" && it.taskId == heartbeat?.taskId }
            ?.taskId

        val verdict = SuspendedRunClassifier.classify(
            SuspendedRunClassifier.Evidence(
                processingTaskId = processingTaskId,
                heartbeat = heartbeat,
                exit = exit,
            )
        )

        // TASK-1037: the session park, BEFORE the suspension arm and the
        // generic sweep. The crash-at-offset counter counts native-crash
        // deaths WITHOUT an intervening checkpoint (the checkpoint sink
        // resets it on progress), which is exactly "deaths at the same
        // contiguousPrefixEnd": the second one trips the resume's
        // engine-poisoned-chunk skip.
        // TASK-1037 phase 6: the paused-session expiry. A session left
        // parked for a month has outlived any realistic resume intent;
        // its source may be gone and its chunks are pure residue, so the
        // row closes as a partial keeping the text it last wrote (no
        // chunk-fold refresh: unlike Cancel, the text stays whatever the
        // last throttled interim write left) and the session rows go.
        // Write order is the canonical close-as-partial sequence (row
        // first, delete second): a failure between the two leaves the
        // row closed and the session retryable next cold start, never a
        // PAUSED row with a dead Resume action.
        runCatching {
            val cutoff = System.currentTimeMillis() - PAUSED_SESSION_EXPIRY_MS
            for (session in sessionDao.getPausedSessions()) {
                if (session.updatedAtMs < cutoff) {
                    logDao.closePausedAsError(
                        session.taskId, context.getString(R.string.session_expired_reason))
                    sessionDao.deleteSession(session.taskId)
                    Log.i(TAG, "Expired paused session ${session.taskId} closed as partial")
                }
            }
        }.onFailure { e -> Log.w(TAG, "Paused-session expiry sweep failed", e) }

        val parkedTaskIds = mutableSetOf<String>()
        val pausedCauses = mutableMapOf<String, PausedSessionNotifier.PausedCause>()
        val activeSessions = runCatching { sessionDao.getActiveSessions() }.getOrElse { e ->
            Log.w(TAG, "Active-session read failed; no sessions parked", e)
            emptyList()
        }
        for (session in activeSessions) {
            val causeLine: String?
            val batteryLink: Boolean
            when (val v = verdict) {
                is SuspendedRunClassifier.Verdict.Suspended -> {
                    causeLine = context.getString(
                        R.string.suspension_notification_text,
                        formatProcessingTime(v.atLeastMs))
                    batteryLink = true
                }
                is SuspendedRunClassifier.Verdict.NativeCrashLineage -> {
                    // Phase 4 review: these two lines reach the user via the
                    // paused notification, so they localize like the
                    // suspension arm above them.
                    causeLine = context.getString(R.string.session_park_cause_native_crash)
                    batteryLink = false
                }
                else -> {
                    causeLine = context.getString(R.string.session_park_cause_app_restart)
                    batteryLink = false
                }
            }
            val crashed = verdict is SuspendedRunClassifier.Verdict.NativeCrashLineage
            var parked = false
            runCatching {
                sessionDao.upsertSession(session.copy(
                    state = TranscriptionSessionEntity.STATE_PAUSED_SYSTEM,
                    crashAtOffsetCount =
                        if (crashed) session.crashAtOffsetCount + 1 else session.crashAtOffsetCount,
                    updatedAtMs = System.currentTimeMillis(),
                ))
                logDao.parkProcessingAsPaused(session.taskId, errorMessage = causeLine)
                parked = true
            }.onFailure { e ->
                Log.w(TAG, "Failed to park session ${session.taskId}", e)
            }
            // Review round 2: bookkeeping only on successful writes; a
            // partially-parked task must fall through to the suspension
            // arm below instead of being skipped on a false belief.
            if (parked) {
                parkedTaskIds.add(session.taskId)
                pausedCauses[session.taskId] = PausedSessionNotifier.PausedCause(causeLine, batteryLink)
                Log.i(TAG, "Session ${session.taskId} parked as PAUSED_SYSTEM " +
                    "(verdict=$verdict, crashAtOffsetCount=" +
                    "${if (crashed) session.crashAtOffsetCount + 1 else session.crashAtOffsetCount})")
            }
        }

        when (val v = verdict) {
            is SuspendedRunClassifier.Verdict.Suspended -> {
                // TASK-1037: a session row was already parked above; the
                // paused notification (with the same verdict cause and the
                // battery link) replaces the suspension notice here.
                if (parkedTaskIds.contains(v.taskId)) {
                    Log.i(TAG, "Suspended verdict for ${v.taskId} owns a session; the paused notification replaces the suspension notice")
                } else {
                    val row = rows.firstOrNull { it.taskId == v.taskId }
                    if (row == null) {
                        Log.w(TAG, "Suspended verdict for ${v.taskId} but no row found; generic sweep applies")
                    } else {
                        val message = context.getString(
                            R.string.suspension_notification_text, formatProcessingTime(v.atLeastMs))
                        runCatching {
                            logDao.markSuspendedBySystem(
                                taskId = row.taskId,
                                errorMessage = message,
                                failureContext = FailureContextJson.toJson(
                                    FailureContext(errorClass = SUSPENDED_ERROR_CLASS, suspendedMs = v.atLeastMs)),
                            )
                        }.onFailure { Log.w(TAG, "Failed to mark suspended row ${row.taskId}", it) }
                        Log.i(TAG, "Run ${row.taskId} closed as suspended " +
                            "(atLeastMs=${v.atLeastMs}, exitReason=${exit?.reason}, gapEvidence=heartbeat-to-death)")
                        // TASK-684 review: the Retry action re-runs row.filePath;
                        // a >24h reopen loses the file to shared_audio's 24h
                        // cleanup (which runs before this sweep). Offer the
                        // notification with the honest label but drop the doomed
                        // Retry action when the file is already gone.
                        val fileAlive = row.filePath?.let { java.io.File(it).exists() } == true
                        postSuspensionNotification(context, row, message, retryFileAlive = fileAlive)
                        if (!fileAlive) {
                            Log.i(TAG, "Retry dropped: $row.filePath no longer exists (shared_audio 24h cleanup)")
                        }
                    }
                    }
            }
            else -> Log.i(TAG, "Process-death close verdict=$verdict (generic interrupted sweep applies)")
        }

        // Everything not marked suspended closes with the pre-existing honest
        // reason, OOM advice included (TASK-396 pt.2). Terminal rows are
        // untouched by the sweep's WHERE clause. The rowcount IS the generic
        // batch size: every row the sweep closed had no suspension marker.
        // This DB reason stays English (locale-independent persistence); the
        // notification localizes its own copy (interrupted_runs_oom_text),
        // which repeats the OOM advice in wording, not byte-for-byte.
        val reason = if (wasOOMCrash) {
            "Interrupted by app restart: out of memory. Try a shorter file, a smaller model, or close other apps."
        } else {
            "Interrupted by app restart"
        }
        val genericCount = logDao.failAllNonTerminal(reason)
        // TASK-684 (GH #109, maintainer decision): the generic class gets a
        // quiet summary notification too, gated by [notifyGenericInterrupted]
        // (the caller owns the preference read; this object has no DI). The
        // suspended class always notifies. One notification for the batch,
        // no retry action: there is no proven re-runnable file here.
        if (genericCount > 0 && notifyGenericInterrupted) {
            post(context, INTERRUPTED_NOTIFICATION_ID) {
                ResultNotificationFactory(context).interruptedRunsNotification(genericCount, wasOOMCrash)
            }
        }

        // TASK-1037: the parked sessions' notification, AFTER the sweep (the
        // table state is final). Causes carry the verdict; post itself
        // cancels when nothing is paused, a no-op for every non-session cold
        // start (the simplify round removed the redundant pre-read of the
        // same set).
        PausedSessionNotifier.post(context, sessionDao, pausedCauses)

        // Single-use evidence: the heartbeat describes a dead run and must not
        // survive into this process's own lifetime.
        heartbeat?.let { RunHeartbeat.clear(context, it.taskId) }
    }

    /**
     * The honest outcome the user sees: a high-priority notification naming
     * the suspension, with the one-tap re-run (the same file through
     * [InferenceEnqueue]) and the battery-exemption deep link one tap away
     * (the 1.11-prep guidance). Contained: a notification failure must never
     * break startup.
     */
    private fun postSuspensionNotification(
        context: Context,
        row: LogEntity,
        message: String,
        retryFileAlive: Boolean,
    ) {
        post(context, NOTIFICATION_ID) {
            ResultNotificationFactory(context).suspensionNotification(
                text = message,
                rerunTaskId = row.taskId,
                filePath = row.filePath,
                prompt = row.prompt,
                sourcePackage = row.sourcePackageName,
                senderName = row.senderName,
                retryFileAlive = retryFileAlive,
            )
        }
    }

    /** Contained notification post: a builder or system failure never breaks startup. */
    private fun post(context: Context, id: Int, build: () -> android.app.Notification) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(id, build())
        }.onFailure { Log.w(TAG, "Failed to post notification id=$id", it) }
    }
}
