package com.antivocale.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow

/**
 * The one card-description renderer (sibling of [CardTitleRow], which was
 * itself extracted from ~30 inline copies): bodySmall on onSurfaceVariant,
 * capped at [SETTINGS_SEARCH_COMPACT_DESCRIPTION_LINES] lines while the
 * Settings search renders compact (the 2026-10-02 field report: a matched
 * card with no context is cryptic, and the sender-recognition limits must
 * be visible where the toggle is FOUND). Cards whose description IS the
 * contract pass their own [maxLinesCompact].
 */
@Composable
fun CardDescription(
    text: String,
    maxLinesCompact: Int = SETTINGS_SEARCH_COMPACT_DESCRIPTION_LINES,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (LocalSettingsSearchCompact.current) maxLinesCompact else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
    )
}
