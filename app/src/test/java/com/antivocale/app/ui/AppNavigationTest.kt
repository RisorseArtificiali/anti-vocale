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
class AppNavigationTest {

    @Test
    fun `tabs map by key in TabRow order`() {
        assertEquals(AppNavigation.Destination.Tab(0), AppNavigation.parse("tab:history"))
        assertEquals(AppNavigation.Destination.Tab(1), AppNavigation.parse("tab:models"))
        assertEquals(AppNavigation.Destination.Tab(2), AppNavigation.parse("tab:settings"))
    }

    @Test
    fun `models targets parse with the models prefix`() {
        assertEquals(
            AppNavigation.Destination.ModelTarget("import"),
            AppNavigation.parse("models:import"))
    }

    @Test
    fun `settings sections and subpages parse with the settings prefix`() {
        AppNavigation.SECTION_KEYS.forEach { key ->
            assertEquals(
                AppNavigation.Destination.SettingsSection(key),
                AppNavigation.parse("settings:$key"))
        }
        AppNavigation.SUBPAGE_KEYS.forEach { key ->
            assertEquals(
                AppNavigation.Destination.SettingsSubPage(key),
                AppNavigation.parse("settings:$key"))
        }
    }

    @Test
    fun `the key tables match the shipped UI`() {
        // The four SettingsTab sections and the three sub-screens, pinned so
        // a UI rename without a parser update (or the reverse) fails here.
        assertEquals(setOf("transcription", "appearance", "advanced", "feedback"), AppNavigation.SECTION_KEYS)
        assertEquals(setOf("icon_picker", "prompt", "per_app", "export"), AppNavigation.SUBPAGE_KEYS)
        assertEquals(setOf("import"), AppNavigation.MODEL_KEYS)
        assertEquals(listOf("history", "models", "settings"), AppNavigation.TAB_KEYS)
    }

    /** Dual-path source read (repo root or module subdirectory cwd), shared by the scans below. */
    private fun sourceOf(relative: String): String =
        java.io.File(relative)
            .let { if (it.isFile) it else java.io.File("app/$relative") }
            .readText()

    @Test
    fun `every section key is wired into the Settings UI`() {
        // The parser tables alone cannot promise the UI acts on a key: each
        // section must carry BOTH the expand counter and the scroll-offset
        // capture at its composition site, or a nav silently no-ops. Source
        // scan (same shape as the manifest tests' file reads).
        val source = sourceOf("src/main/java/com/antivocale/app/ui/tabs/SettingsTab.kt")
        AppNavigation.SECTION_KEYS.forEach { key ->
            assertTrue(
                "SettingsTab lacks the expand wiring for section '$key' (expandCounters[\"$key\"])",
                source.contains("expandCounters[\"$key\"]"))
            assertTrue(
                "SettingsTab lacks the scroll-anchor capture for section '$key' (sectionOffsets[\"$key\"])",
                source.contains("sectionOffsets[\"$key\"]"))
        }
        AppNavigation.SUBPAGE_KEYS.forEach { key ->
            // The export branch is pinned by the shared constant (TASK-548),
            // so it appears in SettingsTab as the constant reference.
            val wired = source.contains("\"$key\"") ||
                (key == AppNavigation.SUBPAGE_KEY_EXPORT &&
                    source.contains("AppNavigation.SUBPAGE_KEY_EXPORT"))
            assertTrue(
                "SettingsTab lacks the sub-page branch for '$key'",
                wired)
        }
    }

    @Test
    fun `the export hand-off uses the pinned key constant`() {
        // TASK-548(B): the auto-save hint is the one PRODUCTION NavRequest
        // writer (every other destination arrives through the debug-only
        // pending token). Its SettingsSubPage must be CONSTRUCTED with the
        // pinned constant so a key rename cannot drift between MainScreen
        // and the parser tables. The regex pins the constant INSIDE the
        // constructor call: two independent substring hits would still pass
        // with a literal hand-off next to an unrelated constant use.
        val source = sourceOf("src/main/java/com/antivocale/app/ui/MainScreen.kt")
        assertTrue(
            "MainScreen's hint hand-off must construct SettingsSubPage with AppNavigation.SUBPAGE_KEY_EXPORT",
            Regex("SettingsSubPage\\s*\\(\\s*AppNavigation\\.SUBPAGE_KEY_EXPORT")
                .containsMatchIn(source))
    }

    @Test
    fun `unknown or malformed destinations are rejected, not guessed`() {
        assertNull(AppNavigation.parse(null))
        assertNull(AppNavigation.parse(""))
        assertNull(AppNavigation.parse("tab:nonexistent"))
        assertNull(AppNavigation.parse("settings:nonexistent"))
        assertNull(AppNavigation.parse("models:nonexistent"))
        assertNull(AppNavigation.parse("history")) // bare tab name without prefix
    }
}
