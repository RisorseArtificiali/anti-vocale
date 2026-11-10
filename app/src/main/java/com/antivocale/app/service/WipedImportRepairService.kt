package com.antivocale.app.service

import android.app.Notification
import android.os.IBinder
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antivocale.app.R
import com.antivocale.app.data.ExternalImportRepairer
import com.antivocale.app.data.ExternalImportProgressRelay
import com.antivocale.app.di.ApplicationScope
import com.antivocale.app.receiver.WipedImportRepairNotifications
import com.antivocale.app.util.AppNotificationChannel
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
 * purpose: a killed repair must NOT silently restart gigabytes - the next
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                repairJob?.cancel()
                // The cancel PendingIntent uses getService, so no
                // startForeground window is owed; the defensive call below
                // costs nothing and keeps us legal if the platform ever
                // requires it. stopSelf drops the foreground notice at
                // once; the next cold start's scan re-offers the wipe.
                startForegroundCompat(WipedImportRepairNotifications.progressNotification(this))
                stopSelf()
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
                startForegroundCompat(WipedImportRepairNotifications.progressNotification(this))
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
                    stopSelf()
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
        stopSelf()
    }

    override fun onDestroy() {
        progressRelay.forward = null
        repairJob?.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                WipedImportRepairNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(WipedImportRepairNotifications.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "WipedImportRepairService"
        const val ACTION_CANCEL = "com.antivocale.app.CANCEL_WIPED_IMPORT_REPAIR"
    }
}
