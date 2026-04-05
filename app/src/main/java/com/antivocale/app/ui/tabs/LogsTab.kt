package com.antivocale.app.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.di.AppContainer
import com.antivocale.app.ui.components.SwipeAction
import com.antivocale.app.ui.components.SwipeToRevealBox
import com.antivocale.app.ui.components.rememberSwipeToRevealState
import com.antivocale.app.ui.viewmodel.LogEntry
import com.antivocale.app.ui.viewmodel.LogsViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Copies transcription text to clipboard and shows a toast.
 */
private fun copyTranscriptionToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
    val clip = ClipData.newPlainText("transcription", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
}

/**
 * Shares transcription text via an intent chooser.
 */
private fun shareTranscription(context: Context, text: String) {
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, text)
        type = "text/plain"
    }
    val shareIntent = Intent.createChooser(
        sendIntent,
        context.getString(R.string.share_transcription)
    )
    context.startActivity(shareIntent)
}

/**
 * Builds the list of swipe actions for a log entry.
 * Copy and Share only appear for successful transcriptions with non-empty results.
 * Delete always appears.
 */
private fun buildSwipeActions(
    log: LogEntry,
    context: Context,
    viewModel: LogsViewModel,
    onDeleted: (LogEntry) -> Unit,
    copyLabel: String,
    shareLabel: String,
    deleteLabel: String,
    colors: ColorScheme
): List<SwipeAction> {
    val actions = mutableListOf<SwipeAction>()

    if (log.status == LogEntry.Status.SUCCESS && log.result.isNotEmpty()) {
        actions.add(
            SwipeAction(
                icon = Icons.Default.ContentCopy,
                label = copyLabel,
                tint = colors.onPrimaryContainer,
                background = colors.primaryContainer,
                onClick = { copyTranscriptionToClipboard(context, log.result) }
            )
        )
        actions.add(
            SwipeAction(
                icon = Icons.Default.Share,
                label = shareLabel,
                tint = colors.onSecondaryContainer,
                background = colors.secondaryContainer,
                onClick = { shareTranscription(context, log.result) }
            )
        )
    }

    actions.add(
        SwipeAction(
            icon = Icons.Default.Delete,
            label = deleteLabel,
            tint = colors.onErrorContainer,
            background = colors.errorContainer,
            onClick = { onDeleted(log); viewModel.deleteLog(log.id) }
        )
    )

    return actions
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsTab(
    viewModel: LogsViewModel = AppContainer.logsViewModel,
    highlightTaskId: String? = null
) {
    val logs by viewModel.logs.collectAsState()
    val filteredLogs by viewModel.filteredLogs.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val context = LocalContext.current
    val swipeActionMode by AppContainer.preferencesManager.swipeActionMode
        .collectAsState(initial = PreferencesManager.DEFAULT_SWIPE_ACTION_MODE)

    // Lifted expanded state — tracks which taskIds are expanded
    var expandedTaskIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var revealedLogId by remember { mutableStateOf<String?>(null) }

    var showClearDialog by remember { mutableStateOf(false) }
    var recentlyDeletedEntry by remember { mutableStateOf<LogEntry?>(null) }

    // Undo deletion via Snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(recentlyDeletedEntry) {
        val entry = recentlyDeletedEntry ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.logs_entry_deleted),
            actionLabel = context.getString(R.string.logs_undo),
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.addLog(entry)
        }
        recentlyDeletedEntry = null
    }

    // Group filtered logs by date
    val groupedLogs = remember(filteredLogs) {
        groupLogsByDate(filteredLogs)
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Clear-all confirmation dialog
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text(stringResource(R.string.logs_clear)) },
                text = { Text(stringResource(R.string.logs_clear_confirmation)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.clearLogs()
                        showClearDialog = false
                    }) {
                        Text(stringResource(R.string.logs_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(stringResource(R.string.logs_cancel))
                    }
                }
            )
        }

        // Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.logs_recent_requests, logs.size),
                        style = MaterialTheme.typography.titleSmall
                    )
                    TextButton(
                        onClick = { showClearDialog = true },
                        enabled = logs.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.logs_clear))
                    }
                }

                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    placeholder = { Text(stringResource(R.string.logs_search_placeholder)) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.clearSearch() }) {
                                Icon(Icons.Default.Clear, contentDescription = null)
                            }
                        }
                    },
                    singleLine = true
                )
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
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
        } else if (filteredLogs.isEmpty()) {
            // Search yielded no results
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.SearchOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.logs_search_no_results),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.logs_search_no_results_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            val listState = rememberLazyListState()

            // Scroll to and expand the highlighted entry
            LaunchedEffect(highlightTaskId) {
                val taskId = highlightTaskId ?: return@LaunchedEffect
                // Clear active search so entry is visible
                if (searchQuery.isNotEmpty()) {
                    viewModel.clearSearch()
                    // Wait for clearSearch to propagate to filteredLogs
                    viewModel.filteredLogs.first()
                }
                // Find flat index in grouped list
                val freshGrouped = groupLogsByDate(filteredLogs)
                val flatIndex = indexOfTaskId(freshGrouped, taskId)
                if (flatIndex >= 0) {
                    expandedTaskIds = expandedTaskIds + taskId
                    listState.animateScrollToItem(flatIndex)
                }
                viewModel.clearHighlight()
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 4.dp,
                    bottom = 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                )
            ) {
                groupedLogs.forEach { (dateLabel, dateLogs) ->
                    // Date group header
                    item(key = "header_$dateLabel") {
                        DateGroupHeader(label = dateLabel, count = dateLogs.size)
                    }

                    // Logs for this date
                    items(dateLogs, key = { it.id }) { log ->
                        val isExpanded = log.taskId in expandedTaskIds

                        if (swipeActionMode == "REVEAL") {
                            // Swipe-to-reveal mode: show action buttons behind the card
                            val revealState = rememberSwipeToRevealState()

                            // Only one message can be revealed at a time
                            LaunchedEffect(log.id, revealState.isRevealed) {
                                if (revealState.isRevealed && revealedLogId != log.id) {
                                    revealedLogId = log.id
                                }
                            }
                            LaunchedEffect(log.id, revealedLogId) {
                                if (revealedLogId != null && revealedLogId != log.id && revealState.isRevealed) {
                                    revealState.reset()
                                }
                            }

                            val colorScheme = MaterialTheme.colorScheme
                            val actions = remember(log.id, log.status, log.result, colorScheme) {
                                buildSwipeActions(
                                    log = log,
                                    context = context,
                                    viewModel = viewModel,
                                    onDeleted = { entry -> recentlyDeletedEntry = entry },
                                    copyLabel = context.getString(R.string.swipe_action_copy_description),
                                    shareLabel = context.getString(R.string.swipe_action_share_description),
                                    deleteLabel = context.getString(R.string.swipe_action_delete_description),
                                    colors = colorScheme
                                )
                            }
                            SwipeToRevealBox(
                                state = revealState,
                                actions = actions
                            ) {
                                LogEntryItem(
                                    log = log,
                                    searchQuery = searchQuery,
                                    expanded = isExpanded,
                                    onExpandChange = { expanded ->
                                        if (revealState.isRevealed) {
                                            revealState.reset()
                                        } else {
                                            expandedTaskIds = if (expanded) {
                                                expandedTaskIds + log.taskId
                                            } else {
                                                expandedTaskIds - log.taskId
                                            }
                                        }
                                    }
                                )
                            }
                        } else {
                            // Immediate delete mode: original SwipeToDismissBox behavior
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = {
                                    if (it == SwipeToDismissBoxValue.EndToStart) {
                                        recentlyDeletedEntry = log
                                        viewModel.deleteLog(log.id)
                                        true
                                    } else false
                                }
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    val color by animateColorAsState(
                                        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart ||
                                            dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
                                        ) MaterialTheme.colorScheme.errorContainer
                                        else MaterialTheme.colorScheme.surface,
                                        label = "swipe_bg"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(color)
                                            .padding(horizontal = 20.dp),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.logs_delete_entry),
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                },
                                enableDismissFromStartToEnd = false
                            ) {
                                LogEntryItem(
                                    log = log,
                                    searchQuery = searchQuery,
                                    expanded = isExpanded,
                                    onExpandChange = { expanded ->
                                        expandedTaskIds = if (expanded) {
                                            expandedTaskIds + log.taskId
                                        } else {
                                            expandedTaskIds - log.taskId
                                        }
                                    }
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    }
}

@Composable
private fun DateGroupHeader(label: String, count: Int) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "($count)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

internal data class DateGroup(val label: String, val logs: List<LogEntry>)

private fun groupLogsByDate(logs: List<LogEntry>): List<DateGroup> {
    val now = System.currentTimeMillis()
    val todayStart = startOfDay(now)
    val yesterdayStart = startOfDay(now - 86_400_000)

    val today = mutableListOf<LogEntry>()
    val yesterday = mutableListOf<LogEntry>()
    val older = mutableListOf<LogEntry>()

    logs.forEach { log ->
        when {
            log.timestamp >= todayStart -> today.add(log)
            log.timestamp >= yesterdayStart -> yesterday.add(log)
            else -> older.add(log)
        }
    }

    val result = mutableListOf<DateGroup>()
    if (today.isNotEmpty()) {
        result.add(DateGroup("Today", today))
    }
    if (yesterday.isNotEmpty()) {
        result.add(DateGroup("Yesterday", yesterday))
    }
    if (older.isNotEmpty()) {
        // Group older entries by date
        val olderByDate = older.groupBy { log ->
            val date = Date(log.timestamp)
            val cal = Calendar.getInstance().apply { time = date }
            "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_YEAR)}"
        }
        olderByDate.forEach { (dateKey, dateLogs) ->
            val date = Date(dateLogs.first().timestamp)
            val cal = Calendar.getInstance().apply { time = date }
            val month = cal.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.ENGLISH)
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val year = cal.get(Calendar.YEAR)
            val label = if (year == Calendar.getInstance().get(Calendar.YEAR)) {
                "$month $day"
            } else {
                "$month $day, $year"
            }
            result.add(DateGroup(label, dateLogs))
        }
    }

    return result
}

