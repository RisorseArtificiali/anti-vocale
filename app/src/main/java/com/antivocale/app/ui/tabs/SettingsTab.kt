package com.antivocale.app.ui.tabs

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.TranscriptionCalibrator.CalibrationProfile
import com.antivocale.app.transcription.InferenceProvider
import com.antivocale.app.data.DiscoveredModel
import com.antivocale.app.data.HuggingFaceTokenManager
import com.antivocale.app.data.HuggingFaceOAuthConfig
import com.antivocale.app.data.ModelSource
import com.antivocale.app.ui.components.CollapsibleSection
import com.antivocale.app.ui.components.HF_TOKEN_SETTINGS_URL
import com.antivocale.app.ui.components.OAuthLoginSection
import com.antivocale.app.ui.components.SettingsDropdown
import com.antivocale.app.ui.components.TokenInputField
import com.antivocale.app.ui.components.ToggleSettingCard
import com.antivocale.app.ui.components.UnloadModelButton
import com.antivocale.app.ui.dialogs.PerformanceStatsDialog
import com.antivocale.app.ui.screens.PerAppSettingsScreen
import com.antivocale.app.ui.screens.PromptSettingsScreen
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
    val viewModel: SettingsViewModel = hiltViewModel()

    val uiState by viewModel.uiState.collectAsState()
    val currentTimeout by viewModel.keepAliveTimeout.collectAsState()
    val autoCopyEnabled by viewModel.autoCopyEnabled.collectAsState()
    val outputFolderUri by viewModel.outputFolderUri.collectAsState()
    val vadEnabled by viewModel.vadEnabled.collectAsState()
    val progressiveEnabled by viewModel.progressiveTranscription.collectAsState()
    val threadCount by viewModel.threadCount.collectAsState()
    val inferenceProvider by viewModel.inferenceProvider.collectAsState()
    val autoDetectedThreads = viewModel.autoDetectedThreadCount
    val currentLanguage by viewModel.currentLanguage.collectAsState()
    val currentTranscriptionLanguage by viewModel.currentTranscriptionLanguage.collectAsState()
    val currentTheme by viewModel.currentTheme.collectAsState()
    val swipeActionMode by viewModel.swipeActionMode.collectAsState()
    val groupLogsByConversation by viewModel.groupLogsByConversation.collectAsState()
    val advancedSharingEnabled by viewModel.advancedSharingEnabled.collectAsState()
    val showRetranscribeButton by viewModel.showRetranscribeButton.collectAsState()
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

    // SAF folder picker for transcript auto-save (issue #14)
    val outputFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w("SettingsTab", "Failed to take persistable URI permission", e)
            }
            viewModel.saveOutputFolderUri(uri.toString())
        }
    }

    // Load models on first composition
    LaunchedEffect(Unit) {
        viewModel.loadCurrentModel()
        viewModel.scanAvailableModels()
    }

    // Check if model is currently loaded (only relevant for LLM backend)
    val isLlmBackend = uiState.transcriptionBackend == PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND
    val isModelLoaded by viewModel.llmIsReadyFlow.collectAsState()
    val remainingTime = viewModel.llmRemainingTimeSeconds ?: 0L

    // Show sub-screens or main settings
    if (showPerAppSettings) {
        PerAppSettingsScreen(
            preferencesManager = viewModel.perAppPreferencesManager,
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
            .navigationBarsPadding()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CollapsibleSection(
            title = stringResource(R.string.settings_section_transcription),
            icon = Icons.Default.Mic,
            initiallyExpanded = true
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
                    if (uiState.currentModelPath != null) {
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
                                    text = uiState.currentModelName ?: stringResource(R.string.model_unknown),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = uiState.currentModelPath ?: "",
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
                    SettingsDropdown(
                        currentValue = currentTranscriptionLanguage,
                        options = viewModel.transcriptionLanguageOptions.map { it.code },
                        currentValueDisplay = viewModel.transcriptionLanguageOptions.find { it.code == currentTranscriptionLanguage }?.let {
                            if (it.code == "auto") stringResource(R.string.transcription_language_auto) else it.displayName
                        } ?: currentTranscriptionLanguage,
                        optionDisplay = { code ->
                            if (code == "auto") stringResource(R.string.transcription_language_auto)
                            else viewModel.transcriptionLanguageOptions.find { it.code == code }?.displayName ?: code
                        },
                        onOptionSelected = { viewModel.saveTranscriptionLanguage(it) },
                        label = stringResource(R.string.transcription_language_title),
                        enabled = !uiState.isSaving
                    )

                    Text(
                        text = stringResource(R.string.transcription_language_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Auto-Copy Setting
            ToggleSettingCard(
                icon = Icons.Default.ContentCopy,
                title = stringResource(R.string.auto_copy_title),
                description = stringResource(R.string.auto_copy_description),
                checked = autoCopyEnabled,
                onCheckedChange = { enabled ->
                    viewModel.saveAutoCopyEnabled(enabled)
                }
            )

            // Output Folder auto-save Setting (issue #14)
            OutputFolderSettingCard(
                outputFolderUri = outputFolderUri,
                onChoose = { outputFolderLauncher.launch(null) },
                onClear = { viewModel.saveOutputFolderUri(null) }
            )

            // VAD Silence Stripping Setting
            ToggleSettingCard(
                icon = Icons.Default.GraphicEq,
                title = stringResource(R.string.vad_title),
                description = stringResource(R.string.vad_description),
                checked = vadEnabled,
                onCheckedChange = { enabled ->
                    viewModel.saveVadEnabled(enabled)
                }
            )

            // Progressive Transcription Display Setting
            ToggleSettingCard(
                icon = Icons.Default.Visibility,
                title = stringResource(R.string.progressive_title),
                description = stringResource(R.string.progressive_description),
                checked = progressiveEnabled,
                onCheckedChange = { enabled ->
                    viewModel.saveProgressiveTranscription(enabled)
                }
            )

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
                        contentDescription = stringResource(R.string.open_prompt_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                    SettingsDropdown(
                        currentValue = currentTimeout,
                        options = viewModel.timeoutOptions,
                        currentValueDisplay = when (currentTimeout) {
                            1 -> stringResource(R.string.timeout_1_minute)
                            60 -> stringResource(R.string.timeout_1_hour)
                            else -> stringResource(R.string.timeout_minutes, currentTimeout)
                        },
                        optionDisplay = { minutes ->
                            when (minutes) {
                                1 -> stringResource(R.string.timeout_1_minute)
                                60 -> stringResource(R.string.timeout_1_hour)
                                else -> stringResource(R.string.timeout_minutes, minutes)
                            }
                        },
                        onOptionSelected = { viewModel.saveKeepAliveTimeout(it) },
                        label = stringResource(R.string.auto_unload_timeout),
                        enabled = !uiState.isSaving
                    )

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
        }

        CollapsibleSection(
            title = stringResource(R.string.settings_section_appearance),
            icon = Icons.Default.Palette,
            initiallyExpanded = true
        ) {
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
                    SettingsDropdown(
                        currentValue = currentTheme,
                        options = viewModel.themeOptions,
                        currentValueDisplay = currentTheme.displayName,
                        optionDisplay = { it.displayName },
                        onOptionSelected = { viewModel.saveThemePreference(it) },
                        label = stringResource(R.string.theme_title),
                        enabled = !uiState.isSaving
                    )
                }
            }

            // Language Setting (App Language)
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
                    SettingsDropdown(
                        currentValue = currentLanguage,
                        options = viewModel.languageOptions.map { it.code },
                        currentValueDisplay = when (currentLanguage) {
                            "system" -> stringResource(R.string.language_system)
                            "en" -> stringResource(R.string.language_english)
                            "it" -> stringResource(R.string.language_italian)
                            else -> currentLanguage
                        },
                        optionDisplay = { code ->
                            when (code) {
                                "system" -> stringResource(R.string.language_system)
                                "en" -> stringResource(R.string.language_english)
                                "it" -> stringResource(R.string.language_italian)
                                else -> viewModel.languageOptions.find { it.code == code }?.displayName ?: code
                            }
                        },
                        onOptionSelected = { viewModel.saveLanguagePreference(it) },
                        label = stringResource(R.string.language_title),
                        enabled = !uiState.isSaving
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

                    SettingsDropdown(
                        currentValue = swipeActionMode,
                        options = listOf("REVEAL", "IMMEDIATE_DELETE"),
                        currentValueDisplay = when (swipeActionMode) {
                            "REVEAL" -> stringResource(R.string.swipe_action_reveal)
                            "IMMEDIATE_DELETE" -> stringResource(R.string.swipe_action_immediate_delete)
                            else -> swipeActionMode
                        },
                        optionDisplay = { mode ->
                            when (mode) {
                                "REVEAL" -> stringResource(R.string.swipe_action_reveal)
                                "IMMEDIATE_DELETE" -> stringResource(R.string.swipe_action_immediate_delete)
                                else -> mode
                            }
                        },
                        onOptionSelected = { viewModel.saveSwipeActionMode(it) },
                        label = stringResource(R.string.swipe_action_title),
                        enabled = !uiState.isSaving
                    )
                }
            }

            // Conversation Grouping Setting
            ToggleSettingCard(
                icon = Icons.Default.Forum,
                title = stringResource(R.string.conversation_grouping_title),
                description = stringResource(R.string.conversation_grouping_description),
                checked = groupLogsByConversation,
                onCheckedChange = { enabled ->
                    viewModel.saveGroupLogsByConversation(enabled)
                }
            )
        }

        CollapsibleSection(
            title = stringResource(R.string.settings_section_advanced),
            icon = Icons.Default.Settings,
            initiallyExpanded = false
        ) {
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
                                    viewModel.huggingFaceAuthManager.startAuthFlow(activity, oauthLauncher)
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
                                            text = stringResource(R.string.token_valid),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Icon(
                                            imageVector = if (showManualDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = if (showManualDetails) stringResource(R.string.hide_details) else stringResource(R.string.show_details),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(onClick = { viewModel.clearToken() }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.clear_token),
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
                                            text = stringResource(R.string.username_label, currentState.username),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = stringResource(R.string.token_label, currentState.maskedToken),
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
                                Text(if (showAdvanced) stringResource(R.string.hide_advanced) else stringResource(R.string.advanced_manual_token))
                            }

                            if (!showAdvanced) {
                                Text(
                                    text = stringResource(R.string.manual_token_scope_info),
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
                                            TokenInputField(
                                                value = tokenInput,
                                                onValueChange = { viewModel.onTokenInputChanged(it) },
                                                tokenPasswordVisible = tokenPasswordVisible,
                                                onPasswordVisibilityToggle = { tokenPasswordVisible = !tokenPasswordVisible },
                                                clipboardManager = clipboardManager,
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
                                                        android.net.Uri.parse(HF_TOKEN_SETTINGS_URL)
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
                                                Text(stringResource(R.string.create_token))
                                            }
                                            OutlinedButton(
                                                onClick = { viewModel.validateAndSaveToken() },
                                                enabled = tokenInput.isNotBlank() && !uiState.isValidatingToken
                                            ) {
                                                Text(stringResource(R.string.retry))
                                            }
                                        }
                                    }

                                    is HuggingFaceTokenManager.TokenState.Validating -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            TokenInputField(
                                                value = tokenInput,
                                                onValueChange = { viewModel.onTokenInputChanged(it) },
                                                tokenPasswordVisible = tokenPasswordVisible,
                                                onPasswordVisibilityToggle = { tokenPasswordVisible = !tokenPasswordVisible },
                                                clipboardManager = clipboardManager,
                                                modifier = Modifier.weight(1f),
                                                enabled = false
                                            )
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                            Text(stringResource(R.string.validating_token))
                                        }
                                    }

                                    is HuggingFaceTokenManager.TokenState.Idle -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            TokenInputField(
                                                value = tokenInput,
                                                onValueChange = { viewModel.onTokenInputChanged(it) },
                                                tokenPasswordVisible = tokenPasswordVisible,
                                                onPasswordVisibilityToggle = { tokenPasswordVisible = !tokenPasswordVisible },
                                                clipboardManager = clipboardManager,
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
                                                Text(if (uiState.isValidatingToken) stringResource(R.string.validating) else stringResource(R.string.validate_and_save))
                                            }
                                            // Link to token creation page
                                            TextButton(
                                                onClick = {
                                                    val intent = android.content.Intent(
                                                        android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(HF_TOKEN_SETTINGS_URL)
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
                                                Text(stringResource(R.string.get_token))
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
                    SettingsDropdown(
                        currentValue = threadCount,
                        options = (1..8).toList(),
                        currentValueDisplay = if (threadCount == autoDetectedThreads)
                            stringResource(R.string.thread_count_auto, autoDetectedThreads)
                        else
                            stringResource(R.string.thread_count_value, threadCount),
                        optionDisplay = { threads ->
                            if (threads == autoDetectedThreads)
                                stringResource(R.string.thread_count_auto, threads)
                            else
                                stringResource(R.string.thread_count_value, threads)
                        },
                        onOptionSelected = { viewModel.saveThreadCount(it) },
                        label = stringResource(R.string.thread_count_title)
                    )
                }
            }

            // Inference Provider Setting
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
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.inference_provider_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = stringResource(R.string.inference_provider_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    SettingsDropdown(
                        currentValue = inferenceProvider,
                        options = InferenceProvider.options,
                        currentValueDisplay = when (inferenceProvider) {
                            InferenceProvider.AUTO -> stringResource(R.string.inference_provider_auto)
                            InferenceProvider.NNAPI -> stringResource(R.string.inference_provider_nnapi)
                            InferenceProvider.CPU -> stringResource(R.string.inference_provider_cpu)
                            else -> inferenceProvider
                        },
                        optionDisplay = { option ->
                            when (option) {
                                InferenceProvider.AUTO -> stringResource(R.string.inference_provider_auto)
                                InferenceProvider.NNAPI -> stringResource(R.string.inference_provider_nnapi)
                                InferenceProvider.CPU -> stringResource(R.string.inference_provider_cpu)
                                else -> option
                            }
                        },
                        onOptionSelected = { viewModel.saveInferenceProvider(it) },
                        label = stringResource(R.string.inference_provider_title)
                    )
                }
            }

            // Advanced Sharing Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
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
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.share_targets_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = stringResource(R.string.share_targets_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.advanced_sharing_toggle),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Switch(
                            checked = advancedSharingEnabled,
                            onCheckedChange = { viewModel.saveAdvancedSharingEnabled(it) }
                        )
                    }

                    if (advancedSharingEnabled) {
                        Text(
                            text = stringResource(R.string.share_targets_models_info),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Re-transcribe Button Setting
            ToggleSettingCard(
                icon = Icons.Default.Refresh,
                title = stringResource(R.string.retranscribe_setting_title),
                description = stringResource(R.string.retranscribe_setting_description),
                checked = showRetranscribeButton,
                onCheckedChange = { viewModel.saveShowRetranscribeButton(it) }
            )

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
                        contentDescription = stringResource(R.string.open_per_app_settings),
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
                            perfStatsProfiles = viewModel.transcriptionCalibrator.getAllProfiles()
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
                        contentDescription = stringResource(R.string.open_performance_stats),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                        viewModel.transcriptionCalibrator.resetAll()
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

/**
 * Setting card for the transcript auto-save folder (issue #14). Mirrors [ToggleSettingCard]'s
 * layout (icon + title + description in a Card) but swaps the switch for either a
 * "Choose folder" button (no folder selected) or the selected folder name + "Clear" button.
 *
 * Enable = a folder is chosen; clearing the folder disables auto-save. No separate toggle.
 */
@Composable
private fun OutputFolderSettingCard(
    outputFolderUri: String?,
    onChoose: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val displayName = remember(outputFolderUri) {
        outputFolderUri?.let { uriStr ->
            runCatching {
                val uri = Uri.parse(uriStr)
                DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: uriStr
            }.getOrNull() ?: uriStr
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
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
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.output_folder_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.output_folder_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (displayName != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            if (outputFolderUri != null) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.output_folder_clear))
                }
            } else {
                Button(onClick = onChoose) {
                    Text(stringResource(R.string.output_folder_choose))
                }
            }
        }
    }
}
