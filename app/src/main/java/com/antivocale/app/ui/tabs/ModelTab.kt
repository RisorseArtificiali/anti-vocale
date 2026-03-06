package com.antivocale.app.ui.tabs

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.antivocale.app.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.antivocale.app.data.ModelDownloader
import com.antivocale.app.di.AppContainer
import com.antivocale.app.transcription.ParakeetDownloader
import com.antivocale.app.transcription.WhisperDownloader
import com.antivocale.app.transcription.WhisperModelManager
import com.antivocale.app.ui.viewmodel.ModelViewModel

private enum class PendingAction {
    PICK_FILE,
    USE_GALLERY_MODEL
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
 * Check if MANAGE_EXTERNAL_STORAGE permission is needed for Gallery access (Android 11+)
 */
private fun needsManageStoragePermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        !Environment.isExternalStorageManager()
    } else {
        false
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
            PendingAction.USE_GALLERY_MODEL -> {
                viewModel.useGalleryModel()
            }
            null -> {}
        }
        pendingAction = null
    }

    // Launcher for MANAGE_EXTERNAL_STORAGE permission (Android 11+)
    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        android.util.Log.d("ModelTab", "manageStorageLauncher callback triggered")
        val hasPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        android.util.Log.d("ModelTab", "hasPermission = $hasPermission")

        // Always refresh gallery models when returning from settings
        viewModel.refreshGalleryModels()

        if (hasPermission) {
            when (pendingAction) {
                PendingAction.USE_GALLERY_MODEL -> viewModel.useGalleryModel()
                else -> {}
            }
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
            snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Long
            )
            viewModel.clearDownloadError()
        }
    }

    // Delete confirmation dialog
    if (downloadUiState.modelToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                viewModel.dismissDeleteDialog()
            },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = {
                Text(stringResource(R.string.dialog_delete_message, downloadUiState.modelToDelete?.displayName ?: ""))
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

    // Parakeet download confirmation dialog
    if (parakeetState.showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissParakeetDownloadDialog() },
            title = { Text(stringResource(R.string.parakeet_download_confirm_title)) },
            text = { Text(stringResource(R.string.parakeet_download_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmParakeetDownload() }) {
                    Text(stringResource(R.string.download))
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
        AlertDialog(
            onDismissRequest = { viewModel.dismissParakeetDeleteDialog() },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(stringResource(R.string.dialog_delete_message, "Parakeet TDT")) },
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

    // Whisper download confirmation dialog
    if (whisperState.showDownloadDialog) {
        val variant = whisperState.selectedVariant
        AlertDialog(
            onDismissRequest = { viewModel.dismissWhisperDownloadDialog() },
            title = { Text(stringResource(R.string.whisper_download_confirm_title, variant?.let { stringResource(it.titleResId) } ?: "Whisper")) },
            text = { Text(stringResource(R.string.whisper_download_confirm_message, variant?.estimatedSizeMB?.toInt() ?: 75)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmWhisperDownload() }) {
                    Text(stringResource(R.string.download))
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
        AlertDialog(
            onDismissRequest = { viewModel.dismissWhisperDeleteDialog() },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(stringResource(R.string.dialog_delete_message, variant?.let { stringResource(it.titleResId) } ?: "Whisper")) },
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

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(16.dp)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .navigationBarsPadding()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // Status Card - compact and centered
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (uiState.status) {
                    ModelViewModel.ModelStatus.READY -> MaterialTheme.colorScheme.primaryContainer
                    ModelViewModel.ModelStatus.LOADING -> MaterialTheme.colorScheme.secondaryContainer
                    ModelViewModel.ModelStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
                    ModelViewModel.ModelStatus.UNLOADED -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = when (uiState.status) {
                        ModelViewModel.ModelStatus.READY -> Icons.Default.CheckCircle
                        ModelViewModel.ModelStatus.LOADING -> Icons.Default.HourglassEmpty
                        ModelViewModel.ModelStatus.ERROR -> Icons.Default.Error
                        ModelViewModel.ModelStatus.UNLOADED -> Icons.Default.Storage
                    },
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = when (uiState.status) {
                        ModelViewModel.ModelStatus.READY -> MaterialTheme.colorScheme.primary
                        ModelViewModel.ModelStatus.LOADING -> MaterialTheme.colorScheme.secondary
                        ModelViewModel.ModelStatus.ERROR -> MaterialTheme.colorScheme.error
                        ModelViewModel.ModelStatus.UNLOADED -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Show model name if available - centered
                if (uiState.modelName.isNotEmpty()) {
                    Text(
                        text = uiState.modelName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Status text - centered
                Text(
                    text = when (uiState.status) {
                        ModelViewModel.ModelStatus.READY -> stringResource(R.string.status_ready)
                        ModelViewModel.ModelStatus.LOADING -> stringResource(R.string.status_loading)
                        ModelViewModel.ModelStatus.ERROR -> stringResource(R.string.status_error)
                        ModelViewModel.ModelStatus.UNLOADED -> if (uiState.modelPath.isNotEmpty()) stringResource(R.string.status_model_selected) else stringResource(R.string.status_no_model)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    color = if (uiState.status == ModelViewModel.ModelStatus.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                // Show error message only when status is ERROR
                if (uiState.status == ModelViewModel.ModelStatus.ERROR && uiState.statusMessage.isNotEmpty()) {
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Download models section - primary UX
        ModelDownloadSection(
            viewModel = viewModel,
            context = context,
            onNavigateToSettings = onNavigateToSettings
        )

        // Parakeet TDT section - alternative ASR backend
        ParakeetDownloadSection(
            viewModel = viewModel
        )

        // Whisper section - multilingual ASR backend
        WhisperDownloadSection(
            viewModel = viewModel
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
    onNavigateToSettings: () -> Unit
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

        // Show token requirement only if no token is configured
        if (!downloadState.hasToken) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.requires_huggingface_token),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onNavigateToSettings) {
                    Text(
                        stringResource(R.string.add_in_settings),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // Model variant cards
        ModelDownloader.ModelVariant.entries.forEach { variant ->
            ModelVariantCard(
                variant = variant,
                downloadState = downloadState,
                isDownloaded = downloadState.downloadedModels.contains(variant),
                isDownloading = downloadState.isDownloading &&
                    downloadState.selectedVariant == variant,
                downloadProgress = downloadState.downloadProgress,
                currentDownloadState = downloadState.downloadState,
                downloadError = downloadState.downloadError,
                onSelect = { viewModel.selectModel(variant) },
                onDownloadClick = { viewModel.startDownload(variant) },
                onCancelClick = { viewModel.cancelDownload() },
                onUseClick = { viewModel.useDownloadedModel(variant) },
                onClearError = { viewModel.clearDownloadError() },
                onDeleteClick = {
                    viewModel.showDeleteDialog(variant)
                },
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
    isDownloading: Boolean,
    downloadProgress: Float,
    currentDownloadState: ModelDownloader.DownloadState,
    downloadError: ModelDownloader.DownloadError?,
    onSelect: () -> Unit,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onUseClick: () -> Unit,
    onClearError: () -> Unit,
    onDeleteClick: () -> Unit,
    context: android.content.Context
) {
    var showInfo by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isDownloaded -> MaterialTheme.colorScheme.primaryContainer
                isDownloading -> MaterialTheme.colorScheme.secondaryContainer
                downloadError != null && downloadState.selectedVariant == variant ->
                    MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
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
                            variant == ModelDownloader.ModelVariant.GEMMA_3N_E2B ->
                                Icons.Default.Star
                            else -> Icons.Default.Storage
                        },
                        contentDescription = null,
                        tint = when {
                            isDownloaded -> MaterialTheme.colorScheme.primary
                            isDownloading -> MaterialTheme.colorScheme.secondary
                            downloadError != null && downloadState.selectedVariant == variant ->
                                MaterialTheme.colorScheme.error
                            variant == ModelDownloader.ModelVariant.GEMMA_3N_E2B ->
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
                            // Recommended badge for E2B
                            if (variant == ModelDownloader.ModelVariant.GEMMA_3N_E2B) {
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
                        }
                        Text(
                            text = "${variant.estimatedSizeMB}MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

                    if (variant.galleryModelName != null) {
                        InfoRow(getLocalizedLabel("AI Gallery name"), variant.galleryModelName)
                    }
                }
            }

            // Download progress
            if (isDownloading) {
                Spacer(modifier = Modifier.height(16.dp))

                when (currentDownloadState) {
                    is ModelDownloader.DownloadState.Downloading -> {
                        val state = currentDownloadState as ModelDownloader.DownloadState.Downloading
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.downloading),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "${(downloadProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = formatBytes(state.bytesDownloaded) +
                                    if (state.totalBytes > 0) {
                                        " / ${formatBytes(state.totalBytes)}"
                                    } else {
                                        ""
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is ModelDownloader.DownloadState.Connecting -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = stringResource(R.string.connecting_to_huggingface),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    else -> {}
                }
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
                        // Use button
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
                    else -> {
                        // Download button
                        Button(
                            onClick = onDownloadClick,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.download))
                        }
                        // View on HuggingFace
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    data = Uri.parse("https://huggingface.co/${variant.huggingFaceRepo}")
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.view))
                        }
                    }
                }
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
        "AI Gallery name" -> stringResource(R.string.label_ai_gallery_name)
        else -> labelKey
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

// ==================== Parakeet Download Section ====================

/**
 * Section for downloading Parakeet TDT model (sherpa-onnx backend).
 * No authentication required - downloads from GitHub releases.
 */
@Composable
private fun ParakeetDownloadSection(
    viewModel: ModelViewModel
) {
    val parakeetState by viewModel.parakeetState.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                parakeetState.isDownloading -> MaterialTheme.colorScheme.secondaryContainer
                parakeetState.modelPath != null -> MaterialTheme.colorScheme.primaryContainer
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
                        Text(
                            text = stringResource(R.string.parakeet_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.parakeet_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons
            Spacer(modifier = Modifier.height(8.dp))

            // Download progress
            if (parakeetState.isDownloading) {
                Spacer(modifier = Modifier.height(16.dp))

                when (val state = parakeetState.downloadState) {
                    is ParakeetDownloader.DownloadState.Downloading -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.download_status_downloading), style = MaterialTheme.typography.bodySmall)
                                Text("${(parakeetState.downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            }
                            LinearProgressIndicator(
                                progress = { parakeetState.downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = formatBytes(state.bytesDownloaded) +
                                    if (state.totalBytes > 0) " / ${formatBytes(state.totalBytes)}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is ParakeetDownloader.DownloadState.Extracting -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                progress = { parakeetState.downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    stringResource(R.string.download_status_extracting, state.fileName.takeIf { it.isNotEmpty() } ?: stringResource(R.string.download_status_file_progress, state.fileIndex, state.totalFiles)),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (state.currentFileSize > 0) {
                                    Text(
                                        "${formatBytes(state.bytesExtracted)} / ${formatBytes(state.currentFileSize)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    is ParakeetDownloader.DownloadState.Connecting -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.download_status_connecting), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> {}
                }
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
                            Text(stringResource(R.string.cancel_download))
                        }
                    }
                    parakeetState.modelPath != null -> {
                        Button(
                            onClick = { viewModel.useParakeetModel() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.use_model))
                        }
                        // Delete button - icon only to match Gemma card
                        OutlinedButton(
                            onClick = { viewModel.showParakeetDeleteDialog() },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                    else -> {
                        Button(
                            onClick = { viewModel.showParakeetDownloadDialog() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.parakeet_download))
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
    viewModel: ModelViewModel
) {
    val whisperState by viewModel.whisperState.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                whisperState.isDownloading -> MaterialTheme.colorScheme.secondaryContainer
                whisperState.downloadedVariants.isNotEmpty() -> MaterialTheme.colorScheme.primaryContainer
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
                            whisperState.isDownloading -> Icons.Default.CloudDownload
                            else -> Icons.Default.Translate
                        },
                        contentDescription = null,
                        tint = when {
                            whisperState.downloadedVariants.isNotEmpty() -> MaterialTheme.colorScheme.primary
                            whisperState.isDownloading -> MaterialTheme.colorScheme.secondary
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
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Model variant selection
            WhisperModelManager.Variant.entries.forEach { variant ->
                WhisperVariantCard(
                    variant = variant,
                    isDownloaded = whisperState.downloadedVariants.contains(variant),
                    isDownloading = whisperState.isDownloading && whisperState.selectedVariant == variant,
                    downloadProgress = whisperState.downloadProgress,
                    downloadState = whisperState.downloadState,
                    errorMessage = if (whisperState.selectedVariant == variant) whisperState.errorMessage else null,
                    onDownloadClick = { viewModel.showWhisperDownloadDialog(variant) },
                    onCancelClick = { viewModel.cancelWhisperDownload() },
                    onUseClick = { viewModel.useWhisperModel(variant) },
                    onDeleteClick = { viewModel.showWhisperDeleteDialog(variant) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Card for a single Whisper variant showing its info and download status.
 */
@Composable
private fun WhisperVariantCard(
    variant: WhisperModelManager.Variant,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    downloadState: WhisperDownloader.DownloadState,
    errorMessage: String?,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onUseClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isDownloaded -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                isDownloading -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when {
                            isDownloaded -> Icons.Default.CheckCircle
                            isDownloading -> Icons.Default.CloudDownload
                            else -> Icons.Default.Storage
                        },
                        contentDescription = null,
                        tint = when {
                            isDownloaded -> MaterialTheme.colorScheme.primary
                            isDownloading -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(variant.titleResId),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(variant.descriptionResId),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Download progress
            if (isDownloading) {
                Spacer(modifier = Modifier.height(12.dp))

                when (val state = downloadState) {
                    is WhisperDownloader.DownloadState.Downloading -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.download_status_downloading), style = MaterialTheme.typography.bodySmall)
                                Text("${(downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            }
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = formatBytes(state.bytesDownloaded) +
                                    if (state.totalBytes > 0) " / ${formatBytes(state.totalBytes)}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is WhisperDownloader.DownloadState.Extracting -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                stringResource(R.string.download_status_extracting, state.fileName.takeIf { it.isNotEmpty() } ?: stringResource(R.string.download_status_extracting_files)),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    is WhisperDownloader.DownloadState.Connecting -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.download_status_connecting), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> {}
                }
            }

            // Error message
            errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
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
                        OutlinedButton(
                            onClick = onDeleteClick,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                    else -> {
                        Button(
                            onClick = onDownloadClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.whisper_download))
                        }
                    }
                }
            }
        }
    }
}

