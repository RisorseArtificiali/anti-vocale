package com.antivocale.app.receiver

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Pins the TASK-963 wiped-imports notification's wiring: the Recover
 * action's PendingIntent must name this app's receiver explicitly (the
 * component is not exported, so a package-scoped action alone would never
 * reach it) with the repair action string. The builder separates from the
 * post (postWiped gates on canPostNotification) so the pin needs no
 * notification-permission setup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class WipedImportRepairNotificationsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun record(id: String) = ExternalModelRecord(
        id = id,
        displayName = "Model $id",
        dir = "/data/models/external/$id",
        family = ModelFamily.CANARY,
        modelType = "",
        languages = listOf("nl"),
        source = ExternalModelSource.URL,
        sourceUrl = "https://example.com/$id.json",
        files = emptyMap(),
        sizeBytes = 1L,
        importedAt = 0L,
    )

    @Test
    fun `recover action targets the foreground service explicitly`() {
        val notification = WipedImportRepairNotifications.wipedNotification(
            context, listOf(record("a"), record("b")))

        val action = notification.actions!!.single()
        val intent = Shadows.shadowOf(action.actionIntent).savedIntent

        // TASK-971: the run belongs to the foreground service (the system
        // starts it in the foreground straight from the tap); explicit
        // component, the service is android:exported="false".
        assertEquals(
            ComponentName(
                context.packageName,
                com.antivocale.app.service.WipedImportRepairService::class.java.name),
            intent.component)
    }

    @Test
    fun `wiped body names three models then collapses the rest into a count`() {
        val notification = WipedImportRepairNotifications.wipedNotification(
            context, listOf(record("a"), record("b"), record("c"), record("d"), record("e")))

        val text = notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.contains("Model a, Model b, Model c"))
        assertTrue(text, text.contains("+ 2 more"))
        assertTrue(text, text.contains("5"))
    }

    @Test
    fun `wiped body tap lands on the Models tab and does not cancel the actionable notice`() {
        val notification = WipedImportRepairNotifications.wipedNotification(
            context, listOf(record("a")))

        // The actionable notice must survive a body tap: auto-cancel would
        // swallow the Recover action with it (the maintainer's report).
        assertEquals(0, notification.flags and android.app.Notification.FLAG_AUTO_CANCEL)
        val intent = Shadows.shadowOf(notification.contentIntent).savedIntent
        assertEquals(
            ComponentName(context.packageName, com.antivocale.app.MainActivity::class.java.name),
            intent.component)
        assertEquals(true, intent.getBooleanExtra(com.antivocale.app.MainActivity.EXTRA_NAVIGATE_TO_MODEL_TAB, false))
    }

    @Test
    fun `source-failed outcome carries the Remove action and the dead copy`() {
        WipedImportRepairNotifications.postOutcome(
            context, attempted = 1, repairedCount = 0, sourceFailedCount = 1)

        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val notification = Shadows.shadowOf(manager).allNotifications.single()
        val text = notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.contains("no longer be downloaded"))
        val actions = notification.actions!!.map { it.title }
        // TASK-973: a pure source failure offers removal, not a retry.
        assertTrue(actions.toString(), actions.contains("Remove"))
        assertFalse(actions.toString(), actions.contains("Recover"))
    }

    @Test
    fun `a no-op removal posts nothing`() {
        WipedImportRepairNotifications.postRemoved(context, 0)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(Shadows.shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `network-failed outcome re-arms the Recover action`() {
        WipedImportRepairNotifications.postOutcome(
            context, attempted = 1, repairedCount = 0, networkFailedCount = 1)

        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val notification = Shadows.shadowOf(manager).allNotifications.single()
        val title = notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString()
        assertEquals("Model recovery failed", title)
        val actions = notification.actions!!.map { it.title }
        // The maintainer's own friction: the old failed notice had no action.
        assertTrue(actions.toString(), actions.contains("Recover"))
    }

    @Test
    fun `failed outcome tap lands on the Models tab and auto-cancels`() {
        WipedImportRepairNotifications.postOutcome(
            context, attempted = 1, repairedCount = 0, networkFailedCount = 1)

        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        val notification = shadow.allNotifications.single()
        // The terminal outcome may auto-cancel: the tap follows its own
        // instruction and lands where the information lives.
        assertEquals(
            android.app.Notification.FLAG_AUTO_CANCEL,
            notification.flags and android.app.Notification.FLAG_AUTO_CANCEL)
        val intent = Shadows.shadowOf(notification.contentIntent).savedIntent
        assertEquals(true, intent.getBooleanExtra(com.antivocale.app.MainActivity.EXTRA_NAVIGATE_TO_MODEL_TAB, false))
    }
}
