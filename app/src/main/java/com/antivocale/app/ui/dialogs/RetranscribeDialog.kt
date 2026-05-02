package com.antivocale.app.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.antivocale.app.ui.viewmodel.LogsViewModel

@Composable
fun RetranscribeDialog(
    availableBackends: List<LogsViewModel.BackendOption>,
    onBackendSelected: (backendId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedBackendId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.retranscribe_title)) },
        text = {
            if (availableBackends.isEmpty()) {
                Text(
                    text = stringResource(R.string.retranscribe_no_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column {
                    Text(
                        text = stringResource(R.string.retranscribe_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(Modifier.selectableGroup()) {
                        availableBackends.forEach { backend ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = selectedBackendId == backend.backendId,
                                        onClick = { selectedBackendId = backend.backendId }
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedBackendId == backend.backendId,
                                    onClick = null
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = backend.displayName,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                if (backend.isCurrentBackend) {
                                    FilterChip(
                                        selected = true,
                                        onClick = {},
                                        label = {
                                            Text(
                                                text = stringResource(R.string.retranscribe_active_badge),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedBackendId?.let { onBackendSelected(it) }
                },
                enabled = selectedBackendId != null
            ) {
                Text(stringResource(R.string.retranscribe_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
