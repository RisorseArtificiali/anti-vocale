package com.antivocale.app.ui.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.antivocale.app.R

/**
 * TASK-842 (GH #157 part 3): the duplicate open-or-reprocess dialog, shared
 * by every INTERACTIVE intake surface (the History browse FAB renders it
 * directly; the share flow uses the platform-dialog form on its non-Compose
 * Activity, with the same four strings and arms). Dismiss outside the
 * buttons is the caller's conservative arm (open), mirroring the share
 * dialog's cancel.
 */
@Composable
fun DuplicateFoundDialog(
    modelName: String,
    onOpen: () -> Unit,
    onReprocess: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onOpen,
        title = { Text(stringResource(R.string.duplicate_found_title)) },
        text = { Text(stringResource(R.string.duplicate_found_message, modelName)) },
        confirmButton = {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.duplicate_found_open)) }
        },
        dismissButton = {
            TextButton(onClick = onReprocess) { Text(stringResource(R.string.duplicate_found_again)) }
        },
    )
}
