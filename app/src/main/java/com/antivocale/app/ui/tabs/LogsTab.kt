package com.antivocale.app.ui.tabs

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.antivocale.app.MainActivity
import com.antivocale.app.R
import com.antivocale.app.util.AppInfoUtils
import com.antivocale.app.util.SharedAudioHandler
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.service.InferenceService
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.Crossfade
import com.antivocale.app.ui.components.SkeletonTranscriptionCard
import com.antivocale.app.ui.components.SkeletonTranscriptionPreview
import com.antivocale.app.ui.components.SwipeAction
import com.antivocale.app.ui.components.VadAdvisoryCard
import com.antivocale.app.ui.components.SwipeToRevealBox
import com.antivocale.app.ui.components.rememberSwipeToRevealState
import com.antivocale.app.util.ToastCompat
import com.antivocale.app.util.FeedbackHelper
import com.antivocale.app.ui.viewmodel.LogEntry
import com.antivocale.app.ui.viewmodel.LogsViewModel
import androidx.compose.runtime.produceState
import com.antivocale.app.ui.dialogs.LongAudioWarningDialog
import com.antivocale.app.ui.dialogs.RetranscribeDialog
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.*

internal data class ConversationGroup(
    val packageName: String?,
    val appName: String,
    override val logs: List<LogEntry>
) : LogGroup

internal interface LogGroup {
    val logs: List<LogEntry>
}

/**
 * TASK-374: opens the feedback email pre-filled with this entry's facts and a
 * TRUNCATED excerpt (the user reviews/edits before sending; full transcripts
 * are never auto-attached).
 */
private fun reportTranscription(context: Context, log: LogEntry) {
    FeedbackHelper.sendOrCopy(
        context,
        FeedbackHelper.transcriptFeedbackSubject(log.taskId),
        FeedbackHelper.buildTranscriptFeedbackBody(
            FeedbackHelper.TranscriptFacts(
                taskId = log.taskId,
                modelName = log.modelName ?: "-",
                audioDurationSeconds = log.audioDurationSeconds,
                processingTimeMs = log.durationMs,
                status = log.status.name,
                excerpt = log.result,
                errorMessage = log.errorMessage,
            ),
            FeedbackHelper.TranscriptLabels(
                task = context.getString(R.string.feedback_label_task),
                model = context.getString(R.string.feedback_label_model),
                duration = context.getString(R.string.feedback_label_duration),
                time = context.getString(R.string.feedback_label_time),
                status = context.getString(R.string.feedback_label_status),
                excerpt = context.getString(R.string.feedback_label_excerpt),
                truncatedNote = context.getString(R.string.feedback_label_truncated),
            ),
        )
    )
}

/**
 * Copies transcription text to clipboard and shows a toast.
 */
private fun copyTranscriptionToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
    val clip = ClipData.newPlainText(context.getString(R.string.clipboard_label_transcription), text)
    clipboard.setPrimaryClip(clip)
    ToastCompat.show(context, context.getString(R.string.copied_to_clipboard))
}

/**
 * Per-task cancel (GH #52 follow-up): tells the service to drop one queued
 * request or abort the in-flight one, leaving the rest of the queue intact.
 */
