package com.antivocale.app.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.antivocale.app.R
import com.antivocale.app.data.ExternalModelRecord

/**
 * The ShareExternal family chooser, rendered by the share flow's Compose
 * surface: one row per imported model. The row label is the same derivation
 * the platform list dialog carried (display name plus the language list).
 * A row tap hands the record back and the flow removes its entry; dismiss
 * outside the rows is the cancel arm (the flow accounts its slot and ends),
 * mirroring the old setOnCancelListener.
 */
@Composable
fun ExternalModelChooserDialog(
    records: List<ExternalModelRecord>,
    onPick: (ExternalModelRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_target_external)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                records.forEach { record ->
                    TextButton(
                        onClick = { onPick(record) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(recordLabel(record))
                    }
                }
            }
        },
        confirmButton = {},
    )
}

private fun recordLabel(record: ExternalModelRecord): String =
    record.displayName + if (record.languages.isEmpty()) "" else " (" + record.languages.joinToString(", ") + ")"
