package com.antivocale.app.util

import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [AppNotificationChannel] enum.
 *
 * Verifies that each enum entry preserves the original channel configuration
 * and that the [AppNotificationChannel.create] method correctly registers channels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class NotificationChannelHelperTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    // ---- Property correctness tests ----

    @Test
    fun `INFERENCE has correct id and properties`() {
        assertEquals("inference_channel", AppNotificationChannel.INFERENCE.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, AppNotificationChannel.INFERENCE.importance)
        assertEquals(false, AppNotificationChannel.INFERENCE.showBadge)
    }

    @Test
    fun `TRANSCRIPTION_RESULT has correct id and properties`() {
        assertEquals("transcription_result_channel", AppNotificationChannel.TRANSCRIPTION_RESULT.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, AppNotificationChannel.TRANSCRIPTION_RESULT.importance)
        assertEquals(true, AppNotificationChannel.TRANSCRIPTION_RESULT.showBadge)
    }

    @Test
    fun `EXTRACTION has correct id and properties`() {
        assertEquals("extraction_channel", AppNotificationChannel.EXTRACTION.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, AppNotificationChannel.EXTRACTION.importance)
        assertEquals(false, AppNotificationChannel.EXTRACTION.showBadge)
    }

    @Test
    fun `TASKER_FALLBACK has correct id and properties`() {
        assertEquals("tasker_fallback_channel", AppNotificationChannel.TASKER_FALLBACK.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, AppNotificationChannel.TASKER_FALLBACK.importance)
        assertEquals(true, AppNotificationChannel.TASKER_FALLBACK.showBadge)
    }

    @Test
    fun `INTERRUPTED_RUNS has correct id and properties`() {
        assertEquals("interrupted_runs_channel", AppNotificationChannel.INTERRUPTED_RUNS.id)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, AppNotificationChannel.INTERRUPTED_RUNS.importance)
        assertEquals(true, AppNotificationChannel.INTERRUPTED_RUNS.showBadge)
    }

    @Test
    fun `all channel IDs are unique`() {
        val ids = AppNotificationChannel.entries.map { it.id }
        assertEquals(
            "All channel IDs should be unique",
            ids.size,
            ids.toSet().size
        )
    }

    @Test
    fun `enum has exactly 5 entries`() {
        assertEquals(
            "AppNotificationChannel should have exactly 5 entries",
            5,
            AppNotificationChannel.entries.size
        )
    }

    @Test
    fun `string resource IDs are correctly assigned`() {
        for (entry in AppNotificationChannel.entries) {
            assertTrue(
                "${entry.name} nameResId should be non-zero",
                entry.nameResId != 0
            )
            assertTrue(
                "${entry.name} descriptionResId should be non-zero",
                entry.descriptionResId != 0
            )
            assertTrue(
                "${entry.name} nameResId and descriptionResId should differ",
                entry.nameResId != entry.descriptionResId
            )
        }
    }

    // ---- create() integration tests (uses Robolectric for real NotificationChannel) ----

    @Test
    fun `create registers INFERENCE channel with correct id and importance`() {
        AppNotificationChannel.INFERENCE.create(context)

        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel("inference_channel")

        assertTrue("Channel should exist", channel != null)
        assertEquals("inference_channel", channel!!.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(false, channel.canShowBadge())
    }

    @Test
    fun `create registers TRANSCRIPTION_RESULT channel with correct id and importance`() {
        AppNotificationChannel.TRANSCRIPTION_RESULT.create(context)

        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel("transcription_result_channel")

        assertTrue("Channel should exist", channel != null)
        assertEquals("transcription_result_channel", channel!!.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(true, channel.canShowBadge())
    }

    @Test
    fun `create registers EXTRACTION channel with correct id and importance`() {
        AppNotificationChannel.EXTRACTION.create(context)

        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel("extraction_channel")

        assertTrue("Channel should exist", channel != null)
        assertEquals("extraction_channel", channel!!.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(false, channel.canShowBadge())
    }

    @Test
    fun `create registers TASKER_FALLBACK channel with correct id and importance`() {
        AppNotificationChannel.TASKER_FALLBACK.create(context)

        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel("tasker_fallback_channel")

        assertTrue("Channel should exist", channel != null)
        assertEquals("tasker_fallback_channel", channel!!.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(true, channel.canShowBadge())
    }
}
