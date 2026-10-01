package com.antivocale.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The 2026-09-30 regroup's hub card: a main-tree card that opens a settings
 * secondary page (title + one-line summary + chevron). The three instances
 * (Diarization, Performance & Memory, Automation & Offload) were identical
 * except the two resource ids, so this is their one shared shape.
 * TASK-732: the entry cards that open a page as their WHOLE purpose
 * (export settings, per-app settings, default prompt) converged onto this
 * shape too; [leadingIcon] carries their icon (null = the plain hub look).
 */
@Composable
fun SettingsHubCard(
    titleRes: Int,
    summaryRes: Int,
    leadingIcon: ImageVector? = null,
    /** Optional chevron announcement for TalkBack (TASK-732: the converged cards keep theirs). */
    openActionLabelRes: Int? = null,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onOpen() },
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingIcon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(summaryRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = openActionLabelRes?.let { stringResource(it) },
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
