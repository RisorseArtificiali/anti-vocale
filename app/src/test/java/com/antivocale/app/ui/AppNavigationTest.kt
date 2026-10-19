package com.antivocale.app.ui

import com.antivocale.app.MainActivity
import com.antivocale.app.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
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
        // TASK-752 follow-up: the Gemma sub-page is SPI-reachable too.
        assertEquals(
            AppNavigation.Destination.ModelTarget("gemma"),
            AppNavigation.parse("models:gemma"))
    }

    @Test
    fun `scroll spec parses top bottom and viewport counts`() {
        assertEquals(AppNavigation.ScrollDirective.ToTop, AppNavigation.parseScrollSpec("top"))
        assertEquals(AppNavigation.ScrollDirective.ToBottom, AppNavigation.parseScrollSpec("bottom"))
        assertEquals(AppNavigation.ScrollDirective.ByViewports(2f), AppNavigation.parseScrollSpec("+2"))
        assertEquals(AppNavigation.ScrollDirective.ByViewports(2f), AppNavigation.parseScrollSpec("2"))
        assertEquals(AppNavigation.ScrollDirective.ByViewports(-1f), AppNavigation.parseScrollSpec("-1"))
        assertNull(AppNavigation.parseScrollSpec("sideways"))
        // Integer contract: fractions and scientific notation are typos.
        assertNull(AppNavigation.parseScrollSpec("+1.5"))
        assertNull(AppNavigation.parseScrollSpec("1e1"))
        assertNull(AppNavigation.parseScrollSpec(null))
        // Out of range: an absurd viewport count is a typo, not a scroll.
        assertNull(AppNavigation.parseScrollSpec("99"))
    }

    @Test
    fun `scrollable destinations are the scroll sub-pages`() {
        // per_app is deliberately out of v1: its body is a LazyColumn.
        assertEquals(
            setOf("automation", "performance", "speaker", "prompt", "icon_picker", "export", "voice_notes", "backup"),
            AppNavigation.SUBPAGE_KEYS.filter { key ->
                AppNavigation.isScrollableDest(AppNavigation.parse("settings:$key")!!)
            }.toSet(),
        )
        assertFalse(AppNavigation.isScrollableDest(AppNavigation.parse("models:gemma")!!))
        assertFalse(AppNavigation.isScrollableDest(AppNavigation.parse("models:import")!!))
        assertFalse(AppNavigation.isScrollableDest(AppNavigation.parse("tab:history")!!))
        // The SPI error message derives from the same helper: pin that the
        // token list and the validation stay the same set.
        assertEquals(
            setOf("settings:automation", "settings:performance", "settings:speaker",
                "settings:prompt", "settings:icon_picker", "settings:export",
                "settings:voice_notes", "settings:backup"),
            AppNavigation.scrollableDestTokens().toSet())
    }

    @Test
    fun `settings sections and subpages parse with the settings prefix`() {
        AppNavigation.SECTION_KEYS.forEach { key ->
            assertEquals(
                AppNavigation.Destination.SettingsSection(key),
                AppNavigation.parse("settings:$key")!!)
        }
        AppNavigation.SUBPAGE_KEYS.forEach { key ->
            assertEquals(
                AppNavigation.Destination.SettingsSubPage(key),
                AppNavigation.parse("settings:$key")!!)
        }
    }

    @Test
    fun `the key tables match the shipped UI`() {
        // The four SettingsTab sections and the three sub-screens, pinned so
        // a UI rename without a parser update (or the reverse) fails here.
        assertEquals(setOf("transcription", "appearance", "advanced", "feedback"), AppNavigation.SECTION_KEYS)
        assertEquals(setOf("icon_picker", "prompt", "per_app", "export", "speaker", "performance", "automation", "voice_notes", "backup"), AppNavigation.SUBPAGE_KEYS)
        assertEquals(setOf("import", "gemma"), AppNavigation.MODEL_KEYS)
        assertEquals(listOf("history", "models", "settings"), AppNavigation.TAB_KEYS)
        // TASK-617 F2: the derived indices pin the TabRow ORDER, not just
        // membership; the load-time check in AppNavigation catches absence,
        // this catches a silent reorder.
        assertEquals(0, AppNavigation.TAB_INDEX_HISTORY)
        assertEquals(1, AppNavigation.TAB_INDEX_MODELS)
        assertEquals(2, AppNavigation.TAB_INDEX_SETTINGS)
    }

    /**
     * Source read shared by the scans below; the shared [RepoFiles]
     * locator owns the resolution (and its cwd-naming failure contract).
     */
    private fun sourceOf(relative: String): String = RepoFiles.readText(relative)

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
                // Anchored to the actual branch use: a bare contains() stays
                // green on any unrelated constant mention elsewhere in the
                // file while the branch itself drifts back to a literal.
                // TASK-632: the branch is now a when-arm label, so both the
                // comparison and the arrow forms count.
                Regex("(==\\s*AppNavigation\\.SUBPAGE_KEY_EXPORT)|(AppNavigation\\.SUBPAGE_KEY_EXPORT\\s*->)").containsMatchIn(source)
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
    fun `the settings row parses through the shared parser and rejects unknown keys`() {
        // TASK-632: the row is a Destination on the settings: prefix, same
        // parser as every other destination; the writer (both error surfaces)
        // and the reader (MainActivity) share the ROW_KEYS key.
        assertEquals(AppNavigation.Destination.SettingsRow(AppNavigation.ROW_KEY_MEMORY_PROTECTION),
            AppNavigation.parse("settings:" + AppNavigation.ROW_KEY_MEMORY_PROTECTION))
        assertNull(AppNavigation.parse("settings:unknown_row"))
        assertNull(AppNavigation.parse("settings:"))
        assertNull(AppNavigation.parse("settings:MEMORY_PROTECTION")) // keys are lowercase
        assertEquals("performance", AppNavigation.rowPage(AppNavigation.ROW_KEY_MEMORY_PROTECTION))
        // Every reachable row must own a page, or the Settings-tab branch
        // silently lands on the main list (review: the parser passing is not
        // the delivery).
        AppNavigation.ROW_KEYS.forEach { key ->
            assertTrue("row '$key' has no sub-page mapping", AppNavigation.rowPage(key) != null)
        }
    }

    @Test
    fun `the settings-row extra name and wire token are pinned`() {
        // Writer (both error-notification surfaces) and reader (MainActivity)
        // share these literals; a drift in either is a silent no-op.
        assertEquals("navigate_to_settings_row", MainActivity.EXTRA_NAVIGATE_TO_SETTINGS_ROW)
        assertEquals("memory_protection", AppNavigation.ROW_KEY_MEMORY_PROTECTION)
    }
}
