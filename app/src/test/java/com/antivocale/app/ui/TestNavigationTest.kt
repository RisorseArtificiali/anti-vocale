package com.antivocale.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-486: the destination parser is the single contract between the SPI
 * receiver (op=nav) and the UI. The key sets ARE the UI's sections/sub-pages
 * in TabRow order; a key added to the UI without a parser entry (or vice
 * versa) fails here.
 */
class TestNavigationTest {

    @Test
    fun `tabs map by key in TabRow order`() {
        assertEquals(TestNavigation.Destination.Tab(0), TestNavigation.parse("tab:history"))
        assertEquals(TestNavigation.Destination.Tab(1), TestNavigation.parse("tab:models"))
        assertEquals(TestNavigation.Destination.Tab(2), TestNavigation.parse("tab:settings"))
    }

    @Test
    fun `models targets parse with the models prefix`() {
        assertEquals(
            TestNavigation.Destination.ModelTarget("import"),
            TestNavigation.parse("models:import"))
    }

    @Test
    fun `settings sections and subpages parse with the settings prefix`() {
        TestNavigation.SECTION_KEYS.forEach { key ->
            assertEquals(
                TestNavigation.Destination.SettingsSection(key),
                TestNavigation.parse("settings:$key"))
        }
        TestNavigation.SUBPAGE_KEYS.forEach { key ->
            assertEquals(
                TestNavigation.Destination.SettingsSubPage(key),
                TestNavigation.parse("settings:$key"))
        }
    }

    @Test
    fun `the key tables match the shipped UI`() {
        // The four SettingsTab sections and the three sub-screens, pinned so
        // a UI rename without a parser update (or the reverse) fails here.
        assertEquals(setOf("transcription", "appearance", "advanced", "feedback"), TestNavigation.SECTION_KEYS)
        assertEquals(setOf("icon_picker", "prompt", "per_app"), TestNavigation.SUBPAGE_KEYS)
        assertEquals(setOf("import"), TestNavigation.MODEL_KEYS)
        assertEquals(listOf("history", "models", "settings"), TestNavigation.TAB_KEYS)
    }

    @Test
    fun `every section key is wired into the Settings UI`() {
        // The parser tables alone cannot promise the UI acts on a key: each
        // section must carry BOTH the expand counter and the scroll-offset
        // capture at its composition site, or a nav silently no-ops. Source
        // scan (same shape as the manifest tests' file reads).
        val source = java.io.File("src/main/java/com/antivocale/app/ui/tabs/SettingsTab.kt")
            .let { if (it.isFile) it else java.io.File("app/src/main/java/com/antivocale/app/ui/tabs/SettingsTab.kt") }
            .readText()
        TestNavigation.SECTION_KEYS.forEach { key ->
            assertTrue(
                "SettingsTab lacks the expand wiring for section '$key' (expandCounters[\"$key\"])",
                source.contains("expandCounters[\"$key\"]"))
            assertTrue(
                "SettingsTab lacks the scroll-anchor capture for section '$key' (sectionOffsets[\"$key\"])",
                source.contains("sectionOffsets[\"$key\"]"))
        }
        TestNavigation.SUBPAGE_KEYS.forEach { key ->
            assertTrue(
                "SettingsTab lacks the sub-page branch for '$key'",
                source.contains("\"$key\""))
        }
    }

    @Test
    fun `unknown or malformed destinations are rejected, not guessed`() {
        assertNull(TestNavigation.parse(null))
        assertNull(TestNavigation.parse(""))
        assertNull(TestNavigation.parse("tab:nonexistent"))
        assertNull(TestNavigation.parse("settings:nonexistent"))
        assertNull(TestNavigation.parse("models:nonexistent"))
        assertNull(TestNavigation.parse("history")) // bare tab name without prefix
    }
}
