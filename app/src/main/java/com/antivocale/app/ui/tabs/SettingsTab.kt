package com.antivocale.app.ui.tabs

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.antivocale.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.TranscriptionCalibrator.CalibrationProfile
import com.antivocale.app.data.DiscoveredModel
import com.antivocale.app.data.HuggingFaceTokenManager
import com.antivocale.app.data.HuggingFaceOAuthConfig
import com.antivocale.app.data.ModelSource
import com.antivocale.app.di.AppContainer
import com.antivocale.app.manager.LlmManager
import com.antivocale.app.ui.components.UnloadModelButton
import com.antivocale.app.ui.screens.PerAppSettingsScreen
import com.antivocale.app.ui.theme.ThemeType
import com.antivocale.app.service.InferenceService
import com.antivocale.app.ui.viewmodel.SettingsViewModel

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
    val autoCopyEnabled by viewModel.autoCopyEnabled.collectAsState()
    val vadEnabled by viewModel.vadEnabled.collectAsState()
    val progressiveEnabled by viewModel.progressiveTranscription.collectAsState()
    val threadCount by viewModel.threadCount.collectAsState()
    val autoDetectedThreads = viewModel.autoDetectedThreadCount
    val currentLanguage by viewModel.currentLanguage.collectAsState()
    val currentTranscriptionLanguage by viewModel.currentTranscriptionLanguage.collectAsState()
    val currentTheme by viewModel.currentTheme.collectAsState()
    val swipeActionMode by viewModel.swipeActionMode.collectAsState()
    val tokenState by viewModel.tokenState.collectAsState()
    val tokenInput by viewModel.tokenInput.collectAsState()
    val oauthState by viewModel.oauthState.collectAsState()
    val isTranscribing by InferenceService.isTranscribing.collectAsState()
    val scrollState = rememberScrollState()
    var tokenPasswordVisible by remember { mutableStateOf(false) }
    var showOAuthConfigDialog by remember { mutableStateOf(false) }
    var showPerAppSettings by remember { mutableStateOf(false) }
    var showPerfStatsDialog by remember { mutableStateOf(false) }
    var perfStatsProfiles by remember { mutableStateOf<List<CalibrationProfile>>(emptyList()) }
    val perfStatsScope = rememberCoroutineScope()
    var showPromptSettings by remember { mutableStateOf(false) }

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

    // Check if model is currently loaded (only relevant for LLM backend)
    // Use StateFlow for reactive updates when model state changes
    val isLlmBackend = settingsState.transcriptionBackend == PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND
    val isModelLoaded by LlmManager.isReadyFlow.collectAsState()
    val remainingTime = LlmManager.getRemainingTimeSeconds() ?: 0L

    // Show sub-screens or main settings
    if (showPerAppSettings) {
        PerAppSettingsScreen(
            preferencesManager = AppContainer.perAppPreferencesManager,
            onBack = { showPerAppSettings = false }
        )
    } else if (showPromptSettings) {
        PromptSettingsScreen(
            viewModel = viewModel,
            onBack = { showPromptSettings = false }
        )
    } else {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Model Status Card (only show for LLM backend)
        if (isLlmBackend) {
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
                            imageVector = if (isModelLoaded) Icons.Default.CheckCircle else Icons.Default.RemoveCircleOutline,
                            contentDescription = null,
                            tint = if (isModelLoaded)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (isModelLoaded) stringResource(R.string.model_loaded) else stringResource(R.string.model_not_loaded),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isModelLoaded && remainingTime > 0L) {
                    val minutes = remainingTime / 60
                    val seconds = remainingTime % 60
                    Text(
                        text = stringResource(R.string.auto_unload_in, minutes, seconds),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                // Unload button - show when model is loaded
                if (isModelLoaded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    UnloadModelButton(
                        onClick = { viewModel.unloadModel() },
                        isTranscribing = isTranscribing
                    )
                }

                if (!isModelLoaded) {
                    Text(
                        text = stringResource(R.string.load_model_from_tab),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                        text = stringResource(R.string.active_model),
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
                            text = stringResource(R.string.no_model_selected_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
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
                        text = stringResource(R.string.huggingface_auth),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = stringResource(R.string.huggingface_auth_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Setup Guide (expandable) - only show when no valid token
                if (tokenState !is HuggingFaceTokenManager.TokenState.Valid) {
                    var showSetupGuide by remember { mutableStateOf(false) }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.HelpOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        stringResource(R.string.setup_guide),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                                IconButton(
                                    onClick = { showSetupGuide = !showSetupGuide },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        if (showSetupGuide) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (showSetupGuide) stringResource(R.string.show_less) else stringResource(R.string.show_more),
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                            if (showSetupGuide) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        stringResource(R.string.setup_step1),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Text(
                                        stringResource(R.string.setup_step2),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Text(
                                        stringResource(R.string.setup_step3),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Text(
                                        stringResource(R.string.setup_step4),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // OAuth Login Section (if configured) - PRIMARY OPTION
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
                }

                // Show token status if valid
                when (val currentState = tokenState) {
                    is HuggingFaceTokenManager.TokenState.Valid -> {
                        // Already handled by OAuth section or show here for manual tokens
                        if (currentState.authType == HuggingFaceTokenManager.AuthType.MANUAL) {
                            var showManualDetails by remember { mutableStateOf(false) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showManualDetails = !showManualDetails },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
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
                                    Icon(
                                        imageVector = if (showManualDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (showManualDetails) "Hide details" else "Show details",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
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
                            if (showManualDetails) {
                                Column(
                                    modifier = Modifier.padding(start = 24.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Username: ${currentState.username}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "Token: ${currentState.maskedToken}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        // Advanced: Manual Token Section (collapsible)
                        var showAdvanced by remember { mutableStateOf(false) }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        TextButton(
                            onClick = { showAdvanced = !showAdvanced },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (showAdvanced) "Hide Advanced: Manual Token" else "Advanced: Manual Token Input")
                        }

                        if (!showAdvanced) {
                            Text(
                                text = "For manual tokens, the 'read' scope is required to download models.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (showAdvanced) {
                            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                            when (val innerState = tokenState) {
                                is HuggingFaceTokenManager.TokenState.Invalid -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = tokenInput,
                                            onValueChange = { viewModel.onTokenInputChanged(it) },
                                            label = { Text("HuggingFace Token") },
                                            placeholder = { Text("hf_...") },
                                            singleLine = true,
                                            visualTransformation = if (tokenPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                            trailingIcon = {
                                                Row {
                                                    IconButton(onClick = {
                                                        clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()?.let {
                                                            viewModel.onTokenInputChanged(it)
                                                        }
                                                    }) {
                                                        Icon(
                                                            imageVector = Icons.Default.ContentPaste,
                                                            contentDescription = "Paste from clipboard"
                                                        )
                                                    }
                                                    IconButton(onClick = { tokenPasswordVisible = !tokenPasswordVisible }) {
                                                        Icon(
                                                            imageVector = if (tokenPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                            contentDescription = if (tokenPasswordVisible) "Hide token" else "Show token"
                                                        )
                                                    }
                                                }
                                            },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                            modifier = Modifier.weight(1f),
                                            isError = true
                                        )
                                    }
                                    Text(
                                        text = innerState.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    // Fix buttons
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        FilledTonalButton(
                                            onClick = {
                                                val intent = android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse("https://huggingface.co/settings/tokens")
                                                )
                                                context.startActivity(intent)
                                            }
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.OpenInNew,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Create Token")
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.validateAndSaveToken() },
                                            enabled = tokenInput.isNotBlank() && !uiState.isValidatingToken
                                        ) {
                                            Text("Retry")
                                        }
                                    }
                                }

                                is HuggingFaceTokenManager.TokenState.Validating -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = tokenInput,
                                            onValueChange = { viewModel.onTokenInputChanged(it) },
                                            label = { Text("HuggingFace Token") },
                                            placeholder = { Text("hf_...") },
                                            singleLine = true,
                                            visualTransformation = if (tokenPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                            trailingIcon = {
                                                Row {
                                                    IconButton(onClick = {
                                                        clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()?.let {
                                                            viewModel.onTokenInputChanged(it)
                                                        }
                                                    }) {
                                                        Icon(
                                                            imageVector = Icons.Default.ContentPaste,
                                                            contentDescription = "Paste from clipboard"
                                                        )
                                                    }
                                                    IconButton(onClick = { tokenPasswordVisible = !tokenPasswordVisible }) {
                                                        Icon(
                                                            imageVector = if (tokenPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                            contentDescription = if (tokenPasswordVisible) "Hide token" else "Show token"
                                                        )
                                                    }
                                                }
                                            },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                            modifier = Modifier.weight(1f),
                                            enabled = false
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                        Text("Validating token...")
                                    }
                                }

                                is HuggingFaceTokenManager.TokenState.Idle -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = tokenInput,
                                            onValueChange = { viewModel.onTokenInputChanged(it) },
                                            label = { Text("HuggingFace Token") },
                                            placeholder = { Text("hf_...") },
                                            singleLine = true,
                                            visualTransformation = if (tokenPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                            trailingIcon = {
                                                Row {
                                                    IconButton(onClick = {
                                                        clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()?.let {
                                                            viewModel.onTokenInputChanged(it)
                                                        }
                                                    }) {
                                                        Icon(
                                                            imageVector = Icons.Default.ContentPaste,
                                                            contentDescription = "Paste from clipboard"
                                                        )
                                                    }
                                                    IconButton(onClick = { tokenPasswordVisible = !tokenPasswordVisible }) {
                                                        Icon(
                                                            imageVector = if (tokenPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                            contentDescription = if (tokenPasswordVisible) "Hide token" else "Show token"
                                                        )
                                                    }
                                                }
                                            },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                        // Link to token creation page
                                        TextButton(
                                            onClick = {
                                                val intent = android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse("https://huggingface.co/settings/tokens")
                                                )
                                                context.startActivity(intent)
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Get Token")
                                        }
                                    }
                                }

                                is HuggingFaceTokenManager.TokenState.Valid -> {
                                    // Already handled above
                                }
                            }
                        }
                    }
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
                        text = stringResource(R.string.auto_unload_timeout),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = stringResource(R.string.timeout_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Timeout dropdown
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    TextField(
                        value = when (currentTimeout) {
                            1 -> stringResource(R.string.timeout_1_minute)
                            60 -> stringResource(R.string.timeout_1_hour)
                            else -> stringResource(R.string.timeout_minutes, currentTimeout)
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.auto_unload_timeout)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        enabled = !uiState.isSaving,
                        colors = ExposedDropdownMenuDefaults.textFieldColors(
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.exposedDropdownSize()
                    ) {
                        viewModel.timeoutOptions.forEach { minutes ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (minutes) {
                                            1 -> stringResource(R.string.timeout_1_minute)
                                            60 -> stringResource(R.string.timeout_1_hour)
                                            else -> stringResource(R.string.timeout_minutes, minutes)
                                        }
                                    )
                                },
                                onClick = {
                                    viewModel.saveKeepAliveTimeout(minutes)
                                    expanded = false
                                },
                                trailingIcon = if (currentTimeout == minutes) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
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
                            text = stringResource(R.string.saving),
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
                            text = stringResource(R.string.settings_saved),
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

        // Thread Count Setting
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
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.thread_count_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = stringResource(R.string.thread_count_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Thread count dropdown
                var threadExpanded by remember { mutableStateOf(false) }
                val threadOptions = (1..8).toList()
                ExposedDropdownMenuBox(
                    expanded = threadExpanded,
                    onExpandedChange = { threadExpanded = it }
                ) {
                    TextField(
                        value = if (threadCount == autoDetectedThreads)
                            stringResource(R.string.thread_count_auto, autoDetectedThreads)
                        else
                            stringResource(R.string.thread_count_value, threadCount),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.thread_count_title)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = threadExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        colors = ExposedDropdownMenuDefaults.textFieldColors(
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = threadExpanded,
                        onDismissRequest = { threadExpanded = false },
                        modifier = Modifier.exposedDropdownSize()
                    ) {
                        threadOptions.forEach { threads ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (threads == autoDetectedThreads)
                                            stringResource(R.string.thread_count_auto, threads)
                                        else
                                            stringResource(R.string.thread_count_value, threads)
                                    )
                                },
                                onClick = {
                                    viewModel.saveThreadCount(threads)
                                    threadExpanded = false
                                },
                                trailingIcon = if (threadCount == threads) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )
                        }
                    }
                }
            }
        }

        // Auto-Copy Setting
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.auto_copy_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.auto_copy_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = autoCopyEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.saveAutoCopyEnabled(enabled)
                    }
                )
            }
        }

        // VAD Silence Stripping Setting
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.vad_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.vad_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = vadEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.saveVadEnabled(enabled)
                    }
                )
            }
        }

        // Progressive Transcription Display Setting
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.progressive_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.progressive_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = progressiveEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.saveProgressiveTranscription(enabled)
                    }
                )
            }
        }

        // Swipe Action Setting
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Swipe,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.swipe_action_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = stringResource(R.string.swipe_action_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                var swipeActionExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = swipeActionExpanded,
                    onExpandedChange = { swipeActionExpanded = it }
                ) {
                    TextField(
                        value = when (swipeActionMode) {
                            "REVEAL" -> stringResource(R.string.swipe_action_reveal)
                            "IMMEDIATE_DELETE" -> stringResource(R.string.swipe_action_immediate_delete)
                            else -> swipeActionMode
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.swipe_action_title)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = swipeActionExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        enabled = !uiState.isSaving,
                        colors = ExposedDropdownMenuDefaults.textFieldColors(
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = swipeActionExpanded,
                        onDismissRequest = { swipeActionExpanded = false },
                        modifier = Modifier.exposedDropdownSize()
                    ) {
                        listOf(
                            "REVEAL" to stringResource(R.string.swipe_action_reveal),
                            "IMMEDIATE_DELETE" to stringResource(R.string.swipe_action_immediate_delete)
                        ).forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    viewModel.saveSwipeActionMode(mode)
                                    swipeActionExpanded = false
                                },
                                trailingIcon = if (swipeActionMode == mode) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )
                        }
                    }
                }
            }
        }

        // Language Setting
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
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.language_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = stringResource(R.string.language_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Language dropdown
                var languageExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = languageExpanded,
                    onExpandedChange = { languageExpanded = it }
                ) {
                    TextField(
                        value = when (currentLanguage) {
                            "system" -> stringResource(R.string.language_system)
                            "en" -> stringResource(R.string.language_english)
                            "it" -> stringResource(R.string.language_italian)
                            else -> currentLanguage
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.language_title)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        enabled = !uiState.isSaving,
                        colors = ExposedDropdownMenuDefaults.textFieldColors(
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = languageExpanded,
                        onDismissRequest = { languageExpanded = false },
                        modifier = Modifier.exposedDropdownSize()
                    ) {
                        viewModel.languageOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (option.code) {
                                            "system" -> stringResource(R.string.language_system)
                                            "en" -> stringResource(R.string.language_english)
                                            "it" -> stringResource(R.string.language_italian)
                                            else -> option.displayName
                                        }
                                    )
                                },
                                onClick = {
                                    viewModel.saveLanguagePreference(option.code)
                                    languageExpanded = false
                                },
                                trailingIcon = if (currentLanguage == option.code) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )
                        }
                    }
                }
            }
        }

        // Transcription Language Setting
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
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.transcription_language_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = stringResource(R.string.transcription_language_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Transcription language dropdown
                var transcriptionLanguageExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = transcriptionLanguageExpanded,
                    onExpandedChange = { transcriptionLanguageExpanded = it }
                ) {
                    TextField(
                        value = viewModel.transcriptionLanguageOptions.find { it.code == currentTranscriptionLanguage }?.let {
                            if (it.code == "auto") stringResource(R.string.transcription_language_auto) else it.displayName
                        } ?: currentTranscriptionLanguage,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.transcription_language_title)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = transcriptionLanguageExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        enabled = !uiState.isSaving,
                        colors = ExposedDropdownMenuDefaults.textFieldColors(
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = transcriptionLanguageExpanded,
                        onDismissRequest = { transcriptionLanguageExpanded = false },
                        modifier = Modifier.exposedDropdownSize()
                    ) {
                        viewModel.transcriptionLanguageOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (option.code == "auto") stringResource(R.string.transcription_language_auto)
                                        else option.displayName
                                    )
                                },
                                onClick = {
                                    viewModel.saveTranscriptionLanguage(option.code)
                                    transcriptionLanguageExpanded = false
                                },
                                trailingIcon = if (currentTranscriptionLanguage == option.code) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.transcription_language_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Theme Setting
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
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.theme_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = stringResource(R.string.theme_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Theme dropdown
                var themeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = themeExpanded,
                    onExpandedChange = { themeExpanded = it }
                ) {
                    TextField(
                        value = currentTheme.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.theme_title)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = themeExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        enabled = !uiState.isSaving,
                        colors = ExposedDropdownMenuDefaults.textFieldColors(
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = themeExpanded,
                        onDismissRequest = { themeExpanded = false },
                        modifier = Modifier.exposedDropdownSize()
                    ) {
                        viewModel.themeOptions.forEach { theme ->
                            DropdownMenuItem(
                                text = { Text(theme.displayName) },
                                onClick = {
                                    viewModel.saveThemePreference(theme)
                                    themeExpanded = false
                                },
                                trailingIcon = if (currentTheme == theme) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )
                        }
                    }
                }
            }
        }

        // Default Prompt Setting Navigation Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showPromptSettings = true },
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.default_prompt_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.default_prompt_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Open prompt settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Per-App Settings Navigation Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showPerAppSettings = true },
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.per_app_settings_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.per_app_settings_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Open per-app settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Performance Stats Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    perfStatsScope.launch {
                        perfStatsProfiles = AppContainer.transcriptionCalibrator.getAllProfiles()
                        showPerfStatsDialog = true
                    }
                },
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.performance_stats_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.performance_stats_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Open performance stats",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Performance Stats Dialog
        if (showPerfStatsDialog) {
            PerformanceStatsDialog(
                profiles = perfStatsProfiles,
                isTranscribing = isTranscribing,
                onDismiss = { showPerfStatsDialog = false },
                onReset = {
                    perfStatsScope.launch {
                        AppContainer.transcriptionCalibrator.resetAll()
                        perfStatsProfiles = emptyList()
                    }
                }
            )
        }

        // Spacer for scroll
        Spacer(modifier = Modifier.height(32.dp))
    }
    } // End of if-else for showPerAppSettings
}

