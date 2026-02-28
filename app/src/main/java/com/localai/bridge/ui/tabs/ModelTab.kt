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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
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
    )
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            // Permission granted, refresh gallery models and perform pending action
            viewModel.refreshGalleryModels()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
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
        } else if (needsManageStoragePermission()) {
            // Show permission request card if Gallery model might be available
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
                            text = "Check for Google AI Gallery Model",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "If you have Google AI Edge Gallery installed with a downloaded model, grant storage permission to reuse it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            manageStorageLauncher.launch(intent)
                        }
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Grant Permission")
                    }
                }
            }
        }

        // Download help section
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Need a model?",
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Download Gemma 3n from HuggingFace LiteRT Community. " +
                            "The E2B model (~2GB) is recommended for most devices. " +
                            "The E4B model (~4GB) offers better quality but requires more RAM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse("https://huggingface.co/litert-community/Gemma-3n-E2B-IQ4_S-0001-of-0001")
                            }
                            context.startActivity(intent)
                        }
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("E2B Model")
                    }

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse("https://huggingface.co/litert-community/Gemma-3n-E4B-IQ4_S-0001-of-0001")
                            }
                            context.startActivity(intent)
                        }
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("E4B Model")
                    }
                }
            }
        }
    }
}
