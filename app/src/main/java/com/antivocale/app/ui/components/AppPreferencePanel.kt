package com.antivocale.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.antivocale.app.R

/**
 * Expandable panel with per-app preference settings.
 *
 * Shows switches for:
 * - Auto-copy transcription to clipboard
 * - Show share action in notification
 * - Quick Share Back (one-tap direct share)
 * - Notification sound selection
 *
 * @param packageName Package name for preferences
 * @param appName App display name
 * @param autoCopy Whether auto-copy is enabled
 * @param showShareAction Whether share action is enabled
 * @param quickShareBack Whether Quick Share Back is enabled
 * @param notificationSound Current notification sound
 * @param onAutoCopyChanged Callback when auto-copy switch is toggled
 * @param onShowShareActionChanged Callback when share action switch is toggled
 * @param onQuickShareBackChanged Callback when Quick Share Back switch is toggled
 * @param onNotificationSoundChanged Callback when notification sound is changed
 * @param onDismiss Callback to close the panel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPreferencePanel(
    packageName: String,
    appName: String,
    autoCopy: Boolean,
    showShareAction: Boolean,
    quickShareBack: Boolean,
    notificationSound: String,
    onAutoCopyChanged: (Boolean) -> Unit,
    onShowShareActionChanged: (Boolean) -> Unit,
    onQuickShareBackChanged: (Boolean) -> Unit,
    onNotificationSoundChanged: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var showSoundDropdown by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.per_app_settings_dialog_title, appName),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Auto-copy switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.per_app_settings_auto_copy_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Automatically copy transcription to clipboard",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoCopy,
                        onCheckedChange = onAutoCopyChanged
                    )
                }

                HorizontalDivider()

                // Show share action switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.per_app_settings_show_share_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Display share button in notification",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showShareAction,
                        onCheckedChange = onShowShareActionChanged
                    )
                }

                HorizontalDivider()

                // Quick Share Back switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.per_app_settings_quick_share_back_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.per_app_settings_quick_share_back_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = quickShareBack,
                        onCheckedChange = onQuickShareBackChanged
                    )
                }

                HorizontalDivider()

                // Notification sound dropdown
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.per_app_settings_notification_sound),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    ExposedDropdownMenuBox(
                        expanded = showSoundDropdown,
                        onExpandedChange = { showSoundDropdown = it }
                    ) {
                        OutlinedTextField(
                            value = notificationSound,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.per_app_settings_sound_label)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = showSoundDropdown)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )

                        ExposedDropdownMenu(
                            expanded = showSoundDropdown,
                            onDismissRequest = { showSoundDropdown = false }
                        ) {
                            listOf(
                                "default" to stringResource(R.string.per_app_settings_sound_default),
                                "silent" to stringResource(R.string.per_app_settings_sound_silent),
                                "chime" to stringResource(R.string.per_app_settings_sound_chime),
                                "bell" to stringResource(R.string.per_app_settings_sound_bell)
                            ).forEach { (soundId, soundLabel) ->
                                DropdownMenuItem(
                                    text = { Text(soundLabel) },
                                    onClick = {
                                        onNotificationSoundChanged(soundId)
                                        showSoundDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.per_app_settings_close))
            }
        }
    )
}
