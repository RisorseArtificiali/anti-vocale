package com.antivocale.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * TASK-731: a small in-section label above a themed cluster of Settings
 * cards. Not a card; the group's label matches in search through its
 * member cards' registry vocabulary (SettingsTab's SettingsSearchGroup).
 * Self-suppresses during active search via [LocalSettingsSearchCompact],
 * the same mechanism ToggleSettingCard and SectionCard use (the search
 * result list is flat by design; TASK-628), so call sites never wrap it.
 * Carries heading semantics like the CollapsibleSection titles: TalkBack
 * users jump between groups by swipe-by-heading.
 */
@Composable
fun GroupHeader(labelRes: Int) {
    if (LocalSettingsSearchCompact.current) return
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() }
            .padding(top = 8.dp),
    )
}
