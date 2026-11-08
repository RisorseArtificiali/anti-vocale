package com.antivocale.app.receiver

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.ModelFamily
import org.junit.Assert.assertEquals
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
    fun `recover action targets the receiver explicitly with the repair action`() {
        val notification = WipedImportRepairNotifications.wipedNotification(
            context, listOf(record("a"), record("b")))

        val action = notification.actions!!.single()
        val intent = Shadows.shadowOf(action.actionIntent).savedIntent

        assertEquals(WipedImportRepairReceiver.ACTION_REPAIR_WIPED_IMPORTS, intent.action)
        // Explicit component, not just a package-scoped action: the
        // receiver is android:exported="false".
        assertEquals(
            ComponentName(context.packageName, WipedImportRepairReceiver::class.java.name),
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
}
