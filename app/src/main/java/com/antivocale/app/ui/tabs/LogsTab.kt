package com.antivocale.app.ui.tabs

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.antivocale.app.MainActivity
import com.antivocale.app.R
import com.antivocale.app.ui.onboarding.tourRevealable
import com.antivocale.app.transcription.SummaryPolicy
import com.antivocale.app.data.local.FailureContextJson
import com.antivocale.app.data.local.ProcessingContextConverter
import com.antivocale.app.util.AppInfoUtils
import com.antivocale.app.util.AudioDurationFormat
import com.antivocale.app.util.DecodedOfTotalFormat
import com.antivocale.app.util.SharedAudioHandler
import com.antivocale.app.util.formatProcessingTime
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
import com.antivocale.app.util.LanguageNames
import com.antivocale.app.ui.viewmodel.LogEntry
import com.antivocale.app.ui.onboarding.TourStep
import com.antivocale.app.ui.MAX_RENDERED_TRANSCRIPT_CHARS
import com.antivocale.app.ui.components.CappedTranscriptText
import com.antivocale.app.ui.components.highlightText
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
                // TASK-568: durationMs on ERROR rows is decoded-at-failure
                // audio, not processing time; the email field keeps its meaning.
                processingTimeMs = if (log.status == LogEntry.Status.SUCCESS) log.durationMs else 0L,
                status = log.status.name,
                excerpt = log.result,
                errorMessage = log.errorMessage,
                failureDiagnostics = FailureContextJson.render(
                    FailureContextJson.fromJson(log.failureContext)),
                appVersion = FeedbackHelper.currentVersionName(context),
                deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim(),
                processingLine = ProcessingContextConverter.render(
                    ProcessingContextConverter.fromJson(log.processingContext)),
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
private fun copyTranscriptionToClipboard(
    context: Context,
    text: String,
    labelRes: Int = R.string.clipboard_label_transcription,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
    // TASK-650 F5: the clipboard is an exit surface here too.
    val sig = com.antivocale.app.util.TranscriptSignature.lastResolved
    val clip = ClipData.newPlainText(
        context.getString(labelRes),
        com.antivocale.app.util.TranscriptSignature.apply(text, sig.text, sig.position))
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
    // TASK-650 F5: sharing is an exit surface here too.
    val sig = com.antivocale.app.util.TranscriptSignature.lastResolved
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(
            Intent.EXTRA_TEXT,
            com.antivocale.app.util.TranscriptSignature.apply(text, sig.text, sig.position))
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
                onClick = {
                    // GH #107/TASK-598 F3: the swipe copy hands off the
                    // speaker-annotated text when the row carries labels.
                    copyTranscriptionToClipboard(
                        context, viewModel.speakerAnnotatedFlow(log.id).value ?: log.result)
                }
            )
        )
        actions.add(
            SwipeAction(
                icon = Icons.Default.Share,
                label = shareLabel,
                tint = colors.onSecondaryContainer,
                background = colors.secondaryContainer,
                onClick = {
                    shareTranscription(
                        context, viewModel.speakerAnnotatedFlow(log.id).value ?: log.result)
                }
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
    onNavigateToSettings: (() -> Unit)? = null,
    /** TASK-617: the chip's own destination (transcription section), NOT
     *  the auto-save hint's export page: one callback cannot serve both. */
    onOpenLanguageSetting: (() -> Unit)? = null,
    viewModel: LogsViewModel = hiltViewModel(),
    highlightTaskId: String? = null,
    tourRevealState: com.svenjacobs.reveal.RevealState,
) {
    val logs by viewModel.logs.collectAsState()
    val filteredLogs by viewModel.filteredLogs.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val context = LocalContext.current
    val swipeActionMode by viewModel.swipeActionMode
        .collectAsState(initial = PreferencesManager.DEFAULT_SWIPE_ACTION_MODE)
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

    // TASK-500: browse-audio FAB. The system document picker returns a
    // content URI with a transient read grant; the ViewModel copies it
    // through the same shared-audio path as the share receiver and enqueues
    // transcription, so no storage permission is needed.
    val browseLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.transcribeLocalFile(context, uri)
    }
    // Lifecycle-aware (code review F1): collect only while RESUMED, so a
    // failure while the activity is STOPPED finds zero subscribers and the
    // ViewModel routes it to a notification instead of an invisible host.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val job = lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.historyError.collect {
                    snackbarHostState.showSnackbar(it)
                }
            }
        }
        onDispose { job.cancel() }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { browseLauncher.launch(arrayOf("audio/*", "video/*")) },
                modifier = Modifier
                    .navigationBarsPadding()
                    .tourRevealable(TourStep.BrowseFab.key, tourRevealState),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.browse_audio_content_description),
                )
            }
        },
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
                        // TASK-500: FAB clearance so the floating button never
                        // sits on the last row's action buttons at list end.
                        bottom = 96.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    )
                ) {
                    item(key = "header") {
                        // TASK-564 (maintainer): the search field is the
                        // tab's first element, at the same 16dp inset and on
                        // the same surface as the Settings search field.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.onSearchQueryChanged(it) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.logs_search_placeholder)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = null)
                                },
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                trailingIcon = {
                                    // Search-clear when a query is active;
                                    // history-clear otherwise (the title row
                                    // that used to carry it is gone).
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.clearSearch() }) {
                                            Icon(
                                                Icons.Default.Clear,
                                                contentDescription = stringResource(R.string.clear_search)
                                            )
                                        }
                                    } else if (logs.isNotEmpty()) {
                                        IconButton(onClick = { showClearDialog = true }) {
                                            Icon(
                                                Icons.Default.DeleteSweep,
                                                contentDescription = stringResource(R.string.logs_clear),
                                                modifier = Modifier.size(20.dp)
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
                                        compactActions = compactActions,
                                        onNavigateToSettings = onNavigateToSettings,
                                        onOpenLanguageSetting = onOpenLanguageSetting,
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
                                    compactActions = compactActions,
                                    onNavigateToSettings = onNavigateToSettings,
                                    onOpenLanguageSetting = onOpenLanguageSetting,
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
    // TASK-566 follow-up: a container from the brand ramp (not the old
    // indigo chip): surfaceContainerHigh sits one step below the cards,
    // so the group reads as a section without a third color family.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "($count)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun LogEntryItem(
    log: LogEntry,
    /** TASK-599: the pre-annotated transcript when this row is expanded and
     *  carries speaker cues; the item stays stateless (no ViewModel). */
    speakerAnnotated: String?,
    /** TASK-601: the first-pass header's display name, pre-derived. */
    firstPassLabel: String = "",
    /** TASK-595: the first-pass transcript via the lean per-row flow. */
    firstPassTranscript: String? = null,
    searchQuery: String = "",
    expanded: Boolean = false,
    onExpandChange: (Boolean) -> Unit = {},
    onRetranscribe: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    compactActions: Boolean = PreferencesManager.DEFAULT_COMPACT_RESULT_ACTIONS,
    onNavigateToSettings: (() -> Unit)? = null,
    onOpenLanguageSetting: (() -> Unit)? = null,
    /** TASK-546: render the language chip (the Settings flag's value). */
    showLanguageChip: Boolean = false,
    /** TASK-616: render the technical processing-context line. No default:
     *  a call site that forgets the pass-through must be a compile error,
     *  not a silently dropped diagnostics line. */
    showTechnicalDetails: Boolean,
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
            ),
        // TASK-564: the Models-tab container idiom (the app-wide style
        // reference); matches SectionCard and the curated model cards.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                                ContextMenuAction.COPY -> copyTranscriptionToClipboard(
                                    // GH #107/TASK-598 F3: the stateless row
                                    // already receives the annotated text.
                                    context, speakerAnnotated ?: log.result)
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
                        text = AudioDurationFormat.format(log.audioDurationSeconds),
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

                // GH #83/TASK-599: when the cues carry speaker labels, every
                // surface of this row's text uses the turn-annotated form,
                // the same rendering the exports produce; the wrapper passes
                // it pre-derived, unlabeled rows fall back to the stored
                // transcript.
                val displayResult = speakerAnnotated ?: log.result

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
                        CappedTranscriptText(
                            text = displayResult,
                            searchQuery = searchQuery,
                            container = MaterialTheme.colorScheme.primaryContainer,
                            onAutoSaveHintClick = onNavigateToSettings,
                        )

                        // TASK-121.4: the AI summary of a long transcript, when the
                        // pass produced one. Metadata only: the result box above stays
                        // the delivered transcript. Shown above the original block
                        // (summary before provenance).
                        log.summary?.let { summary ->
                            LabeledTranscriptBlock(
                                label = stringResource(R.string.logs_summary_label),
                                copyLabelRes = R.string.copy_summary,
                                text = summary,
                                searchQuery = searchQuery,
                            )
                        }

                        // TASK-494: an attended summary attempt that produced
                        // nothing. Silence read as "the app forgot my
                        // summary"; the caption names the real cause. One
                        // mapping for every token, so new reasons cannot
                        // bypass the caption silently.
                        summarySkipCaptionRes(log.summarySkipReason)?.let { captionRes ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(captionRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.transcriptBlockSurface()
                            )
                        }

                        // TASK-276 AC3: the pre-punctuation original, when the pass
                        // changed the text. Always visible in the expanded card
                        // (the raw ASR output is what the model actually heard).
                        log.rawTranscript?.let { original ->
                            LabeledTranscriptBlock(
                                label = stringResource(R.string.logs_original_label),
                                copyLabelRes = R.string.copy_original,
                                text = original,
                                searchQuery = searchQuery,
                            )
                        }

                        // GH #43: the superseded fast first pass, kept copyable
                        // when refinement replaced it with better text.
                        firstPassTranscript?.let { firstPass ->
                            LabeledTranscriptBlock(
                                label = stringResource(
                                    // TASK-601: the wrapper's pre-derived
                                    // display name, never the raw catalog id.
                                    R.string.logs_first_pass_label, firstPassLabel,
                                ),
                                copyLabelRes = R.string.copy_first_pass,
                                text = firstPass,
                                searchQuery = searchQuery,
                            )
                        }

                        // Metadata row. FlowRow, not Row: the model name is
                        // unbounded, so at large font scales the block wraps
                        // to new lines instead of collapsing the name to a
                        // zero-width sliver after the fixed siblings.
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
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
                            // TASK-512: the processing line (decode path, chunk
                            // coverage, cap, RAM) makes a long-run report
                            // attributable from the card alone. TASK-616: it
                            // stays persisted and rides the feedback report,
                            // but the card parses and renders it only on
                            // opt-in (the list re-emits on every interim write).
                            val renderedProcessing = remember(log.processingContext, showTechnicalDetails) {
                                if (!showTechnicalDetails) {
                                    null
                                } else {
                                    ProcessingContextConverter.render(
                                        ProcessingContextConverter.fromJson(log.processingContext))
                                }
                            }
                            renderedProcessing?.let { rendered ->
                                Text(
                                    text = rendered,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Model that produced the transcription (GH #45); null on pre-v4
                            // rows. Long external-import names wrap (TASK-495) instead of
                            // ellipsizing their tail.
                            log.modelName?.let { name ->
                                Text(
                                    text = stringResource(R.string.logs_model_label, name),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (showLanguageChip) {
                                LanguageChip(
                                    detected = log.detectedLanguage,
                                    pinned = log.languagePin,
                                    onOpenSetting = { onOpenLanguageSetting?.invoke() },
                                )
                            }
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
                                    onClick = { copyTranscriptionToClipboard(context, displayResult) },
                                    icon = Icons.Default.ContentCopy,
                                    labelRes = R.string.copy,
                                    contentDescriptionRes = R.string.copy_transcription
                                )
                                // Share button
                                ResultActionButton(
                                    compact = compactActions,
                                    onClick = { shareTranscription(context, displayResult) },
                                    icon = Icons.Default.Share,
                                    labelRes = R.string.share_transcription
                                )
                            }
                        }
                    }
                    LogEntry.Status.ERROR -> {
                        // TASK-568: a failed long run keeps whatever was
                        // transcribed before the stream died (the streaming
                        // catches persist it); show it with a partial
                        // qualifier and, when the failure point is known,
                        // the decoded-of-total line. Empty result renders
                        // exactly as before.
                        if (log.result.isNotEmpty()) {
                            // The salvaged transcript is the run's only
                            // output: the shared block gives it the copy
                            // affordance the summary blocks have (GH #72
                            // rationale), not just a rendered Text.
                            LabeledTranscriptBlock(
                                label = stringResource(R.string.partial_transcript_label),
                                copyLabelRes = R.string.copy,
                                text = log.result,
                                searchQuery = searchQuery,
                            )
                            if (log.durationMs > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                DecodedOfTotalFormat
                                    .format(context, log.durationMs / 1000.0, log.audioDurationSeconds)
                                    ?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
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
                        // TASK-570: the structured diagnostics line (backend,
                        // provider, version, chunk coverage, durations) so a
                        // screenshot of the failure is actionable alone. The
                        // JSON parse is remembered: list recompositions are
                        // frequent (every interim write re-emits the flow).
                        val renderedDiagnostics = remember(log.failureContext) {
                            FailureContextJson.render(FailureContextJson.fromJson(log.failureContext))
                        }
                        renderedDiagnostics?.let { rendered ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = rendered,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                            // TASK-506 /simplify F-A: the interim streaming
                            // result is the exact surface where a repetition
                            // loop grows; same cap as the completed result.
                            CappedTranscriptText(
                                text = log.result,
                                searchQuery = searchQuery,
                                container = MaterialTheme.colorScheme.primaryContainer,
                                onAutoSaveHintClick = onNavigateToSettings,
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

// Format relative time: "5 min ago", "Yesterday 14:32", "Mar 2, 14:32"
private fun formatRelativeTime(timestamp: Long, context: Context): String {
    val now = System.currentTimeMillis()
    // Clamp future timestamps (clock skew, imported data) to "now": a negative
    // diff fell into the seconds branch and rendered raw "-46213s ago"
    // (maintainer trial 2026-09-13, TASK-507).
    val diff = (now - timestamp).coerceAtLeast(0)
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

// Get preview text with ellipsis
private fun getPreviewText(text: String, maxLength: Int = 50): String {
    if (text.length <= maxLength) return text
    return text.take(maxLength) + "…"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogEntryWithSwipe(
    log: LogEntry,
    searchQuery: String,
    isExpanded: Boolean,
    swipeActionMode: String,
    revealedLogId: String?,
    onRevealedLogIdChange: (String?) -> Unit,
    onExpandChange: (Boolean) -> Unit,
    onSwipeCollapse: () -> Unit = {},
    onDeleted: (LogEntry) -> Unit,
    onDeleteLog: (String) -> Unit,
    viewModel: LogsViewModel,
    onRetranscribe: (() -> Unit)? = null,
    compactActions: Boolean = PreferencesManager.DEFAULT_COMPACT_RESULT_ACTIONS,
    onNavigateToSettings: (() -> Unit)? = null,
    onOpenLanguageSetting: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // TASK-546: the chip flag, collected once here (the item stays stateless).
    val showLanguageChip by viewModel.languageChipEnabled.collectAsState()
    // TASK-616: same pattern for the technical processing-context line.
    val showTechnicalDetails by viewModel.showTechnicalDetails.collectAsState()
    // TASK-599: the expanded row's annotated transcript is collected HERE
    // (the wrapper knows expansion; the item stays stateless) from the
    // ViewModel's cached per-row flow: no cold-flow-per-recomposition, no
    // flash of unlabeled text, no JSON parse on the composition thread.
    val speakerAnnotated = if (isExpanded) {
        viewModel.speakerAnnotatedFlow(log.id).collectAsState().value
    } else {
        null
    }
    // TASK-595 F5: the first-pass transcript rides the same lean per-row
    // flow (it left the list projections); collected here, handed down.
    val firstPassTranscript = if (isExpanded) {
        viewModel.firstPassFlow(log.id).collectAsState().value
    } else {
        null
    }
    // TASK-601 via TASK-599: the first-pass header's display name, derived
    // here (remembered; the registry lookup rebuilds external descriptors
    // and the list re-emits on every interim write) and handed down as a
    // string: the item stays stateless.
    val firstPassLabel = if (firstPassTranscript != null) {
        // Gated on the transcript actually existing (review F1): the parse
        // and the registry lookup run only for rows that render the block,
        // not for every row entering composition.
        remember(log.processingContext) {
            viewModel.fastBackendDisplayName(
                ProcessingContextConverter.fromJson(log.processingContext)?.refinementPhase?.backendId,
            )
        } ?: ""
    } else {
        ""
    }
    if (SwipeActionMode.from(swipeActionMode) == SwipeActionMode.REVEAL) {
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
                speakerAnnotated = speakerAnnotated,
                firstPassLabel = firstPassLabel,
                firstPassTranscript = firstPassTranscript,
                searchQuery = searchQuery,
                expanded = isExpanded,
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
                compactActions = compactActions,
                onNavigateToSettings = onNavigateToSettings,
                onOpenLanguageSetting = onOpenLanguageSetting,
                showLanguageChip = showLanguageChip,
                showTechnicalDetails = showTechnicalDetails,
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
                speakerAnnotated = speakerAnnotated,
                firstPassLabel = firstPassLabel,
                firstPassTranscript = firstPassTranscript,
                searchQuery = searchQuery,
                expanded = isExpanded,
                onExpandChange = onExpandChange,
                onRetranscribe = onRetranscribe,
                onCancel = { cancelTask(context, log.taskId) },
                onDelete = { onDeleted(log); onDeleteLog(log.id) },
                compactActions = compactActions,
                onNavigateToSettings = onNavigateToSettings,
                onOpenLanguageSetting = onOpenLanguageSetting,
                showLanguageChip = showLanguageChip,
                showTechnicalDetails = showTechnicalDetails,
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
        // TASK-566 follow-up: the brand ramp step shared with the date
        // headers, so a collapsed conversation group reads as a section.
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = appName.ifBlank { stringResource(R.string.conversation_group_unknown) },
                style = MaterialTheme.typography.labelMedium,
                // Locale-safe: ellipsize before the count/timestamp column (TASK-345).
                // TASK-507: the ONLY weighted child. The previous pair (this
                // one fill=false + a weighted spacer) leaked the name's
                // unused share past the row end under Arrangement.Start, so
                // the timestamp floated left by (share/2 - nameWidth):
                // short group names visibly misaligned the times across rows.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = pluralStringResource(R.plurals.conversation_group_count, count, count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
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

/** The shared surface of the expanded card's secondary blocks, so the
 *  transcript blocks and the skip-note caption cannot drift apart. */
@Composable
private fun Modifier.transcriptBlockSurface(
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
): Modifier = this
    .fillMaxWidth()
    .background(color.copy(alpha = 0.3f), shape = MaterialTheme.shapes.small)
    .padding(8.dp)

/** The single skip-reason-token to caption mapping (TASK-494); unknown or
 *  null tokens render nothing, pre-v7 rows stay silent. */
private fun summarySkipCaptionRes(reason: String?): Int? = when (reason) {
    SummaryPolicy.SKIP_REASON_GUARDS -> R.string.summary_skipped_guard
    SummaryPolicy.SKIP_REASON_CONTEXT -> R.string.summary_skipped_context
    SummaryPolicy.SKIP_REASON_NO_MODEL -> R.string.summary_skipped_no_model
    SummaryPolicy.SKIP_REASON_FAILED -> R.string.summary_skipped_failed
    else -> null
}


/**
 * TASK-546: the language fact of a result, visible (the detection-failure
 * class used to be invisible). Shows the backend-reported language when the
 * run auto-detected, the pin when it was forced; tapping opens the facts and
 * the path to the language setting (the recovery arm: pin there, then
 * re-transcribe the same audio). Hidden entirely when neither fact exists
 * (old rows, text entries) or via the Settings toggle (maintainer directive).
 */
@Composable
private fun LanguageChip(
    detected: String?,
    pinned: String?,
    onOpenSetting: () -> Unit,
) {
    val autoDetected = pinned == null || pinned == "auto"
    val code = if (autoDetected) detected else pinned
    if (code.isNullOrBlank()) return
    var showFacts by remember { mutableStateOf(false) }
    TextButton(
        onClick = { showFacts = true },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        modifier = Modifier.height(28.dp)
    ) {
        Text(
            text = LanguageNames.nativeLanguageName(code) +
                (if (!autoDetected) "" else " *"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showFacts) {
        AlertDialog(
            onDismissRequest = { showFacts = false },
            title = { Text(stringResource(R.string.language_chip_dialog_title)) },
            text = { Text(
                if (autoDetected)
                    stringResource(
                        R.string.language_chip_detected_body,
                        LanguageNames.nativeLanguageName(detected ?: code))
                else
                    stringResource(
                        R.string.language_chip_pinned_body,
                        LanguageNames.nativeLanguageName(code))
            ) },
            confirmButton = {
                TextButton(onClick = {
                    showFacts = false
                    onOpenSetting()
                }) { Text(stringResource(R.string.language_chip_open_setting)) }
            },
            dismissButton = {
                TextButton(onClick = { showFacts = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/**
 * A labeled secondary transcript block of the expanded log card (summary,
 * pre-punctuation original): labelSmall caption, highlighted body on the
 * subdued surfaceVariant background, and a copy affordance so the block's
 * own text is reachable without re-selecting it by hand (GH #72: the
 * summary is the end result of the two-model pipeline, it must be
 * copyable). Shared so the two blocks cannot drift.
 */
@Composable
private fun LabeledTranscriptBlock(
    label: String,
    @StringRes copyLabelRes: Int,
    text: String,
    searchQuery: String,
) {
    val context = LocalContext.current
    Spacer(modifier = Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.weight(1f))
        // The compact action recipe (48dp target, 18dp icon, localized
        // description): reuse it so the card's other copy buttons and this
        // one cannot drift apart.
        ResultActionButton(
            compact = true,
            onClick = { copyTranscriptionToClipboard(context, text, labelRes = copyLabelRes) },
            icon = Icons.Default.ContentCopy,
            labelRes = copyLabelRes,
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    CappedTranscriptText(
        text = text,
        searchQuery = searchQuery,
        style = MaterialTheme.typography.bodySmall,
        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
