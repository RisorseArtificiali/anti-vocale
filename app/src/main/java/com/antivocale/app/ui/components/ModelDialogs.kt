package com.antivocale.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.antivocale.app.R

/**
 * Shared delete confirmation dialog with three-branch transcription awareness.
 *
 * - **Hard block**: transcribing AND active model → single "Understood" button, no delete.
 * - **Soft warning**: transcribing but inactive → delete + cancel (inactive model warning).
 * - **Normal**: not transcribing → delete + cancel (standard message).
 */
@Composable
fun DeleteConfirmationDialog(
    modelName: String,
    isTranscribing: Boolean,
    isActiveModel: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (isTranscribing && isActiveModel) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.dialog_transcription_active_title)) },
            text = { Text(stringResource(R.string.dialog_delete_active_model_message, modelName)) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_understood))
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = {
                if (isTranscribing) {
                    Text(stringResource(R.string.dialog_delete_inactive_model_message, modelName))
                } else {
                    Text(stringResource(R.string.dialog_delete_message, modelName))
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/**
 * Shared download confirmation dialog with title, message, and confirm/dismiss buttons.
 */
@Composable
fun DownloadConfirmationDialog(
    title: String,
    message: String,
    confirmButtonText: String = stringResource(R.string.download),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmButtonText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
