package com.antivocale.app.ui

import com.antivocale.app.MainActivity
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
        // TASK-617 F2: the derived indices pin the TabRow ORDER, not just
        // membership; the load-time check in AppNavigation catches absence,
        // this catches a silent reorder.
        assertEquals(0, AppNavigation.TAB_INDEX_HISTORY)
        assertEquals(1, AppNavigation.TAB_INDEX_MODELS)
        assertEquals(2, AppNavigation.TAB_INDEX_SETTINGS)
    }

    /**
     * Dual-path source read (repo root or module subdirectory cwd), shared
     * by the scans below. TASK-617 F8: names the cwd on failure, so a
     * working-dir regression is diagnosable instead of a bare
     * FileNotFoundException with no context.
     */
    private fun sourceOf(relative: String): String {
        val direct = java.io.File(relative)
        val file = if (direct.isFile) direct else java.io.File("app/$relative")
        check(file.isFile) {
            "cannot locate '$relative' from cwd ${java.io.File(".").absolutePath}"
        }
        return file.readText()
    }

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
            // TASK-617 F4: export is pinned by the shared constant (TASK-548)
            // and must appear in SettingsTab AS the constant reference; the
            // other three sub-pages are matched by literal. An either/or
            // here would let a rename ship as a silent no-op.
            val wired = if (key == AppNavigation.SUBPAGE_KEY_EXPORT) {
                // Anchored to the actual comparison: a bare contains() stays
                // green on any unrelated constant mention elsewhere in the
                // file while the branch itself drifts back to a literal.
                Regex("==\\s*AppNavigation\\.SUBPAGE_KEY_EXPORT").containsMatchIn(source)
            } else {
                source.contains("\"$key\"")
            }
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

    @Test
    fun `settings focus rows parse by enum name and reject unknown values`() {
        // TASK-625: the wire value is the enum name the error notification's
        // PendingIntent writes and MainActivity parses at its boundary.
        assertEquals(SettingsFocusRow.MEMORY_PROTECTION, AppNavigation.parseSettingsFocusRow("MEMORY_PROTECTION"))
        assertNull(AppNavigation.parseSettingsFocusRow(null))
        assertNull(AppNavigation.parseSettingsFocusRow(""))
        assertNull(AppNavigation.parseSettingsFocusRow("memory_protection")) // valueOf is exact
        assertNull(AppNavigation.parseSettingsFocusRow("MEMORY_PROTECTION "))
    }

    @Test
    fun `the settings-row extra name and wire token are pinned`() {
        // Writer (both error-notification surfaces) and reader (MainActivity)
        // share these literals; a drift in either is a silent no-op.
        assertEquals("navigate_to_settings_row", MainActivity.EXTRA_NAVIGATE_TO_SETTINGS_ROW)
        assertEquals("MEMORY_PROTECTION", SettingsFocusRow.MEMORY_PROTECTION.name)
    }
}
