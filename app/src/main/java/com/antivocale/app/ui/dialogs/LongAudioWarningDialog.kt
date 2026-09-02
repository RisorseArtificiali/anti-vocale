package com.antivocale.app.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.antivocale.app.R

/**
 * Pre-start advisory for long audio (TASK-432): shows the calibrated compute
 * estimate before the transcription begins. Purely advisory, the duration
 * ceilings are enforced by the AudioPreprocessor metadata pre-read.
 */
@Composable
fun LongAudioWarningDialog(
    durationMinutes: Int,
    estimateMinutes: Long,
    isRough: Boolean,
    modelDisplayName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.dialog_long_audio_title)) },
        text = {
            Column {
                Text(stringResource(
                    R.string.dialog_long_audio_message,
                    durationMinutes, modelDisplayName, estimateMinutes))
                if (isRough) {
                    Text(stringResource(R.string.dialog_long_audio_roughly),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) {
            Text(stringResource(R.string.dialog_long_audio_continue)) } },
        dismissButton = { TextButton(onClick = onCancel) {
            Text(stringResource(R.string.dialog_long_audio_cancel)) } },
    )
}
