package com.antivocale.app.service

import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.util.ProgressThrottler

/**
 * Tests that InferenceService creates the correct notification channels in onCreate().
 * These tests target the CURRENT production code and establish a baseline for the
 * notification channel refactor. The TASK-266 tests below cover the progress-notification
 * throttle: at most one progress-bar post per ~1s window, terminal notifications never
 * suppressed, chunk-nav rendering left ungated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class InferenceServiceNotificationTest {

    @Test
    fun `onCreate registers inference_channel with IMPORTANCE_LOW and no badge`() {
        val controller = Robolectric.buildService(InferenceService::class.java)
        val service = controller.create().get()

        val notificationManager = service.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel("inference_channel")

        assertNotNull("inference_channel should be created", channel)
        assertEquals(
            "inference_channel should have IMPORTANCE_LOW",
            NotificationManager.IMPORTANCE_LOW,
            channel.importance
        )
        assertEquals(
            "inference_channel should not show badge",
            false,
            channel.canShowBadge()
        )
    }

    @Test
    fun `onCreate registers transcription_result_channel with IMPORTANCE_HIGH and badge`() {
        val controller = Robolectric.buildService(InferenceService::class.java)
        val service = controller.create().get()

        val notificationManager = service.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel("transcription_result_channel")

        assertNotNull("transcription_result_channel should be created", channel)
        assertEquals(
            "transcription_result_channel should have IMPORTANCE_HIGH",
            NotificationManager.IMPORTANCE_HIGH,
            channel.importance
        )
        assertEquals(
            "transcription_result_channel should show badge",
            true,
            channel.canShowBadge()
        )
    }

    @Test
    fun `onCreate registers exactly 2 notification channels`() {
        val controller = Robolectric.buildService(InferenceService::class.java)
        val service = controller.create().get()

        val notificationManager = service.getSystemService(NotificationManager::class.java)
        val channels = notificationManager.notificationChannels

        assertEquals(
            "InferenceService should create exactly 2 notification channels",
            2,
            channels.size
        )
    }

    /**
     * Verifies the subtitle-track-index intent extra round-trips through the constant
     * used by both [InferenceService.onStartCommand] (read) and the share flow (write).
     * This locks the contract for Task 5/6 wiring without requiring a Hilt-injected
     * orchestrator (which a pure Robolectric service start would need).
     */
    @Test
    fun `EXTRA_SUBTITLE_TRACK_INDEX round-trips through an Intent`() {
        val intent = Intent().putExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, 3)

        assertEquals(
            "subtitle_track_index extra should round-trip",
            3,
            intent.getIntExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, -1)
        )
    }

    /**
     * Verifies PendingRequest defaults trackIndex to -1 (the "no subtitle track" sentinel)
     * so existing audio/text requests are unaffected by the new field.
     */
    @Test
    fun `PendingRequest defaults trackIndex to -1`() {
        val request = InferenceService.PendingRequest(
            taskId = "t1",
            requestType = "audio",
            prompt = "",
            filePath = "/tmp/x.mp4"
        )

        assertEquals(
            "PendingRequest.trackIndex should default to -1 when not provided",
            -1,
            request.trackIndex
        )
    }

    // ---- Progress-notification throttle (TASK-266) ----

    /**
     * Builds a created service whose progress throttle reads the given fake clock, so
     * throttle windows advance only when the test moves its captured time state (the
     * same seam idea as the orchestrator's throttleClock; no real time passes).
     */
    private fun serviceWithFakeThrottleClock(clock: () -> Long): InferenceService {
        val service = Robolectric.buildService(InferenceService::class.java).create().get()
        service.progressNotifyThrottler = ProgressThrottler(clock = clock)
        return service
    }

    /** The shadow for notification inspection, one place. */
    private fun notificationShadow(service: InferenceService) =
        Shadows.shadowOf(service.getSystemService(NotificationManager::class.java))

    /**
     * The shadow keeps ONE entry per notification id (latest post wins), so suppression
     * is asserted through the content still on display: a throttled-away tick leaves the
     * previous post's values in place.
     */
    private fun latestProgressOn(service: InferenceService): Int? =
        notificationShadow(service)
            .getNotification(InferenceService.NOTIFICATION_ID)
            ?.extras
            ?.getInt(NotificationCompat.EXTRA_PROGRESS)

    @Test
    fun `rapid onProgress ticks within one throttle window post at most once`() {
        var fakeNowMs = 1_000_000L
        val service = serviceWithFakeThrottleClock { fakeNowMs }

        repeat(5) { index ->
            service.onProgress("Transcribing", 10 + index, "ETA", 0, 0L, 0)
        }

        assertEquals(
            "only the first tick in the window may post (later ticks must not overwrite it)",
            10,
            latestProgressOn(service)
        )

        fakeNowMs += 1000L
        service.onProgress("Transcribing", 60, "ETA", 0, 0L, 0)
        assertEquals(
            "the first tick after the window elapses should post again",
            60,
            latestProgressOn(service)
        )
    }

    @Test
    fun `terminal error notification posts despite an exhausted throttle window`() {
        var fakeNowMs = 1_000_000L
        val service = serviceWithFakeThrottleClock { fakeNowMs }

        // Consume the current throttle window, then terminate in the same instant.
        service.onProgress("Transcribing", 10, "ETA", 0, 0L, 0)
        service.onError("task-1", "GENERIC", "boom", false, false, 0)

        assertEquals(
            "the progress tick inside the fresh window should have posted",
            10,
            latestProgressOn(service)
        )
        assertTrue(
            "the terminal error notification must post despite the consumed throttle window",
            notificationShadow(service)
                .allNotifications
                .any { it.channelId == InferenceService.RESULT_CHANNEL_ID }
        )
    }

    @Test
    fun `chunk-nav ticks with changing ETA still post inside one throttle window`() {
        var fakeNowMs = 1_000_000L
        val service = serviceWithFakeThrottleClock { fakeNowMs }

        // A smooth-progress tick consumes the window before chunk nav activates.
        service.onProgress("Transcribing", 20, "ETA 5s", 0, 0L, 0)
        // Activate chunk navigation with the first completed chunk of a 2-chunk job.
        service.onInterimResult("partial", "partial", "", 0, "hello chunk", 2)
        // Ticks whose ETA changes the nav signature: each must still post (the nav path
        // keeps its signature-based dedup and is not gated by the time throttle).
        listOf("ETA 4s", "ETA 3s", "ETA 2s").forEach { eta ->
            service.onProgress("Transcribing", 30, eta, 0, 0L, 0)
        }

        val latest = notificationShadow(service)
            .getNotification(InferenceService.NOTIFICATION_ID)
        assertEquals(
            "the chunk view must be what is on display (progress out of 2 chunks)",
            2,
            latest?.extras?.getInt(NotificationCompat.EXTRA_PROGRESS_MAX)
        )
        assertTrue(
            "the last signature-changing tick must have posted despite the exhausted window",
            latest?.extras?.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT)
                ?.endsWith("ETA 2s") == true
        )
    }
}
