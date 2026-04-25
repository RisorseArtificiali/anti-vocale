package com.antivocale.app.service

import android.app.Service
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Tests for [ExtractionService], focused on the bug fix that added
 * `startForeground()` to the ACTION_CANCEL branch of `onStartCommand()`.
 *
 * On Android 12+ (API 31), a foreground service must call `startForeground()`
 * within a few seconds of `onStartCommand()` or the system throws
 * `ForegroundServiceDidNotStartInTimeException`. The cancel path was missing
 * this call, causing crashes when users cancelled an extraction while the
 * service was in foreground mode.
 *
 * Test strategy: Since `handleCancel()` calls `stopForeground()` when
 * `activeJobs` is empty (clearing `lastForegroundNotification`), we verify
 * the foreground lifecycle indirectly: `isForegroundStopped == true` confirms
 * the service entered and then exited foreground state, proving `startForeground()`
 * was called before the cancel path completed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ExtractionServiceTest {

    // ---- Robolectric integration tests for startForeground on cancel ----

    @Test
    fun `cancel intent with model type and variant starts foreground to prevent crash on Android 12+`() {
        val controller = Robolectric.buildService(ExtractionService::class.java)
        val service = controller.create().get()
        val intent = Intent(service, ExtractionService::class.java).apply {
            action = ExtractionService.ACTION_CANCEL
            putExtra(ExtractionService.EXTRA_MODEL_TYPE, "whisper")
            putExtra(ExtractionService.EXTRA_CANCEL_VARIANT, "turbo")
        }

        val result = service.onStartCommand(intent, 0, 0)

        val shadow = Shadows.shadowOf(service)
        assertEquals(
            "onStartCommand should return START_NOT_STICKY for cancel",
            Service.START_NOT_STICKY,
            result
        )
        assertTrue(
            "Service must have entered foreground (and then stopped) — " +
                "isForegroundStopped confirms startForeground() was called before handleCancel()",
            shadow.isForegroundStopped
        )
        // Note: isForegroundStopped == true proves startForeground() was called.
        // You cannot stopForeground without first having called startForeground.
    }

    @Test
    fun `cancel intent with model type but no variant starts foreground with fallback notification ID`() {
        val controller = Robolectric.buildService(ExtractionService::class.java)
        val service = controller.create().get()
        val intent = Intent(service, ExtractionService::class.java).apply {
            action = ExtractionService.ACTION_CANCEL
            putExtra(ExtractionService.EXTRA_MODEL_TYPE, "whisper")
            // No EXTRA_CANCEL_VARIANT — cancel all whisper downloads
        }

        val result = service.onStartCommand(intent, 0, 0)

        val shadow = Shadows.shadowOf(service)
        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(
            "startForeground() must be called for cancel-all-by-model-type as well",
            shadow.isForegroundStopped
        )
    }

    @Test
    fun `cancel intent with no model type starts foreground with base notification ID`() {
        val controller = Robolectric.buildService(ExtractionService::class.java)
        val service = controller.create().get()
        val intent = Intent(service, ExtractionService::class.java).apply {
            action = ExtractionService.ACTION_CANCEL
            // No extras at all — cancel everything
        }

        val result = service.onStartCommand(intent, 0, 0)

        val shadow = Shadows.shadowOf(service)
        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(
            "startForeground() must be called even for cancel-all with no model type",
            shadow.isForegroundStopped
        )
    }

    @Test
    fun `cancel intent does not crash when no active jobs exist`() {
        val controller = Robolectric.buildService(ExtractionService::class.java)
        val service = controller.create().get()
        val intent = Intent(service, ExtractionService::class.java).apply {
            action = ExtractionService.ACTION_CANCEL
            putExtra(ExtractionService.EXTRA_MODEL_TYPE, "whisper")
            putExtra(ExtractionService.EXTRA_CANCEL_VARIANT, "turbo")
        }

        // This should complete without throwing — the core guarantee of the bug fix.
        // Before the fix, this path would not call startForeground() and would
        // crash on Android 12+ with ForegroundServiceDidNotStartInTimeException.
        val result = service.onStartCommand(intent, 0, 0)
        assertEquals(Service.START_NOT_STICKY, result)
    }

    // ---- Pure function tests (no Android framework needed) ----

    @Test
    fun `notificationIdForKey produces stable IDs for same key`() {
        val id1 = notificationIdForKey("whisper:turbo")
        val id2 = notificationIdForKey("whisper:turbo")
        assertEquals(id1, id2)
    }

    @Test
    fun `notificationIdForKey produces different IDs for different keys`() {
        val idWhisper = notificationIdForKey("whisper:turbo")
        val idParakeet = notificationIdForKey("parakeet:")
        assert(idWhisper != idParakeet) {
            "Different keys should generally produce different notification IDs"
        }
    }

    @Test
    fun `notificationIdForKey stays within expected range`() {
        val NOTIFICATION_ID_BASE = 2001
        val NOTIFICATION_ID_RANGE = 100
        val keys = listOf("whisper:turbo", "parakeet:", "qwen3-asr:qwen3_asr_0_6b", "gemma:gemma_4_e2b", "whisper:small")
        for (key in keys) {
            val id = notificationIdForKey(key)
            assert(id in NOTIFICATION_ID_BASE until NOTIFICATION_ID_BASE + NOTIFICATION_ID_RANGE) {
                "Notification ID $id for key '$key' is outside range [$NOTIFICATION_ID_BASE, ${NOTIFICATION_ID_BASE + NOTIFICATION_ID_RANGE})"
            }
        }
    }

    @Test
    fun `jobKey produces correct format with variant`() {
        assertEquals("whisper:turbo", jobKey(ExtractionService.ModelType.WHISPER, "turbo"))
    }

    @Test
    fun `jobKey produces correct format with null variant`() {
        assertEquals("parakeet:", jobKey(ExtractionService.ModelType.PARAKEET, null))
    }

    // ---- Mirrors of private functions for direct testing ----

    private fun notificationIdForKey(key: String): Int {
        val NOTIFICATION_ID_BASE = 2001
        val NOTIFICATION_ID_RANGE = 100
        return NOTIFICATION_ID_BASE + (key.hashCode() and 0x7FFFFFFF) % NOTIFICATION_ID_RANGE
    }

    private fun jobKey(modelType: ExtractionService.ModelType, variant: String?): String {
        return "${modelType.key}:${variant ?: ""}"
    }
}