private fun cancelTask(context: Context, taskId: String) {
    val intent = Intent(context, InferenceService::class.java).apply {
        action = InferenceService.ACTION_CANCEL_TASK
        putExtra(InferenceService.EXTRA_CANCEL_TASK_ID, taskId)
    }
    context.startService(intent)
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
 * A result-card action rendered either as a compact icon-only button (localized
 * contentDescription) or as a labeled TextButton, per the user preference.
 */
@Composable
private fun RowScope.ResultActionButton(
    compact: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    labelRes: Int,
    contentDescriptionRes: Int = labelRes
) {
    if (compact) {
        // TASK-381: no explicit size, IconButton defaults to 48dp touch target
        IconButton(onClick = onClick) {
            Icon(
                icon,
                contentDescription = stringResource(contentDescriptionRes),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        TextButton(
            onClick = onClick,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(labelRes))
        }
    }
}

/**
 * Long-press context menu actions for a log entry (GH #52). Kept as a pure
 * function so the gating mirrors [buildSwipeActions] and stays unit-tested.
 */
enum class ContextMenuAction { RETRANSCRIBE, CANCEL, COPY, REPORT, DELETE }

/** Shared "Queued" label (collapsed and expanded views render it identically). */
@Composable
private fun QueuedStatusLabel() {
    Text(
        text = stringResource(R.string.logs_status_queued),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary
    )
}

fun buildContextMenuActions(log: LogEntry, canRetranscribe: Boolean): List<ContextMenuAction> {
    val actions = mutableListOf<ContextMenuAction>()
    // In-flight entries (queued or processing) can be cancelled (GH #52 follow-up);
    // Cancel leads while the item is in flight, it is the primary action there.
    if (log.status == LogEntry.Status.QUEUED || log.status == LogEntry.Status.PROCESSING) {
        actions.add(ContextMenuAction.CANCEL)
    }
    if (canRetranscribe) actions.add(ContextMenuAction.RETRANSCRIBE)
    // Mirrors buildSwipeActions: Copy needs a completed result, not interim text.
    if (log.hasCompletedResult) actions.add(ContextMenuAction.COPY)
    // TASK-374: report entry available on EVERY entry (errors are the most
    // valuable reports and have no completed result).
    actions.add(ContextMenuAction.REPORT)
    actions.add(ContextMenuAction.DELETE)
    return actions
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

    if (log.hasCompletedResult) {
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
    viewModel: LogsViewModel = hiltViewModel(),
    highlightTaskId: String? = null
) {
    val logs by viewModel.logs.collectAsState()
    val filteredLogs by viewModel.filteredLogs.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val context = LocalContext.current
    val swipeActionMode by viewModel.swipeActionMode
        .collectAsState(initial = PreferencesManager.DEFAULT_SWIPE_ACTION_MODE)
    val showTaskDetails by viewModel.showTaskDetails.collectAsState()
    val showVadAdvisory by viewModel.showVadAdvisory.collectAsState()
    val groupByConversation by viewModel.groupLogsByConversation.collectAsState()

    // Lifted expanded state — tracks which taskIds are expanded
    var expandedTaskIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedConversationGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var revealedLogId by remember { mutableStateOf<String?>(null) }

    var showClearDialog by remember { mutableStateOf(false) }
    var recentlyDeletedEntry by remember { mutableStateOf<LogEntry?>(null) }
    var retranscribeTarget by remember { mutableStateOf<LogEntry?>(null) }
    val pendingLongAudioWarning by viewModel.pendingLongAudioWarning.collectAsState()
    val showRetranscribeButton by viewModel.showRetranscribeButton.collectAsState()
    val compactActions by viewModel.compactResultActions.collectAsState()

    if (retranscribeTarget != null) {
        val retranscribeBackends by produceState(
            initialValue = emptyList<LogsViewModel.BackendOption>(),
            key1 = retranscribeTarget
        ) {
            value = viewModel.getAvailableAudioBackendsWithModels(context)
        }
        RetranscribeDialog(
            availableBackends = retranscribeBackends,
            onBackendSelected = { backendId ->
                retranscribeTarget?.let { entry ->
                    viewModel.reTranscribeWithBackend(entry, backendId, context)
                }
                retranscribeTarget = null
            },
            onDismiss = { retranscribeTarget = null }
        )
    }

    // Long-audio advisory (TASK-432): renders only after the retranscribe gate
    // decides a warning is warranted; state lives in the view model so
    // Confirm/Cancel survive recomposition.
    pendingLongAudioWarning?.let { warning ->
        LongAudioWarningDialog(
            durationMinutes = warning.durationMinutes,
            estimateMinutes = warning.estimateMinutes,
            isRough = warning.isRough,
            modelDisplayName = warning.modelDisplayName,
            onConfirm = { viewModel.confirmLongAudioWarning() },
            onCancel = { viewModel.cancelLongAudioWarning() }
        )
    }

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

    // Group filtered logs by date or conversation
    val groupedLogs = remember(filteredLogs) {
        groupLogsByDate(filteredLogs, context)
    }
    val conversationGroups = remember(filteredLogs, groupByConversation) {
        if (groupByConversation) groupLogsByConversation(filteredLogs, context) else emptyList()
    }

    // Clear-all confirmation dialog (outside Scaffold so it overlays everything)
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        // Scaffold insets are zeroed above (edge-to-edge list); consume the param
        // explicitly so lint does not flag it as an ignored safety contract.
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val interruptedText by viewModel.interruptedTranscription.collectAsState()

            if (interruptedText != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.transcription_interrupted_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = interruptedText!!.take(200),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 3
                            )
                        }
                        TextButton(onClick = { viewModel.dismissInterruptedTranscription() }) {
                            Text(stringResource(R.string.action_dismiss))
                        }
                    }
                }
            }

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 32.dp),
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (filteredLogs.isEmpty()) {
                // Search yielded no results
                Box(
                    modifier = Modifier
                        .weight(1f),
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val listState = rememberLazyListState()
                val listScope = rememberCoroutineScope()

                fun collapseAndScrollTo(taskId: String, groups: List<LogGroup>) {
                    expandedTaskIds = expandedTaskIds - taskId
                    val idx = indexOfTaskIdInGroups(groups, taskId)
                    if (idx >= 0) listScope.launch { listState.animateScrollToItem(idx) }
                }

                // Scroll to and expand the highlighted entry
                LaunchedEffect(highlightTaskId) {
                    val taskId = highlightTaskId ?: return@LaunchedEffect
                    // Clear active search so entry is visible
                    if (searchQuery.isNotEmpty()) {
                        viewModel.clearSearch()
                        // Wait for clearSearch to propagate to filteredLogs
                        viewModel.filteredLogs.first()
                    }
                    expandedTaskIds = expandedTaskIds + taskId
                    val flatIndex = if (groupByConversation) {
                        val freshConversation = groupLogsByConversation(filteredLogs, context)
                        val entry = filteredLogs.find { it.taskId == taskId }
                        val groupKey = entry?.sourcePackageName ?: "__unknown__"
                        expandedConversationGroups = expandedConversationGroups + groupKey
                        indexOfTaskIdInGroups(freshConversation, taskId)
                    } else {
                        val freshGrouped = groupLogsByDate(filteredLogs, context)
                        indexOfTaskIdInGroups(freshGrouped, taskId)
                    }
                    if (flatIndex >= 0) {
                        listState.animateScrollToItem(flatIndex)
                    }
                    viewModel.clearHighlight()
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        bottom = 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    )
                ) {
                    item(key = "header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Locale-safe: weight + ellipsis keeps the Clear button
                                // anchored under longer locales (TASK-345)
                                Text(
                                    text = stringResource(R.string.logs_recent_requests, logs.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
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
                                            Icon(
                                                Icons.Default.Clear,
                                                contentDescription = stringResource(R.string.clear_search)
                                            )
                                        }
                                    }
                                },
                                singleLine = true
                            )
                        }
                    }
                    item(key = "vad_advisory") {
                        VadAdvisoryCard(
                            visible = showVadAdvisory,
                            onDismiss = { viewModel.dismissVadAdvisory() },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                    if (groupByConversation && conversationGroups.isNotEmpty()) {
                        conversationGroups.forEach { group ->
                            val groupKey = group.packageName ?: "__unknown__"
                            val isGroupExpanded = groupKey in expandedConversationGroups
                            item(key = "conv_$groupKey") {
                                ConversationGroupHeader(
                                    appName = group.appName,
                                    count = group.logs.size,
                                    lastTimestamp = group.logs.first().timestamp,
                                    expanded = isGroupExpanded,
                                    onToggle = {
                                        expandedConversationGroups = if (isGroupExpanded) {
                                            expandedConversationGroups - groupKey
                                        } else {
                                            expandedConversationGroups + groupKey
                                        }
                                    }
                                )
                            }
                            if (isGroupExpanded) {
                                items(group.logs, key = { it.id }) { log ->
                                    LogEntryWithSwipe(
                                        log = log,
                                        searchQuery = searchQuery,
                                        isExpanded = log.taskId in expandedTaskIds,
                                        swipeActionMode = swipeActionMode,
                                        showTaskDetails = showTaskDetails,
                                        revealedLogId = revealedLogId,
                                        onRevealedLogIdChange = { revealedLogId = it },
                                        onExpandChange = { expanded ->
                                            expandedTaskIds = if (expanded) {
                                                expandedTaskIds + log.taskId
                                            } else {
                                                expandedTaskIds - log.taskId
                                            }
                                        },
                                        onSwipeCollapse = { collapseAndScrollTo(log.taskId, conversationGroups) },
                                        onDeleted = { entry -> recentlyDeletedEntry = entry },
                                        onDeleteLog = { id -> viewModel.deleteLog(id) },
                                        viewModel = viewModel,
                                        onRetranscribe = if (showRetranscribeButton && log.type == LogEntry.Type.AUDIO && log.filePath != null) {{ retranscribeTarget = log }} else null,
                                        compactActions = compactActions
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        }
                    } else {
                        groupedLogs.forEach { (dateLabel, dateLogs) ->
                            // Date group header
                            item(key = "header_$dateLabel") {
                                DateGroupHeader(label = dateLabel, count = dateLogs.size)
                            }

                            // Logs for this date
                            items(dateLogs, key = { it.id }) { log ->
                                LogEntryWithSwipe(
                                    log = log,
                                    searchQuery = searchQuery,
                                    isExpanded = log.taskId in expandedTaskIds,
                                    swipeActionMode = swipeActionMode,
                                    showTaskDetails = showTaskDetails,
                                    revealedLogId = revealedLogId,
                                    onRevealedLogIdChange = { revealedLogId = it },
                                    onExpandChange = { expanded ->
                                        expandedTaskIds = if (expanded) {
                                            expandedTaskIds + log.taskId
                                        } else {
                                            expandedTaskIds - log.taskId
                                        }
                                    },
                                    onSwipeCollapse = { collapseAndScrollTo(log.taskId, groupedLogs) },
                                    onDeleted = { entry -> recentlyDeletedEntry = entry },
                                    onDeleteLog = { id -> viewModel.deleteLog(id) },
                                    viewModel = viewModel,
                                    onRetranscribe = if (showRetranscribeButton && log.type == LogEntry.Type.AUDIO && log.filePath != null) {{ retranscribeTarget = log }} else null,
                                    compactActions = compactActions
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }
            }
        }
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
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

internal data class DateGroup(val label: String, override val logs: List<LogEntry>) : LogGroup

private fun groupLogsByDate(logs: List<LogEntry>, context: Context): List<DateGroup> {
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
        result.add(DateGroup(context.getString(R.string.today), today))
    }
    if (yesterday.isNotEmpty()) {
        result.add(DateGroup(context.getString(R.string.yesterday_label), yesterday))
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

/** Fixed LazyColumn items above the date groups: header (0) and vad_advisory (1). */
private const val FIXED_ITEMS_ABOVE_GROUPS = 2

internal fun indexOfTaskIdInGroups(groups: List<LogGroup>, taskId: String): Int {
    var flatIndex = FIXED_ITEMS_ABOVE_GROUPS
    for (group in groups) {
        flatIndex++
        for (log in group.logs) {
            if (log.taskId == taskId) return flatIndex
            flatIndex++
        }
    }
    return -1
}

/**
 * Inline warning shown in the expanded view when a transcription completed but one or
 * more audio chunks were skipped (e.g. low-RAM OOM). Mirrors the "transcription
 * interrupted" banner language: errorContainer surface + Warning icon.
 */
@Composable
private fun PartialTranscriptionBanner(failedChunkCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = pluralStringResource(R.plurals.transcription_partial, failedChunkCount, failedChunkCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LogEntryItem(
    log: LogEntry,
    searchQuery: String = "",
    expanded: Boolean = false,
    showTaskDetails: Boolean = false,
    onExpandChange: (Boolean) -> Unit = {},
    onRetranscribe: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    compactActions: Boolean = PreferencesManager.DEFAULT_COMPACT_RESULT_ACTIONS
) {
    val context = LocalContext.current
    var contextMenuExpanded by remember { mutableStateOf(false) }
    val menuActions = remember(log.id, log.result, onRetranscribe) {
        buildContextMenuActions(log, canRetranscribe = onRetranscribe != null)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .combinedClickable(
                onClick = { onExpandChange(!expanded) },
                onLongClick = { contextMenuExpanded = true }
            )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Long-press context menu (GH #52), anchored to the card's top-start.
            // Composed only while open: idle rows carry just the boolean flag.
            if (contextMenuExpanded) DropdownMenu(
                expanded = true,
                onDismissRequest = { contextMenuExpanded = false }
            ) {
                menuActions.forEach { action ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    when (action) {
                                        ContextMenuAction.RETRANSCRIBE -> R.string.retranscribe
                                        ContextMenuAction.CANCEL -> R.string.logs_cancel
                                        ContextMenuAction.COPY -> R.string.copy
                                        ContextMenuAction.REPORT -> R.string.feedback_report_transcription
                                        ContextMenuAction.DELETE -> R.string.logs_delete_entry
                                    }
                                )
                            )
                        },
                        leadingIcon = {
                            Icon(
                                when (action) {
                                    ContextMenuAction.RETRANSCRIBE -> Icons.Default.Refresh
                                    ContextMenuAction.CANCEL -> Icons.Default.Close
                                    ContextMenuAction.COPY -> Icons.Default.ContentCopy
                                    ContextMenuAction.REPORT -> Icons.Default.Mail
                                    ContextMenuAction.DELETE -> Icons.Default.Delete
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            contextMenuExpanded = false
                            when (action) {
                                ContextMenuAction.RETRANSCRIBE -> onRetranscribe?.invoke()
                                ContextMenuAction.CANCEL -> onCancel?.invoke()
                                ContextMenuAction.COPY -> copyTranscriptionToClipboard(context, log.result)
                                ContextMenuAction.REPORT -> reportTranscription(context, log)
                                ContextMenuAction.DELETE -> onDelete?.invoke()
                            }
                        }
                    )
                }
            }
            // === COLLAPSED VIEW ===
            // Header row: Audio duration + Status icon + Relative time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Audio duration with mic icon
                // Locale-safe: weight keeps the right-hand status/time column
                // anchored when the source label grows (TASK-345)
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                            text = log.sourcePackageName?.let { AppInfoUtils.getAppName(context, it) }
                                ?: stringResource(R.string.voice_message_duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // Locale-safe: ellipsize instead of pushing the timestamp (TASK-345)
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (SharedAudioHandler.isVideoFile(log.filePath)) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = stringResource(R.string.source_was_video),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Right: Status icon + Relative time
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            log.status == LogEntry.Status.SUCCESS && log.isPartial -> Icons.Default.Warning
                            log.status == LogEntry.Status.SUCCESS -> Icons.Default.CheckCircle
                            log.status == LogEntry.Status.ERROR -> Icons.Default.Error
                            log.status == LogEntry.Status.QUEUED -> Icons.Default.Schedule
                            log.status == LogEntry.Status.PROCESSING -> Icons.Default.HourglassEmpty
                            else -> Icons.Default.CheckCircle
                        },
                        contentDescription = when {
                            log.status == LogEntry.Status.SUCCESS && log.isPartial ->
                                stringResource(R.string.transcription_partial_chip)
                            log.status == LogEntry.Status.SUCCESS ->
                                stringResource(R.string.logs_status_success)
                            log.status == LogEntry.Status.ERROR ->
                                stringResource(R.string.logs_status_error)
                            log.status == LogEntry.Status.QUEUED ->
                                stringResource(R.string.logs_status_queued)
                            log.status == LogEntry.Status.PROCESSING ->
                                stringResource(R.string.logs_status_processing)
                            else -> stringResource(R.string.logs_status_success)
                        },
                        modifier = Modifier.size(14.dp),
                        tint = when {
                            log.status == LogEntry.Status.SUCCESS && log.isPartial -> MaterialTheme.colorScheme.error
                            log.status == LogEntry.Status.SUCCESS -> MaterialTheme.colorScheme.primary
                            log.status == LogEntry.Status.ERROR -> MaterialTheme.colorScheme.error
                            log.status == LogEntry.Status.QUEUED -> MaterialTheme.colorScheme.tertiary
                            log.status == LogEntry.Status.PROCESSING -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.primary
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
            if (log.hasCompletedResult) {
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
            } else if (log.status == LogEntry.Status.QUEUED) {
                Spacer(modifier = Modifier.height(6.dp))
                QueuedStatusLabel()
            } else if (log.status == LogEntry.Status.PROCESSING) {
                Spacer(modifier = Modifier.height(6.dp))
                Crossfade(
                    targetState = log.result.isNotEmpty(),
                    label = "pending_skeleton"
                ) { hasResult ->
                    if (hasResult) {
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
                        SkeletonTranscriptionPreview()
                    }
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
                        if (log.isPartial) {
                            PartialTranscriptionBanner(failedChunkCount = log.failedChunkCount)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
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
                            // Model that produced the transcription (GH #45); null on pre-v4 rows
                            log.modelName?.let { name ->
                                Text(
                                    text = stringResource(R.string.logs_model_label, name),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Task ID: opt-in detail (GH #45 follow-up, default off)
                        if (showTaskDetails) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.logs_task_id, log.taskId.take(8)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Action buttons: compact icon-only actions tucked into the
                        // bottom corner of the card (no labels; localized
                        // contentDescription keeps them accessible). Icon-only also
                        // sidesteps the TASK-345 label-overflow class entirely.
                        // The labeled layout remains available as a user preference.
                        if (log.result.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(if (compactActions) 4.dp else 8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Re-transcribe button (audio entries with file)
                                if (onRetranscribe != null) {
                                    ResultActionButton(
                                        compact = compactActions,
                                        onClick = onRetranscribe,
                                        icon = Icons.Default.Refresh,
                                        labelRes = R.string.retranscribe
                                    )
                                }
                                // Copy button
                                ResultActionButton(
                                    compact = compactActions,
                                    onClick = { copyTranscriptionToClipboard(context, log.result) },
                                    icon = Icons.Default.ContentCopy,
                                    labelRes = R.string.copy,
                                    contentDescriptionRes = R.string.copy_transcription
                                )
                                // Share button
                                ResultActionButton(
                                    compact = compactActions,
                                    onClick = { shareTranscription(context, log.result) },
                                    icon = Icons.Default.Share,
                                    labelRes = R.string.share_transcription
                                )
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
                    LogEntry.Status.QUEUED -> {
                        QueuedStatusLabel()
                    }
                    LogEntry.Status.PROCESSING -> {
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
                        } else {
                            SkeletonTranscriptionCard()
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        // PiP button — only on devices that support PiP
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = {
                                    (context as? MainActivity)?.enterPipMode()
                                }) {
                                    Icon(
                                        Icons.Default.PictureInPictureAlt,
                                        contentDescription = stringResource(R.string.pip_enter_description),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.pip_enter_button))
                                }
                            }
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
        diff < 60_000 -> context.getString(R.string.time_seconds_ago, diff / 1000)
        diff < 3_600_000 -> context.getString(R.string.time_minutes_ago, diff / 60_000)
        diff < 86_400_000 -> context.getString(R.string.time_hours_ago, diff / 3_600_000)
        else -> {
            val date = Date(timestamp)
            val today = Date(now)
            val cal1 = Calendar.getInstance().apply { time = date }
            val cal2 = Calendar.getInstance().apply { time = today }
            val timeFormat = SimpleDateFormat("HH:mm", locale)
            if (cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR) - 1) {
                context.getString(R.string.yesterday, timeFormat.format(date))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogEntryWithSwipe(
    log: LogEntry,
    searchQuery: String,
    isExpanded: Boolean,
    swipeActionMode: String,
    showTaskDetails: Boolean,
    revealedLogId: String?,
    onRevealedLogIdChange: (String?) -> Unit,
    onExpandChange: (Boolean) -> Unit,
    onSwipeCollapse: () -> Unit = {},
    onDeleted: (LogEntry) -> Unit,
    onDeleteLog: (String) -> Unit,
    viewModel: LogsViewModel,
    onRetranscribe: (() -> Unit)? = null,
    compactActions: Boolean = PreferencesManager.DEFAULT_COMPACT_RESULT_ACTIONS
) {
    val context = LocalContext.current
    if (swipeActionMode == "REVEAL") {
        val revealState = rememberSwipeToRevealState()

        LaunchedEffect(log.id, revealState.isRevealed) {
            if (revealState.isRevealed && revealedLogId != log.id) {
                onRevealedLogIdChange(log.id)
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
                onDeleted = onDeleted,
                copyLabel = context.getString(R.string.swipe_action_copy_description),
                shareLabel = context.getString(R.string.swipe_action_share_description),
                deleteLabel = context.getString(R.string.swipe_action_delete_description),
                colors = colorScheme
            )
        }
        SwipeToRevealBox(
            state = revealState,
            actions = actions,
            onSwipeStart = {
                if (isExpanded) onSwipeCollapse()
            }
        ) {
            LogEntryItem(
                log = log,
                searchQuery = searchQuery,
                expanded = isExpanded,
                showTaskDetails = showTaskDetails,
                onExpandChange = { expanded ->
                    if (revealState.isRevealed) {
                        revealState.reset()
                    } else {
                        onExpandChange(expanded)
                    }
                },
                onRetranscribe = onRetranscribe,
                onCancel = { cancelTask(context, log.taskId) },
                onDelete = { onDeleted(log); viewModel.deleteLog(log.id) },
                compactActions = compactActions
            )
        }
    } else {
        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = {
                if (it == SwipeToDismissBoxValue.EndToStart) {
                    onDeleted(log)
                    onDeleteLog(log.id)
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
                showTaskDetails = showTaskDetails,
                onExpandChange = onExpandChange,
                onRetranscribe = onRetranscribe,
                onCancel = { cancelTask(context, log.taskId) },
                onDelete = { onDeleted(log); onDeleteLog(log.id) },
                compactActions = compactActions
            )
        }
    }
}

@Composable
private fun ConversationGroupHeader(
    appName: String,
    count: Int,
    lastTimestamp: Long,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    // TASK-384: clickable Surface has no role; announce expand/collapse state to talkback
    val toggleStateDescription = stringResource(
        if (expanded) R.string.a11y_collapse else R.string.a11y_expand
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics {
                role = Role.Button
                stateDescription = toggleStateDescription
            },
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small,
        onClick = onToggle
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = appName.ifBlank { stringResource(R.string.conversation_group_unknown) },
                style = MaterialTheme.typography.labelMedium,
                // Locale-safe: ellipsize before the count/timestamp column (TASK-345)
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = pluralStringResource(R.plurals.conversation_group_count, count, count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatRelativeTime(lastTimestamp, context),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

private fun groupLogsByConversation(
    logs: List<LogEntry>,
    context: Context
): List<ConversationGroup> {
    return logs
        .groupBy { it.sourcePackageName }
        .map { (packageName, entries) ->
            val sorted = entries.sortedByDescending { it.timestamp }
            ConversationGroup(
                packageName = packageName,
                appName = packageName?.let { AppInfoUtils.getAppName(context, it) }
                    ?: context.getString(R.string.conversation_group_unknown),
                logs = sorted
            )
        }
        .sortedByDescending { it.logs.first().timestamp }
}
