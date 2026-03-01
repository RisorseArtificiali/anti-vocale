package com.localai.bridge.ui.tabs

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localai.bridge.data.ModelDownloader
import com.localai.bridge.di.AppContainer
import com.localai.bridge.ui.viewmodel.ModelViewModel

/**
 * Enum to track what action is pending after permission request
 */
private enum class PendingAction {
    PICK_FILE,
    LOAD_MODEL,
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
 * Check if notification permission is needed (Android 13+)
 */
private fun needsNotificationPermission(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
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

/**
 * Get notification permissions to request (Android 13+)
 */
private fun getNotificationPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
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

    // Snackbar host state for displaying errors
    val snackbarHostState = remember { SnackbarHostState() }

    // Observe token state changes and refresh ModelViewModel
    val tokenState by AppContainer.huggingFaceTokenManager.tokenState.collectAsState()
    LaunchedEffect(tokenState) {
        viewModel.refreshTokenState()
    }

    // State for permission request type
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }

    // Permission launcher for storage and notifications
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
            PendingAction.LOAD_MODEL -> {
                if (allGranted || !needsNotificationPermission(context)) {
                    viewModel.loadModel(context)
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

    // Auto-scroll only when download starts (not for errors - those go to Snackbar)
    LaunchedEffect(downloadUiState.isDownloading) {
        if (downloadUiState.isDownloading) {
            // Scroll to bottom to show the downloading card
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
            title = { Text("Delete Model?") },
            text = {
                Text("This will permanently remove '${downloadUiState.modelToDelete?.displayName}' from your device. You can download it again later.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmDeleteModel()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissDeleteDialog()
                }) {
                    Text("Cancel")
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
        // Status Card
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
                modifier = Modifier.padding(16.dp),
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
                    modifier = Modifier.size(48.dp),
                    tint = when (uiState.status) {
                        ModelViewModel.ModelStatus.READY -> MaterialTheme.colorScheme.primary
                        ModelViewModel.ModelStatus.LOADING -> MaterialTheme.colorScheme.secondary
                        ModelViewModel.ModelStatus.ERROR -> MaterialTheme.colorScheme.error
                        ModelViewModel.ModelStatus.UNLOADED -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = when (uiState.status) {
                        ModelViewModel.ModelStatus.READY -> "Ready"
                        ModelViewModel.ModelStatus.LOADING -> "Loading Model..."
                        ModelViewModel.ModelStatus.ERROR -> "Error"
                        ModelViewModel.ModelStatus.UNLOADED -> "No Model Loaded"
                    },
                    style = MaterialTheme.typography.titleMedium
                )

                if (uiState.statusMessage.isNotEmpty()) {
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Model Path
        if (uiState.modelPath.isNotEmpty()) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Model Path",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = uiState.modelPath,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Previous Model Restore Card
        if (uiState.previousModelPath != null && uiState.previousModelName != null) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Restore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Previous Model Available",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = uiState.previousModelName ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = "Switch back to your previously selected model",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { viewModel.restorePreviousModel() },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restore Previous Model")
                    }
                }
            }
        }

        // Memory Info (when loaded)
        if (uiState.status == ModelViewModel.ModelStatus.READY && uiState.memoryUsage > 0) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Memory Usage",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "~${uiState.memoryUsage} MB",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Select Model Button
            Button(
                onClick = {
                    if (needsStoragePermission(context)) {
                        pendingAction = PendingAction.PICK_FILE
                        permissionLauncher.launch(getStoragePermissions())
                    } else {
                        viewModel.openFilePicker()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select Model")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Load/Unload Button
            when (uiState.status) {
                ModelViewModel.ModelStatus.UNLOADED, ModelViewModel.ModelStatus.ERROR -> {
                    Button(
                        onClick = {
                            // Check notification permission for Android 13+
                            if (needsNotificationPermission(context)) {
                                pendingAction = PendingAction.LOAD_MODEL
                                permissionLauncher.launch(getNotificationPermissions())
                            } else {
                                viewModel.loadModel(context)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.modelPath.isNotEmpty()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Load Model")
                    }
                }
                ModelViewModel.ModelStatus.READY -> {
                    OutlinedButton(
                        onClick = { viewModel.unloadModel() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Unload Model")
                    }
                }
                else -> {
                    Button(
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        enabled = false
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Loading...")
                    }
                }
            }
        }

        // Info text
        Text(
            text = "Place your Gemma 3n .litertlm model file in Downloads or Documents folder",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )

        // Google AI Edge Gallery model section
        if (uiState.isGalleryModelAvailable) {
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Google AI Edge Gallery Model Found!",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = uiState.galleryModelName ?: "Gemma 3n E2B",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        text = "Reuse the model you already downloaded in AI Gallery",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            // Check if MANAGE_EXTERNAL_STORAGE is needed for Gallery access
                            if (needsManageStoragePermission()) {
                                pendingAction = PendingAction.USE_GALLERY_MODEL
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                manageStorageLauncher.launch(intent)
                            } else {
                                viewModel.useGalleryModel()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Use Gallery Model")
                    }
                }
            }
        } else {
            // Show option to browse for Edge Gallery model manually
            // Note: Android scoped storage blocks direct access to other apps' private directories
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Import from Google AI Gallery",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "If you have a model in Google AI Edge Gallery, use the file picker below to select it. Look in: Android/data/com.google.ai.edge.gallery/files/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            if (needsStoragePermission(context)) {
                                pendingAction = PendingAction.PICK_FILE
                                permissionLauncher.launch(getStoragePermissions())
                            } else {
                                viewModel.openFilePicker()
                            }
                        }
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Browse for Model File")
                    }
                }
            }
        }

        // Download models section
        Spacer(modifier = Modifier.height(16.dp))

        ModelDownloadSection(
            viewModel = viewModel,
            context = context,
            onNavigateToSettings = onNavigateToSettings
        )

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
                text = "Download Models",
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
                    text = "Requires HuggingFace token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onNavigateToSettings) {
                    Text(
                        "Add in Settings",
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
                                        text = "Recommended",
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
                        Text(
                            text = variant.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                    InfoRow("File name", variant.fileName)
                    InfoRow(
                        "Supports audio",
                        if (variant.supportsAudio) "Yes (multimodal)" else "No (text only)"
                    )
                    InfoRow("HuggingFace", variant.huggingFaceRepo)

                    if (variant.galleryModelName != null) {
                        InfoRow("AI Gallery name", variant.galleryModelName)
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
                                    text = "Downloading...",
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
                                text = "Connecting to HuggingFace...",
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
                            Text("Cancel Download")
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
                            Text("Use Model")
                        }
                        // Delete button
                        OutlinedButton(
                            onClick = onDeleteClick,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
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
                            Text("Download")
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
                            Text("View")
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

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