// ==================== Prompt Settings Screen ====================

@Composable
private fun PromptSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val defaultPrompt by viewModel.defaultPrompt.collectAsState()
    var promptInput by remember { mutableStateOf(defaultPrompt) }
    val context = LocalContext.current

    LaunchedEffect(defaultPrompt) {
        promptInput = defaultPrompt
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with back button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back"
                )
            }
            Text(
                text = stringResource(R.string.default_prompt_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        // Description
        Text(
            text = stringResource(R.string.default_prompt_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Current default info
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.current_default_prompt_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = defaultPrompt.ifEmpty { stringResource(R.string.builtin_default_prompt) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = if (defaultPrompt.isEmpty()) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal
                )
            }
        }

        // Compatibility info banner
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(R.string.default_prompt_compatibility_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Prompt input field
        OutlinedTextField(
            value = promptInput,
            onValueChange = { newValue ->
                if (newValue.length <= 500) {
                    promptInput = newValue
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.default_prompt_placeholder)) },
            minLines = 3,
            maxLines = 6,
            supportingText = {
                Text(
                    text = stringResource(R.string.default_prompt_chars, promptInput.length),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End
                )
            },
            trailingIcon = {
                if (promptInput != defaultPrompt) {
                    IconButton(onClick = {
                        viewModel.saveDefaultPrompt(promptInput)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.save)
                        )
                    }
                }
            }
        )

        HorizontalDivider()

        // Example Prompts Section
        Text(
            text = stringResource(R.string.example_prompts_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.example_prompts_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val examplePrompts = listOf(
            R.string.example_prompt_transcribe,
            R.string.example_prompt_summarize,
            R.string.example_prompt_formal,
            R.string.example_prompt_translate_en,
            R.string.example_prompt_translate_it,
            R.string.example_prompt_notes
        )

        examplePrompts.forEach { promptResId ->
            val promptText = stringResource(promptResId)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        promptInput = promptText
                        viewModel.saveDefaultPrompt(promptText)
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.prompt_applied),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (defaultPrompt == promptText)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (defaultPrompt == promptText) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = promptText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

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
                    var showDetails by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDetails = !showDetails },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
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
                            Icon(
                                imageVector = if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (showDetails) "Hide details" else "Show details",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = onLogoutClick) {
                            Icon(
                                imageVector = Icons.Default.Logout,
                                contentDescription = "Logout",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (showDetails) {
                        Column(
                            modifier = Modifier.padding(start = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
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
                    }
                }
                else -> {
                    // No valid token - show login button prominently
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
                    // Alternative: Get token manually link
                    val context = LocalContext.current
                    TextButton(
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://huggingface.co/settings/tokens")
                            )
                            context.startActivity(intent)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Don't have an account? Create one at huggingface.co")
                    }
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


// ==================== Performance Stats Dialog ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PerformanceStatsDialog(
    profiles: List<CalibrationProfile>,
    isTranscribing: Boolean,
    onDismiss: () -> Unit,
    onReset: () -> Unit
) {
    var showResetConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(stringResource(R.string.performance_stats_title))
            }
        },
        text = {
            if (profiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.performance_stats_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.performance_stats_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Find slowest speed for relative calculation
                    val slowestMsPerSec = profiles.maxOf { it.msPerSecondOfAudio }
                    val fastestProfile = profiles.first()

                    profiles.forEach { profile ->
                        val isFastest = profile == fastestProfile && profiles.size > 1

                        // Real-time factor: lower ms/s = faster. RTF < 1.0 means faster than real-time.
                        val rtf = profile.msPerSecondOfAudio / 1000f
                        val speedLabel = if (rtf <= 1f) {
                            String.format("%.1fx real-time", 1f / rtf)
                        } else {
                            String.format("%.2fx real-time", 1f / rtf)
                        }
                        val relativeSpeed = if (slowestMsPerSec > 0 && profiles.size > 1) {
                            slowestMsPerSec / profile.msPerSecondOfAudio
                        } else null

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            colors = CardDefaults.cardColors(
                                containerColor = if (isFastest)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Row 1: Model name + Fastest badge
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = profile.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1
                                    )
                                    if (isFastest) {
                                        Surface(
                                            shape = MaterialTheme.shapes.extraSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Text(
                                                text = stringResource(R.string.performance_stats_fastest_badge),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                // Row 2: Speed + relative
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = speedLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isFastest) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (relativeSpeed != null) {
                                        Text(
                                            text = String.format("(%.1fx)", relativeSpeed),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                // Row 3: Metadata
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.performance_stats_samples_count,
                                            profile.sampleCount
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (profile.lastTimestamp > 0) {
                                        Text(
                                            text = formatLastUsed(profile.lastTimestamp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Total audio processed
                    val totalAudio = profiles.sumOf { it.totalAudioSeconds }
                    if (totalAudio > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                        Text(
                            text = stringResource(
                                R.string.performance_stats_total_audio,
                                formatAudioDuration(totalAudio)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profiles.isNotEmpty()) {
                    TextButton(onClick = { showResetConfirm = true }) {
                        Text(
                            text = stringResource(R.string.performance_stats_clear),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        }
    )

    // Reset confirmation dialog
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.performance_stats_clear)) },
            text = {
                if (isTranscribing) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.performance_stats_clear_confirm))
                        HorizontalDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.warn_reset_stats_during_transcription),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                } else {
                    Text(stringResource(R.string.performance_stats_clear_confirm))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onReset()
                        showResetConfirm = false
                    }
                ) {
                    Text(
                        text = stringResource(R.string.performance_stats_clear),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun formatLastUsed(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val elapsed = System.currentTimeMillis() - timestamp
    val minutes = elapsed / 60_000
    val hours = elapsed / 3_600_000
    val days = elapsed / 86_400_000

    return when {
        minutes < 1 -> stringResource(R.string.performance_stats_just_now)
        minutes < 60 -> stringResource(R.string.performance_stats_minutes_ago, minutes)
        hours < 24 -> stringResource(R.string.performance_stats_hours_ago, hours)
        else -> stringResource(R.string.performance_stats_days_ago, days)
    }
}

private fun formatAudioDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> String.format("%dh %dm", hours, minutes)
        minutes > 0 -> String.format("%dm %ds", minutes, seconds)
        else -> String.format("%ds", seconds)
    }
}
