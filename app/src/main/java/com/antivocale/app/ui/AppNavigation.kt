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
 * release build uses lives here. TASK-632: the settings-row deep link parses
 * here too (as [Destination.SettingsRow]) instead of riding a parallel
 * enum channel.
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
    data class NavRequest(val destination: Destination, val seq: Long, val scroll: ScrollRequest? = null) {
        companion object {
            fun next(destination: Destination, scrollDirective: ScrollDirective? = null) =
                NavRequest(destination, seqCounter.incrementAndGet(),
                    scrollDirective?.let { ScrollRequest(it, seqCounter.incrementAndGet()) })
        }
    }

    /**
     * TASK-815: the programmatic scroll a nav request may carry (device
     * trials must not emulate touch swipes). The directive is the WHAT; the
     * seq makes the same directive twice two distinct requests so the
     * consumer's LaunchedEffect refires.
     */
    data class ScrollRequest(val directive: ScrollDirective, val seq: Long)

    /** The scroll shapes a page can execute. */
    sealed class ScrollDirective {
        data object ToTop : ScrollDirective()
        data object ToBottom : ScrollDirective()
        /** Positive scrolls down; [viewports] is in page-height units. */
        data class ByViewports(val viewports: Float) : ScrollDirective()
    }

    /**
     * Parses the SPI `scroll` extra: "top", "bottom", or a signed integer
     * viewport count ("+2", "-1", "1"). Null on anything else (the receiver
     * turns that into the SPI error contract, never a silent no-op).
     */
    fun parseScrollSpec(raw: String?): ScrollDirective? = when {
        raw == null -> null
        raw == "top" -> ScrollDirective.ToTop
        raw == "bottom" -> ScrollDirective.ToBottom
        // toIntOrNull, not toFloatOrNull: the documented contract is a
        // signed INTEGER viewport count; "1.5" and "1e1" are typos to name,
        // not half-viewport scrolls to run.
        else -> raw.toIntOrNull()?.let {
            if (it == 0 || it !in -50..50) null else ScrollDirective.ByViewports(it.toFloat())
        }
    }

    /**
     * Destinations whose page owns a programmatic scroll target (the
     * SubPageScrollScaffold bodies). Everything else (the tab roots and the
     * per-app page's LazyColumn) is out of scope for v1 and answers the SPI
     * error contract when a scroll extra targets it.
     */
    fun isScrollableDest(destination: Destination): Boolean = when (destination) {
        is Destination.SettingsSubPage -> destination.key != SUBPAGE_KEY_PER_APP
        is Destination.ModelTarget -> destination.key == MODEL_KEY_GEMMA
        else -> false
    }

    /**
     * The scrollable destinations as tokens, for the SPI's error message:
     * one source with [isScrollableDest] so the message cannot drift from
     * the validation.
     */
    fun scrollableDestTokens(): List<String> =
        SUBPAGE_KEYS.filter { it != SUBPAGE_KEY_PER_APP }.map { "settings:$it" } +
            "models:$MODEL_KEY_GEMMA"

    /** The one non-scrollable sub-page key: its body is a LazyColumn. */
    const val SUBPAGE_KEY_PER_APP = "per_app"

    /** TASK-835: the voice-note settings page (the N>=2 subpage rule). */
    const val SUBPAGE_KEY_VOICE_NOTES = "voice_notes"

    /** Settings sections reachable by key (single source for parser + UI). */
    val SECTION_KEYS = setOf("transcription", "appearance", "advanced", "feedback")

    /** Settings sub-pages reachable by key. */
    val SUBPAGE_KEYS = setOf("icon_picker", "prompt", "per_app", SUBPAGE_KEY_EXPORT, "speaker", "performance", "automation", SUBPAGE_KEY_VOICE_NOTES)

    /**
     * TASK-632: Settings ROWS reachable by key (the deep-link targets one
     * row, which lives on a sub-page since the 2026-09-30 regroup). The row
     * key and its page are pinned here so the parser, the notification
     * action, and the Settings-tab branch cannot drift apart.
     */
    const val ROW_KEY_MEMORY_PROTECTION = "memory_protection"
    val ROW_KEYS = setOf(ROW_KEY_MEMORY_PROTECTION)

    /** The sub-page a deep-linked row lives on (single source for the branch). */
    fun rowPage(key: String): String? =
        when (key) {
            ROW_KEY_MEMORY_PROTECTION -> "performance"
            else -> null
        }

    /** Models-tab targets reachable by key (single source for parser + UI). */
    /** TASK-752 follow-up: pinned literals; ModelTab dispatches on these
     *  exact constants (a drifted literal there would navigate nowhere,
     *  silently - the class the export key's KDoc warns about). */
    const val MODEL_KEY_IMPORT = "import"
    const val MODEL_KEY_GEMMA = "gemma"

    val MODEL_KEYS = setOf(MODEL_KEY_IMPORT, MODEL_KEY_GEMMA)

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

        /** TASK-632: one Settings row, by key (see [ROW_KEYS]). */
        data class SettingsRow(val key: String) : Destination
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
            ROW_KEYS.firstOrNull { it == key }?.let { return Destination.SettingsRow(it) }
        }
        return null
    }
}
