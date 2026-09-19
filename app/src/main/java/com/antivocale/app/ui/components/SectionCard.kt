package com.antivocale.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * TASK-564/510: the ONE card-title idiom (primary icon + titleMedium Bold),
 * extracted from the ~30 inline copies the tabs had grown by 2026-09-19
 * (SettingsTab's collapsible-section cards most numerously; ModelTab's
 * headers were plain titleMedium and joined the idiom in the same pass).
 * ToggleSettingCard's header row is the original shape; this is it, shared.
 */
@Composable
fun CardTitleRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * TASK-510: the section-card scaffold (Card > 16dp Column spacedBy 12 >
 * title row > optional bodySmall description > divider > content) that
 * SettingsTab had copied inline 13+ times, so a styling change (radius,
 * spacing, header tint) touches one definition. Consumers with their own
 * inner layout use [CardTitleRow] inside a plain Card.
 */
@Composable
fun SectionCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    // Color.Unspecified keeps the platform Card default: cardColors treats
    // an unspecified containerColor as "resolve from the scheme" (verified
    // against the material3 bytecode), so every caller that passes nothing
    // renders exactly like a plain Card.
    containerColor: Color = Color.Unspecified,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CardTitleRow(icon = icon, title = title, iconTint = iconTint)
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            content()
        }
    }
}
