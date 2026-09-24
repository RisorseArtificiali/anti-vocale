package com.antivocale.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * TASK-628: true while the Settings search query is active. Cards render
 * COMPACT (descriptions suppressed) so several matched cards fit one
 * viewport; the descriptions stay searchable (matching runs on the strings
 * before rendering). Blank query = false = the normal layout, untouched.
 */
val LocalSettingsSearchCompact = staticCompositionLocalOf { false }
