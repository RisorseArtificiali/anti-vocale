package com.antivocale.app.data

import android.app.Application
import com.antivocale.app.testing.TempDataStoreRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-993 review F4: the preset write contract exercised against the REAL
 * Impl over a temp store (the fake mirrors the rules; only this harness
 * catches an Impl regression). Pins: key normalization, the NEVER-clears-
 * the-prompt rule (dormancy, not deletion), cache sync, and the one-shot
 * legacy promotion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PreferencesManagerSummaryPresetTest {

    @get:Rule
    val store = TempDataStoreRule("summary-preset")

    @Test
    fun `saving a preset normalizes unknown keys and never touches the prompt`() = runTest {
        store.prefs.saveSummaryPrompt("MY INSTRUCTION")
        store.prefs.saveSummaryPreset("action_items")
        assertEquals("action_items", store.prefs.summaryPreset.first())
        assertEquals("MY INSTRUCTION", store.prefs.summaryPrompt.first())

        store.prefs.saveSummaryPreset("garbage")
        assertEquals("summary", store.prefs.summaryPreset.first())
        // Even a typo'd write must not delete user text.
        assertEquals("MY INSTRUCTION", store.prefs.summaryPrompt.first())
    }

    @Test
    fun `the legacy promotion fires once and only for a never-set key`() = runTest {
        store.prefs.saveSummaryPrompt("MY INSTRUCTION")
        // No preset key stored yet: the prompt is legacy state.
        store.prefs.reconcileLegacySummaryPrompt()
        assertEquals("custom", store.prefs.summaryPreset.first())
        assertEquals("MY INSTRUCTION", store.prefs.summaryPrompt.first())

        // Idempotent: the key now exists, a second reconcile is a no-op.
        store.prefs.reconcileLegacySummaryPrompt()
        assertEquals("custom", store.prefs.summaryPreset.first())

        // A DELIBERATE non-custom choice stores the key; a later reconcile
        // must not hijack it even with a (dormant) prompt present.
        store.prefs.saveSummaryPreset("meeting_minutes")
        store.prefs.reconcileLegacySummaryPrompt()
        assertEquals("meeting_minutes", store.prefs.summaryPreset.first())
    }

    @Test
    fun `the promotion is a no-op with no legacy prompt`() = runTest {
        store.prefs.reconcileLegacySummaryPrompt()
        assertEquals("summary", store.prefs.summaryPreset.first())
    }

    @Test
    fun `an import carrying a legacy prompt archive promotes to custom`() = runTest {
        store.prefs.importPortableSettings(
            mapOf("summary_prompt" to "ARCHIVED INSTRUCTION"))
        assertEquals("custom", store.prefs.summaryPreset.first())
        assertEquals("ARCHIVED INSTRUCTION", store.prefs.summaryPrompt.first())
    }

    @Test
    fun `a legacy archive prompt wins over a local deliberate preset`() = runTest {
        // Review F3: the archive predates presets, so its non-blank prompt
        // WAS the active instruction on the source device; a local
        // action-items choice must not silently park it dormant.
        store.prefs.saveSummaryPreset("action_items")
        store.prefs.importPortableSettings(
            mapOf("summary_prompt" to "ARCHIVED INSTRUCTION"))
        assertEquals("custom", store.prefs.summaryPreset.first())
        assertEquals("ARCHIVED INSTRUCTION", store.prefs.summaryPrompt.first())
    }

    @Test
    fun `an archive carrying its own preset key imports verbatim`() = runTest {
        store.prefs.saveSummaryPreset("custom")
        store.prefs.importPortableSettings(
            mapOf(
                "summary_prompt" to "NEW",
                "summary_preset" to "meeting_minutes"))
        // The archive's preset wins as-is; its blank prompt is the archive's
        // own truth too (no promotion: the key is present in the archive).
        assertEquals("meeting_minutes", store.prefs.summaryPreset.first())
        assertEquals("NEW", store.prefs.summaryPrompt.first())
    }
}
