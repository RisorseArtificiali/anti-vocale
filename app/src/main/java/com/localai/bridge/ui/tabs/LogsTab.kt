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
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                )
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
            // === COLLAPSED VIEW ===
            // Header row: Audio duration + Status icon + Relative time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Audio duration with mic icon
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatAudioDuration(log.audioDurationSeconds),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (log.type == LogEntry.Type.AUDIO) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.voice_message_duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Right: Status icon + Relative time
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (log.status) {
                            LogEntry.Status.SUCCESS -> Icons.Default.CheckCircle
                            LogEntry.Status.ERROR -> Icons.Default.Error
                            LogEntry.Status.PENDING -> Icons.Default.HourglassEmpty
                        },
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = when (log.status) {
                            LogEntry.Status.SUCCESS -> MaterialTheme.colorScheme.primary
                            LogEntry.Status.ERROR -> MaterialTheme.colorScheme.error
                            LogEntry.Status.PENDING -> MaterialTheme.colorScheme.secondary
                        }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatRelativeTime(log.timestamp, context),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Preview text (transcription preview for successful audio)
            if (log.status == LogEntry.Status.SUCCESS && log.result.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = getPreviewText(log.result),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else if (log.status == LogEntry.Status.ERROR) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = log.errorMessage ?: stringResource(R.string.logs_unknown_error),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (log.status == LogEntry.Status.PENDING) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.transcription_started),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // === EXPANDED VIEW ===
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(12.dp))

                // Full transcription result
                when (log.status) {
                    LogEntry.Status.SUCCESS -> {
                        Text(
                            text = stringResource(R.string.logs_result_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = log.result,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                    shape = MaterialTheme.shapes.small
                                )
                                .padding(8.dp)
                        )

                        // Metadata row
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Timestamp
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = formatFullTimestamp(log.timestamp, context),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // Processing time
                            if (log.durationMs > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Timer,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.processed_in, formatProcessingTime(log.durationMs)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Task ID (less prominent)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.logs_task_id, log.taskId.take(8)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )

                        // Action buttons
                        if (log.result.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                // Copy button
                                TextButton(onClick = {
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
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.copy))
                                }
                                // Share button
                                TextButton(onClick = {
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
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.share_transcription))
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
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = log.errorMessage ?: stringResource(R.string.logs_unknown_error),
                            style = MaterialTheme.typography.bodySmall,
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

// Format audio duration: 83.5 -> "1:23", 45.0 -> "0:45"
private fun formatAudioDuration(seconds: Double): String {
    if (seconds <= 0) return "0:00"
    val totalSeconds = seconds.toInt()
    val minutes = totalSeconds / 60
    val secs = totalSeconds % 60
    return "$minutes:${secs.toString().padStart(2, '0')}"
}

// Format relative time: "5 min ago", "Yesterday 14:32", "Mar 2, 14:32"
private fun formatRelativeTime(timestamp: Long, context: Context): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val locale = context.resources.configuration.locales.get(0)
    return when {
        diff < 60_000 -> "${diff / 1000}s ago"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> {
            val date = Date(timestamp)
            val today = Date(now)
            val cal1 = Calendar.getInstance().apply { time = date }
            val cal2 = Calendar.getInstance().apply { time = today }
            val timeFormat = SimpleDateFormat("HH:mm", locale)
            if (cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR) - 1) {
                "${context.getString(R.string.yesterday)} ${timeFormat.format(date)}"
            } else {
                SimpleDateFormat("MMM d, HH:mm", locale).format(date)
            }
        }
    }
}

// Format full timestamp: "Today 14:32" or "Mar 2, 14:32"
private fun formatFullTimestamp(timestamp: Long, context: Context): String {
    val date = Date(timestamp)
    val now = Date(System.currentTimeMillis())
    val cal1 = Calendar.getInstance().apply { time = date }
    val cal2 = Calendar.getInstance().apply { time = now }
    val locale = context.resources.configuration.locales.get(0)
    val timeFormat = SimpleDateFormat("HH:mm", locale)
    return if (cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR) &&
        cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)) {
        "${context.getString(R.string.today)} ${timeFormat.format(date)}"
    } else {
        SimpleDateFormat("MMM d, HH:mm", locale).format(date)
    }
}

// Format processing time: 2300ms -> "2.3s", 500ms -> "0.5s"
private fun formatProcessingTime(durationMs: Long): String {
    return if (durationMs >= 1000) {
        String.format("%.1fs", durationMs / 1000.0)
    } else {
        "${durationMs}ms"
    }
}

// Get preview text with ellipsis
private fun getPreviewText(text: String, maxLength: Int = 50): String {
    if (text.length <= maxLength) return text
    return text.take(maxLength) + "…"
}
