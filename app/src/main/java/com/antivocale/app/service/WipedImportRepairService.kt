package com.antivocale.app.service

import android.app.Notification
import android.os.IBinder
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import com.antivocale.app.data.ExternalImportRepairer
import com.antivocale.app.data.ExternalImportProgressRelay
import com.antivocale.app.di.ApplicationScope
import com.antivocale.app.receiver.WipedImportRepairNotifications
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.util.CrashReporter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * TASK-971: the foreground surface for the Recover-triggered repair. Before
 * this service the repair ran on the bare application scope behind a
 * passive in-progress notice: a background kill minutes into a
 * multi-gigabyte download lost the whole repair silently (the user learned
 * nothing until the NEXT cold start re-posted the wiped notice). The
 * service does three things the bare coroutine could not: [startForeground]
 * keeps the process alive through the download, the foreground notification
 * shows per-file progress (relayed from the importer through the DI-scoped
 * [ExternalImportProgressRelay]: the singleton repairer's reimport lambda
 * is fixed at DI time, so the relay is the one seam a second party can
 * listen on without rebuilding the graph), and a Cancel action stops the
 * job honestly (the next cold start's scan re-offers the wipe).
 *
 * Starts ONLY from the user's Recover tap (the maintainer's
 * no-background-gigabytes decision): the receiver starts this service and
 * nothing else ever does; no startup path reaches it. START_NOT_STICKY on
 * purpose: a killed repair must NOT silently restart gigabytes; the next
 * cold start re-posts the wiped notice and the user decides again.
 *
 * The single-flight discipline moves here from the receiver: the service
 * refuses a second concurrent run ([repairJob] != null), which covers the
 * double-tap the repairer's per-instance mutex covered when the receiver
 * owned the run; the repairer itself stays the AppModule singleton (the
 * startup capture/scan paths share it).
 */
@AndroidEntryPoint
class WipedImportRepairService : Service() {

    @Inject lateinit var repairer: ExternalImportRepairer
    @Inject lateinit var progressRelay: ExternalImportProgressRelay
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    private var repairJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * TASK-1032 A: the startId of the most recent delivery; teardown stops
     * are conditional on it (the InferenceService comment documents the
     * ActiveServices mechanics), so a startForegroundService delivery
     * landing while the repair tears down owns the service instead of dying
     * to an unconditional stop.
     */
    @Volatile private var lastStartId = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> {
                repairJob?.cancel()
                // The cancel PendingIntent uses getService, so no
                // startForeground window is owed; the defensive call below
                // costs nothing and keeps us legal if the platform ever
                // requires it. stopSelf drops the foreground notice at
                // once; the next cold start's scan re-offers the wipe.
                startForegroundCompat(WipedImportRepairNotifications.progressNotification(this))
                stopSelf(lastStartId)
                return START_NOT_STICKY
            }
            else -> {
                // A duplicate start while a repair runs: the foreground
                // notification is already live, but the fresh
                // startForegroundService delivery re-arms the fgRequired
                // expectation, so startForeground MUST run again before any
                // early return (skipping it crashes the process with
                // ForegroundServiceDidNotStartInTimeException; code review
                // 2026-11-10). The call is idempotent.
                if (repairJob?.isActive == true) {
                    startForegroundCompat(WipedImportRepairNotifications.progressNotification(this))
                    return START_NOT_STICKY
                }
                if (!startForegroundCompat(
                        WipedImportRepairNotifications.progressNotification(this))) {
                    // Twice-rejected start: the helper already stopped us;
                    // do NOT launch the repair into a tearing-down service
                    // (the ragged-edge fix from the simplify round).
                    return START_NOT_STICKY
                }
                repairJob = applicationScope.launch(Dispatchers.IO) {
                    val result = try {
                        repairer.repairAll()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Wiped-import repair failed", e)
                        null
                    }
                    // DETACH before posting: the system cancels whatever
                    // occupies the foreground id at teardown, so an outcome
                    // posted on id 2504 and then stopSelf()ed would be wiped
                    // the moment the service stopped. DETACH leaves the
                    // posted notice on the shade; the family's outcome
                    // states are the terminal surface.
                    stopForeground(STOP_FOREGROUND_DETACH)
                    runCatching {
                        WipedImportRepairNotifications.postOutcome(
                            this@WipedImportRepairService,
                            attempted = result?.attempted ?: 1,
                            repairedCount = result?.repairedIds?.size ?: 0,
                            networkFailedCount = result?.networkFailedIds?.size ?: 0,
                            sourceFailedCount = result?.sourceFailedIds?.size ?: 0)
                    }.onFailure { e -> Log.w(TAG, "Failed to post the outcome", e) }
                    stopSelf(lastStartId)
                }
                return START_NOT_STICKY
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The relay forwards the importer's per-file progress; filenames and
        // counts are locale-free, so no per-locale string is needed.
        progressRelay.forward = { fileIndex, fileCount, fileName, _, _ ->
            val nm = getSystemService(NotificationManager::class.java)
            WipedImportRepairNotifications.postProgress(
                this@WipedImportRepairService, "${fileIndex + 1}/$fileCount  $fileName")
        }
    }