private fun startOfDay(timestamp: Long): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/**
 * Computes the flat LazyColumn index for a given taskId within grouped logs.
 * Each group contributes 1 header item + N entry items.
 * Returns -1 if not found.
 */
internal fun indexOfTaskId(groupedLogs: List<DateGroup>, taskId: String): Int {
    var flatIndex = 0
    for (group in groupedLogs) {
        // Date group header occupies one slot
        flatIndex++
        for (log in group.logs) {
            if (log.taskId == taskId) return flatIndex
            flatIndex++
        }
    }
    return -1
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogEntryItem(
    log: LogEntry,
    searchQuery: String = "",
    expanded: Boolean = false,
    onExpandChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = { onExpandChange(!expanded) }
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
                    text = highlightText(
                        getPreviewText(log.result),
                        searchQuery,
                        MaterialTheme.colorScheme.tertiary
                    ),
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
                if (log.result.isNotEmpty()) {
                    // Interim transcription text during progressive VAD transcription
                    Text(
                        text = highlightText(
                            getPreviewText(log.result),
                            searchQuery,
                            MaterialTheme.colorScheme.tertiary
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.transcription_started),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                            text = highlightText(
                                log.result,
                                searchQuery,
                                MaterialTheme.colorScheme.tertiary
                            ),
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
                                    copyTranscriptionToClipboard(context, log.result)
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
                                    shareTranscription(context, log.result)
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
                    LogEntry.Status.PENDING -> {
                        if (log.result.isNotEmpty()) {
                            // Interim transcription text during progressive VAD transcription
                            Text(
                                text = stringResource(R.string.transcription_started),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = highlightText(
                                    log.result,
                                    searchQuery,
                                    MaterialTheme.colorScheme.tertiary
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                        shape = MaterialTheme.shapes.small
                                    )
                                    .padding(8.dp)
                            )
                        }
                    }
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

// Highlight all occurrences of query in text (case-insensitive)
@Composable
private fun highlightText(
    text: String,
    query: String,
    highlightColor: androidx.compose.ui.graphics.Color
): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)

    return buildAnnotatedString {
        var currentIndex = 0
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()

        while (currentIndex < text.length) {
            val matchIndex = lowerText.indexOf(lowerQuery, currentIndex)
            if (matchIndex == -1) {
                append(text.substring(currentIndex))
                break
            }
            // Append text before the match
            if (matchIndex > currentIndex) {
                append(text.substring(currentIndex, matchIndex))
            }
            // Append the matched text with highlight
            withStyle(SpanStyle(
                color = highlightColor,
                fontWeight = FontWeight.Bold,
                background = highlightColor.copy(alpha = 0.15f)
            )) {
                append(text.substring(matchIndex, matchIndex + query.length))
            }
            currentIndex = matchIndex + query.length
        }
    }
}
