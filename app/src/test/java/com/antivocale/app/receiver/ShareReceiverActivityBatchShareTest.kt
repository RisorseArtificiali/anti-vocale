package com.antivocale.app.receiver

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * TASK-927: the ACTION_SEND_MULTIPLE entry. The extraction contract is the
 * unit: delivery-ordered URIs from the ParcelableArrayList, null and
 * non-Uri entries dropped, empty when the intent carries no stream. The
 * activity-level assertions cover the routing fork: a stream-less multi
 * share takes the same dead end as a stream-less SEND, and a carried
 * multi-stream intent does NOT dead-end (the per-file flows queue on the
 * paused looper, so the assertions stay synchronous).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareReceiverActivityBatchShareTest {

    private fun multiStreamIntent(vararg uris: Uri): Intent =
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(*uris))
        }

    @Test
    fun `multi stream extraction returns every URI in delivery order`() {
        val uris = listOf(
            Uri.parse("content://sender/1"),
            Uri.parse("content://sender/2"),
            Uri.parse("content://sender/3"),
        )
        assertEquals(
            uris,
            ShareReceiverActivity.extractSharedUris(multiStreamIntent(*uris.toTypedArray())),
        )
    }

    @Test
    fun `single send extraction returns the one stream`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://sender/1"))
        }
        assertEquals(
            listOf(Uri.parse("content://sender/1")),
            ShareReceiverActivity.extractSharedUris(intent),
        )
    }

    @Test
    fun `multi stream without the extra extracts empty, not null`() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "audio/*" }
        assertTrue(ShareReceiverActivity.extractSharedUris(intent).isEmpty())
    }

    @Test
    fun `stream-less multi share takes the legacy dead end`() {
        val activity = Robolectric.buildActivity(
            ShareReceiverActivity::class.java,
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "audio/*" },
        )
            .create()
            .get()

        assertTrue(activity.isFinishing)
        // The TASK-385 dead-end contract: a durable failure notification.
        assertEquals(1, notificationsPosted().size)
        assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
    }

    @Test
    fun `carried multi share routes into the per-file flows, no dead end`() {
        val intent = multiStreamIntent(
            Uri.parse("content://sender/1"),
            Uri.parse("content://sender/2"),
        )
        val activity = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent)
            .create()
            .get()

        // The flows queued on the paused looper: no dead-end signal, and the
        // transparent Activity stays alive for the grants and the dialogs.
        assertFalse(activity.isFinishing)
        assertTrue(notificationsPosted().isEmpty())
        assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
    }

    private fun notificationsPosted(): List<android.app.Notification> =
        Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<Context>()
                .getSystemService(NotificationManager::class.java)
        ).getAllNotifications()
}