    /**
     * TASK-971: dataSync services are hard-capped (6h on Android 15+) and
     * the timeout must end in the designed stop, not a system crash: cancel
     * the job (the progress hook makes the cancel prompt), detach the
     * notice (a cold start re-offers the wipe), stop.
     */
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "dataSync timeout reached; cancelling the repair")
        repairJob?.cancel()
        stopForeground(STOP_FOREGROUND_DETACH)
        // TASK-1032 A: conditional stop, same discipline as the other
        // services' teardown tails.
        stopSelf(lastStartId)
    }

    /**
     * TASK-1032 C: Android 15+ delivers the dataSync timeout to the
     * TWO-argument form; without this override the designed stop above
     * never runs and the budget exhaustion ends in the system's crash
     * instead (the review's read of the Android 15 behavior-change note).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        onTimeout(startId)
    }

    override fun onDestroy() {
        progressRelay.forward = null
        repairJob?.cancel()
        super.onDestroy()
    }

    /**
     * TASK-1051: the service owns its channel. The receiver's post() was
     * the ONLY creator of MODEL_REPAIR, so a channel lost to a reinstall
     * or an OEM channel cleanup left the foreground start holding a
     * notification on an unregistered id, which surfaces as
     * CannotPostForegroundServiceNotificationException (the Android 16
     * Vivo report; a plain notify() would just drop). The per-delivery
     * create() is stronger than the sibling onCreate precedent
     * (ExtractionService): it also covers an OEM channel deletion
     * mid-lifetime. A REJECTED notification retries once with the minimal
     * form; if that too is rejected the service stops itself and returns
     * false so the caller skips its own work (the normal-start arm must
     * not launch a multi-GB repair into a service already tearing down;
     * there the wipe notice stays and a re-tap restarts it). The stop on
     * a REQUIRED delivery is a DESIGNED stop with attribution (the
     * CrashReporter report; the TASK-1014 lesson that Log.w alone proved
     * unattributable), not a guaranteed crash avoidance: on ROMs that
     * still enforce the fgRequired promise the system may raise the
     * timeout exception anyway. On the duplicate-start arm (a repair
     * already live) the stop cancels the repair at onDestroy and the
     * shade empties until the next cold-start scan re-offers the wipe;
     * that edge is documented rather than bridged, because re-posting the
     * actionable notice needs a suspend scan from this synchronous arm.
     */
    private fun startForegroundCompat(notification: Notification): Boolean {
        AppNotificationChannel.MODEL_REPAIR.create(this)
        return runCatching {
            foregroundStart(notification)
        }.recoverCatching {
            Log.w(TAG, "Foreground start rejected; retrying with the minimal form", it)
            // Wrapped: a throw from the reporting infra here would convert
            // the retry into a failure (the InferenceService outside-the-
            // catch placement, same contract).
            runCatching { CrashReporter.report(it, "WipedImportRepairService.startForeground") }
            foregroundStart(WipedImportRepairNotifications.minimalProgressNotification(this))
        }.onFailure {
            Log.e(TAG, "Foreground start rejected twice; stopping (the wipe notice stays)", it)
            stopSelf(lastStartId)
        }.isSuccess
    }

    /**
     * Test seam (the InferenceService.foregroundPromotion precedent):
     * production always calls the real startForeground; tests substitute a
     * recorder or a throwing form to drive the rejection arm.
     */
    internal var foregroundStart: (Notification) -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                WipedImportRepairNotifications.NOTIFICATION_ID,
                it,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(WipedImportRepairNotifications.NOTIFICATION_ID, it)
        }
    }

    companion object {
        private const val TAG = "WipedImportRepairService"
        const val ACTION_CANCEL = "com.antivocale.app.CANCEL_WIPED_IMPORT_REPAIR"

        /** TASK-1023: the ONE repair-start intent (explicit component: the
         *  service is not exported). The notification's Recover
         * PendingIntent, the receiver's stale-notice router arm, and the
         *  Models-tab banner's Recover button all start the same run. */
        fun repairIntent(context: android.content.Context): android.content.Intent =
            android.content.Intent(
                context, com.antivocale.app.service.WipedImportRepairService::class.java)
    }
}
