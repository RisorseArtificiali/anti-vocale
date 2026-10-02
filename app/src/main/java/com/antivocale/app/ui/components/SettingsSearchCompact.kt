package com.antivocale.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * TASK-628: true while the Settings search query is active. Cards render
 * COMPACT so several matched cards fit one viewport; the descriptions
 * stay searchable (matching runs on the strings before rendering).
 * Blank query = false = the normal layout, untouched.
 *
 * 2026-10-02 field report: full suppression hid the sender-recognition
 * limits exactly where the toggle was FOUND (via search), so compact now
 * CAPS descriptions at [SETTINGS_SEARCH_COMPACT_DESCRIPTION_LINES]
 * instead of hiding them - the 628 goal was height, not the absence of
 * context. Cards whose description IS the contract (the identity
 * toggle's limits) override the cap and render in full.
 */
val LocalSettingsSearchCompact = staticCompositionLocalOf { false }

/** The compact-mode description cap; single-sourced beside the local. */
const val SETTINGS_SEARCH_COMPACT_DESCRIPTION_LINES = 4
