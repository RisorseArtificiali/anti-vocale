package com.localai.bridge.ui.tabs

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localai.bridge.data.DiscoveredModel
import com.localai.bridge.data.HuggingFaceTokenManager
import com.localai.bridge.data.HuggingFaceOAuthConfig
import com.localai.bridge.data.ModelSource
import com.localai.bridge.di.AppContainer
import com.localai.bridge.manager.LlmManager
import com.localai.bridge.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    onNavigateToModelTab: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            application = context.applicationContext as android.app.Application,
            preferencesManager = AppContainer.preferencesManager,
            huggingFaceTokenManager = AppContainer.huggingFaceTokenManager,
            huggingFaceAuthManager = AppContainer.huggingFaceAuthManager
        )
    )

    val uiState by viewModel.uiState.collectAsState()
    val currentTimeout by viewModel.keepAliveTimeout.collectAsState()
    val tokenState by viewModel.tokenState.collectAsState()
    val tokenInput by viewModel.tokenInput.collectAsState()
    val oauthState by viewModel.oauthState.collectAsState()
    val scrollState = rememberScrollState()
    var tokenPasswordVisible by remember { mutableStateOf(false) }
    var showOAuthConfigDialog by remember { mutableStateOf(false) }

    // OAuth launcher
    val oauthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleOAuthResult(result.data)
    }

    // Model selection state
    val settingsState by viewModel.uiState.collectAsState()

    // Load models on first composition
    LaunchedEffect(Unit) {
        viewModel.loadCurrentModel()
        viewModel.scanAvailableModels()
    }

    // Check if model is currently loaded
    val isModelLoaded = LlmManager.isReady()
    val remainingTime = LlmManager.getRemainingTimeSeconds() ?: 0L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Model Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isModelLoaded)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isModelLoaded) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (isModelLoaded)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (isModelLoaded) "Model Loaded" else "Model Not Loaded",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isModelLoaded && remainingTime > 0L) {
                    val minutes = remainingTime / 60
                    val seconds = remainingTime % 60
                    Text(
                        text = "Auto-unload in: ${minutes}m ${seconds}s",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                if (!isModelLoaded) {
                    Text(
                        text = "Load a model from the Model tab to enable inference",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Active Model Selection Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Active Model",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Current model display
                if (settingsState.currentModelPath != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Column {
                            Text(
                                text = settingsState.currentModelName ?: "Unknown",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = settingsState.currentModelPath ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "No model selected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Available models list
                if (settingsState.availableModels.isEmpty()) {
                    Text(
                        text = "No models found. Download a model or add one via the Model tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Available models:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(modifier = Modifier.selectableGroup()) {
                        settingsState.availableModels.forEach { model ->
                            val isSelected = settingsState.currentModelPath == model.path
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = isSelected,
                                        role = Role.RadioButton,
                                        onClick = { viewModel.selectModel(model) }
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = null
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = model.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isSelected)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurface
                                        )
                                        // Source badge
                                        Text(
                                            text = when (model.source) {
                                                ModelSource.DOWNLOADED -> "HF"
                                                ModelSource.GALLERY -> "Gallery"
                                                ModelSource.PREVIOUS -> "Previous"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                    }
                                    Text(
                                        text = "${model.sizeMB} MB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Manage Models button
                OutlinedButton(
                    onClick = onNavigateToModelTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Manage Models")
                }
            }
        }

        // HuggingFace Token Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "HuggingFace Authentication",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Your HuggingFace token is required for downloading models from the HuggingFace Hub.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // OAuth Login Section (if configured)
                if (viewModel.isOAuthConfigured && activity != null) {
                    OAuthLoginSection(
                        oauthState = oauthState,
                        tokenState = tokenState,
                        onLoginClick = {
                            try {
                                AppContainer.huggingFaceAuthManager.startAuthFlow(activity, oauthLauncher)
                            } catch (e: Exception) {
                                viewModel.clearError()
                                viewModel.clearOAuthState()
                            }
                        },
                        onLogoutClick = { viewModel.clearToken() },
                        onDismissError = { viewModel.clearOAuthState() }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "Or enter a token manually:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                when (tokenState) {
                    is HuggingFaceTokenManager.TokenState.Valid -> {
                        val state = tokenState as HuggingFaceTokenManager.TokenState.Valid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Token Valid",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = "Username: ${state.username}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Token: ${state.maskedToken}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { viewModel.clearToken() }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Clear token",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    is HuggingFaceTokenManager.TokenState.Invalid -> {
                        val state = tokenState as HuggingFaceTokenManager.TokenState.Invalid
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { viewModel.onTokenInputChanged(it) },
                            label = { Text("HuggingFace Token") },
                            placeholder = { Text("hf_...") },
                            singleLine = true,
                            visualTransformation = if (tokenPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { tokenPasswordVisible = !tokenPasswordVisible }) {
                                    Icon(
                                        imageVector = if (tokenPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (tokenPasswordVisible) "Hide token" else "Show token"
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            isError = true
                        )
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            FilledTonalButton(
                                onClick = { viewModel.validateAndSaveToken() },
                                enabled = tokenInput.isNotBlank() && !uiState.isValidatingToken
                            ) {
                                if (uiState.isValidatingToken) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (uiState.isValidatingToken) "Validating..." else "Validate & Save")
                            }
                        }
                    }

                    is HuggingFaceTokenManager.TokenState.Validating -> {
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { viewModel.onTokenInputChanged(it) },
                            label = { Text("HuggingFace Token") },
                            placeholder = { Text("hf_...") },
                            singleLine = true,
                            visualTransformation = if (tokenPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { tokenPasswordVisible = !tokenPasswordVisible }) {
                                    Icon(
                                        imageVector = if (tokenPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (tokenPasswordVisible) "Hide token" else "Show token"
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = false
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Text("Validating token...")
                        }
                    }

                    is HuggingFaceTokenManager.TokenState.Idle -> {
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { viewModel.onTokenInputChanged(it) },
                            label = { Text("HuggingFace Token") },
                            placeholder = { Text("hf_...") },
                            singleLine = true,
                            visualTransformation = if (tokenPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { tokenPasswordVisible = !tokenPasswordVisible }) {
                                    Icon(
                                        imageVector = if (tokenPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (tokenPasswordVisible) "Hide token" else "Show token"
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            FilledTonalButton(
                                onClick = { viewModel.validateAndSaveToken() },
                                enabled = tokenInput.isNotBlank() && !uiState.isValidatingToken
                            ) {
                                if (uiState.isValidatingToken) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (uiState.isValidatingToken) "Validating..." else "Validate & Save")
                            }
                        }
                    }
                }

                // Link to token creation page
                TextButton(
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://huggingface.co/settings/tokens")
                        )
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Get a token at huggingface.co/settings/tokens")
                }
            }
        }

        // Keep-Alive Timeout Setting
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Auto-Unload Timeout",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Automatically unload the model after a period of inactivity to free memory. " +
                           "The model will be automatically reloaded when needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Timeout options
                Column(modifier = Modifier.selectableGroup()) {
                    viewModel.timeoutOptions.forEach { minutes ->
                        val isSelected = currentTimeout == minutes
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        if (!isSelected && !uiState.isSaving) {
                                            viewModel.saveKeepAliveTimeout(minutes)
                                        }
                                    }
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                enabled = !uiState.isSaving
                            )
                            Text(
                                text = when (minutes) {
                                    1 -> "1 minute"
                                    60 -> "1 hour"
                                    else -> "$minutes minutes"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Saving indicator
                if (uiState.isSaving) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Saving...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Success indicator
                if (uiState.saveSuccess == true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Settings saved",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Error message
                uiState.errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Preload Info Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "Auto-Load & Preload",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                Text(
                    text = "The model will automatically load when a request is received and the model is not in memory. " +
                           "You can also preload the model explicitly:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Via Tasker:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "Action: com.localai.bridge.PRELOAD_MODEL",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Via ADB:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "adb shell am broadcast -a com.localai.bridge.PRELOAD_MODEL",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        // Spacer for scroll
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ==================== OAuth Login Section ====================

/**
 * OAuth login section with login button and status display.
 */
@Composable
private fun OAuthLoginSection(
    oauthState: SettingsViewModel.OAuthState,
    tokenState: HuggingFaceTokenManager.TokenState,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onDismissError: () -> Unit
) {
    when (oauthState) {
        is SettingsViewModel.OAuthState.Idle -> {
            // Show current token status or login button
            when (val state = tokenState) {
                is HuggingFaceTokenManager.TokenState.Valid -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = when (state.authType) {
                                        HuggingFaceTokenManager.AuthType.OAUTH -> MaterialTheme.colorScheme.tertiary
                                        HuggingFaceTokenManager.AuthType.MANUAL -> MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = when (state.authType) {
                                        HuggingFaceTokenManager.AuthType.OAUTH -> "OAuth Connected"
                                        HuggingFaceTokenManager.AuthType.MANUAL -> "Token Valid"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = when (state.authType) {
                                        HuggingFaceTokenManager.AuthType.OAUTH -> MaterialTheme.colorScheme.tertiary
                                        HuggingFaceTokenManager.AuthType.MANUAL -> MaterialTheme.colorScheme.primary
                                    }
                                )
                            }
                            Text(
                                text = "Username: ${state.username}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Token: ${state.maskedToken}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // Show expiration for OAuth tokens
                            if (state.authType == HuggingFaceTokenManager.AuthType.OAUTH && state.expiresAt != null) {
                                val expiresText = formatExpiration(state.expiresAt)
                                val isExpiringSoon = state.needsRefresh()
                                Text(
                                    text = "Expires: $expiresText",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isExpiringSoon)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = onLogoutClick) {
                            Icon(
                                imageVector = Icons.Default.Logout,
                                contentDescription = "Logout",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                else -> {
                    // No valid token - show login button
                    Button(
                        onClick = onLoginClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Icon(Icons.Default.Login, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Login with HuggingFace")
                    }
                    Text(
                        text = "Quick login using your browser. If you're already logged into HuggingFace in Chrome, you'll be redirected back instantly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        is SettingsViewModel.OAuthState.InProgress -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Authenticating with HuggingFace...")
            }
        }
        is SettingsViewModel.OAuthState.Success -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Logged in as ${oauthState.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        is SettingsViewModel.OAuthState.Error -> {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = oauthState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissError) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

/**
 * Formats expiration timestamp to a human-readable string.
 */
private fun formatExpiration(expiresAt: Long): String {
    val now = System.currentTimeMillis()
    val remaining = expiresAt - now

    return when {
        remaining <= 0 -> "Expired"
        remaining < 60_000 -> "${remaining / 1000} seconds"
        remaining < 3_600_000 -> "${remaining / 60_000} minutes"
        remaining < 86_400_000 -> "${remaining / 3_600_000} hours"
        else -> "${remaining / 86_400_000} days"
    }
}
