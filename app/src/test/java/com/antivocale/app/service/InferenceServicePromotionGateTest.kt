package com.antivocale.app.service

import android.app.NotificationManager
import android.content.Intent
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.transcription.TranscriptionBackendManager
import com.antivocale.app.transcription.TranscriptionOrchestrator
import com.antivocale.app.util.AppNotificationChannel
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * TASK-1030: the promotion-first invariant, restored on the delivery path.
 *
 * Mechanism C (the 1.17.0 recurrence): TASK-1010's deferred re-arm drain
 * ran `unloadActiveBackend()` on the intake thread BEFORE the delivery
 * promotion, so a REQUIRED delivery could spend its whole fgRequired window
 * in native model teardown. The interleaving itself is the assertion: the
 * drain must observe the promotion already happened.
 *
 * Mechanism A (the TASK-1014 recorded-but-unhandled arm): a promotion that
 * THROWS on an EMPTY instance leaves a REQUIRED delivery unsatisfiable; the
 * system raises the same exception whatever the app does, so the failure arm
 * preserves the request through the enqueue path's fallback notification
 * (whose tap is a user-initiated start) and stops the empty service. On a
 * LIVE batch the delivery is not doomed (AOSP arms the per-delivery timeout
 * only when the service is not already foreground) and 1.17.0's
 * report-and-carry-on stands. Control deliveries carry no request identity
 * and must not become notifications.
 *
 * Determinism: the [InferenceService.foregroundPromotion] seam records and
 * still performs the real promotion, the mocked backend manager records the
 * drain, the preserve/stop arm runs synchronously when nothing is tracked
 * (the fresh-service case), and every assertion is read after
 * onStartCommand returns. No sleeps, no polling (the teardown-race test's
 * rule).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class InferenceServicePromotionGateTest {

    /** The reflection-injection seam the teardown race test established. */
    private fun inject(target: Any, field: String, value: Any?) {
        val f = target.javaClass.getDeclaredField(field)
        f.isAccessible = true
        f.set(target, value)
    }

    /** The queue depth increments synchronously inside onStartCommand
     * (before the async drain touches it), so this is the deterministic
     * enqueue-vs-preserve discriminator; the logQueued coVerify alone can
     * race its Dispatchers.IO launch (fable review). */
    private fun pendingCountOf(service: InferenceService): Int {
        val f = InferenceService::class.java.getDeclaredField("pendingCount")
        f.isAccessible = true
        return (f.get(service) as java.util.concurrent.atomic.AtomicInteger).get()
    }

    /** The fallback notification is the only traffic on the tasker_fallback
     * channel, so the channel identifies it without depending on the
     * sequential band id the slot allocator hands out. */
    private fun fallbackNotifications(service: InferenceService) =
        Shadows.shadowOf(service.getSystemService(NotificationManager::class.java))
            .allNotifications
            .filter { it.channelId == AppNotificationChannel.TASKER_FALLBACK.id }

    @Test
    fun `a pending re-arm drains only after the delivery promotion (mechanism C)`() {
        val service = Robolectric.buildService(InferenceService::class.java).create().get()
        // Main thread only (the delivery and the drain both run on it), so a
        // plain list records the interleaving.
        val order = mutableListOf<String>()
        service.foregroundPromotion = { notification ->
            order += "promote"
            service.startForeground(InferenceService.NOTIFICATION_ID, notification)
        }
        val backendManager = mockk<TranscriptionBackendManager>(relaxed = true)
        every { backendManager.unloadActiveBackend() } answers { order += "unload" }
        inject(service, "backendManager", backendManager)

        InferenceService.requestRearm()
        // A control delivery is enough to reach the drain, and it keeps the
        // queue empty so no other machinery runs: only the interleaving
        // under test executes.
        service.onStartCommand(
            Intent(service, InferenceService::class.java).setAction(InferenceService.ACTION_CANCEL),
            0, 1)

        assertTrue("the deferred re-arm must still drain at intake", "unload" in order)
        assertEquals(
            "the promotion must precede the re-arm drain (the 1.17.0 crash recurrence)",
            listOf("promote", "unload"),
            order,
        )
    }

    @Test
    fun `a failed promotion preserves a request delivery and queues nothing (mechanism A)`() {
        val service = Robolectric.buildService(InferenceService::class.java).create().get()
        service.foregroundPromotion = { throw SecurityException("simulated FGS restriction") }
        val orchestrator = mockk<TranscriptionOrchestrator>(relaxed = true)
        inject(service, "orchestrator", orchestrator)

        val intent = Intent(service, InferenceService::class.java).apply {
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, "gate-1")
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "audio")
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, "/nonexistent/gate.wav")
        }
        service.onStartCommand(intent, 0, 1)

        assertTrue(
            "the failed delivery must be preserved via the fallback notification",
            fallbackNotifications(service).isNotEmpty(),
        )
        assertEquals("nothing may be queued on the doomed instance", 0, pendingCountOf(service))
        // A queued request would have logged its History row at enqueue
        // time; the IO-launched logQueued can lag the assertion, so this is
        // corroborating rather than load-bearing evidence.
        coVerify(exactly = 0) { orchestrator.logQueued(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue("the empty service must stop itself", Shadows.shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a failed promotion does not preserve control deliveries (guard)`() {
        val service = Robolectric.buildService(InferenceService::class.java).create().get()
        service.foregroundPromotion = { throw SecurityException("simulated FGS restriction") }

        service.onStartCommand(
            Intent(service, InferenceService::class.java).setAction(InferenceService.ACTION_CANCEL),
            0, 1)

        assertFalse(
            "a control delivery must not become a fallback notification",
            fallbackNotifications(service).isNotEmpty(),
        )
        assertTrue("the empty service must stop itself", Shadows.shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a failed promotion on a live batch carries on like 1_17_0 (fable review)`() {
        val service = Robolectric.buildService(InferenceService::class.java).create().get()
        service.foregroundPromotion = { throw SecurityException("simulated FGS restriction") }
        // A batch mid-inference: batchIdle() false via the companion
        // transcribing flag (the drain sets it before touching audio), the
        // same field the public isTranscribing flow exposes. The drain gate
        // is pre-armed so the enqueue's processQueue launch stays out: a
        // REAL drain here would outlive the test and leave the JVM-shared
        // transcribing flag set for whichever test runs next.
        val flag = InferenceService::class.java.getDeclaredField("_isTranscribing")
        flag.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = flag.get(null) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
        flow.value = true
        val gate = InferenceService::class.java.getDeclaredField("drainEntered")
        gate.isAccessible = true
        (gate.get(service) as java.util.concurrent.atomic.AtomicBoolean).set(true)
        try {
            val intent = Intent(service, InferenceService::class.java).apply {
                putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, "gate-live")
                putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "audio")
                putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, "/nonexistent/live.wav")
            }
            service.onStartCommand(intent, 0, 1)

            // AOSP arms the per-delivery fgRequired timeout only when the
            // service is not already foreground: a live batch means an
            // earlier promotion answered it, so the delivery is not doomed
            // and must be enqueued, not diverted to a tap-to-run
            // notification (which would also drop any concurrent cancel or
            // nav tap).
            assertEquals("the delivery must be enqueued, not preserved", 1, pendingCountOf(service))
            assertTrue(
                "a live batch must not be diverted to a fallback notification",
                fallbackNotifications(service).isEmpty(),
            )
            assertFalse(
                "a live batch must not be stopped from the failed-promotion arm",
                Shadows.shadowOf(service).isStoppedBySelf,
            )
        } finally {
            flow.value = false
        }
    }
}
