package com.antivocale.app.ui.tabs

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import com.antivocale.app.BuildConfig
import com.antivocale.app.R
import com.antivocale.app.ui.AppNavigation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.TranscriptionCalibrator.CalibrationProfile
import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.InferenceProvider
import com.antivocale.app.transcription.PunctuationPolicy
import com.antivocale.app.transcription.TranscriptionLanguagePolicy
import com.antivocale.app.data.DiscoveredModel
import com.antivocale.app.data.HuggingFaceTokenManager
import com.antivocale.app.data.HuggingFaceOAuthConfig
import com.antivocale.app.data.ModelSource
import com.antivocale.app.ui.components.CardTitleRow
import com.antivocale.app.ui.components.CollapsibleSection
import com.antivocale.app.ui.components.HF_TOKEN_SETTINGS_URL
import com.antivocale.app.ui.components.OAuthLoginSection
import com.antivocale.app.ui.components.SectionCard
import com.antivocale.app.ui.components.SettingsDropdown
import com.antivocale.app.ui.components.TokenInputField
import com.antivocale.app.ui.components.ToggleSettingCard
import com.antivocale.app.ui.components.UnloadModelButton
import com.antivocale.app.ui.dialogs.PerformanceStatsDialog
import com.antivocale.app.ui.screens.LauncherIconScreen
import com.antivocale.app.ui.screens.PerAppSettingsScreen
import com.antivocale.app.ui.screens.PromptSettingsScreen
import com.antivocale.app.ui.theme.ThemeType
import com.antivocale.app.util.FeedbackHelper
import com.antivocale.app.util.LanguageNames
import com.antivocale.app.util.SubtitleFormatter
import com.antivocale.app.service.InferenceService
import com.antivocale.app.ui.viewmodel.LanguageOption
import com.antivocale.app.ui.components.EditablePromptCard
import com.antivocale.app.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    onNavigateToModelTab: () -> Unit = {},
    navRequest: AppNavigation.NavRequest? = null,
    onNavConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val viewModel: SettingsViewModel = hiltViewModel()

    val uiState by viewModel.uiState.collectAsState()
    val currentTimeout by viewModel.keepAliveTimeout.collectAsState()
    val autoCopyEnabled by viewModel.autoCopyEnabled.collectAsState()
    val outputFolderUri by viewModel.outputFolderUri.collectAsState()
    val transcriptExportFormat by viewModel.transcriptExportFormat.collectAsState()
    val vadEnabled by viewModel.vadEnabled.collectAsState()
    val progressiveEnabled by viewModel.progressiveTranscription.collectAsState()
    val threadCount by viewModel.threadCount.collectAsState()
    val inferenceProvider by viewModel.inferenceProvider.collectAsState()
    val autoDetectedThreads = viewModel.autoDetectedThreadCount
    val currentLanguage by viewModel.currentLanguage.collectAsState()
    val currentTranscriptionLanguage by viewModel.currentTranscriptionLanguage.collectAsState()
    val transcriptionPicker by viewModel.transcriptionLanguagePicker.collectAsState()
    val currentTheme by viewModel.currentTheme.collectAsState()
    val swipeActionMode by viewModel.swipeActionMode.collectAsState()
    val groupLogsByConversation by viewModel.groupLogsByConversation.collectAsState()
    val advancedSharingEnabled by viewModel.advancedSharingEnabled.collectAsState()
    val showRetranscribeButton by viewModel.showRetranscribeButton.collectAsState()
    val forceModelLoad by viewModel.forceModelLoad.collectAsState()
    val compactResultActions by viewModel.compactResultActions.collectAsState()
    // TASK-546: the chip flag (maintainer directive: the flag lives here).
    val languageChipEnabled by viewModel.languageChipEnabled.collectAsState()
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
    var showIconSettings by remember { mutableStateOf(false) }
    var showExportSettings by remember { mutableStateOf(false) }

    // TASK-542 (GH #98): live settings search. Blank = the normal tab.
    var searchQuery by remember { mutableStateOf("") }

    // TASK-486: TEST_SPI navigation. Sub-pages flip their flag; a section
    // destination bumps that section's expand counter and scrolls to it via
    // the offsets captured by each section's onGloballyPositioned.
    // Offsets are layout-thread writes read only inside the nav effect: a
    // plain map (no snapshot bookkeeping). Root-space Y of each section minus
    // the scroll container's own root Y gives the content-space target
    // animateScrollTo expects.
    val sectionOffsets = remember { mutableStateMapOf<String, Int>() }
    var scrollContentRootY by remember { mutableStateOf(0) }
    val expandCounters = remember { mutableStateMapOf<String, Int>() }
    val navScope = rememberCoroutineScope()
    LaunchedEffect(navRequest) {
        // TASK-543: production code (the auto-save hint) sets navRequest too,
        // so the BuildConfig.DEBUG gate is removed. The debug-only part is the
        // TEST_SPI receiver that SETS TestNavigation.pending, not this effect.
        val request = navRequest ?: return@LaunchedEffect
        // Consume FIRST at the source: a tab re-entry then sees null instead
        // of replaying (a guard remembered here would die with the tab).
        onNavConsumed()
        when (val dest = request.destination) {
            is AppNavigation.Destination.SettingsSubPage -> {
                // Exactly one sub-page wins the if/else-if chain: clear the
                // siblings, or the currently-open screen silently keeps it.
                showIconSettings = dest.key == "icon_picker"
                showPromptSettings = dest.key == "prompt"
                showPerAppSettings = dest.key == "per_app"
                showExportSettings = dest.key == AppNavigation.SUBPAGE_KEY_EXPORT
            }
            is AppNavigation.Destination.SettingsSection -> {
                // A section target needs the main Column composed: back out
                // of any open sub-page first or the scroll anchor never lays
                // out and the expand lands on a hidden screen.
                showIconSettings = false
                showPromptSettings = false
                showPerAppSettings = false
                showExportSettings = false
                expandCounters[dest.key] = (expandCounters[dest.key] ?: 0) + 1
                // First composition may run before layout delivers offsets:
                // wait one frame, then scroll if the anchor appeared.
                var target = sectionOffsets[dest.key]
                if (target == null) {
                    withFrameNanos { }
                    target = sectionOffsets[dest.key]
                }
                target?.let { rootY ->
                    val contentY = rootY - scrollContentRootY
                    navScope.launch { scrollState.animateScrollTo(maxOf(0, contentY - 32)) }
                }
            }
            else -> Unit
        }
    }

    // OAuth launcher
    val oauthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleOAuthResult(result.data)
    }

    // SAF folder picker for transcript auto-save (issue #14, TASK-539)
    val outputFolderLauncher = rememberOutputFolderPickerLauncher(viewModel, "SettingsTab")

    // Load models on first composition; the battery-kill count joins here so
    // the search filter sees it before the Advanced section ever composes
    // (that section's content only exists when expanded AND visible).
    LaunchedEffect(Unit) {
        viewModel.loadCurrentModel()
        viewModel.scanAvailableModels()
        viewModel.refreshBackgroundKills()
    }

    // Check if model is currently loaded (only relevant for LLM backend).
    // MUST compare to the LLM's own id, never to DEFAULT_TRANSCRIPTION_BACKEND:
    // the default was "llm" when this line was written and flipped to
    // "sherpa-onnx" (Parakeet) in de9b6aac, silently inverting the comparison
    // for six weeks (found in the 2026-09-13 settings audit; the status card
    // was rendering for Parakeet users and hiding from Gemma users).
    val isLlmBackend = BuiltInBackendIds.isLlm(uiState.transcriptionBackend)
    val isModelLoaded by viewModel.llmIsReadyFlow.collectAsState()
    val remainingTime = viewModel.llmRemainingTimeSeconds ?: 0L
    // TASK-507: the Gemma-pass rows (punctuation, summarize)
    // expose only when a Gemma model is configured; without one they can
    // never run and were silent no-ops.
    val gemmaConfigured by viewModel.gemmaConfigured.collectAsState()
    // Collected here (not inside their sections) so the search filter's card
    // groups can mirror each card's runtime condition exactly.
    val backgroundKills by viewModel.backgroundKills.collectAsState()
    val summarizeOn by viewModel.summarizeEnabled.collectAsState()
    val currentPunctuationMode by viewModel.currentPunctuationMode.collectAsState()

    // Show sub-screens or main settings
    if (showPerAppSettings) {
        PerAppSettingsScreen(
            preferencesManager = viewModel.perAppPreferencesManager,
            onBack = { showPerAppSettings = false }
        )
    } else if (showIconSettings) {
        LauncherIconScreen(
            viewModel = viewModel,
            onBack = { showIconSettings = false }
        )
    } else if (showExportSettings) {
        // TASK-543: the auto-save export config (folder + format) on its
        // own page, not buried inside the transcription section.
        ExportSettingsScreen(
            viewModel = viewModel,
            outputFolderUri = outputFolderUri,
            onBack = { showExportSettings = false }
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
            .onGloballyPositioned { scrollContentRootY = it.positionInRoot().y.toInt() }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // TASK-542 (GH #98): search field. Blank = normal tab.
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.settings_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.settings_search_clear)
                        )
                    }
                }
            },
            singleLine = true,
            // TASK-564: matches the Models tab's language filter field
            // (RoundedCornerShape(12.dp)), not the extraLarge pill.
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        )

        // TASK-457/TASK-542: the pin state and the one hint line that renders
        // for it, hoisted so the search groups and the card below share the
        // exact same values (the card would otherwise match text it does not
        // display). Mutually exclusive by design: a model without language
        // conditioning shows only the no-conditioning explanation, never the
        // pin notes.
        val transcriptionPinState = TranscriptionLanguagePolicy.pinState(
            currentTranscriptionLanguage, transcriptionPicker.offeredCodes,
            phoneLanguage = com.antivocale.app.util.LocaleManager.phoneLanguage(context),
        )
        val transcriptionHintRes = when {
            !transcriptionPicker.conditioningAvailable ->
                R.string.transcription_language_no_conditioning
            transcriptionPinState == TranscriptionLanguagePolicy.PinState.SUPPORTED_PIN ->
                R.string.transcription_language_forced_hint
            transcriptionPinState == TranscriptionLanguagePolicy.PinState.UNSUPPORTED_PIN ->
                R.string.transcription_language_unsupported_pin
            else -> null
        }

        // TASK-542: card-level live filter. Each group is one card's title +
        // description resource ids (resolved against the current locale, so
        // the match works in all 12); a section shows when any of its cards
        // matches, and the count line reports matching cards. Keep the groups
        // in sync with the SearchFilterRow wraps below: same ids, same runtime
        // conditions as the tree applies (a drifted entry degrades the filter
        // and the count gracefully, never crashes). The Feedback section is
        // one Card of rows, so it is a single group. remember keeps the ~55
        // resource lookups off every keystroke; the condition flags are the
        // keys so the groups still track the runtime state of the cards.
        val transcriptionSearchGroups = remember(
            context, isLlmBackend, gemmaConfigured, isModelLoaded,
            transcriptionHintRes, currentPunctuationMode, summarizeOn,
        ) {
            listOfNotNull(
                if (isLlmBackend) listOf(
                    // Only the live status title, mirroring the card: listing
                    // both would count a match the tree never renders.
                    if (isModelLoaded) R.string.model_loaded
                    else R.string.model_not_loaded,
                ) else null,
                listOf(R.string.active_model),
                listOfNotNull(
                    R.string.transcription_language_title,
                    // At most one hint renders (see transcriptionHintRes); the
                    // group must not match text the tree does not show.
                    transcriptionHintRes,
                ),
                listOf(R.string.auto_copy_title, R.string.auto_copy_description),
                listOf(R.string.export_settings_title, R.string.export_settings_description),
                listOf(R.string.vad_title, R.string.vad_description),
                listOf(R.string.progressive_title, R.string.progressive_description),
                if (gemmaConfigured && !isLlmBackend) listOf(
                    R.string.punctuation_mode_title, R.string.punctuation_mode_description,
                ) else null,
                if (gemmaConfigured && !isLlmBackend &&
                    currentPunctuationMode == PunctuationPolicy.PREF_ALWAYS
                ) listOf(
                    R.string.punctuation_prompt_title, R.string.punctuation_prompt_description,
                ) else null,
                if (gemmaConfigured) listOf(
                    R.string.summarize_title, R.string.summarize_description,
                ) else null,
                if (gemmaConfigured && summarizeOn) listOf(
                    R.string.summary_prompt_title, R.string.summary_prompt_description,
                ) else null,
                if (isLlmBackend) listOf(
                    R.string.default_prompt_title, R.string.default_prompt_description,
                ) else null,
                listOf(R.string.auto_unload_timeout, R.string.timeout_description),
            ).map { group -> group.map { context.getString(it) } }
        }
        val appearanceSearchGroups = remember(context) {
            listOf(
                listOf(
                    R.string.theme_title, R.string.theme_description,
                    R.string.theme_mode_title, R.string.theme_mode_description,
                ),
                listOf(R.string.app_icon_title),
                listOf(R.string.language_title, R.string.language_description),
                listOf(R.string.swipe_action_title, R.string.swipe_action_description),
                listOf(R.string.conversation_grouping_title, R.string.conversation_grouping_description),
                listOf(R.string.compact_result_actions_title, R.string.compact_result_actions_description),
                listOf(R.string.language_chip_setting_title, R.string.language_chip_setting_description),
                listOf(R.string.retranscribe_setting_title, R.string.retranscribe_setting_description),
            ).map { group -> group.map { context.getString(it) } }
        }
        val advancedSearchGroups = remember(context, backgroundKills > 0) {
            listOfNotNull(
                if (backgroundKills > 0) listOf(
                    R.string.battery_exemption_title, R.string.battery_exemption_description,
                ) else null,
                listOf(R.string.huggingface_auth, R.string.huggingface_auth_description),
                listOf(R.string.thread_count_title, R.string.thread_count_description),
                listOf(R.string.inference_provider_title, R.string.inference_provider_description),
                listOf(
                    R.string.share_targets_title, R.string.share_targets_description,
                    R.string.advanced_sharing_toggle,
                ),
                listOf(R.string.subtitle_timeout_title, R.string.subtitle_timeout_description),
                listOf(R.string.force_model_load, R.string.force_model_load_desc),
                listOf(R.string.per_app_settings_title, R.string.per_app_settings_description),
                listOf(R.string.performance_stats_title, R.string.performance_stats_subtitle),
            ).map { group -> group.map { context.getString(it) } }
        }
        val feedbackSearchGroups = remember(context) {
            // One group for one Card: the count reports cards, and the
            // Feedback rows do not filter individually. The replay-tour
            // button and the privacy note are part of the same card, so their
            // strings match it too.
            listOf(
                listOf(
                    R.string.settings_feedback_send_title, R.string.settings_feedback_version_title,
                    R.string.settings_feedback_license_title, R.string.settings_feedback_source_title,
                    R.string.settings_feedback_translation_title,
                    R.string.settings_replay_tour, R.string.settings_feedback_privacy_note,
                ).map { context.getString(it) }
            )
        }

        val transcriptionVisible = transcriptionSearchGroups.any { matchesQuery(searchQuery, it) }
        val appearanceVisible = appearanceSearchGroups.any { matchesQuery(searchQuery, it) }
        val advancedVisible = advancedSearchGroups.any { matchesQuery(searchQuery, it) }
        val feedbackVisible = feedbackSearchGroups.any { matchesQuery(searchQuery, it) }
        val searchActive = searchQuery.isNotBlank()
        val searchMatchCount = if (searchActive)
            transcriptionSearchGroups.count { matchesQuery(searchQuery, it) } +
                appearanceSearchGroups.count { matchesQuery(searchQuery, it) } +
                advancedSearchGroups.count { matchesQuery(searchQuery, it) } +
                feedbackSearchGroups.count { matchesQuery(searchQuery, it) }
        else 0

        if (searchActive) {
            Text(
                text = if (searchMatchCount > 0)
                    pluralStringResource(
                        R.plurals.settings_search_matches, searchMatchCount, searchMatchCount)
                else
                    stringResource(R.string.settings_search_no_results),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CollapsibleSection(
            title = stringResource(R.string.settings_section_transcription),
            icon = Icons.Default.Mic,
            expandSignal = (expandCounters["transcription"] ?: 0) + if (searchActive) 1 else 0,
            visible = transcriptionVisible,
            modifier = Modifier.onGloballyPositioned {
                sectionOffsets["transcription"] = it.positionInRoot().y.toInt()
            },
            initiallyExpanded = true
        ) {
            // Model Status Card (only show for LLM backend)
            if (isLlmBackend) {
                val modelStatusTitle =
                    if (isModelLoaded) stringResource(R.string.model_loaded)
                    else stringResource(R.string.model_not_loaded)
                SearchFilterRow(searchQuery, modelStatusTitle) {
                    // Deliberately NOT SectionCard: this banner is the one
                    // divider-free compact card (spacedBy 8), and a divider
                    // over its tinted container would be a redesign, not a
                    // normalization (TASK-564 review).
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
                            CardTitleRow(
                                icon = if (isModelLoaded) Icons.Default.CheckCircle else Icons.Default.RemoveCircleOutline,
                                title = modelStatusTitle,
                                iconTint = if (isModelLoaded)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (isModelLoaded && remainingTime > 0L) {
                                val minutes = remainingTime / 60
                                val seconds = remainingTime % 60
                                Text(
                                    text = stringResource(R.string.auto_unload_in, minutes, seconds),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }

                            // Unload button: shown when the model is loaded.
                            if (isModelLoaded) {
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
            }

            // Active Model Selection Card
            SearchFilterRow(searchQuery, stringResource(R.string.active_model)) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CardTitleRow(
                            icon = Icons.Default.Storage,
                            title = stringResource(R.string.active_model)
                        )

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
            }

            // Transcription Language Setting (pin state and hint hoisted
            // above the search groups; see transcriptionHintRes)
            val transcriptionLanguageTitle = stringResource(R.string.transcription_language_title)
            SearchFilterRow(
                searchQuery,
                transcriptionLanguageTitle,
                // Only the hint that actually renders (may be null: skipped).
                transcriptionHintRes?.let { stringResource(it) }
            ) {
                SectionCard(
                    icon = Icons.Default.Translate,
                    title = transcriptionLanguageTitle
                ) {
                    // TASK-458: the dropdown offers what the ACTIVE model conditions
                    // on; backends without language conditioning render it disabled.
                    SettingsDropdown(
                        currentValue = currentTranscriptionLanguage,
                        options = transcriptionPicker.codes,
                        currentValueDisplay = languageOptionLabel(
                            currentTranscriptionLanguage,
                            transcriptionSentinelLabels,
                            transcriptionPicker.optionByCode
                        ),
                        optionDisplay = { code ->
                            languageOptionLabel(
                                code,
                                transcriptionSentinelLabels,
                                transcriptionPicker.optionByCode
                            )
                        },
                        onOptionSelected = { viewModel.saveTranscriptionLanguage(it) },
                        label = transcriptionLanguageTitle,
                        enabled = transcriptionPicker.conditioningAvailable && !uiState.isSaving
                    )

                    // TASK-458: one explanatory line per the state of the active
                    // model vs the stored preference; hoisted as
                    // transcriptionHintRes so the search filter matches the
                    // same text this card renders.
                    transcriptionHintRes?.let {
                        Text(
                            text = stringResource(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Auto-Copy Setting
            val autoCopyTitle = stringResource(R.string.auto_copy_title)
            val autoCopyDescription = stringResource(R.string.auto_copy_description)
            SearchFilterRow(searchQuery, autoCopyTitle, autoCopyDescription) {
                ToggleSettingCard(
                    icon = Icons.Default.ContentCopy,
                    title = autoCopyTitle,
                    description = autoCopyDescription,
                    checked = autoCopyEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.saveAutoCopyEnabled(enabled)
                    }
                )
            }

            // TASK-543: the two export cards live on their own sub-page now;
            // this entry card navigates there.
            SearchFilterRow(
                searchQuery,
                stringResource(R.string.export_settings_title),
                stringResource(R.string.export_settings_description)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { showExportSettings = true },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.export_settings_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.export_settings_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // VAD Silence Stripping Setting
            val vadTitle = stringResource(R.string.vad_title)
            val vadDescription = stringResource(R.string.vad_description)
            SearchFilterRow(searchQuery, vadTitle, vadDescription) {
                ToggleSettingCard(
                    icon = Icons.Default.GraphicEq,
                    title = vadTitle,
                    description = vadDescription,
                    checked = vadEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.saveVadEnabled(enabled)
                    }
                )
            }

            // Progressive Transcription Display Setting
            val progressiveTitle = stringResource(R.string.progressive_title)
            val progressiveDescription = stringResource(R.string.progressive_description)
            SearchFilterRow(searchQuery, progressiveTitle, progressiveDescription) {
                ToggleSettingCard(
                    icon = Icons.Default.Visibility,
                    title = progressiveTitle,
                    description = progressiveDescription,
                    checked = progressiveEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.saveProgressiveTranscription(enabled)
                    }
                )
            }

            // TASK-276: punctuation pass mode + prompt override. TASK-507: exposed only when the pass can run at all. Runtime
            // preconditions are a configured Gemma (the pass engine) AND a
            // non-LLM active backend (LLM output is polished by its own final
            // pass; double-passing is skipped in the orchestrator). The
            // dropdown sits in the section's standard Card (icon header +
            // description + divider), matching every sibling setting; the
            // TASK-276 bare-dropdown shape read as a foreign element
            // (maintainer trial, radius mismatch).
            if (gemmaConfigured && !isLlmBackend) {
                val punctuationModeTitle = stringResource(R.string.punctuation_mode_title)
                SearchFilterRow(
                    searchQuery,
                    punctuationModeTitle,
                    stringResource(R.string.punctuation_mode_description)
                ) {
                    SectionCard(
                        icon = Icons.Default.FormatQuote,
                        title = punctuationModeTitle,
                        description = stringResource(R.string.punctuation_mode_description)
                    ) {
                        SettingsDropdown(
                            currentValue = currentPunctuationMode,
                            options = viewModel.punctuationModeOptions,
                            currentValueDisplay = punctuationModeLabel(currentPunctuationMode),
                            optionDisplay = { punctuationModeLabel(it) },
                            onOptionSelected = { viewModel.savePunctuationMode(it) },
                            label = punctuationModeTitle,
                            enabled = !uiState.isSaving
                        )
                    }
                }
                // TASK-507 (maintainer): the prompt override text area stays
                // hidden until the user forces the pass (ALWAYS), mirroring
                // how the summary prompt only appears behind its enabled
                // toggle. AUTO can never run it today (see the options note
                // in SettingsViewModel); the previous condition (mode != off)
                // kept the box on screen from the untouched AUTO default.
                SearchFilterRow(
                    searchQuery,
                    stringResource(R.string.punctuation_prompt_title),
                    stringResource(R.string.punctuation_prompt_description)
                ) {
                    if (currentPunctuationMode == PunctuationPolicy.PREF_ALWAYS) {
                        PunctuationPromptCard(
                            prompt = viewModel.currentPunctuationPrompt.collectAsState().value,
                            onSave = { viewModel.savePunctuationPrompt(it) }
                        )
                    }
                }
            }

            // TASK-121.4: smart-summary toggle. TASK-507: shown
            // only when a Gemma model is configured (the pass engine); without
            // one it silently skipped at runtime, so the toggle was a no-op.
            // Unlike punctuation, it is offered on the LLM backend too: it
            // summarizes any transcript, including Gemma's own.
            if (gemmaConfigured) {
                val summarizeTitle = stringResource(R.string.summarize_title)
                val summarizeDescription = stringResource(R.string.summarize_description)
                SearchFilterRow(searchQuery, summarizeTitle, summarizeDescription) {
                    ToggleSettingCard(
                        icon = Icons.Default.Notes,
                        title = summarizeTitle,
                        description = summarizeDescription,
                        checked = summarizeOn,
                        onCheckedChange = { enabled ->
                            viewModel.saveSummarizeEnabled(enabled)
                        }
                    )
                }
                SearchFilterRow(
                    searchQuery,
                    stringResource(R.string.summary_prompt_title),
                    stringResource(R.string.summary_prompt_description)
                ) {
                    if (summarizeOn) {
                        SummaryPromptCard(
                            prompt = viewModel.currentSummaryPrompt.collectAsState().value,
                            onSave = { viewModel.saveSummaryPrompt(it) }
                        )
                    }
                }
            }

            // Default Prompt Setting Navigation Card. TASK-507:
            // the prompt feeds resolvePrompt -> ChunkPromptPolicy, which only
            // the LLM backend consumes (ASR models take no instruction), so the
            // card exposes only on the LLM backend, symmetric with the model
            // status card at the top of this section.
            if (isLlmBackend) {
                SearchFilterRow(
                    searchQuery,
                    stringResource(R.string.default_prompt_title),
                    stringResource(R.string.default_prompt_description)
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { showPromptSettings = true },
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Locale-safe: weight lets title/description wrap instead of
                            // displacing the trailing chevron (TASK-345)
                            Row(
                                modifier = Modifier.weight(1f),
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
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = stringResource(R.string.default_prompt_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.open_prompt_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Keep-Alive Timeout Setting
            val timeoutTitle = stringResource(R.string.auto_unload_timeout)
            SearchFilterRow(searchQuery, timeoutTitle, stringResource(R.string.timeout_description)) {
                TimeoutSettingCard(
                    icon = Icons.Default.Timer,
                    title = timeoutTitle,
                    description = stringResource(R.string.timeout_description),
                    currentValue = currentTimeout,
                    options = viewModel.timeoutOptions,
                    currentValueDisplay = when (currentTimeout) {
                        1 -> stringResource(R.string.timeout_1_minute)
                        60 -> stringResource(R.string.timeout_1_hour)
                        else -> pluralStringResource(R.plurals.timeout_minutes, currentTimeout, currentTimeout)
                    },
                    optionDisplay = { minutes ->
                        when (minutes) {
                            1 -> stringResource(R.string.timeout_1_minute)
                            60 -> stringResource(R.string.timeout_1_hour)
                            else -> pluralStringResource(R.plurals.timeout_minutes, minutes, minutes)
                        }
                    },
                    onOptionSelected = { viewModel.saveKeepAliveTimeout(it) },
                    enabled = !uiState.isSaving,
                    isSaving = uiState.isSaving,
                    saveSuccess = uiState.saveSuccess,
                    errorMessage = uiState.errorMessage,
                )
            }
        }

        CollapsibleSection(
            title = stringResource(R.string.settings_section_appearance),
            icon = Icons.Default.Palette,
            expandSignal = (expandCounters["appearance"] ?: 0) + if (searchActive) 1 else 0,
            visible = appearanceVisible,
            modifier = Modifier.onGloballyPositioned {
                sectionOffsets["appearance"] = it.positionInRoot().y.toInt()
            },
            initiallyExpanded = true
        ) {
            // Theme Setting
            val themeTitle = stringResource(R.string.theme_title)
            val themeModeTitle = stringResource(R.string.theme_mode_title)
            SearchFilterRow(
                searchQuery,
                themeTitle,
                stringResource(R.string.theme_description),
                themeModeTitle,
                stringResource(R.string.theme_mode_description)
            ) {
                SectionCard(
                    icon = Icons.Default.Palette,
                    title = themeTitle,
                    description = stringResource(R.string.theme_description)
                ) {
                    // Theme dropdown
                    SettingsDropdown(
                        currentValue = currentTheme,
                        options = viewModel.themeOptions,
                        currentValueDisplay = currentTheme.displayName,
                        optionDisplay = { it.displayName },
                        onOptionSelected = { viewModel.saveThemePreference(it) },
                        label = themeTitle,
                        enabled = !uiState.isSaving
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = themeModeTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.theme_mode_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Theme mode dropdown (System / Dark / Light)
                    val currentThemeMode by viewModel.currentThemeMode.collectAsState()
                    SettingsDropdown(
                        currentValue = currentThemeMode,
                        options = viewModel.themeModeOptions,
                        currentValueDisplay = currentThemeMode.displayName,
                        optionDisplay = { it.displayName },
                        onOptionSelected = { viewModel.saveThemeMode(it) },
                        label = themeModeTitle
                    )
                }
            }

            // App icon variants (TASK-392, TASK-473): selection moved to a
            // dedicated sub-page (maintainer decision 2026-09-09); the row
            // shows the active variant and opens the picker grid.
            val currentLauncherIcon by viewModel.currentLauncherIcon.collectAsState()
            SearchFilterRow(searchQuery, stringResource(R.string.app_icon_title)) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { showIconSettings = true }
                            .padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Apps,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.app_icon_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(currentLauncherIcon.nameRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Language Setting (App Language)
            val languageTitle = stringResource(R.string.language_title)
            SearchFilterRow(searchQuery, languageTitle, stringResource(R.string.language_description)) {
                SectionCard(
                    icon = Icons.Default.Language,
                    title = languageTitle,
                    description = stringResource(R.string.language_description)
                ) {
                    // Language dropdown
                    val appOptionsByCode = viewModel.languageOptions.associateBy { option -> option.code }
                    SettingsDropdown(
                        currentValue = currentLanguage,
                        options = viewModel.languageOptions.map { it.code },
                        currentValueDisplay = languageOptionLabel(
                            currentLanguage,
                            appLanguageSentinelLabels,
                            appOptionsByCode
                        ),
                        optionDisplay = { code ->
                            languageOptionLabel(
                                code,
                                appLanguageSentinelLabels,
                                appOptionsByCode
                            )
                        },
                        onOptionSelected = { viewModel.saveLanguagePreference(it) },
                        label = languageTitle,
                        enabled = !uiState.isSaving
                    )
                }
            }

            // Swipe Action Setting
            val swipeActionTitle = stringResource(R.string.swipe_action_title)
            SearchFilterRow(searchQuery, swipeActionTitle, stringResource(R.string.swipe_action_description)) {
                SectionCard(
                    icon = Icons.Default.Swipe,
                    title = swipeActionTitle,
                    description = stringResource(R.string.swipe_action_description)
                ) {
                        SettingsDropdown(
                            currentValue = swipeActionMode,
                            options = PreferencesManager.SWIPE_ACTION_MODES,
                            currentValueDisplay = swipeActionMode.swipeActionLabel(),
                            optionDisplay = { mode -> mode.swipeActionLabel() },
                            onOptionSelected = { viewModel.saveSwipeActionMode(it) },
                            label = swipeActionTitle,
                            enabled = !uiState.isSaving
                        )
                    }
                }

            // Conversation Grouping Setting
            val conversationGroupingTitle = stringResource(R.string.conversation_grouping_title)
            val conversationGroupingDescription = stringResource(R.string.conversation_grouping_description)
            SearchFilterRow(searchQuery, conversationGroupingTitle, conversationGroupingDescription) {
                ToggleSettingCard(
                    icon = Icons.Default.Forum,
                    title = conversationGroupingTitle,
                    description = conversationGroupingDescription,
                    checked = groupLogsByConversation,
                    onCheckedChange = { enabled ->
                        viewModel.saveGroupLogsByConversation(enabled)
                    }
                )
            }

            // Compact icon-only actions on result cards
            val compactResultActionsTitle = stringResource(R.string.compact_result_actions_title)
            val compactResultActionsDescription = stringResource(R.string.compact_result_actions_description)
            SearchFilterRow(searchQuery, compactResultActionsTitle, compactResultActionsDescription) {
                ToggleSettingCard(
                    icon = Icons.Default.TouchApp,
                    title = compactResultActionsTitle,
                    description = compactResultActionsDescription,
                    checked = compactResultActions,
                    onCheckedChange = { enabled ->
                        viewModel.saveCompactResultActions(enabled)
                    }
                )
            }

            // TASK-546: the language chip is conditional on this flag.
            val languageChipTitle = stringResource(R.string.language_chip_setting_title)
            val languageChipDescription = stringResource(R.string.language_chip_setting_description)
            SearchFilterRow(searchQuery, languageChipTitle, languageChipDescription) {
                ToggleSettingCard(
                    icon = Icons.Default.Language,
                    title = languageChipTitle,
                    description = languageChipDescription,
                    checked = languageChipEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.saveLanguageChip(enabled)
                    }
                )
            }

            // Re-transcribe button on history entries. TASK-507: moved from Advanced; it is History-list behavior,
            // the same class as grouping/compact/swipe above, and a user
            // decluttering History never finds it under Advanced.
            val retranscribeTitle = stringResource(R.string.retranscribe_setting_title)
            val retranscribeDescription = stringResource(R.string.retranscribe_setting_description)
            SearchFilterRow(searchQuery, retranscribeTitle, retranscribeDescription) {
                ToggleSettingCard(
                    icon = Icons.Default.Refresh,
                    title = retranscribeTitle,
                    description = retranscribeDescription,
                    checked = showRetranscribeButton,
                    onCheckedChange = { viewModel.saveShowRetranscribeButton(it) }
                )
            }
        }

        CollapsibleSection(
            title = stringResource(R.string.settings_section_advanced),
            icon = Icons.Default.Settings,
            expandSignal = (expandCounters["advanced"] ?: 0) + if (searchActive) 1 else 0,
            visible = advancedVisible,
            modifier = Modifier.onGloballyPositioned {
                sectionOffsets["advanced"] = it.positionInRoot().y.toInt()
            },
            initiallyExpanded = false
        ) {
            // TASK-336: offer the battery-optimization exemption after a detected
            // background kill (OEM killed the FGS; the sweep recorded the
            // interruption). The count refresh itself is hoisted to the tab
            // level: this section's content only composes when expanded AND
            // visible, and the search filter needs the count before that.
            if (backgroundKills > 0) {
                val context = LocalContext.current
                SearchFilterRow(
                    searchQuery,
                    stringResource(R.string.battery_exemption_title),
                    stringResource(R.string.battery_exemption_description)
                ) {
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(stringResource(R.string.battery_exemption_title), style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(R.string.battery_exemption_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = {
                                runCatching {
                                    context.startActivity(android.content.Intent(
                                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        android.net.Uri.parse("package:" + context.packageName)))
                                }
                            }) { Text(stringResource(R.string.battery_exemption_action)) }
                        }
                    }
                }
            }

            // HuggingFace Token Card
            SearchFilterRow(
                searchQuery,
                stringResource(R.string.huggingface_auth),
                stringResource(R.string.huggingface_auth_description)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CardTitleRow(
                            icon = Icons.Default.Key,
                            title = stringResource(R.string.huggingface_auth)
                        )

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
                                        // Locale-safe: weight lets the label wrap instead of
                                        // pushing the expand button off-card (TASK-345)
                                        Row(
                                            modifier = Modifier.weight(1f),
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
                                        // TASK-381: no explicit size, IconButton defaults to 48dp touch target
                                        IconButton(
                                            onClick = { showSetupGuide = !showSetupGuide }
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
                                            .clickable(role = Role.Button) { showManualDetails = !showManualDetails },
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
                                                // Locale-safe: weight(1f) on both buttons so
                                                // longer labels share the row (TASK-345)
                                                FilledTonalButton(
                                                    onClick = {
                                                        val intent = android.content.Intent(
                                                            android.content.Intent.ACTION_VIEW,
                                                            android.net.Uri.parse(HF_TOKEN_SETTINGS_URL)
                                                        )
                                                        context.startActivity(intent)
                                                    },
                                                    modifier = Modifier.weight(1f)
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
                                                    enabled = tokenInput.isNotBlank() && !uiState.isValidatingToken,
                                                    modifier = Modifier.weight(1f)
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
                                                // Locale-safe: weight(1f) on both buttons so
                                                // longer labels share the row (TASK-345)
                                                FilledTonalButton(
                                                    onClick = { viewModel.validateAndSaveToken() },
                                                    enabled = tokenInput.isNotBlank() && !uiState.isValidatingToken,
                                                    modifier = Modifier.weight(1f)
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
                                                    },
                                                    modifier = Modifier.weight(1f)
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
            }

            // Thread Count Setting
            val threadCountTitle = stringResource(R.string.thread_count_title)
            SearchFilterRow(searchQuery, threadCountTitle, stringResource(R.string.thread_count_description)) {
                SectionCard(
                    icon = Icons.Default.Memory,
                    title = threadCountTitle,
                    description = stringResource(R.string.thread_count_description)
                ) {
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
                        label = threadCountTitle
                    )
                }
            }

            // Inference Provider Setting
            val providerTitle = stringResource(R.string.inference_provider_title)
            SearchFilterRow(searchQuery, providerTitle, stringResource(R.string.inference_provider_description)) {
                SectionCard(
                    icon = Icons.Default.Bolt,
                    title = providerTitle,
                    description = stringResource(R.string.inference_provider_description)
                ) {
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
                        label = providerTitle
                    )
                }
            }

            // Advanced Sharing Card
            SearchFilterRow(
                searchQuery,
                stringResource(R.string.share_targets_title),
                stringResource(R.string.share_targets_description),
                stringResource(R.string.advanced_sharing_toggle)
            ) {
                SectionCard(
                    icon = Icons.Default.Share,
                    title = stringResource(R.string.share_targets_title),
                    description = stringResource(R.string.share_targets_description)
                ) {
                    // TASK-382: canonical toggleable row; the Switch itself is display-only
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = advancedSharingEnabled,
                                role = Role.Switch,
                                onValueChange = { viewModel.saveAdvancedSharingEnabled(it) }
                            ),
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
                            onCheckedChange = null
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

            // TASK-515: the subtitles-or-transcribe choice timeout. Next to
            // the share-targets card it explains: same share flow.
            val subtitleTimeout by viewModel.subtitleChoiceTimeout.collectAsState()
            val subtitleTimeoutTitle = stringResource(R.string.subtitle_timeout_title)
            SearchFilterRow(
                searchQuery,
                subtitleTimeoutTitle,
                stringResource(R.string.subtitle_timeout_description)
            ) {
                TimeoutSettingCard(
                    icon = Icons.Default.Timer,
                    title = subtitleTimeoutTitle,
                    description = stringResource(R.string.subtitle_timeout_description),
                    currentValue = subtitleTimeout,
                    options = viewModel.subtitleTimeoutOptions,
                    currentValueDisplay = pluralStringResource(
                        R.plurals.timeout_minutes, subtitleTimeout, subtitleTimeout),
                    optionDisplay = { minutes ->
                        pluralStringResource(R.plurals.timeout_minutes, minutes, minutes)
                    },
                    onOptionSelected = { viewModel.saveSubtitleChoiceTimeout(it) },
                    enabled = !uiState.isSaving,
                    isSaving = uiState.isSaving,
                    saveSuccess = uiState.saveSuccess,
                    errorMessage = uiState.errorMessage,
                )
            }

            // Force model load (bypass the low-memory pre-flight)
            val forceModelLoadTitle = stringResource(R.string.force_model_load)
            val forceModelLoadDescription = stringResource(R.string.force_model_load_desc)
            SearchFilterRow(searchQuery, forceModelLoadTitle, forceModelLoadDescription) {
                ToggleSettingCard(
                    icon = Icons.Default.Memory,
                    title = forceModelLoadTitle,
                    description = forceModelLoadDescription,
                    checked = forceModelLoad,
                    onCheckedChange = { viewModel.saveForceModelLoad(it) }
                )
            }

            // Per-App Settings Navigation Card
            SearchFilterRow(
                searchQuery,
                stringResource(R.string.per_app_settings_title),
                stringResource(R.string.per_app_settings_description)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { showPerAppSettings = true },
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
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.per_app_settings_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.open_per_app_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Performance Stats Card
            SearchFilterRow(
                searchQuery,
                stringResource(R.string.performance_stats_title),
                stringResource(R.string.performance_stats_subtitle)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
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
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.performance_stats_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.open_performance_stats),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Feedback & About section (issue #34 / TASK-341)
        if (feedbackVisible) FeedbackSection(
            expandSignal = (expandCounters["feedback"] ?: 0) + if (searchActive) 1 else 0,
            onPositioned = { sectionOffsets["feedback"] = it },
            activeBackendId = uiState.transcriptionBackend,
            activeModelName = uiState.currentModelName,
            currentLanguage = currentLanguage,
            onReplayTour = { viewModel.replayOnboardingTour() }
        )

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
 * Feedback & About section (issue #34 / TASK-341). Mirrors the sibling card pattern
 * (one Card with title row, description, divider, then rows) used by the HuggingFace
 * and advanced cards. Mail rows go through [FeedbackHelper.sendOrCopy], which falls
 * back to copying the address to the clipboard when no mail app is installed.
 */
@Composable
private fun FeedbackSection(
    activeBackendId: String,
    activeModelName: String?,
    currentLanguage: String,
    expandSignal: Int = 0,
    onPositioned: (Int) -> Unit = {},
    onReplayTour: () -> Unit = {},
) {
    val context = LocalContext.current

    val bodyLabels = FeedbackHelper.BodyLabels(
        version = stringResource(R.string.settings_feedback_body_version),
        android = stringResource(R.string.settings_feedback_body_android),
        device = stringResource(R.string.settings_feedback_body_device),
        locale = stringResource(R.string.settings_feedback_body_locale),
        model = stringResource(R.string.settings_feedback_body_model),
        yourMessage = stringResource(R.string.settings_feedback_body_your_message),
        note = stringResource(R.string.settings_feedback_body_note)
    )

    fun sendFeedback(translation: Boolean) {
        // The in-app language (not the system locale) is what the user wants
        // reported when flagging a wrong translation.
        val localeTag = if (currentLanguage.isBlank()) java.util.Locale.getDefault().toLanguageTag() else currentLanguage
        val diagnostics = FeedbackHelper.currentDiagnostics(context, activeBackendId, activeModelName, localeTag = localeTag)
        if (translation) {
            FeedbackHelper.sendOrCopy(
                context,
                FeedbackHelper.translationSubject(localeTag),
                FeedbackHelper.buildTranslationBody(diagnostics, bodyLabels)
            )
        } else {
            FeedbackHelper.sendOrCopy(
                context,
                FeedbackHelper.feedbackSubject(),
                FeedbackHelper.buildFeedbackBody(diagnostics, bodyLabels)
            )
        }
    }

    CollapsibleSection(
        title = stringResource(R.string.settings_section_feedback),
        icon = Icons.Default.Mail,
        expandSignal = expandSignal,
        modifier = Modifier.onGloballyPositioned { onPositioned(it.positionInRoot().y.toInt()) },
        initiallyExpanded = false
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Send feedback row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { sendFeedback(translation = false) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Locale-safe: weight lets title/description wrap instead of
                    // displacing the trailing link icon (TASK-345)
                    Row(
                        modifier = Modifier.weight(1f),
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
                                text = stringResource(R.string.settings_feedback_send_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.settings_feedback_send_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Report wrong translation row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { sendFeedback(translation = true) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Locale-safe: weight lets title/description wrap instead of
                    // displacing the trailing link icon (TASK-345)
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.settings_feedback_translation_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.settings_feedback_translation_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Source code row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(FeedbackHelper.SOURCE_CODE_URL))
                                )
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.settings_feedback_source_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // License row (informational, no action)
                InfoRow(
                    icon = Icons.Default.Description,
                    title = stringResource(R.string.settings_feedback_license_title),
                    value = stringResource(R.string.settings_feedback_license_value)
                )

                // Version row (TASK-459): lets users tell which build they run;
                // the versionCode identifies the exact per-ABI build (F-Droid
                // can serve an older version for days after a release)
                InfoRow(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.settings_feedback_version_title),
                    value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                )

                // Replay the first-install tour on demand (TASK-491; the only
                // reset path besides a fresh install). TASK-507: moved inside About from a bare button that
                // floated between the sections.
                OutlinedButton(
                    onClick = onReplayTour,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_replay_tour))
                }

                // Privacy note
                Text(
                    text = stringResource(R.string.settings_feedback_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Informational (non-clickable) row of the Feedback & About section: icon +
 * bold title on the leading edge, value on the trailing edge. Shared by the
 * license and version rows (TASK-459 extraction; the clickable rows above
 * have a different shape and stay inline).
 */
@Composable
private fun InfoRow(icon: ImageVector, title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CardTitleRow(icon = icon, title = title)
        // Locale-safe: weighted value wraps under a longer title instead
        // of overflowing the row (TASK-345)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * TASK-542: the one matching rule behind the settings search: a blank query
 * matches everything (the normal tab is unchanged), otherwise any non-null
 * entry must contain the query, case-insensitive. Null entries are skipped,
 * so callers can pass optional descriptions unchanged. Both the count line
 * (via the card groups) and the per-card [SearchFilterRow] gate go through
 * this function, so they cannot disagree.
 */
/**
 * TASK-571: card scaffold shared by the two timeout settings (keep-alive
 * auto-unload, subtitle choice): icon + title + description + divider +
 * dropdown, plus the save indicators. Both saves flow through the same
 * uiState.isSaving path, so both cards show the same feedback.
 */
@Composable
private fun TimeoutSettingCard(
    icon: ImageVector,
    title: String,
    description: String,
    currentValue: Int,
    options: List<Int>,
    currentValueDisplay: String,
    optionDisplay: @Composable (Int) -> String,
    onOptionSelected: (Int) -> Unit,
    enabled: Boolean,
    isSaving: Boolean,
    saveSuccess: Boolean?,
    errorMessage: String?,
) {
    SectionCard(
        icon = icon,
        title = title,
        description = description
    ) {
        SettingsDropdown(
            currentValue = currentValue,
            options = options,
            currentValueDisplay = currentValueDisplay,
            optionDisplay = optionDisplay,
            onOptionSelected = onOptionSelected,
            label = title,
            enabled = enabled
        )
        if (isSaving) {
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
        if (saveSuccess == true) {
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
        errorMessage?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun matchesQuery(query: String, texts: List<String?>): Boolean =
    query.isBlank() || texts.any { it?.contains(query, ignoreCase = true) == true }

/**
 * TASK-542: card-level gate for the settings search. Renders [content] only
 * when [matchesQuery] accepts the query against [matchTexts].
 */
@Composable
private fun SearchFilterRow(query: String, vararg matchTexts: String?, content: @Composable () -> Unit) {
    if (matchesQuery(query, matchTexts.toList())) {
        content()
    }
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
    // returns String?; lint misresolves the elvis chain
    @SuppressLint("RememberReturnType")
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
                CardTitleRow(
                    icon = Icons.Default.Folder,
                    title = stringResource(R.string.output_folder_title)
                )
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

/**
 * Single label policy for the language dropdowns: a sentinel code resolves to
 * its string resource, everything else goes through the option list and falls
 * back to an ICU native name. Shared by both dropdowns so the fallback chain
 * cannot drift apart. [sentinelLabels] carries each sentinel's code → label
 * resource; both dropdowns pass the same hoisted map to the current-value and
 * per-option label calls.
 */
@Composable
private fun languageOptionLabel(
    code: String,
    sentinelLabels: Map<String, Int>,
    options: Map<String, LanguageOption>,
): String {
    // TASK-547 AC#2: the phone sentinel formats with the resolved language's
    // NAME (a raw ISO code reads as noise; every sibling row shows a native
    // name), blank only when the locale is unreadable.
    if (code == TranscriptionLanguagePolicy.PREF_PHONE) {
        val phone = com.antivocale.app.util.LocaleManager.phoneLanguage(
            androidx.compose.ui.platform.LocalContext.current
        )
        return stringResource(
            R.string.language_phone_option,
            phone?.let { LanguageNames.nativeLanguageName(it) } ?: "",
        )
    }
    sentinelLabels[code]?.let { return stringResource(it) }
    return options[code]?.displayName
        ?: LanguageNames.nativeLanguageName(code)
}

/** App-language dropdown sentinel (the per-app "System Default" entry). */
private val appLanguageSentinelLabels = mapOf("system" to R.string.language_system)

/**
 * Transcription-language dropdown sentinel (TASK-457): "auto" is the
 * model-side detection choice. A stored "system" (the pre-457 untouched
 * default) resolves identically now that the app-locale pinning is gone, so
 * it renders with the same label instead of a second sentinel entry.
 */
private val transcriptionSentinelLabels = mapOf(
    TranscriptionLanguagePolicy.PREF_AUTO to R.string.transcription_language_auto,
)

/**
 * TASK-483: editable override of the summary-pass prompt. Same contract as
 * the punctuation prompt card: blank means the built-in two-to-three-sentence
 * default, commits on focus loss, 500-char cap.
 */
@Composable
private fun SummaryPromptCard(
    prompt: String,
    onSave: (String) -> Unit,
) = EditablePromptCard(
    prompt = prompt,
    onSave = onSave,
    titleRes = R.string.summary_prompt_title,
    descriptionRes = R.string.summary_prompt_description,
    placeholderRes = R.string.summary_default_prompt,
)

/**
 * TASK-276: editable override of the punctuation-pass prompt. Blank means the
 * localized built-in default. Commits on focus loss so DataStore is not
 * written per keystroke (same 500-char cap as the impl layer).
 */
@Composable
private fun PunctuationPromptCard(
    prompt: String,
    onSave: (String) -> Unit,
) = EditablePromptCard(
    prompt = prompt,
    onSave = onSave,
    titleRes = R.string.punctuation_prompt_title,
    descriptionRes = R.string.punctuation_prompt_description,
    placeholderRes = R.string.punctuation_prompt_placeholder,
)

/** TASK-276: pref value -> localized label, one fallback for unknown values. */
@Composable
private fun punctuationModeLabel(pref: String): String = when (pref) {
    PunctuationPolicy.PREF_OFF -> stringResource(R.string.punctuation_mode_off)
    PunctuationPolicy.PREF_ALWAYS -> stringResource(R.string.punctuation_mode_always)
    else -> stringResource(R.string.punctuation_mode_auto)
}

/** GH #92: format -> localized label; the timed formats carry the experimental suffix. */
@Composable
private fun transcriptExportFormatLabel(format: SubtitleFormatter.Format): String = when (format) {
    SubtitleFormatter.Format.TXT -> stringResource(R.string.transcript_export_format_txt)
    SubtitleFormatter.Format.TXT_TIMED -> stringResource(R.string.transcript_export_format_txt_timed)
    SubtitleFormatter.Format.SRT -> stringResource(R.string.transcript_export_format_srt)
    SubtitleFormatter.Format.VTT -> stringResource(R.string.transcript_export_format_vtt)
}

/**
 * TASK-543: the auto-save export config (folder + format) on its own page.
 * Both cards moved here from the inline transcription section; the entry
 * card in that section navigates here, and the capped-transcript auto-save
 * hint targets this page via settings:export.
 */
/**
 * The ONE folder-picker handler for transcript auto-save (TASK-539 + review
 * rounds 1-2), shared by the inline transcription card and
 * [ExportSettingsScreen]. Probe the tree for writability BEFORE persisting
 * (the Downloads quick root passes the pick but rejects every write, and a
 * persisted-then-rejected pick burns one of the 128 persisted-grant slots;
 * the probe rides the picker's transient grant); refuse the pick if the
 * grant cannot persist (it would die with the process and silently no-op
 * downstream); save only what survives. The probe and the permission call
 * run on IO (binder calls must not sit on the main thread); toasts marshal
 * back to Main.
 */
@Composable
internal fun rememberOutputFolderPickerLauncher(
    viewModel: SettingsViewModel,
    logTag: String,
): ManagedActivityResultLauncher<Uri?, Uri?> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
            if (tree?.canWrite() != true) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.error_folder_not_writable),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(logTag, "Failed to take persistable URI permission", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.error_folder_not_writable),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }
            viewModel.saveOutputFolderUri(uri.toString())
        }
    }
}

@Composable
fun ExportSettingsScreen(
    viewModel: SettingsViewModel,
    outputFolderUri: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val outputFolderLauncher = rememberOutputFolderPickerLauncher(viewModel, "ExportSettings")
    val transcriptExportFormat by viewModel.transcriptExportFormat.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // Header with back
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = stringResource(R.string.export_settings_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        // The two cards, verbatim from the inline transcription section
        OutputFolderSettingCard(
            outputFolderUri = outputFolderUri,
            onChoose = { outputFolderLauncher.launch(null) },
            onClear = { viewModel.saveOutputFolderUri(null) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        SectionCard(
            icon = Icons.Default.Subtitles,
            title = stringResource(R.string.transcript_export_format_title),
            description = stringResource(R.string.transcript_export_format_description)
        ) {
                val selectedFormat = SubtitleFormatter.Format.fromStored(transcriptExportFormat)
                SettingsDropdown(
                    currentValue = selectedFormat,
                    options = SubtitleFormatter.Format.entries,
                    currentValueDisplay = transcriptExportFormatLabel(selectedFormat),
                    optionDisplay = { format -> transcriptExportFormatLabel(format) },
                    onOptionSelected = { viewModel.saveTranscriptExportFormat(it.name) },
                    label = stringResource(R.string.transcript_export_format_title),
                    enabled = outputFolderUri != null
                )
        }
    }
}
