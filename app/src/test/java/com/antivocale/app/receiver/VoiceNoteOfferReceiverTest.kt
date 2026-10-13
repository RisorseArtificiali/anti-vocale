package com.antivocale.app.receiver

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.antivocale.app.service.InferenceService
import com.antivocale.app.service.VoiceNoteOfferNotifier
import com.antivocale.app.util.AppNotificationChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * TASK-271: the offer's Transcribe action. The service-intent shape (the
 * share path's mirror) and the tap behavior reachable without Hilt: an
 * unresolvable file cancels the offer silently with no service start. The
 * enqueue leg resolves the WhatsApp path through the SAF tree grant. Under
 * plain Robolectric the generated Hilt_ base does inject (verified by the
 * passing cancel branch: an unset lateinit would skip the cancel), so the
 * cancel here is the ABSENT grant preference, not a broken seam; seeding
 * that preference plus a documents-provider shadow is the path to JVM
 * coverage of the resolved leg (backlog task; the tap flow itself is
 * device-proven by the 2026-10-13 trial).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceNoteOfferReceiverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun offerIntent(
        path: String,
        sender: String? = "Anna",
        owner: String? = "com.whatsapp",
    ): Intent = Intent(VoiceNoteOfferReceiver.ACTION_TRANSCRIBE).apply {
        putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, path)
        sender?.let { putExtra(InferenceService.EXTRA_SENDER_NAME, it) }
        owner?.let { putExtra(InferenceService.EXTRA_SOURCE_PACKAGE, it) }
    }

    @Test
    fun `buildServiceIntent mirrors the share path's audio shape`() {
        val serviceIntent = VoiceNoteOfferReceiver.buildServiceIntent(context, offerIntent("/n.opus"), "/local/n.opus")
        assertEquals(
            TaskerRequestReceiver.REQUEST_TYPE_AUDIO,
            serviceIntent.getStringExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE))
        assertEquals(
            "the service must receive the RESOLVED sandbox path",
            "/local/n.opus", serviceIntent.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH))
        assertTrue(
            "task id must carry the voicenote prefix: " +
                serviceIntent.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID),
            serviceIntent.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID)?.startsWith("voicenote_") == true)
        assertEquals("Anna", serviceIntent.getStringExtra(InferenceService.EXTRA_SENDER_NAME))
        assertEquals("com.whatsapp", serviceIntent.getStringExtra(InferenceService.EXTRA_SOURCE_PACKAGE))
        assertEquals(InferenceService.SOURCE_VOICE_NOTE, serviceIntent.getStringExtra(InferenceService.EXTRA_SOURCE))
        assertEquals(InferenceService::class.java.name, serviceIntent.component?.className)
    }

    @Test
    fun `buildServiceIntent omits the optional extras when absent`() {
        val serviceIntent = VoiceNoteOfferReceiver.buildServiceIntent(
            context, offerIntent("/n.opus", sender = null, owner = null), "/local/n.opus")
        assertNull(serviceIntent.getStringExtra(InferenceService.EXTRA_SENDER_NAME))
        assertNull(serviceIntent.getStringExtra(InferenceService.EXTRA_SOURCE_PACKAGE))
    }

    /** Posts a stub with the offer id so a cancel is observable. */
    private fun postOfferStub() {
        AppNotificationChannel.VOICE_NOTE_OFFER.create(context)
        context.getSystemService(NotificationManager::class.java).notify(
            VoiceNoteOfferNotifier.OFFER_NOTIFICATION_ID,
            NotificationCompat.Builder(context, AppNotificationChannel.VOICE_NOTE_OFFER.id)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build())
    }

    private fun offerActive(): Boolean = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.any { it.id == VoiceNoteOfferNotifier.OFFER_NOTIFICATION_ID }

    @Test
    fun `a tap whose file cannot be resolved cancels the offer silently without starting the service`() {
        // No tree-grant preference is saved, so resolve() returns null for
        // ANY path (the device trial covers the resolved leg): the offer
        // must cancel and nothing may start. The
        // cancel now rides the receiver's IO coroutine (goAsync), so the
        // test waits for it instead of asserting synchronously.
        val file = java.io.File(context.cacheDir, "note.opus").apply { writeText("x") }
        postOfferStub()
        VoiceNoteOfferReceiver().onReceive(context, offerIntent(file.absolutePath))
        assertNull(shadowOf(context.applicationContext as Application).nextStartedService)
        val deadline = System.currentTimeMillis() + 5_000
        while (offerActive() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("the stale offer must be cancelled", !offerActive())
    }
}
