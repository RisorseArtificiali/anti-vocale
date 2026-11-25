package com.antivocale.app.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import com.antivocale.app.util.AppNotificationChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * TASK-1051: the repair service owns its MODEL_REPAIR channel.
 *
 * The Android 16 Vivo report (CannotPostForegroundServiceNotificationException)
 * matched the one architectural hole a notification rejection can come from:
 * the RECEIVER's post() was the only creator of the channel, so the service
 * could reach startForeground with an unregistered channel id (a plain
 * notify() drops silently; startForeground throws). The tests pin the two
 * halves of the fix through the ACTION_CANCEL arm, which reaches
 * startForegroundCompat without touching the injected repairer or scope
 * (the reflection-injection seam pattern of InferenceServicePromotionGateTest
 * is not needed: this arm is null-safe on both).
 *
 * The rejection arm mirrors the TASK-1030/TASK-1014 discipline: retry once
 * with the minimal form, then stop inside the fgRequired window instead of
 * leaving a doomed expectation to the system's timeout crash.
 */
@RunWith(RobolectricTestRunner::class)
class WipedImportRepairServiceForegroundTest {

    @Test
    fun `the service registers the channel before the foreground start`() {
        val service = Robolectric.buildService(WipedImportRepairService::class.java).create().get()
        val nm = service.getSystemService(NotificationManager::class.java)
        // The hole the crash report exposed: with no prior receiver post,
        // the channel does not exist when the service starts.
        assertNull("precondition: no prior receiver post created the channel",
            nm.getNotificationChannel(AppNotificationChannel.MODEL_REPAIR.id))
        val attempts = mutableListOf<Notification>()
        service.foregroundStart = {
            // The order IS the fix: the channel must already be registered
            // when the foreground call is made, not just by the end.
            assertNotNull("the channel must exist BEFORE the foreground start",
                nm.getNotificationChannel(AppNotificationChannel.MODEL_REPAIR.id))
            attempts += it
        }

        service.onStartCommand(
            Intent(service, WipedImportRepairService::class.java)
                .setAction(WipedImportRepairService.ACTION_CANCEL), 0, 1)

        assertEquals("the foreground start ran exactly once", 1, attempts.size)
    }

    @Test
    fun `a rejected start retries the minimal form once and then stops itself`() {
        val service = Robolectric.buildService(WipedImportRepairService::class.java).create().get()
        val attempts = mutableListOf<Notification>()
        service.foregroundStart = { n ->
            attempts += n
            throw RuntimeException("simulated notification rejection")
        }

        service.onStartCommand(
            Intent(service, WipedImportRepairService::class.java)
                .setAction(WipedImportRepairService.ACTION_CANCEL), 0, 1)

        assertEquals("full form first, then exactly one minimal retry", 2, attempts.size)
        assertEquals("the retry is the minimal title-only form",
            service.getString(com.antivocale.app.R.string.wiped_imports_progress_title),
            attempts[1].extras.getCharSequence(Notification.EXTRA_TITLE))
        assertNull("the minimal form carries no actions", attempts[1].actions)
        assertTrue(
            "a twice-rejected start must stop inside the fgRequired window",
            Shadows.shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a twice-rejected start on the normal arm never launches the repair`() {
        val service = Robolectric.buildService(WipedImportRepairService::class.java).create().get()
        service.foregroundStart = { throw RuntimeException("simulated notification rejection") }
        // The repair must not start into a service the helper just stopped
        // (the ragged-edge finding): a mock proves the negative, and a fake
        // scope makes a regression (the launch running anyway) observable
        // synchronously instead of on Hilt's real application scope.
        val repairer = io.mockk.mockk<com.antivocale.app.data.ExternalImportRepairer>(relaxed = true)
        val f = service.javaClass.getDeclaredField("repairer")
        f.isAccessible = true
        f.set(service, repairer)
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.Job())
        val sf = service.javaClass.getDeclaredField("applicationScope")
        sf.isAccessible = true
        sf.set(service, scope)

        service.onStartCommand(
            Intent(service, WipedImportRepairService::class.java), 0, 1)

        io.mockk.coVerify(exactly = 0, timeout = 1_000) { repairer.repairAll() }
        assertTrue(
            "the twice-rejected normal arm must leave the service stopped",
            Shadows.shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `one rejection then a successful minimal retry proceeds with the repair`() {
        val service = Robolectric.buildService(WipedImportRepairService::class.java).create().get()
        val attempts = mutableListOf<Notification>()
        service.foregroundStart = { n ->
            attempts += n
            if (attempts.size == 1) throw RuntimeException("simulated notification rejection")
        }
        val repairer = io.mockk.mockk<com.antivocale.app.data.ExternalImportRepairer>(relaxed = true)
        val f = service.javaClass.getDeclaredField("repairer")
        f.isAccessible = true
        f.set(service, repairer)
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.Job())
        val sf = service.javaClass.getDeclaredField("applicationScope")
        sf.isAccessible = true
        sf.set(service, scope)

        service.onStartCommand(
            Intent(service, WipedImportRepairService::class.java), 0, 1)

        assertEquals("the full form was rejected, the minimal retry accepted", 2, attempts.size)
        assertEquals("the second attempt is the minimal title-only form",
            service.getString(com.antivocale.app.R.string.wiped_imports_progress_title),
            attempts[1].extras.getCharSequence(Notification.EXTRA_TITLE))
        io.mockk.coVerify(exactly = 1, timeout = 1_000) { repairer.repairAll() }
        // No stopped-state assert here, on purpose: with the Unconfined fake
        // scope the instant mock repair COMPLETES inside onStartCommand and
        // the service then stops itself honestly. The pin is the recovered
        // start PROCEEDING (two attempts, minimal form, repair launched);
        // the twice-rejected stop is pinned by the previous test.
    }
}
