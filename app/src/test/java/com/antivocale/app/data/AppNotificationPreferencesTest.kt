package com.antivocale.app.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for AppNotificationPreferences data class.
 *
 * Pure Kotlin tests with no Android dependencies — verifies
 * factory defaults and data class semantics.
 */
class AppNotificationPreferencesTest {

    @Test
    fun `whatsappDefaults has correct values`() {
        val prefs = AppNotificationPreferences.whatsappDefaults()

        assertTrue("WhatsApp should auto-copy", prefs.autoCopy)
        assertTrue("WhatsApp should show share action", prefs.showShareAction)
        assertTrue("WhatsApp should have quick share back", prefs.quickShareBack)
        assertEquals("default", prefs.notificationSound)
    }

    @Test
    fun `telegramDefaults has correct values`() {
        val prefs = AppNotificationPreferences.telegramDefaults()

        assertFalse("Telegram should not auto-copy", prefs.autoCopy)
        assertTrue("Telegram should show share action", prefs.showShareAction)
        assertTrue("Telegram should have quick share back", prefs.quickShareBack)
        assertEquals("default", prefs.notificationSound)
    }

    @Test
    fun `default has correct values`() {
        val prefs = AppNotificationPreferences.default()

        assertFalse("Default should not auto-copy", prefs.autoCopy)
        assertTrue("Default should show share action", prefs.showShareAction)
        assertFalse("Default should not have quick share back", prefs.quickShareBack)
        assertEquals("default", prefs.notificationSound)
    }

    @Test
    fun `data class equality and copy work correctly`() {
        val prefs1 = AppNotificationPreferences(
            autoCopy = true,
            showShareAction = true,
            notificationSound = "default",
            quickShareBack = false
        )
        val prefs2 = AppNotificationPreferences(
            autoCopy = true,
            showShareAction = true,
            notificationSound = "default",
            quickShareBack = false
        )

        assertEquals("Identical instances should be equal", prefs1, prefs2)

        val prefs3 = prefs1.copy(autoCopy = false)
        assertNotEquals("copy with change should differ", prefs1, prefs3)
        assertFalse("Changed field should be updated", prefs3.autoCopy)
        assertEquals("Unchanged fields should remain", prefs1.showShareAction, prefs3.showShareAction)
    }

}
