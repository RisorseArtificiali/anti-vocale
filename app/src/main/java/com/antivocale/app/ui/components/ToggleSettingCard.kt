package com.antivocale.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun ToggleSettingCard(
    icon: ImageVector,
    title: String,
    description: String,
    /** TASK-736 field report: cards whose description is the contract
     *  (the sender-recognition limits) render it in full even in the
     *  compact search rendering. */
    descriptionMaxLinesCompact: Int = SETTINGS_SEARCH_COMPACT_DESCRIPTION_LINES,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** GH #43: greyed-out and inert when the feature's requirements (here: a
     *  streaming model installed) are not met. */
    enabled: Boolean = true,
    /** TASK-611: an extra line under the description (why the toggle is
     *  disabled, which models support the feature). */
    supportingText: String? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        // TASK-382: canonical toggleable row; the Switch itself is display-only
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                CardTitleRow(icon = icon, title = title)
                // TASK-628 bounded the description away in search; the
                // maintainer's field report (2026-10-02: the sender-
                // recognition limits, found VIA search, were invisible
                // there) settles it: compact shows a CAPPED description
                // instead of none - through the ONE renderer (range
                // review: this inline copy was the idiom's second site).
                Spacer(modifier = Modifier.height(4.dp))
                CardDescription(text = description, maxLinesCompact = descriptionMaxLinesCompact)
                supportingText?.let {
                    // Same compact height discipline as the description.
                    CardDescription(text = it, maxLinesCompact = 2)
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = null
            )
        }
    }
}
