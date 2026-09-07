package com.antivocale.app.receiver

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
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
import org.robolectric.annotation.Config

/**
 * The dynamic-shortcut tap flow (TASK-393): a launcher shortcut intent carries
 * the share-alias component but no EXTRA_STREAM, so the FROM_SHORTCUT marker
 * must route the null-stream case into the SAF audio picker instead of the
 * "no audio" dead end (toast + failure notification). Driven through the real
 * activity lifecycle; the picker launch is asserted on Robolectric's captured
 * startActivityForResult, and the dead end on the posted error notification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareReceiverActivityShortcutTest {

    /** The intent ShareShortcutManager.buildShortcut builds (alias component + marker, no stream). */
    private fun shortcutIntent(): Intent = Intent(Intent.ACTION_SEND).apply {
        component = ComponentName("com.antivocale.app", "com.antivocale.app.ShareWhisper")
        type = "audio/*"
        putExtra(ShareReceiverActivity.EXTRA_FROM_SHORTCUT, true)
    }

    @Test
    fun `shortcut tap with no stream opens the SAF audio picker, not the error path`() {
        val activity = Robolectric.buildActivity(ShareReceiverActivity::class.java, shortcutIntent())
            .create()
            .get()

        val started = Shadows.shadowOf(activity).nextStartedActivityForResult
        assertNotNull("expected the SAF audio picker to launch", started)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
        // The OpenDocument contract filters via EXTRA_MIME_TYPES over setType("*/*")
        // (verified against the androidx activity-1.8.2 artifact): audio only.
        assertArrayEquals(
            arrayOf("audio/*"),
            started.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
        )
        // The transparent activity must stay alive: the picker result returns to this instance.
        assertFalse(activity.isFinishing)
        // No dead-end signal: neither the TASK-385 failure notification nor a finish.
        assertTrue(notificationsPosted().isEmpty())
    }

    @Test
    fun `null-stream intent without the marker keeps the legacy error path`() {
        val plain = Intent(shortcutIntent()).apply {
            removeExtra(ShareReceiverActivity.EXTRA_FROM_SHORTCUT)
        }
        val activity = Robolectric.buildActivity(ShareReceiverActivity::class.java, plain)
            .create()
            .get()

        assertNull("no picker may launch without the marker", Shadows.shadowOf(activity).nextStartedActivityForResult)
        assertTrue(activity.isFinishing)
        // The TASK-385 dead-end contract: a durable failure notification accompanies the toast.
        assertEquals(1, notificationsPosted().size)
    }

    private fun notificationsPosted(): List<android.app.Notification> =
        Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<Context>()
                .getSystemService(NotificationManager::class.java)
        ).getAllNotifications()
}
