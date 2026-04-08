package com.antivocale.app.ui.tabs

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.ClickableText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import com.antivocale.app.R
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.antivocale.app.data.ModelDownloader
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.di.AppContainer
import com.antivocale.app.service.InferenceService
import com.antivocale.app.util.formatFileSize
import com.antivocale.app.transcription.WhisperModelManager
import com.antivocale.app.transcription.Qwen3AsrModelManager
import com.antivocale.app.ui.components.DownloadButtonState
import com.antivocale.app.ui.components.DownloadProgressView
import com.antivocale.app.ui.components.ModelVariantCard
import com.antivocale.app.ui.components.ModelVariantCardState
import com.antivocale.app.ui.components.UnloadModelButton
import com.antivocale.app.ui.components.PartialDownloadSection
import com.antivocale.app.ui.viewmodel.ModelViewModel

private enum class PendingAction {
    PICK_FILE
}

/**
 * Check if storage permission is needed
 */
private fun needsStoragePermission(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Get storage permissions to request
 */
private fun getStoragePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelTab(
    viewModel: ModelViewModel = viewModel(
        factory = ModelViewModel.Factory(AppContainer.preferencesManager)
    ),
    onNavigateToSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val downloadUiState by viewModel.downloadUiState.collectAsState()
    val parakeetState by viewModel.parakeetState.collectAsState()
    val whisperState by viewModel.whisperState.collectAsState()
    val qwen3AsrState by viewModel.qwen3AsrState.collectAsState()

    // Transcription active state — used to warn about destructive operations
    val isTranscribing by InferenceService.isTranscribing.collectAsState()
    var pendingModelSwitch by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showUnloadDialog by remember { mutableStateOf(false) }

    // Snackbar host state for displaying errors
    val snackbarHostState = remember { SnackbarHostState() }

    // Observe token state changes and refresh ModelViewModel
    val tokenState by AppContainer.huggingFaceTokenManager.tokenState.collectAsState()
    LaunchedEffect(tokenState) {
        viewModel.refreshTokenState()
    }

    // State for permission request type
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }

    // Permission launcher for storage
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        when (pendingAction) {
            PendingAction.PICK_FILE -> {
                if (allGranted || !needsStoragePermission(context)) {
                    viewModel.openFilePicker()
                }
            }
            null -> {}
        }
        pendingAction = null
    }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onModelSelected(context, it) }
    }

    // Handle file picker event
    LaunchedEffect(viewModel.filePickerEvent) {
        viewModel.filePickerEvent.collect {
            filePickerLauncher.launch(arrayOf("*/*"))
        }
    }

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll when download error occurs (license requirement, auth error, etc.)
    // This scrolls to the error area so user can see what went wrong
    LaunchedEffect(downloadUiState.downloadError) {
        if (downloadUiState.downloadError != null) {
            delay(150) // Small delay to let the UI update first
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    // Collect one-time Snackbar events from ViewModel (Channel-based for guaranteed delivery)
    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            if (message == "auth_required") {
                snackbarHostState.showSnackbar(
                    message = AppContainer.applicationContext.getString(R.string.model_requires_auth),
                    actionLabel = "Settings",
                    duration = SnackbarDuration.Long
                ).let { result ->
                    if (result == SnackbarResult.ActionPerformed) {
                        onNavigateToSettings()
                    }
                }
            } else {
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = SnackbarDuration.Long
                )
            }
            viewModel.clearDownloadError()
        }
    }

    // Delete confirmation dialog
    if (downloadUiState.modelToDelete != null) {
        val deletingModelName = downloadUiState.modelToDelete?.displayName ?: ""
        val isActiveModel = uiState.modelName == deletingModelName

        if (isTranscribing && isActiveModel) {
            // Hard block: cannot delete the model currently being used for transcription
            AlertDialog(
                onDismissRequest = { viewModel.dismissDeleteDialog() },
                title = { Text(stringResource(R.string.dialog_transcription_active_title)) },
                text = {
                    Text(stringResource(R.string.dialog_delete_active_model_message, deletingModelName))
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissDeleteDialog() }) {
                        Text(stringResource(R.string.action_understood))
                    }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = {
                    viewModel.dismissDeleteDialog()
                },
                title = { Text(stringResource(R.string.dialog_delete_title)) },
                text = {
                    if (isTranscribing) {
                        Text(stringResource(R.string.dialog_delete_inactive_model_message, deletingModelName))
                    } else {
                        Text(stringResource(R.string.dialog_delete_message, deletingModelName))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.confirmDeleteModel()
                        }
                    ) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.dismissDeleteDialog()
                    }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }

    // Parakeet download confirmation dialog
    if (parakeetState.showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissParakeetDownloadDialog() },
            title = { Text(stringResource(if (parakeetState.needsExtraction) R.string.parakeet_extract_confirm_title else R.string.parakeet_download_confirm_title)) },
            text = { Text(stringResource(if (parakeetState.needsExtraction) R.string.parakeet_extract_confirm_message else R.string.parakeet_download_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmParakeetDownload() }) {
                    Text(stringResource(if (parakeetState.needsExtraction) R.string.extract_model else R.string.download))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissParakeetDownloadDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Parakeet delete confirmation dialog
    if (parakeetState.showDeleteDialog) {
        val parakeetName = stringResource(R.string.parakeet_name)
        val isParakeetActive = uiState.modelName == parakeetName

        if (isTranscribing && isParakeetActive) {
            // Hard block: Parakeet is the active transcription model
            AlertDialog(
                onDismissRequest = { viewModel.dismissParakeetDeleteDialog() },
                title = { Text(stringResource(R.string.dialog_transcription_active_title)) },
                text = { Text(stringResource(R.string.dialog_delete_active_model_message, parakeetName)) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissParakeetDeleteDialog() }) {
                        Text(stringResource(R.string.action_understood))
                    }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { viewModel.dismissParakeetDeleteDialog() },
                title = { Text(stringResource(R.string.dialog_delete_title)) },
                text = {
                    if (isTranscribing) {
                        Text(stringResource(R.string.dialog_delete_inactive_model_message, parakeetName))
                    } else {
                        Text(stringResource(R.string.dialog_delete_message, parakeetName))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmParakeetDelete() }) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissParakeetDeleteDialog() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }

    // Gemma download confirmation dialog
    if (downloadUiState.showDownloadDialog) {
        val variant = downloadUiState.selectedVariant
        AlertDialog(
            onDismissRequest = { viewModel.dismissDownloadDialog() },
            title = { Text(stringResource(R.string.gemma_download_confirm_title, variant?.displayName ?: "Gemma")) },
            text = { Text(stringResource(R.string.gemma_download_confirm_message, variant?.estimatedSizeMB?.toInt() ?: 0)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDownload() }) {
                    Text(stringResource(R.string.download))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDownloadDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Whisper download confirmation dialog
    if (whisperState.showDownloadDialog) {
        val variant = whisperState.selectedVariant
        val isExtract = variant != null && whisperState.variantsNeedingExtraction.contains(variant)
        AlertDialog(
            onDismissRequest = { viewModel.dismissWhisperDownloadDialog() },
            title = { Text(stringResource(if (isExtract) R.string.whisper_extract_confirm_title else R.string.whisper_download_confirm_title, variant?.let { stringResource(it.titleResId) } ?: "Whisper")) },
            text = { Text(stringResource(if (isExtract) R.string.whisper_extract_confirm_message else R.string.whisper_download_confirm_message, variant?.estimatedSizeMB?.toInt() ?: 75)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmWhisperDownload() }) {
                    Text(stringResource(if (isExtract) R.string.extract_model else R.string.download))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissWhisperDownloadDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Whisper delete confirmation dialog
    if (whisperState.showDeleteDialog) {
        val variant = whisperState.variantToDelete
        val variantDisplayName = variant?.let { stringResource(it.titleResId) } ?: "Whisper"
        val isWhisperModelActive = uiState.modelName == variantDisplayName

        if (isTranscribing && isWhisperModelActive) {
            // Hard block: this Whisper variant is the active transcription model
            AlertDialog(
                onDismissRequest = { viewModel.dismissWhisperDeleteDialog() },
                title = { Text(stringResource(R.string.dialog_transcription_active_title)) },
                text = { Text(stringResource(R.string.dialog_delete_active_model_message, variantDisplayName)) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissWhisperDeleteDialog() }) {
                        Text(stringResource(R.string.action_understood))
                    }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { viewModel.dismissWhisperDeleteDialog() },
                title = { Text(stringResource(R.string.dialog_delete_title)) },
                text = {
                    if (isTranscribing) {
                        Text(stringResource(R.string.dialog_delete_inactive_model_message, variantDisplayName))
                    } else {
                        Text(stringResource(R.string.dialog_delete_message, variantDisplayName))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmWhisperDelete() }) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissWhisperDeleteDialog() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }

    // Qwen3-ASR download confirmation dialog
    if (qwen3AsrState.showDownloadDialog) {
        val variant = qwen3AsrState.selectedVariant
        AlertDialog(
            onDismissRequest = { viewModel.dismissQwen3AsrDownloadDialog() },
            title = { Text(stringResource(R.string.qwen3_asr_download_confirm_title, variant?.let { stringResource(it.titleResId) } ?: "Qwen3-ASR")) },
            text = { Text(stringResource(R.string.qwen3_asr_download_confirm_message, variant?.estimatedSizeMB?.toInt() ?: 827)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmQwen3AsrDownload() }) {
                    Text(stringResource(R.string.download))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissQwen3AsrDownloadDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Qwen3-ASR delete confirmation dialog
    if (qwen3AsrState.showDeleteDialog) {
        val variant = qwen3AsrState.variantToDelete
        val variantDisplayName = variant?.let { stringResource(it.titleResId) } ?: "Qwen3-ASR"
        val isQwen3ModelActive = uiState.modelName == variantDisplayName

        if (isTranscribing && isQwen3ModelActive) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissQwen3AsrDeleteDialog() },
                title = { Text(stringResource(R.string.dialog_transcription_active_title)) },
                text = { Text(stringResource(R.string.dialog_delete_active_model_message, variantDisplayName)) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissQwen3AsrDeleteDialog() }) {
                        Text(stringResource(R.string.action_understood))
                    }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { viewModel.dismissQwen3AsrDeleteDialog() },
                title = { Text(stringResource(R.string.dialog_delete_title)) },
                text = {
                    if (isTranscribing) {
                        Text(stringResource(R.string.dialog_delete_inactive_model_message, variantDisplayName))
                    } else {
                        Text(stringResource(R.string.dialog_delete_message, variantDisplayName))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmQwen3AsrDelete() }) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissQwen3AsrDeleteDialog() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }

    // Unload model confirmation dialog
    if (showUnloadDialog) {
        AlertDialog(
            onDismissRequest = { showUnloadDialog = false },
            title = { Text(stringResource(R.string.dialog_unload_title)) },
            text = { Text(stringResource(R.string.dialog_unload_message, uiState.modelName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.unloadModel()
                        showUnloadDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.action_unload))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnloadDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Model switch warning during active transcription
    if (pendingModelSwitch != null) {
        AlertDialog(
            onDismissRequest = {
                pendingModelSwitch = null
            },
            title = { Text(stringResource(R.string.dialog_transcription_active_title)) },
            text = { Text(stringResource(R.string.dialog_switch_model_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingModelSwitch?.invoke()
                        pendingModelSwitch = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.action_switch_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingModelSwitch = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // Guarded model switch: warns if transcription is active
        val guardedSwitch: (() -> Unit) -> Unit = { action ->
            if (isTranscribing) {
                pendingModelSwitch = action
            } else {
                action()
            }
        }

        // Unload Model button — only shown when model is actually loaded in memory
        if (uiState.status == ModelViewModel.ModelStatus.READY) {
            UnloadModelButton(
                onClick = { showUnloadDialog = true },
                isTranscribing = isTranscribing
            )
        }

        // Whisper section - multilingual ASR backend (recommended)
        WhisperDownloadSection(
            viewModel = viewModel,
            activeModelName = uiState.modelName,
            guardedModelSwitch = guardedSwitch
        )

        // Qwen3-ASR section - state-of-the-art multilingual ASR backend
        Qwen3AsrDownloadSection(
            viewModel = viewModel,
            activeModelName = uiState.modelName,
            guardedModelSwitch = guardedSwitch
        )

        // Parakeet TDT section - fast multilingual ASR backend
        ParakeetDownloadSection(
            viewModel = viewModel,
            activeModelName = uiState.modelName,
            context = context,
            guardedModelSwitch = guardedSwitch
        )

        // Download models section - Gemma LLM models (advanced features)
        ModelDownloadSection(
            viewModel = viewModel,
            context = context,
            onNavigateToSettings = onNavigateToSettings,
            activeModelName = uiState.modelName
        )

        // Select Model Button - secondary option for local files
        OutlinedButton(
            onClick = {
                if (needsStoragePermission(context)) {
                    pendingAction = PendingAction.PICK_FILE
                    permissionLauncher.launch(getStoragePermissions())
                } else {
                    viewModel.openFilePicker()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.select_model_from_device))
        }

        // Extra spacer to ensure downloading card can be fully scrolled into view
        Spacer(modifier = Modifier.height(200.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 24.dp)
        ) { snackbarData ->
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = 300f)
                ) + fadeIn(animationSpec = spring(dampingRatio = 0.75f, stiffness = 300f)),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(4.dp)
                ) {
                    Text(
                        text = snackbarData.visuals.message,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

// ==================== Model Download Section ====================

/**
 * Model download section with cards for each available model variant.
 */
@Composable
private fun ModelDownloadSection(
    viewModel: ModelViewModel,
    context: android.content.Context,
    onNavigateToSettings: () -> Unit,
    activeModelName: String
) {
    val downloadState by viewModel.downloadUiState.collectAsState()

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(R.string.download_models),
                style = MaterialTheme.typography.titleSmall
            )
        }

        // Description: Gemma are full LLMs with advanced features
        Text(
            text = stringResource(R.string.gemma_advanced_features_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Model variant cards
        ModelDownloader.ModelVariant.entries.forEach { variant ->
            val variantState = downloadState.variantDownloadStates[variant]
            ModelVariantCard(
                variant = variant,
                downloadState = downloadState,
                isDownloaded = downloadState.downloadedModels.contains(variant),
                isActive = activeModelName == variant.displayName,
                isDownloading = variantState?.isDownloading == true,
                downloadProgress = variantState?.downloadProgress ?: 0f,
                currentDownloadState = variantState?.downloadState ?: DownloadState.Idle,
                downloadError = if (downloadState.selectedVariant == variant) downloadState.downloadError else null,
                partialDownload = variantState?.partialDownload,
                onSelect = { viewModel.selectModel(variant) },
                onDownloadClick = { viewModel.showDownloadDialog(variant) },
                onCancelClick = { viewModel.cancelDownload(variant) },
                onResumeClick = { viewModel.resumeDownload(variant) },
                onClearPartialClick = { viewModel.clearPartialDownload(variant) },
                onUseClick = { viewModel.useDownloadedModel(variant) },
                onClearError = { viewModel.clearDownloadError() },
                onDeleteClick = {
                    viewModel.showDeleteDialog(variant)
                },
                hasToken = downloadState.hasToken,
                onNavigateToSettings = onNavigateToSettings,
                context = context
            )
        }
    }
}

/**
 * Card for a single model variant showing its info and download status.
 */
@Composable
private fun ModelVariantCard(
    variant: ModelDownloader.ModelVariant,
    downloadState: ModelViewModel.DownloadUiState,
    isDownloaded: Boolean,
    isActive: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    currentDownloadState: DownloadState,
    downloadError: ModelDownloader.DownloadError?,
    partialDownload: DownloadState.PartiallyDownloaded? = null,
    onSelect: () -> Unit,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onResumeClick: () -> Unit,
    onClearPartialClick: () -> Unit,
    onUseClick: () -> Unit,
    onClearError: () -> Unit,
    onDeleteClick: () -> Unit,
    hasToken: Boolean = false,
    onNavigateToSettings: () -> Unit = {},
    context: android.content.Context
) {
    var showInfo by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isDownloading -> MaterialTheme.colorScheme.secondaryContainer
                downloadError != null && downloadState.selectedVariant == variant ->
                    MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Status icon
                    Icon(
                        imageVector = when {
                            isDownloaded -> Icons.Default.CheckCircle
                            isDownloading -> Icons.Default.CloudDownload
                            downloadError != null && downloadState.selectedVariant == variant ->
                                Icons.Default.Error
                            variant == ModelDownloader.ModelVariant.GEMMA_4_E2B ->
                                Icons.Default.Star
                            else -> Icons.Default.Storage
                        },
                        contentDescription = null,
                        tint = when {
                            isDownloaded -> MaterialTheme.colorScheme.primary
                            isDownloading -> MaterialTheme.colorScheme.secondary
                            downloadError != null && downloadState.selectedVariant == variant ->
                                MaterialTheme.colorScheme.error
                            variant == ModelDownloader.ModelVariant.GEMMA_4_E2B ->
                                MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // Model name and badge
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = variant.displayName.substringBefore("(").trim().removeSuffix(" "),
                                style = MaterialTheme.typography.titleMedium
                            )
                            // Active badge
                            if (isActive) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = stringResource(R.string.active_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        variant.descriptionResId?.let {
                            Text(
                                text = stringResource(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Info toggle
                IconButton(onClick = { showInfo = !showInfo }) {
                    Icon(
                        if (showInfo) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (showInfo) "Show less" else "Show more"
                    )
                }
            }

            // Expanded info section
            if (showInfo) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow(getLocalizedLabel("File name"), variant.fileName)
                    InfoRow(
                        getLocalizedLabel("Supports audio"),
                        if (variant.supportsAudio) stringResource(R.string.label_yes_multimodal) else stringResource(R.string.label_no_text_only)
                    )
                    InfoRow(getLocalizedLabel("HuggingFace"), variant.huggingFaceRepo)
                }
            }

            // Download progress
            if (isDownloading) {
                Spacer(modifier = Modifier.height(16.dp))
                DownloadProgressView(currentDownloadState, downloadProgress)
            }

            // Partial download (from per-variant state)
            partialDownload?.let { partial ->
                Spacer(modifier = Modifier.height(8.dp))
                PartialDownloadSection(
                    partial = partial,
                    onResumeClick = onResumeClick,
                    onClearClick = onClearPartialClick
                )
            }

            // Action buttons
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    isDownloading -> {
                        // Cancel button
                        OutlinedButton(
                            onClick = onCancelClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.cancel_download))
                        }
                    }
                    isDownloaded -> {
                        if (!isActive) {
                            // Use button (only when not active)
                            Button(
                                onClick = onUseClick,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.use_model))
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        // Delete button
                        OutlinedButton(
                            onClick = onDeleteClick,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                    partialDownload != null -> {
                        // PartialDownloadSection above already shows Resume/Clear buttons
                    }
                    else -> {
                        // Download button
                        Button(
                            onClick = onDownloadClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.download))
                        }
                    }
                }
            }

            // Auth warning for gated models when no token is configured
            if (variant.requiresAuth && !hasToken) {
                val tokenWarning = stringResource(R.string.requires_huggingface_token)
                val addSettings = stringResource(R.string.add_in_settings)
                val errorColor = MaterialTheme.colorScheme.error
                val primaryColor = MaterialTheme.colorScheme.primary
                val authWarningText = remember(tokenWarning, addSettings, errorColor, primaryColor) {
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = errorColor)) {
                            append(tokenWarning + " ")
                        }
                        withStyle(SpanStyle(
                            color = primaryColor,
                            textDecoration = TextDecoration.Underline
                        )) {
                            append(addSettings)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                ClickableText(
                    text = authWarningText,
                    style = MaterialTheme.typography.labelSmall,
                    onClick = { onNavigateToSettings() }
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
    }
}

// Helper function to get localized label strings
@Composable
private fun getLocalizedLabel(labelKey: String): String {
    return when (labelKey) {
        "File name" -> stringResource(R.string.label_filename)
        "Supports audio" -> stringResource(R.string.label_supports_audio)
        "HuggingFace" -> stringResource(R.string.label_huggingface)
        else -> labelKey
    }
}

// ==================== Qwen3-ASR Download Section ====================

/**
 * Section for downloading Qwen3-ASR model (sherpa-onnx backend).
 * Supports multiple variants with download/delete/use functionality.
 */
@Composable
private fun Qwen3AsrDownloadSection(
    viewModel: ModelViewModel,
    activeModelName: String,
    guardedModelSwitch: (() -> Unit) -> Unit = {}
) {
    val qwen3AsrState by viewModel.qwen3AsrState.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                qwen3AsrState.isAnyDownloading -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            qwen3AsrState.downloadedVariants.isNotEmpty() -> Icons.Default.CheckCircle
                            qwen3AsrState.isAnyDownloading -> Icons.Default.CloudDownload
                            else -> Icons.Default.Translate
                        },
                        contentDescription = null,
                        tint = when {
                            qwen3AsrState.downloadedVariants.isNotEmpty() -> MaterialTheme.colorScheme.primary
                            qwen3AsrState.isAnyDownloading -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.qwen3_asr_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.qwen3_asr_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Model variant cards
            Qwen3AsrModelManager.Variant.entries.forEach { variant ->
                val variantState = qwen3AsrState.variantDownloadStates[variant]
                ModelVariantCard(
                    state = ModelVariantCardState(
                        variant = variant,
                        isActive = activeModelName == stringResource(variant.titleResId),
                        downloadProgress = variantState?.downloadProgress ?: 0f,
                        downloadState = variantState?.downloadState ?: DownloadState.Idle,
                        errorMessage = variantState?.errorMessage,
                        partialDownload = variantState?.partialDownload,
                        buttonState = when {
                            variantState?.isDownloading == true -> DownloadButtonState.Downloading
                            qwen3AsrState.downloadedVariants.contains(variant) -> DownloadButtonState.Downloaded
                            variantState?.partialDownload != null -> DownloadButtonState.PartiallyDownloaded
                            else -> DownloadButtonState.Idle
                        }
                    ),
                    downloadButtonTextResId = R.string.qwen3_asr_download,
                    onDownloadClick = { viewModel.showQwen3AsrDownloadDialog(variant) },
                    onCancelClick = { viewModel.cancelQwen3AsrDownload(variant) },
                    onResumeClick = { viewModel.resumeQwen3AsrDownload(variant) },
                    onClearPartialClick = { viewModel.clearQwen3AsrPartialDownload(variant) },
                    onUseClick = { guardedModelSwitch { viewModel.useQwen3AsrModel(variant) } },
                    onDeleteClick = { viewModel.showQwen3AsrDeleteDialog(variant) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}


// ==================== Parakeet Download Section ====================
@Composable
private fun ParakeetDownloadSection(
    viewModel: ModelViewModel,
    activeModelName: String,
    context: Context,
    guardedModelSwitch: (() -> Unit) -> Unit
) {
    val parakeetState by viewModel.parakeetState.collectAsState()
    val parakeetName = stringResource(R.string.parakeet_name)
    val isActive = activeModelName == parakeetName

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                parakeetState.isDownloading -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            parakeetState.modelPath != null -> Icons.Default.CheckCircle
                            parakeetState.isDownloading -> Icons.Default.CloudDownload
                            else -> Icons.Default.GraphicEq
                        },
                        contentDescription = null,
                        tint = when {
                            parakeetState.modelPath != null -> MaterialTheme.colorScheme.primary
                            parakeetState.isDownloading -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.parakeet_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (isActive) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = stringResource(R.string.active_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = stringResource(R.string.parakeet_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Download progress
            if (parakeetState.isDownloading) {
                Spacer(modifier = Modifier.height(16.dp))
                DownloadProgressView(parakeetState.downloadState, parakeetState.downloadProgress, showExtractingFileSize = true)
            }

            // Error message
            parakeetState.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Partial download
            parakeetState.partialDownload?.let { partial ->
                Spacer(modifier = Modifier.height(8.dp))
                PartialDownloadSection(
                    partial = partial,
                    onResumeClick = { viewModel.resumeParakeetDownload() },
                    onClearClick = { viewModel.clearParakeetPartialDownload() }
                )
            }

            // Action buttons
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    parakeetState.isDownloading -> {
                        OutlinedButton(
                            onClick = { viewModel.cancelParakeetDownload() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (parakeetState.downloadState is DownloadState.Extracting)
                                    stringResource(R.string.cancel_extract)
                                else
                                    stringResource(R.string.cancel_download)
                            )
                        }
                    }
                    parakeetState.modelPath != null -> {
                        if (!isActive) {
                            Button(
                                onClick = { guardedModelSwitch { viewModel.useParakeetModel() } },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.use_model))
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        // Delete button
                        OutlinedButton(
                            onClick = { viewModel.showParakeetDeleteDialog() },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                    parakeetState.partialDownload != null -> {
                        // PartialDownloadSection above already shows Resume/Clear buttons
                    }
                    else -> {
                        val needsExtraction = parakeetState.needsExtraction
                        if (parakeetState.hasOrphanedFiles && parakeetState.needsExtraction) {
                            OutlinedButton(
                                onClick = { viewModel.clearOrphanedParakeetFiles() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.download_clear_partial))
                            }
                        } else {
                            Button(
                                onClick = { viewModel.showParakeetDownloadDialog() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = if (needsExtraction) Icons.Default.FileDownload else Icons.Default.Download,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (needsExtraction)
                                        stringResource(R.string.extract_model)
                                    else
                                        stringResource(R.string.parakeet_download)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== Whisper Download Section ====================

/**
 * Section for downloading Whisper models (sherpa-onnx backend).
 * No authentication required - downloads from GitHub releases.
 * Excellent multilingual support with proper punctuation.
 */
@Composable
private fun WhisperDownloadSection(
    viewModel: ModelViewModel,
    activeModelName: String,
    guardedModelSwitch: (() -> Unit) -> Unit = {}
) {
    val whisperState by viewModel.whisperState.collectAsState()
    var showSpeedComparison by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                whisperState.isAnyDownloading -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            whisperState.downloadedVariants.isNotEmpty() -> Icons.Default.CheckCircle
                            whisperState.isAnyDownloading -> Icons.Default.CloudDownload
                            else -> Icons.Default.Translate
                        },
                        contentDescription = null,
                        tint = when {
                            whisperState.downloadedVariants.isNotEmpty() -> MaterialTheme.colorScheme.primary
                            whisperState.isAnyDownloading -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.whisper_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.whisper_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { showSpeedComparison = true }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(R.string.speed_comparison_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Model variant selection
            WhisperModelManager.Variant.entries.forEach { variant ->
                val needsExtraction = whisperState.variantsNeedingExtraction.contains(variant)
                val isOrphaned = whisperState.orphanedVariants.contains(variant)
                val variantState = whisperState.variantDownloadStates[variant]
                ModelVariantCard(
                    state = ModelVariantCardState(
                        variant = variant,
                        isActive = activeModelName == stringResource(variant.titleResId),
                        downloadProgress = variantState?.downloadProgress ?: 0f,
                        downloadState = variantState?.downloadState ?: DownloadState.Idle,
                        errorMessage = variantState?.errorMessage,
                        partialDownload = variantState?.partialDownload,
                        buttonState = when {
                            variantState?.isDownloading == true -> DownloadButtonState.Downloading
                            whisperState.downloadedVariants.contains(variant) -> DownloadButtonState.Downloaded
                            isOrphaned && needsExtraction -> DownloadButtonState.Orphaned
                            needsExtraction -> DownloadButtonState.NeedsExtraction
                            variantState?.partialDownload != null -> DownloadButtonState.PartiallyDownloaded
                            else -> DownloadButtonState.Idle
                        }
                    ),
                    downloadButtonTextResId = R.string.whisper_download,
                    extraBadges = {
                        if (variant == WhisperModelManager.Variant.TURBO) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiary,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = stringResource(R.string.recommended),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (variant == WhisperModelManager.Variant.DISTIL_LARGE_V3) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondary,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = stringResource(R.string.fastest_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    },
                    cancelTextExtractor = { downloadState ->
                        if (downloadState is DownloadState.Extracting)
                            stringResource(R.string.cancel_extract)
                        else
                            stringResource(R.string.cancel_download)
                    },
                    onDownloadClick = { viewModel.showWhisperDownloadDialog(variant) },
                    onCancelClick = { viewModel.cancelWhisperDownload(variant) },
                    onResumeClick = { viewModel.resumeWhisperDownload(variant) },
                    onClearPartialClick = { viewModel.clearWhisperPartialDownload(variant) },
                    onExtraActionClick = { viewModel.clearOrphanedWhisperFiles(variant) },
                    onUseClick = { guardedModelSwitch { viewModel.useWhisperModel(variant) } },
                    onDeleteClick = { viewModel.showWhisperDeleteDialog(variant) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    // Speed comparison dialog
    if (showSpeedComparison) {
        AlertDialog(
            onDismissRequest = { showSpeedComparison = false },
            title = {
                Text(
                    stringResource(R.string.speed_comparison_title),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.speed_comparison_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Comparison table
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            // Header row
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    stringResource(R.string.speed_comparison_header_model),
                                    modifier = Modifier.weight(2f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    stringResource(R.string.speed_comparison_header_size),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    stringResource(R.string.speed_comparison_header_speed),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    stringResource(R.string.speed_comparison_header_quality),
                                    modifier = Modifier.weight(1.5f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // Whisper Small
                            ComparisonRow(
                                name = stringResource(R.string.speed_comparison_small_name),
                                size = stringResource(R.string.speed_comparison_small_size),
                                speed = stringResource(R.string.speed_comparison_small_speed),
                                quality = stringResource(R.string.speed_comparison_small_quality)
                            )
                            // Whisper Turbo
                            ComparisonRow(
                                name = stringResource(R.string.speed_comparison_turbo_name),
                                size = stringResource(R.string.speed_comparison_turbo_size),
                                speed = stringResource(R.string.speed_comparison_turbo_speed),
                                quality = stringResource(R.string.speed_comparison_turbo_quality)
                            )
                            // Whisper Medium
                            ComparisonRow(
                                name = stringResource(R.string.speed_comparison_medium_name),
                                size = stringResource(R.string.speed_comparison_medium_size),
                                speed = stringResource(R.string.speed_comparison_medium_speed),
                                quality = stringResource(R.string.speed_comparison_medium_quality)
                            )
                            // Distil Italian
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            ComparisonRow(
                                name = stringResource(R.string.speed_comparison_distil_it_name),
                                size = stringResource(R.string.speed_comparison_distil_it_size),
                                speed = stringResource(R.string.speed_comparison_distil_it_speed),
                                quality = stringResource(R.string.speed_comparison_distil_it_quality),
                                badge = stringResource(R.string.speed_comparison_distil_it_note),
                                muted = false
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedComparison = false }) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        )
    }
}

@Composable
private fun ComparisonRow(
    name: String,
    size: String,
    speed: String,
    quality: String,
    badge: String? = null,
    muted: Boolean = false
) {
    val textColor = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
                     else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(name, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall, color = textColor)
        Text(size, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(speed, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (badge != null) {
            Column(modifier = Modifier.weight(1.5f)) {
                Text(quality, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Text(quality, modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
        }
    }
}
