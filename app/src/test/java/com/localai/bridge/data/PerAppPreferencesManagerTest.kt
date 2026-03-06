package com.localai.bridge.data

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
        assertEquals("default", prefs.notificationSound)
    }

    @Test
    fun testDefaultPreferencesForTelegram() = runTest {
        val prefs = manager.getCurrentPreferences(PerAppPreferencesManager.TELEGRAM)

        // Telegram should have auto-copy disabled by default
        assertFalse("Telegram should not auto-copy", prefs.autoCopy)
        assertTrue("Telegram should show share action", prefs.showShareAction)
        assertEquals("default", prefs.notificationSound)
    }

    @Test
    fun testDefaultPreferencesForUnknownApp() = runTest {
        val prefs = manager.getCurrentPreferences("com.unknown.app")

        // Unknown apps should have conservative defaults
        assertFalse("Unknown app should not auto-copy", prefs.autoCopy)
        assertTrue("Unknown app should show share action", prefs.showShareAction)
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
}
