package com.antivocale.app.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.local.AppDatabase
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.data.local.FailureContextJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * TASK-684 (GH #109): the cold-start recovery pass end to end: rows in, the
 * classifier's verdict out, the honest close on every row, the notification
 * with the re-run only for the proven suspension. The exit record is injected
 * through Robolectric's ShadowActivityManager (the only way to rehearse a
 * process death inside a live process).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SuspendedRunRecoveryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var dao: LogDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.logDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insert(taskId: String, status: String) {
        dao.insert(LogEntity(
            id = "id-$taskId", timestamp = 1L, taskId = taskId,
            type = "AUDIO", status = status, filePath = "/shared_audio/long.wav",
            prompt = "", sourcePackageName = "org.telegram.messenger",
        ))
    }

    /** Injects the previous process's death as the exit-info API would report it. */
    private fun death(reason: Int, timestampMs: Long) {
        val am = context.getSystemService(ActivityManager::class.java)
        Shadows.shadowOf(am).addApplicationExitInfo(
            org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setReason(reason)
                .setTimestamp(timestampMs)
                .setDescription("OEM killer")
                .build())
    }

    private fun shadowNotificationManager() =
        Shadows.shadowOf(context.getSystemService(android.app.NotificationManager::class.java))

    @Test
    fun `a proven suspension closes honestly with marker and notification`() = runBlocking {
        insert("run-1", "PROCESSING")
        insert("queued-1", "QUEUED")
        RunHeartbeat.touch(context, "run-1")
        // The death sits far in the future of the seeded tick only to keep the
        // fixture inside one clock read: the classifier sees exactly the real
        // shape, a heartbeat silent long before the death.
        death(ApplicationExitInfo.REASON_SIGNALED,
            System.currentTimeMillis() + 10 * SuspendedRunClassifier.SUSPENDED_MIN_GAP_MS)

        SuspendedRunRecovery.closeInterruptedRuns(context, dao, wasOOMCrash = false)

        val suspended = dao.getByTaskId("run-1")!!
        assertEquals("ERROR", suspended.status)
        assertNotNull(suspended.errorMessage)
        val failure = FailureContextJson.fromJson(suspended.failureContext)!!
        assertEquals(SuspendedRunRecovery.SUSPENDED_ERROR_CLASS, failure.errorClass)
        assertNotNull(failure.suspendedMs)
        // The queued row never ticked: it can only be interrupted.
        val queued = dao.getByTaskId("queued-1")!!
        assertEquals("ERROR", queued.status)
        assertEquals("Interrupted by app restart", queued.errorMessage)
        assertNull(queued.failureContext)
        // Single-use evidence consumed; the honest notification is up.
        assertNull(RunHeartbeat.read(context))
        val posted = shadowNotificationManager()
            .getNotification(SuspendedRunRecovery.NOTIFICATION_ID)
        assertNotNull("the suspension notification must be posted", posted)
        // TASK-684: the queued row IS the generic batch (sweep rowcount 1);
        // the quiet summary is up too, carrying the exact count.
        val summary = shadowNotificationManager()
            .getNotification(SuspendedRunRecovery.INTERRUPTED_NOTIFICATION_ID)
        assertNotNull("the interrupted-runs summary must be posted", summary)
        assertTrue(summary.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("1"))
    }

    @Test
    fun `a fresh heartbeat at death is no suspension, the 2026-09-25 class`() = runBlocking {
        insert("run-1", "PROCESSING")
        RunHeartbeat.touch(context, "run-1")
        death(ApplicationExitInfo.REASON_SIGNALED,
            System.currentTimeMillis() + RunHeartbeat.TICK_MS)

        SuspendedRunRecovery.closeInterruptedRuns(context, dao, wasOOMCrash = false)

        // Alive when killed: honest generic close, no suspension claim, no
        // suspension notification, no marker. The generic class DOES get its
        // quiet summary: on by default (the preference gates it).
        val row = dao.getByTaskId("run-1")!!
        assertEquals("ERROR", row.status)
        assertEquals("Interrupted by app restart", row.errorMessage)
        assertNull(row.failureContext)
        assertNull("no suspension notification may be posted", shadowNotificationManager()
            .getNotification(SuspendedRunRecovery.NOTIFICATION_ID))
        assertNotNull("the generic interrupted summary is on by default", shadowNotificationManager()
            .getNotification(SuspendedRunRecovery.INTERRUPTED_NOTIFICATION_ID))
    }

    @Test
    fun `the generic summary respects the user opt-out without touching the close`() = runBlocking {
        insert("run-1", "PROCESSING")
        RunHeartbeat.touch(context, "run-1")
        death(ApplicationExitInfo.REASON_SIGNALED,
            System.currentTimeMillis() + RunHeartbeat.TICK_MS)

        SuspendedRunRecovery.closeInterruptedRuns(
            context, dao, wasOOMCrash = false, notifyGenericInterrupted = false)

        // The opt-out silences only the summary: every row still closes.
        val row = dao.getByTaskId("run-1")!!
        assertEquals("ERROR", row.status)
        assertEquals("Interrupted by app restart", row.errorMessage)
        assertNull(shadowNotificationManager()
            .getNotification(SuspendedRunRecovery.INTERRUPTED_NOTIFICATION_ID))
    }

    @Test
    fun `no heartbeat means no label even with a death on record`() = runBlocking {
        insert("run-1", "PROCESSING")
        death(ApplicationExitInfo.REASON_SIGNALED,
            System.currentTimeMillis() + 10 * SuspendedRunClassifier.SUSPENDED_MIN_GAP_MS)

        SuspendedRunRecovery.closeInterruptedRuns(context, dao, wasOOMCrash = false)

        val row = dao.getByTaskId("run-1")!!
        assertEquals("ERROR", row.status)
        assertEquals("Interrupted by app restart", row.errorMessage)
        assertNull(row.failureContext)
    }

    @Test
    fun `the OOM crash variant keeps its advice reason`() = runBlocking {
        insert("run-1", "PROCESSING")
        death(ApplicationExitInfo.REASON_LOW_MEMORY,
            System.currentTimeMillis() + 10 * SuspendedRunClassifier.SUSPENDED_MIN_GAP_MS)

        SuspendedRunRecovery.closeInterruptedRuns(context, dao, wasOOMCrash = true)

        // NativeCrashDetector's lineage wins: the OOM advice, not a suspension.
        val row = dao.getByTaskId("run-1")!!
        assertEquals("ERROR", row.status)
        assertTrue(row.errorMessage!!.startsWith("Interrupted by app restart: out of memory"))
        assertNull(row.failureContext)
    }

    @Test
    fun `a clean cold start with no orphans posts nothing and touches nothing`() = runBlocking {
        SuspendedRunRecovery.closeInterruptedRuns(context, dao, wasOOMCrash = false)
        assertEquals(0, dao.getNonTerminal().size)
        assertNull("no suspension notification may be posted", shadowNotificationManager()
            .getNotification(SuspendedRunRecovery.NOTIFICATION_ID))
        assertNull("an empty batch posts no summary", shadowNotificationManager()
            .getNotification(SuspendedRunRecovery.INTERRUPTED_NOTIFICATION_ID))
    }
}
