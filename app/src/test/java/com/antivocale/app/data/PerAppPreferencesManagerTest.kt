package com.antivocale.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import junit.framework.TestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PerAppPreferencesManager.
 *
 * Tests preference storage, retrieval, and updates using a test DataStore.
 */
class PerAppPreferencesManagerTest {

    private lateinit var context: Context
    private lateinit var manager: PerAppPreferencesManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = PerAppPreferencesManager(context)
    }

    @Test
    fun testDefaultPreferencesForWhatsApp() = runTest {
        val prefs = manager.getCurrentPreferences(PerAppPreferencesManager.WHATSAPP)

        // WhatsApp should have auto-copy enabled by default
        assertTrue("WhatsApp should auto-copy", prefs.autoCopy)
        assertTrue("WhatsApp should show share action", prefs.showShareAction)
        assertTrue("WhatsApp should have quick share back enabled", prefs.quickShareBack)
        assertEquals("default", prefs.notificationSound)
    }

    @Test
    fun testDefaultPreferencesForTelegram() = runTest {
        val prefs = manager.getCurrentPreferences(PerAppPreferencesManager.TELEGRAM)

        // Telegram should have auto-copy disabled by default
        assertFalse("Telegram should not auto-copy", prefs.autoCopy)
        assertTrue("Telegram should show share action", prefs.showShareAction)
        assertTrue("Telegram should have quick share back enabled", prefs.quickShareBack)
        assertEquals("default", prefs.notificationSound)
    }

    @Test
    fun testDefaultPreferencesForUnknownApp() = runTest {
        val prefs = manager.getCurrentPreferences("com.unknown.app")

        // Unknown apps should have conservative defaults
        assertFalse("Unknown app should not auto-copy", prefs.autoCopy)
        assertTrue("Unknown app should show share action", prefs.showShareAction)
        assertFalse("Unknown app should not have quick share back enabled", prefs.quickShareBack)
        assertEquals("default", prefs.notificationSound)
    }

    @Test
    fun testUpdatePreferencesForPackage() = runTest {
        // Update WhatsApp preferences to disable auto-copy
        manager.updatePreferencesForPackage(PerAppPreferencesManager.WHATSAPP) {
            copy(autoCopy = false)
        }

        val updated = manager.getCurrentPreferences(PerAppPreferencesManager.WHATSAPP)

        assertFalse("Auto-copy should be disabled", updated.autoCopy)
        assertTrue("Share action should still be enabled", updated.showShareAction)
    }

    @Test
    fun testPreferencesPersistAcrossReads() = runTest {
        // Set a preference
        manager.updatePreferencesForPackage("com.test.app") {
            copy(autoCopy = true, showShareAction = false, notificationSound = "custom_sound")
        }

        // Read it back
        val firstRead = manager.getCurrentPreferences("com.test.app")

        // Read again to verify persistence
        val secondRead = manager.getCurrentPreferences("com.test.app")

        assertEquals("Preferences should persist", firstRead, secondRead)
        assertTrue("Auto-copy should be true", firstRead.autoCopy)
        assertFalse("Share action should be false", firstRead.showShareAction)
        assertEquals("custom_sound", firstRead.notificationSound)
    }

    @Test
    fun testPreferencesFlowEmitsOnChanges() = runTest {
        // Get the flow
        val flow = manager.getPreferencesForPackage("com.flow.test")

        // Get initial value
        val initial = flow.first()
        assertFalse("Initial auto-copy should be false", initial.autoCopy)

        // Update preference
        manager.updatePreferencesForPackage("com.flow.test") {
            copy(autoCopy = true)
        }

        // Collect updated value
        val updated = flow.first()
        assertTrue("Updated auto-copy should be true", updated.autoCopy)
    }

    @Test
    fun testClearPreferencesForPackage() = runTest {
        // Set preferences
        manager.updatePreferencesForPackage("com.clear.test") {
            copy(autoCopy = true, showShareAction = false, notificationSound = "test_sound")
        }

        // Verify they're set
        val beforeClear = manager.getCurrentPreferences("com.clear.test")
        assertTrue("Auto-copy should be set before clear", beforeClear.autoCopy)

        // Clear preferences
        manager.clearPreferencesForPackage("com.clear.test")

        // Verify they're reset to defaults
        val afterClear = manager.getCurrentPreferences("com.clear.test")
        assertFalse("Auto-copy should be default after clear", afterClear.autoCopy)
    }

    @Test
    fun testMultipleAppsIndependentPreferences() = runTest {
        // Set different preferences for two apps
        manager.updatePreferencesForPackage("com.app1") {
            copy(autoCopy = true)
        }

        manager.updatePreferencesForPackage("com.app2") {
            copy(autoCopy = false)
        }

        // Verify they're independent (get them in sequence within same coroutine)
        val app1Prefs = manager.getCurrentPreferences("com.app1")
        val app2Prefs = manager.getCurrentPreferences("com.app2")

        assertTrue("App1 should auto-copy", app1Prefs.autoCopy)
        assertFalse("App2 should not auto-copy", app2Prefs.autoCopy)
    }

    @Test
    fun testQuickShareBackDefaultForWhatsApp() = runTest {
        val prefs = manager.getCurrentPreferences(PerAppPreferencesManager.WHATSAPP)
        assertTrue("WhatsApp should have quick share back enabled by default", prefs.quickShareBack)
    }

    @Test
    fun testQuickShareBackDefaultForTelegram() = runTest {
        val prefs = manager.getCurrentPreferences(PerAppPreferencesManager.TELEGRAM)
        assertTrue("Telegram should have quick share back enabled by default", prefs.quickShareBack)
    }

    @Test
    fun testQuickShareBackDefaultForUnknownApp() = runTest {
        val prefs = manager.getCurrentPreferences("com.unknown.app")
        assertFalse("Unknown app should have quick share back disabled by default", prefs.quickShareBack)
    }

    @Test
    fun testUpdateQuickShareBackPreference() = runTest {
        // Disable quick share back for WhatsApp
        manager.updatePreferencesForPackage(PerAppPreferencesManager.WHATSAPP) {
            copy(quickShareBack = false)
        }

        val updated = manager.getCurrentPreferences(PerAppPreferencesManager.WHATSAPP)
        assertFalse("Quick share back should be disabled", updated.quickShareBack)
        // Other preferences should remain unchanged
        assertTrue("Auto-copy should still be enabled", updated.autoCopy)
    }

    @Test
    fun testQuickShareBackPersistsAcrossReads() = runTest {
        // Enable quick share back for an unknown app
        manager.updatePreferencesForPackage("com.test.app") {
            copy(quickShareBack = true)
        }

        val firstRead = manager.getCurrentPreferences("com.test.app")
        val secondRead = manager.getCurrentPreferences("com.test.app")

        assertEquals("Quick share back should persist", firstRead.quickShareBack, secondRead.quickShareBack)
        assertTrue("Quick share back should be true", firstRead.quickShareBack)
    }

    @Test
    fun testQuickShareBackFlowEmitsOnChanges() = runTest {
        val flow = manager.getPreferencesForPackage("com.flow.test")

        val initial = flow.first()
        assertFalse("Initial quick share back should be false", initial.quickShareBack)

        manager.updatePreferencesForPackage("com.flow.test") {
            copy(quickShareBack = true)
        }

        val updated = flow.first()
        assertTrue("Updated quick share back should be true", updated.quickShareBack)
    }

    @Test
    fun testQuickShareBackClearedWithPreferences() = runTest {
        // Set quick share back to true
        manager.updatePreferencesForPackage("com.clear.test") {
            copy(quickShareBack = true)
        }

        val beforeClear = manager.getCurrentPreferences("com.clear.test")
        assertTrue("Quick share back should be set before clear", beforeClear.quickShareBack)

        manager.clearPreferencesForPackage("com.clear.test")

        val afterClear = manager.getCurrentPreferences("com.clear.test")
        assertFalse("Quick share back should be default after clear", afterClear.quickShareBack)
    }
}
