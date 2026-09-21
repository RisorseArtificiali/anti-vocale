package com.antivocale.app.ui

import java.util.concurrent.atomic.AtomicLong

/**
 * The production navigation vocabulary: parsed destinations, handed-off
 * requests, and the key tables that keep the parser and the UI in sync.
 *
 * TASK-558: extracted from TestNavigation, which kept the vocabulary next to
 * its debug-only pending token and read as test-only tooling while
 * production navigation (the capped-transcript auto-save hint, TASK-548)
 * depended on it. The debug bridge keeps its own object; everything the
 * release build uses lives here.
 */
object AppNavigation {
    private val seqCounter = AtomicLong(0)

    /**
     * A handed-off navigation request: the ALREADY-PARSED destination plus a
     * sequence number (two identical commands must both fire, and a data
     * class alone would be structurally equal). Consume-once contract: the
     * consumer calls [onConsumed] as its first act, which nulls the request
     * at the source; a tab re-entry therefore sees null, not a stale replay
     * (a guard remembered inside the consumer would die with the tab under
     * Crossfade and replay - found by review, 2026-09-12).
     */
    data class NavRequest(val destination: Destination, val seq: Long) {
        companion object {
            fun next(destination: Destination) = NavRequest(destination, seqCounter.incrementAndGet())
        }
    }

    /** Settings sections reachable by key (single source for parser + UI). */
    val SECTION_KEYS = setOf("transcription", "appearance", "advanced", "feedback")

    /** Settings sub-pages reachable by key. */
    val SUBPAGE_KEYS = setOf("icon_picker", "prompt", "per_app", SUBPAGE_KEY_EXPORT)

    /** Models-tab targets reachable by key (single source for parser + UI). */
    val MODEL_KEYS = setOf("import")

    /** Main tabs by key, in TabRow order. */
    val TAB_KEYS = listOf("history", "models", "settings")

    /**
     * Tab indices derived from [TAB_KEYS] and checked once: a key rename or
     * reorder anywhere fails loudly HERE, not as a silent -1 that crashes
     * Crossfade at a distant tap (TASK-617 F2). Every tab-switch site reads
     * these instead of a raw literal.
     */
    val TAB_INDEX_HISTORY = tabIndex("history")
    val TAB_INDEX_MODELS = tabIndex("models")
    val TAB_INDEX_SETTINGS = tabIndex("settings")

    /** The only mint is this private, checked helper: a future fourth tab
     *  cannot copy half the derivation and reintroduce a -1. */
    private fun tabIndex(key: String): Int =
        TAB_KEYS.indexOf(key).also { check(it >= 0) { "$key missing from TAB_KEYS" } }

    /**
     * The export sub-page key (TASK-543), pinned because three sites must
     * agree: [SUBPAGE_KEYS], SettingsTab's comparison, and MainScreen's
     * auto-save-hint hand-off. A drifted literal in any of them navigates
     * nowhere, silently.
     */
    const val SUBPAGE_KEY_EXPORT = "export"

    /** The parsed destination; null when the token is unknown. */
    sealed interface Destination {
        data class Tab(val index: Int) : Destination
        data class SettingsSubPage(val key: String) : Destination
        data class SettingsSection(val key: String) : Destination
        data class ModelTarget(val key: String) : Destination
    }

    fun parse(dest: String?): Destination? {
        if (dest.isNullOrBlank()) return null
        if (dest.startsWith("tab:")) {
            TAB_KEYS.indexOf(dest.removePrefix("tab:")).takeIf { it >= 0 }
                ?.let { return Destination.Tab(it) }
        }
        if (dest.startsWith("models:")) {
            val key = dest.removePrefix("models:")
            MODEL_KEYS.firstOrNull { it == key }?.let { return Destination.ModelTarget(it) }
        }
        if (dest.startsWith("settings:")) {
            val key = dest.removePrefix("settings:")
            SECTION_KEYS.firstOrNull { it == key }?.let { return Destination.SettingsSection(it) }
            SUBPAGE_KEYS.firstOrNull { it == key }?.let { return Destination.SettingsSubPage(it) }
        }
        return null
    }
}
