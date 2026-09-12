package com.antivocale.app.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * TASK-486: the debug-only navigation bridge. The TEST_SPI receiver (debug
 * source set) writes a destination token here via a MainActivity intent
 * extra; [MainScreen] consumes it and drives the tab bar, and [SettingsTab]
 * consumes the settings-scoped part (sub-page or section).
 *
 * Release posture: the WRITE side (the intent-extra read in MainActivity) is
 * gated on BuildConfig.DEBUG and the receiver does not exist in release, so
 * nothing ever sets [pending] there. The consumer effects are also
 * BuildConfig.DEBUG-gated (constant-folded away by R8), so what survives in
 * release is the parse tables and inert state, not the machinery. No
 * manifest component, no receiver, no attack surface.
 *
 * Debug-build caveat (review 2026-09-12): when a nav cold-starts the task,
 * Android stores the intent as the Recents base intent and a later
 * relaunch-from-Recents after a process death redelivers the extra (same
 * exposure as the pre-existing EXTRA_NAVIGATE_TO_MODEL_TAB, but nav can
 * reopen dialogs). Debug builds only; acceptable for a test tool.
 */
object TestNavigation {
    const val EXTRA_TEST_NAV = "com.antivocale.app.TEST_NAV"

    /** One-shot destination token; consumed (nulled) by MainScreen. */
    val pending = MutableStateFlow<String?>(null)

    private val seqCounter = java.util.concurrent.atomic.AtomicLong(0)

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
    val SUBPAGE_KEYS = setOf("icon_picker", "prompt", "per_app")

    /** Models-tab targets reachable by key (single source for parser + UI). */
    val MODEL_KEYS = setOf("import")

    /** Main tabs by key, in TabRow order. */
    val TAB_KEYS = listOf("history", "models", "settings")

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
