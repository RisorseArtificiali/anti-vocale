package com.localai.bridge.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localai.bridge.R
import com.localai.bridge.di.AppContainer
import com.localai.bridge.ui.viewmodel.LogEntry
import com.localai.bridge.ui.viewmodel.LogsViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LogsTab(viewModel: LogsViewModel = AppContainer.logsViewModel) {
    val logs by viewModel.logs.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.logs_recent_requests, logs.size),
                    style = MaterialTheme.typography.titleSmall
                )
                TextButton(onClick = { viewModel.clearLogs() }) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.logs_clear))
                }
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.logs_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.logs_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    LogEntryItem(log = log)
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogEntryItem(log: LogEntry) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Request type badge
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = log.type.name,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (log.type == LogEntry.Type.AUDIO)
                                Icons.Default.AudioFile else Icons.Default.TextFields,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = when (log.status) {
                            LogEntry.Status.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
                            LogEntry.Status.ERROR -> MaterialTheme.colorScheme.errorContainer
                            LogEntry.Status.PENDING -> MaterialTheme.colorScheme.secondaryContainer
                        }
                    )
                )

                // Timestamp
                Text(
                    text = formatTimestamp(log.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (log.status) {
                        LogEntry.Status.SUCCESS -> Icons.Default.CheckCircle
                        LogEntry.Status.ERROR -> Icons.Default.Error
                        LogEntry.Status.PENDING -> Icons.Default.HourglassEmpty
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = when (log.status) {
                        LogEntry.Status.SUCCESS -> MaterialTheme.colorScheme.primary
                        LogEntry.Status.ERROR -> MaterialTheme.colorScheme.error
                        LogEntry.Status.PENDING -> MaterialTheme.colorScheme.secondary
                    }
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = log.status.name,
                    style = MaterialTheme.typography.labelMedium
                )
                if (log.durationMs > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "(${log.durationMs}ms)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Task ID
            Text(
                text = stringResource(R.string.logs_task_id, log.taskId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Expanded content
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))

                // Prompt
                if (log.prompt.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.logs_prompt_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = log.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Result or Error
                when (log.status) {
                    LogEntry.Status.SUCCESS -> {
                        Text(
                            text = stringResource(R.string.logs_result_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = log.result,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                    shape = MaterialTheme.shapes.small
                                )
                                .padding(8.dp),
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Share and Copy buttons
                        if (log.result.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                // Copy button
                                IconButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    val clip = ClipData.newPlainText("transcription", log.result)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.copied_to_clipboard),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = context.getString(R.string.copy_transcription)
                                    )
                                }
                                // Share button
                                IconButton(onClick = {
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, log.result)
                                        type = "text/plain"
                                    }
                                    val shareIntent = Intent.createChooser(
                                        sendIntent,
                                        context.getString(R.string.share_transcription)
                                    )
                                    context.startActivity(shareIntent)
                                }) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = context.getString(R.string.share_transcription)
                                    )
                                }
                            }
                        }
                    }
                    LogEntry.Status.ERROR -> {
                        Text(
                            text = stringResource(R.string.logs_error_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = log.errorMessage ?: stringResource(R.string.logs_unknown_error),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                    shape = MaterialTheme.shapes.small
                                )
                                .padding(8.dp)
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun formatTimestamp(timestamp: Long): String {
    return timeFormat.format(Date(timestamp))
}
