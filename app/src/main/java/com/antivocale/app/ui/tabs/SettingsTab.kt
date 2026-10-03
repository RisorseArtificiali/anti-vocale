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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
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
import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.InferenceProvider
import com.antivocale.app.transcription.PunctuationPolicy
import com.antivocale.app.transcription.RemoteOmnivoiceBackend
import com.antivocale.app.transcription.TranscriptionLanguagePolicy
import com.antivocale.app.transcription.diarization.SpeakerEnrollError
import com.antivocale.app.data.DiscoveredModel
import com.antivocale.app.data.HuggingFaceTokenManager
import com.antivocale.app.data.HuggingFaceOAuthConfig
import com.antivocale.app.data.ModelSource
import com.antivocale.app.ui.components.SearchField
import com.antivocale.app.ui.components.CardTitleRow
import com.antivocale.app.ui.components.languageOptionLabel
import com.antivocale.app.ui.components.transcriptionSentinelLabels
import com.antivocale.app.ui.components.CollapsibleSection
import com.antivocale.app.ui.components.GroupHeader
import com.antivocale.app.ui.components.HF_TOKEN_SETTINGS_URL
import com.antivocale.app.ui.components.OAuthLoginSection
import com.antivocale.app.ui.components.SectionCard
import com.antivocale.app.ui.components.SettingsDropdown
import com.antivocale.app.ui.components.TokenInputField
import com.antivocale.app.ui.components.ToggleSettingCard
import com.antivocale.app.ui.components.UnloadModelButton
import com.antivocale.app.ui.screens.LauncherIconScreen
import com.antivocale.app.ui.screens.PerAppSettingsScreen
import com.antivocale.app.ui.screens.PromptSettingsScreen
import com.antivocale.app.ui.components.SettingsHubCard
import com.antivocale.app.ui.screens.SpeakerSettingsScreen
import com.antivocale.app.ui.screens.PerformanceSettingsScreen
import com.antivocale.app.ui.screens.AutomationSettingsScreen
import com.antivocale.app.ui.theme.TextScale
import com.antivocale.app.ui.theme.ThemeType
import com.antivocale.app.util.AutomationBroadcastSnippet
import com.antivocale.app.util.ClipboardWriter
import com.antivocale.app.util.FeedbackHelper
import com.antivocale.app.util.LanguageNames
import com.antivocale.app.util.SubtitleFormatter
import com.antivocale.app.util.ToastCompat
import java.io.File
import com.antivocale.app.service.InferenceService
import com.antivocale.app.ui.components.LanguageOption
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
    val earlyPreviewEnabled by viewModel.earlyPreviewEnabled.collectAsState()
    val interruptedRunNotifications by viewModel.interruptedRunNotifications.collectAsState()
    val currentLanguage by viewModel.currentLanguage.collectAsState()
    val currentTranscriptionLanguage by viewModel.currentTranscriptionLanguage.collectAsState()
    val transcriptionPicker by viewModel.transcriptionLanguagePicker.collectAsState()
    val currentTheme by viewModel.currentTheme.collectAsState()
    val swipeActionMode by viewModel.swipeActionMode.collectAsState()
    val groupLogsByConversation by viewModel.groupLogsByConversation.collectAsState()
    val advancedSharingEnabled by viewModel.advancedSharingEnabled.collectAsState()
    val showRetranscribeButton by viewModel.showRetranscribeButton.collectAsState()
    val compactResultActions by viewModel.compactResultActions.collectAsState()
    val showTechnicalDetails by viewModel.showTechnicalDetails.collectAsState()
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
    var showPromptSettings by remember { mutableStateOf(false) }
    var showIconSettings by remember { mutableStateOf(false) }
    var showExportSettings by remember { mutableStateOf(false) }
    var showSpeakerSettings by remember { mutableStateOf(false) }
    var showPerformanceSettings by remember { mutableStateOf(false) }
    var showAutomationSettings by remember { mutableStateOf(false) }

    // The ONE sub-page clear (TASK-632): system back and every nav-effect
    // branch share it, so a future sub-page flag cannot be cleared in one
    // place and missed in the other.
    fun closeSubPages() {
        showIconSettings = false
        showExportSettings = false
        showPromptSettings = false
        showPerAppSettings = false
        showSpeakerSettings = false
        showPerformanceSettings = false
        showAutomationSettings = false
    }

    // 2026-09-30 regroup: system back on ANY subpage must return to the
    // main tree, not finish the activity (no other BackHandler covers
    // these flags; without this, back from a subpage closes the app).
    androidx.activity.compose.BackHandler(enabled = showIconSettings || showExportSettings ||
        showPromptSettings || showPerAppSettings || showSpeakerSettings ||
        showPerformanceSettings || showAutomationSettings) {
        closeSubPages()
    }
    // TASK-625 deep-link carrier: the memory-failure notification action
    // opens the performance page with focus on its card (2026-09-30 regroup).
    var performanceFocusMemoryProtection by remember { mutableStateOf(false) }

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
                closeSubPages()
                when (dest.key) {
                    "icon_picker" -> showIconSettings = true
                    "prompt" -> showPromptSettings = true
                    "per_app" -> showPerAppSettings = true
                    AppNavigation.SUBPAGE_KEY_EXPORT -> showExportSettings = true
                    "speaker" -> showSpeakerSettings = true
                    "performance" -> showPerformanceSettings = true
                    "automation" -> showAutomationSettings = true
                }
            }
            is AppNavigation.Destination.SettingsRow -> {
                // TASK-632: the row deep link rides the ONE navRequest
                // channel. A row lives on a sub-page since the 2026-09-30
                // regroup: open that page (AppNavigation.rowPage owns the
                // mapping) with the page's focus flag, and the page runs its
                // own capture/flash on its scroll state.
                closeSubPages()
                if (AppNavigation.rowPage(dest.key) == "performance") {
                    showPerformanceSettings = true
                    performanceFocusMemoryProtection = true
                }
            }
            is AppNavigation.Destination.SettingsSection -> {
                // A section target needs the main Column composed: back out
                // of any open sub-page first or the scroll anchor never lays
                // out and the expand lands on a hidden screen.
                closeSubPages()
                expandCounters[dest.key] = (expandCounters[dest.key] ?: 0) + 1
                // First composition may run before layout delivers offsets
                // (TASK-632: slow frames, large expansions). Wait UP TO ten
                // frames for the anchor instead of exactly one: each frame is
                // ~16ms, so the bound caps the wait at ~160ms while giving
                // heavy sections room to lay out.
                var target = sectionOffsets[dest.key]
                var frames = 0
                while (target == null && frames < 10) {
                    withFrameNanos { }
                    target = sectionOffsets[dest.key]
                    frames++
                }
                target?.let { rootY ->
                    // positionInRoot() shifts with the scroll placement, so
                    // converting back to content space needs the CURRENT
                    // scroll added (review: without it the target lands
                    // scroll-now pixels too low on an already-scrolled list).
                    val contentY = rootY - scrollContentRootY + scrollState.value
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
    // TASK-574: tick the countdown every second while a live deadline exists;
    // the getter is a plain read, so without the producer the banner would
    // freeze at whatever the composition first saw.
    val remainingTime by produceState(
        initialValue = viewModel.llmRemainingTimeSeconds ?: 0L,
        key1 = isModelLoaded,
    ) {
        // TASK-603 (TASK-574 regression): a null reading means PAUSED (work
        // in flight; beginWork drops the idle deadline and endWork re-arms
        // the FULL timeout), so it HIDES the row (the >0L display gate)
        // instead of freezing a stale imminent number; and there is no
        // break-on-zero: a transient zero (re-arm racing the fire) must not
        // kill the producer, and every genuine expiry ends with the unload
        // flipping isModelLoaded, which is the loop's key and its exit.
        while (isModelLoaded) {
            value = viewModel.llmRemainingTimeSeconds ?: 0L
            kotlinx.coroutines.delay(1_000)
        }
    }
    // TASK-507: the Gemma-pass rows (punctuation, summarize)
    // expose only when a Gemma model is configured; without one they can
    // never run and were silent no-ops.
    val gemmaConfigured by viewModel.gemmaConfigured.collectAsState()
    // Collected here (not inside their sections) so the search filter's card
    // groups can mirror each card's runtime condition exactly.
    val backgroundKills by viewModel.backgroundKills.collectAsState()
    val summarizeOn by viewModel.summarizeEnabled.collectAsState()
    // TASK-670/689: the speaker-identities privacy gate (default off),
    // collected here for the same reason as the flags above: the search
    // registry's card condition must mirror EVERY condition between the
    // section and the card's actually-rendered content, inner guards
    // included (review F2: PUNCTUATION_PROMPT renders under a mode if INSIDE
    // the gate's content; mirroring only the outer if would ghost-count)
    // ever composes.
    val speakerIdEnabled by viewModel.speakerIdEnabled.collectAsState()
    // TASK-647: the AI-disclaimer signature on exit surfaces.
    val signatureOn by viewModel.signatureEnabled.collectAsState()
    val signatureTextValue by viewModel.signatureText.collectAsState()
    val signaturePositionValue by viewModel.signaturePosition.collectAsState()
    val signaturePositionTitle = stringResource(R.string.signature_position_title)
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
    } else if (showSpeakerSettings) {
        SpeakerSettingsScreen(
            viewModel = viewModel,
            speakerIdEnabled = speakerIdEnabled,
            onBack = { showSpeakerSettings = false }
        )
    } else if (showPerformanceSettings) {
        PerformanceSettingsScreen(
            viewModel = viewModel,
            focusMemoryProtection = performanceFocusMemoryProtection,
            onFocusConsumed = { performanceFocusMemoryProtection = false },
            onBack = { showPerformanceSettings = false },
        )
    } else if (showAutomationSettings) {
        AutomationSettingsScreen(
            viewModel = viewModel,
            onBack = { showAutomationSettings = false },
        )
    } else {
    // TASK-628: compact rendering while search is active (descriptions
    // capped: see SETTINGS_SEARCH_COMPACT_DESCRIPTION_LINES) so matched
    // cards stay reachable below tall merged cards.
    androidx.compose.runtime.CompositionLocalProvider(
        com.antivocale.app.ui.components.LocalSettingsSearchCompact provides
            searchQuery.isNotBlank()
    ) {
    // TASK-563: the pin derivation and the search derivation hoist ABOVE the
    // bar/sections split (the bar's count line and the sections' visibility
    // both read them).
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

        // TASK-493: the proactive battery-exemption offer, one derivation
        // feeding the registry visible-lambda and the card's compositional if.
        val offerBatteryExemption = backgroundKills > 0 || viewModel.proactiveBatteryExemption

        // TASK-689 contract (full text on SETTINGS_SEARCH_CARDS' KDoc): every
        // gate and the count below read that ONE registry, so the match
        // vocabulary can never drift from the tree.
        val searchState = SettingsSearchState(
            isLlmBackend = isLlmBackend,
            isModelLoaded = isModelLoaded,
            gemmaConfigured = gemmaConfigured,
            punctuationPromptForced = currentPunctuationMode == PunctuationPolicy.PREF_ALWAYS,
            summarizeOn = summarizeOn,
            batteryExemptionOffered = offerBatteryExemption,
            speakerIdEnabled = speakerIdEnabled,
            transcriptionHintRes = transcriptionHintRes,
        )
        @SuppressLint("RememberReturnType")
        val resolver = remember(context, searchState) { LocaleVariantResolver(context) }
        val searchGroups = remember(context, searchState) {
            SETTINGS_SEARCH_CARDS
                .filter { card -> card.visible(searchState) }
                .groupBy(
                    keySelector = { card -> card.section },
                    valueTransform = { card -> localizedVocabulary(resolver, card, searchState) },
                )
        }
        fun sectionGroups(section: SettingsSearchSection): List<List<String>> =
            searchGroups[section].orEmpty()
        val transcriptionSearchGroups = sectionGroups(SettingsSearchSection.TRANSCRIPTION)
        val appearanceSearchGroups = sectionGroups(SettingsSearchSection.APPEARANCE)
        val advancedSearchGroups = sectionGroups(SettingsSearchSection.ADVANCED)
        val feedbackSearchGroups = sectionGroups(SettingsSearchSection.FEEDBACK)

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

    // TASK-563: the pinned collapsing search bar (enterAlways semantics):
    // any downward scroll slides it out, any upward scroll brings it back
    // mid-list, and an ACTIVE query pins it open so the count line and the
    // filtered results can never be trapped off-screen (AC3).
    var searchBarOffsetPx by remember { mutableStateOf(0f) }
    var searchBarHeightPx by remember { mutableStateOf(0f) }
    val searchBarScroll = remember {
        // The delegated query read is LIVE here (closure over the state, not
        // a captured Boolean). Known v1 gap: no fling settle, so a fling can
        // leave the bar half-collapsed; the next touch completes it.
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): Offset {
                if (searchQuery.isNotBlank()) {
                    if (searchBarOffsetPx != 0f) searchBarOffsetPx = 0f
                    return Offset.Zero
                }
                // available.y < 0 is scrolling DOWN the list (the androidx
                // collapsing-toolbar sign): the bar collapses, and the taken
                // delta is returned so the content does not double-consume.
                val previous = searchBarOffsetPx
                searchBarOffsetPx = (previous - available.y).coerceIn(0f, searchBarHeightPx)
                return Offset(0f, previous - searchBarOffsetPx)
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        // The collapse reads the offset in the LAYOUT phase only (a
        // composition-scope height read would recompose the whole tab every
        // scroll frame): measure the bar once at full size, place it shifted
        // up by the offset, and report the shrunken height so the sections
        // column grows into the freed space.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .clipToBounds()
                .layout { measurable, constraints ->
                    val bar = measurable.measure(
                        constraints.copy(minWidth = 0, minHeight = 0))
                    searchBarHeightPx = bar.height.toFloat()
                    val offset = searchBarOffsetPx.coerceIn(0f, bar.height.toFloat())
                    layout(bar.width, (bar.height - offset.roundToInt()).coerceAtLeast(0)) {
                        bar.placeRelative(0, -offset.roundToInt())
                    }
                }
        ) {
            // TASK-542 (GH #98): search field. Blank = normal tab.
            // TASK-605 (c): the shared SearchField (icon drift unified).
            // Range review: the no-op Column wrapper left by the 563 move
            // is gone; the Box's measured layout takes the field directly.
            SearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholderRes = R.string.settings_search_hint,
            )
        if (searchActive) {
            // TASK-628: matched cards can sit below tall merged cards (the
            // "forza" diagnosis: the ~1420px Tema card pushed the force row to
            // y=2580 of a 2780px screen and read as "no result"). The count
            // line is the match navigator: each tap scrolls to the NEXT
            // matched section, cycling.
            val matchedSections = listOfNotNull(
                "transcription".takeIf { transcriptionVisible },
                "appearance".takeIf { appearanceVisible },
                "advanced".takeIf { advancedVisible },
                "feedback".takeIf { feedbackVisible })
            var matchHop by remember { mutableStateOf(0) }
            Text(
                text = if (searchMatchCount > 0)
                    pluralStringResource(
                        R.plurals.settings_search_matches, searchMatchCount, searchMatchCount)
                else
                    stringResource(R.string.settings_search_no_results),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (matchedSections.isNotEmpty()) Modifier.clickable {
                    val key = matchedSections[matchHop % matchedSections.size]
                    matchHop++
                    val target = sectionOffsets[key] ?: return@clickable
                    navScope.launch { scrollState.animateScrollTo(maxOf(0, target - scrollContentRootY + scrollState.value - 32)) }
                } else Modifier
            )
        }
        }
        // TASK-563: the sections keep the original scroll column verbatim;
        // the bar's connection sits above verticalScroll so collapse and
        // expand consume the delta before the content moves (enterAlways).
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(searchBarScroll)
                .verticalScroll(scrollState)
                .onGloballyPositioned { scrollContentRootY = it.positionInRoot().y.toInt() }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {



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
                SearchFilterRow(searchQuery, SettingsSearchId.MODEL_STATUS, searchState) {
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
            SearchFilterRow(searchQuery, SettingsSearchId.ACTIVE_MODEL, searchState) {
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
            // Only the hint that actually renders rides the registry entry
            // (res is null-aware; TASK-689).
            SearchFilterRow(searchQuery, SettingsSearchId.TRANSCRIPTION_LANGUAGE, searchState) {
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

            SettingsGroupLabel(SettingsSearchGroup.DECODING, searchState)

            // VAD Silence Stripping Setting
            val vadTitle = stringResource(R.string.vad_title)
            val vadDescription = stringResource(R.string.vad_description)
            SearchFilterRow(searchQuery, SettingsSearchId.VAD, searchState) {
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
            SearchFilterRow(searchQuery, SettingsSearchId.PROGRESSIVE, searchState) {
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

            // TASK-186: early preview of the pipeline's first chunk. Default
            // off (the extra head decode costs battery on every long clip).
            // Review F2: the preview rides the progressive pipeline, so the
            // card is greyed with a reason until its sibling is on.
            val earlyPreviewTitle = stringResource(R.string.early_preview_title)
            val earlyPreviewDescription = stringResource(R.string.early_preview_description)
            val earlyPreviewRequiresProgressive =
                stringResource(R.string.early_preview_requires_progressive)
            SearchFilterRow(searchQuery, SettingsSearchId.EARLY_PREVIEW, searchState) {
                ToggleSettingCard(
                    icon = Icons.Default.Preview,
                    title = earlyPreviewTitle,
                    description = earlyPreviewDescription,
                    checked = earlyPreviewEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.saveEarlyPreviewEnabled(enabled)
                    },
                    enabled = progressiveEnabled,
                    supportingText = if (progressiveEnabled) null else earlyPreviewRequiresProgressive,
                )
            }

            // GH #43: two-pass transcription (instant preview, then refine).
            val refinementTitle = stringResource(R.string.refinement_title)
            val refinementDescription = stringResource(R.string.refinement_description)
            val refinementEnabled by viewModel.refinementEnabled.collectAsState()
            val refinementAvailable by viewModel.refinementAvailable.collectAsState()
            // TASK-689: the gate reads the registry; this GH #43 card had a
            // gate but no count entry under the old two-list convention.
            SearchFilterRow(searchQuery, SettingsSearchId.REFINEMENT, searchState) {
                ToggleSettingCard(
                    icon = Icons.Default.Bolt,
                    title = refinementTitle,
                    description = refinementDescription,
                    // Greyed out until a streaming model is installed and the
                    // selected backend is not the streaming one itself.
                    enabled = refinementAvailable,
                    checked = refinementEnabled && refinementAvailable,
                    onCheckedChange = { enabled -> viewModel.saveRefinementEnabled(enabled) }
                )
            }

            SettingsGroupLabel(SettingsSearchGroup.GEMMA_TEXT, searchState)

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
                SearchFilterRow(searchQuery, SettingsSearchId.PUNCTUATION_MODE, searchState) {
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
                // TASK-666: CONSERVATIVE forces the pass too but PINS the
                // fenced prompt (an override could break the fences), so the
                // card stays ALWAYS-only: the condition is "override
                // honored", not "pass forced".
                SearchFilterRow(searchQuery, SettingsSearchId.PUNCTUATION_PROMPT, searchState) {
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
                SearchFilterRow(searchQuery, SettingsSearchId.SUMMARIZE, searchState) {
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
                SearchFilterRow(searchQuery, SettingsSearchId.SUMMARY_PROMPT, searchState) {
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
                SearchFilterRow(searchQuery, SettingsSearchId.DEFAULT_PROMPT, searchState) {
                    SettingsHubCard(
                        titleRes = R.string.default_prompt_title,
                        summaryRes = R.string.default_prompt_description,
                        leadingIcon = Icons.Default.Edit,
                        openActionLabelRes = R.string.open_prompt_settings,
                        onOpen = { showPromptSettings = true },
                    )
            }
            }

            // Maintainer decision 2026-09-30: diarization settings moved
            // to their own page; the hub below opens it. Search contract:
            // the hub's registry vocabulary is the UNION of its own and
            // both children's strings (state-gated to the identities
            // card's privacy gate), so old queries still land one tap
            // from the card they matched.
            SearchFilterRow(searchQuery, SettingsSearchId.DIARIZATION_HUB, searchState) {
                SettingsHubCard(R.string.speaker_settings_title, R.string.speaker_settings_summary) { showSpeakerSettings = true }
            }

            SettingsGroupLabel(SettingsSearchGroup.OUTPUT, searchState)

            // Auto-Copy Setting
            val autoCopyTitle = stringResource(R.string.auto_copy_title)
            val autoCopyDescription = stringResource(R.string.auto_copy_description)
            SearchFilterRow(searchQuery, SettingsSearchId.AUTO_COPY, searchState) {
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
            SearchFilterRow(searchQuery, SettingsSearchId.EXPORT_SETTINGS, searchState) {
                SettingsHubCard(
                    titleRes = R.string.export_settings_title,
                    summaryRes = R.string.export_settings_description,
                    leadingIcon = Icons.Default.Save,
                    onOpen = { showExportSettings = true },
                )
            }

            // TASK-647: the AI-disclaimer signature. Applies to what LEAVES
            // the app (copy, share, export); the in-app screens stay raw.
            SearchFilterRow(searchQuery, SettingsSearchId.SIGNATURE, searchState) {
                ToggleSettingCard(
                    icon = Icons.Default.Notes,
                    title = stringResource(R.string.signature_setting_title),
                    description = stringResource(R.string.signature_setting_description),
                    checked = signatureOn,
                    onCheckedChange = { enabled ->
                        viewModel.saveSignatureEnabled(enabled)
                    }
                )
                if (signatureOn) {
                    SignatureTextCard(
                        text = signatureTextValue,
                        onSave = { viewModel.saveSignatureText(it) }
                    )
                    SectionCard(
                        icon = Icons.Default.SwapVert,
                        title = signaturePositionTitle,
                        description = null
                    ) {
                        SettingsDropdown(
                            currentValue = signaturePositionValue,
                            options = PreferencesManager.SIGNATURE_POSITIONS,
                            currentValueDisplay = signaturePositionLabel(signaturePositionValue),
                            optionDisplay = { signaturePositionLabel(it) },
                            onOptionSelected = { viewModel.saveSignaturePosition(it) },
                            label = signaturePositionTitle,
                            enabled = true
                        )
                    }
                }
            }

            // TASK-684 (GH #109): the quiet summary notification for runs
            // the process death closed without a proven cause. Default on;
            // the user can silence it here (the suspended class always
            // notifies, independent of this toggle).
            SearchFilterRow(searchQuery, SettingsSearchId.INTERRUPTED_RUN_NOTIFICATIONS, searchState) {
                ToggleSettingCard(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.interrupted_run_notifications_title),
                    description = stringResource(R.string.interrupted_run_notifications_description),
                    checked = interruptedRunNotifications,
                    onCheckedChange = { enabled ->
                        viewModel.saveInterruptedRunNotifications(enabled)
                    },
                )
            }

            // Keep-Alive Timeout Setting
            val timeoutTitle = stringResource(R.string.auto_unload_timeout)
            SearchFilterRow(searchQuery, SettingsSearchId.KEEP_ALIVE_TIMEOUT, searchState) {
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
            SettingsGroupLabel(SettingsSearchGroup.LOOK_AND_FEEL, searchState)

            // Theme Setting
            val themeTitle = stringResource(R.string.theme_title)
            val themeModeTitle = stringResource(R.string.theme_mode_title)
            // TASK-689: the registry entry carries all six strings, so the
            // count now matches what this card renders; under the old
            // convention TASK-576's text size joined the gate but not the
            // count group ("text size" reported 0 matches over the card
            // that owns the setting).
            SearchFilterRow(searchQuery, SettingsSearchId.THEME, searchState) {
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

                    Spacer(modifier = Modifier.height(8.dp))

                    // TASK-576: text size, four steps over the system scale
                    val textSizeTitle = stringResource(R.string.text_size_title)
                    val currentTextScale by viewModel.currentTextScale.collectAsState()
                    SettingsDropdown(
                        currentValue = currentTextScale,
                        options = TextScale.entries.toList(),
                        currentValueDisplay = stringResource(currentTextScale.nameRes),
                        optionDisplay = { stringResource(it.nameRes) },
                        onOptionSelected = { viewModel.saveTextScale(it) },
                        label = textSizeTitle
                    )
                }
            }

            // App icon variants (TASK-392, TASK-473): selection moved to a
            // dedicated sub-page (maintainer decision 2026-09-09); the row
            // shows the active variant and opens the picker grid.
            val currentLauncherIcon by viewModel.currentLauncherIcon.collectAsState()
            SearchFilterRow(searchQuery, SettingsSearchId.APP_ICON, searchState) {
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
            SearchFilterRow(searchQuery, SettingsSearchId.APP_LANGUAGE, searchState) {
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

            SettingsGroupLabel(SettingsSearchGroup.HISTORY, searchState)

            // Swipe Action Setting
            val swipeActionTitle = stringResource(R.string.swipe_action_title)
            SearchFilterRow(searchQuery, SettingsSearchId.SWIPE_ACTION, searchState) {
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
            SearchFilterRow(searchQuery, SettingsSearchId.CONVERSATION_GROUPING, searchState) {
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
            SearchFilterRow(searchQuery, SettingsSearchId.COMPACT_RESULT_ACTIONS, searchState) {
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

            // TASK-616: the technical processing-context line on transcript
            // entries; off by default, the data rides the report regardless.
            val technicalDetailsTitle = stringResource(R.string.technical_details_title)
            val technicalDetailsDescription = stringResource(R.string.technical_details_description)
            SearchFilterRow(searchQuery, SettingsSearchId.TECHNICAL_DETAILS, searchState) {
                ToggleSettingCard(
                    icon = Icons.Default.DataObject,
                    title = technicalDetailsTitle,
                    description = technicalDetailsDescription,
                    checked = showTechnicalDetails,
                    onCheckedChange = { enabled ->
                        viewModel.saveShowTechnicalDetails(enabled)
                    }
                )
            }

            // TASK-546: the language chip is conditional on this flag.
            val languageChipTitle = stringResource(R.string.language_chip_setting_title)
            val languageChipDescription = stringResource(R.string.language_chip_setting_description)
            val languageChipNote = stringResource(R.string.language_chip_setting_note)
            val languageChipAvailable by viewModel.languageChipAvailable.collectAsState()
            SearchFilterRow(searchQuery, SettingsSearchId.LANGUAGE_CHIP, searchState) {
                ToggleSettingCard(
                    icon = Icons.Default.Language,
                    title = languageChipTitle,
                    description = languageChipDescription,
                    // TASK-611: visible but disabled on models that cannot
                    // detect the language; the note names the supported ones.
                    enabled = languageChipAvailable,
                    supportingText = languageChipNote.takeIf { !languageChipAvailable },
                    checked = languageChipEnabled && languageChipAvailable,
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
            SearchFilterRow(searchQuery, SettingsSearchId.RETRANSCRIBE, searchState) {
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
            // TASK-493: on the kill-vulnerable class (low-RAM; the TASK-468
            // MIUI/PowerKeeper verdict) the card appears PROACTIVELY: after
            // the first kill the trace may be gone, and a pre-emptive grant
            // is the only reachable mitigation there.
            if (offerBatteryExemption) {
                val context = LocalContext.current
                SearchFilterRow(searchQuery, SettingsSearchId.BATTERY_EXEMPTION, searchState) {
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

            // Maintainer decision 2026-09-30: performance and memory
            // settings moved to their own page; the hub below opens it.
            // Search contract: the hub's registry vocabulary is the union
            // of its own and all five children's strings.
            SearchFilterRow(searchQuery, SettingsSearchId.PERFORMANCE_HUB, searchState) {
                SettingsHubCard(R.string.performance_settings_title, R.string.performance_settings_summary) { showPerformanceSettings = true }
            }

            // HuggingFace Token Card
            SearchFilterRow(searchQuery, SettingsSearchId.HUGGINGFACE_AUTH, searchState) {
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
                                                        FeedbackHelper.openUrlOrToast(
                                                            context, HF_TOKEN_SETTINGS_URL)
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
                                                        FeedbackHelper.openUrlOrToast(
                                                            context, HF_TOKEN_SETTINGS_URL)
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

            // Per-App Settings Navigation Card
            SearchFilterRow(searchQuery, SettingsSearchId.PER_APP_SETTINGS, searchState) {
                SettingsHubCard(
                    titleRes = R.string.per_app_settings_title,
                    summaryRes = R.string.per_app_settings_description,
                    leadingIcon = Icons.Default.Settings,
                    openActionLabelRes = R.string.open_per_app_settings,
                    onOpen = { showPerAppSettings = true },
                )
            }

            SettingsGroupLabel(SettingsSearchGroup.INTEGRATIONS, searchState)

            // Advanced Sharing Card
            SearchFilterRow(searchQuery, SettingsSearchId.SHARE_TARGETS, searchState) {
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
            SearchFilterRow(searchQuery, SettingsSearchId.SUBTITLE_TIMEOUT, searchState) {
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

            // Maintainer decision 2026-09-30: automation and offload
            // settings moved to their own page; the hub below opens it.
            // Search contract: the hub's registry vocabulary is the static
            // union of its own and all three children's strings.
            SearchFilterRow(searchQuery, SettingsSearchId.AUTOMATION_HUB, searchState) {
                SettingsHubCard(R.string.automation_settings_title, R.string.automation_settings_summary) { showAutomationSettings = true }
            }

            // TASK-735: the voice-note identity listener's app-level gate.
            // The system's notification access is the outer gate; while this
            // toggle is off the listener reads nothing (the RAM-only privacy
            // contract is on VoiceNoteIdentityListener). The access probe is
            // NOT remembered: it must re-read when the user returns from the
            // system screen.
            val voiceNoteIdentityEnabled by viewModel.voiceNoteIdentityEnabled.collectAsState()
            // The access probe is a binder IPC; refresh it exactly when the
            // user comes back from the system screen (ON_RESUME), not on
            // every recomposition of the section.
            var notificationAccessGranted by remember { mutableStateOf(false) }
            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                fun probe() {
                    notificationAccessGranted = androidx.core.app.NotificationManagerCompat
                        .getEnabledListenerPackages(context).contains(context.packageName)
                }
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) probe()
                }
                probe() // ON_RESUME alone never fires for an already-resumed activity
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            SearchFilterRow(searchQuery, SettingsSearchId.VOICE_NOTE_IDENTITY, searchState) {
                ToggleSettingCard(
                    icon = Icons.Default.RecordVoiceOver,
                    title = stringResource(R.string.voice_note_identity_title),
                    description = stringResource(R.string.voice_note_identity_description),
                    // The description IS the maintainer-mandated limits
                    // text: it renders in full wherever the card renders,
                    // search included. (Range review: the first attempt at
                    // this override silently never applied - the fourth
                    // scripted non-application; disk grep is the proof.)
                    descriptionMaxLinesCompact = Int.MAX_VALUE,
                    checked = voiceNoteIdentityEnabled,
                    onCheckedChange = { enabled -> viewModel.saveVoiceNoteIdentityEnabled(enabled) }
                )
                if (voiceNoteIdentityEnabled && !notificationAccessGranted) {
                    // The battery-exemption precedent: the grant action is a
                    // Button row, not bare text.
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    }) {
                        Text(
                            text = stringResource(R.string.voice_note_identity_grant_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            SearchFilterRow(searchQuery, SettingsSearchId.SHARE_SHORTCUT_ICONS, searchState) {
                ShareShortcutIconsCard(viewModel)
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

        // Spacer for scroll
        Spacer(modifier = Modifier.height(32.dp))
        }
        }
    }
    } // End of if-else for showPerAppSettings
}

/**
 * TASK-679: the read-only memory diagnostics card (Settings > Advanced).
 * Resident engines, free RAM, the app heap ceiling and the last post-OOM
 * breadcrumb, exactly as the recorder wrote them. The Copy action puts the
 * scrubbed bundle on the clipboard (model identities and memory numbers
 * only; no transcript, no paths), which is the v1 export: no share intent.
 */
@Composable
internal fun MemoryDiagnosticsCard(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    // Live exactly while the card is composed (the collapsed-by-default
    // Advanced section keeps this collector from ever running unseen).
    LaunchedEffect(viewModel) {
        viewModel.memoryDiagnosticsTriggers.collect { viewModel.refreshMemoryDiagnostics() }
    }
    val diagnostics by viewModel.memoryDiagnostics.collectAsState()
    val state = diagnostics

    // Simplify F1: the shared section-card scaffold (TASK-510), matching
    // every neighbor in the Advanced section and inheriting the compact-
    // search description suppression (TASK-628).
    com.antivocale.app.ui.components.SectionCard(
        icon = Icons.Default.Memory,
        title = stringResource(R.string.memory_diagnostics_title),
        description = stringResource(R.string.memory_diagnostics_subtitle),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            Text(
                text = stringResource(R.string.memory_diagnostics_resident_label),
                style = MaterialTheme.typography.labelLarge
            )
            val residentLines = state?.residentLines().orEmpty()
            if (residentLines.isEmpty()) {
                Text(
                    text = stringResource(R.string.memory_diagnostics_resident_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                residentLines.forEach { line ->
                    Text(text = line, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text(
                text = stringResource(R.string.memory_diagnostics_ram_label),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = state?.freeRamMb()?.let { free ->
                    stringResource(
                        R.string.memory_diagnostics_ram_value,
                        free, state.totalRamMb() ?: "")
                } ?: stringResource(R.string.memory_diagnostics_ram_unknown),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.memory_diagnostics_heap_label),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = state?.heapLimitLine() ?: "",
                style = MaterialTheme.typography.bodyMedium
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = stringResource(R.string.memory_diagnostics_breadcrumb_label),
                style = MaterialTheme.typography.labelLarge
            )
            val crumb = state?.lastBreadcrumb
            if (crumb == null) {
                Text(
                    text = stringResource(R.string.memory_diagnostics_breadcrumb_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.lastBreadcrumbAtMs?.let { atMs ->
                    Text(
                        text = android.text.format.DateUtils.getRelativeTimeSpanString(atMs).toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = crumb,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
            Text(
                text = stringResource(R.string.memory_diagnostics_breadcrumb_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextButton(
                enabled = state != null,
                onClick = {
                    val bundle = state?.exportBundle() ?: return@TextButton
                    ClipboardWriter.copy(
                        context, context.getString(R.string.memory_diagnostics_title), bundle)
                    com.antivocale.app.util.ToastCompat.show(
                        context, context.getString(R.string.copied_to_clipboard))
                }
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.memory_diagnostics_copy))
            }
            Text(
                text = stringResource(R.string.memory_diagnostics_scrub_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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

                // TASK-608: the in-app FAQ (the five cards design note):
                // the questions users actually hit, localized; the long tail
                // stays on the web FAQ (the link-out two rows below).
                Text(
                    text = stringResource(R.string.faq_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                val faqCards = listOf(
                    Triple(R.string.faq_card_calls_title, R.string.faq_card_calls_body, Icons.Default.QuestionAnswer),
                    Triple(R.string.faq_card_models_title, R.string.faq_card_models_body, Icons.Default.Memory),
                    Triple(R.string.faq_card_queue_title, R.string.faq_card_queue_body, Icons.Default.Queue),
                    Triple(R.string.faq_card_results_title, R.string.faq_card_results_body, Icons.Default.Description),
                    Triple(R.string.faq_card_trouble_title, R.string.faq_card_trouble_body, Icons.Default.BugReport),
                )
                faqCards.forEach { (titleRes, bodyRes, icon) ->
                    var expanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { expanded = !expanded }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(titleRes),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (expanded) {
                                Text(
                                    text = stringResource(bodyRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Full FAQ link row (TASK-608: the long tail stays web).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            FeedbackHelper.openUrlOrToast(
                                context, "https://github.com/RisorseArtificiali/anti-vocale/blob/main/FAQ.md")
                        }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.faq_full_link),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Source code row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            FeedbackHelper.openUrlOrToast(
                                context, FeedbackHelper.SOURCE_CODE_URL)
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

internal fun matchesQuery(query: String, texts: List<String?>): Boolean =
    query.isBlank() || texts.any { it?.contains(query, ignoreCase = true) == true }

/**
 * TASK-629: resolves the search's locale set ONCE (the app context plus the
 * two derived contexts: the PHONE locale and the English base), then every
 * resource id resolves to its distinct variants against them. A user on an
 * English UI typing an Italian query ("forza" against "Force model load")
 * must still find the setting: the MATCH walks every variant, while the
 * RENDERED text stays app-locale everywhere (no UI change).
 *
 * The phone leg reads through [com.antivocale.app.util.LocaleManager.phoneLocale]
 * (the per-app-aware system read): Resources.getSystem() returns the APP
 * locale on API 33+ once a per-app language is pinned (the TASK-547
 * finding), which would collapse the phone leg exactly for the users this
 * feature targets. A leg whose LANGUAGE matches the app's is skipped (the
 * base leg under any English app locale, the phone leg for a same-language
 * device); distinct keeps mono-locale setups at exactly today's token list.
 *
 * The two derived contexts are built per RESOLVER, not per id (review: one
 * per id allocated hundreds of ContextImpls per searchState flip), and a
 * failed leg logs and falls back to nothing rather than throwing out of
 * composition.
 */
internal class LocaleVariantResolver(
    private val context: Context,
    // DEVICE FINDING (2026-10-02, the maintainer's own en-IT phone): the
    // SYSTEM locale list's FIRST entry can be the same language as the app
    // (en-IT primary with it-IT second): reading only [0] collapses the
    // phone leg exactly for the multilingual users the feature targets.
    // Every system locale joins the set, bounded so a long list cannot
    // mint a context per locale.
    phoneLocales: List<java.util.Locale> = com.antivocale.app.util.LocaleManager.phoneLocalesList(context),
) {
    private val app: Context = context
    private val appLanguage: String = context.resources.configuration.locales[0].language
    private val phone: List<Context> = phoneLocales
        .filter { it.language != appLanguage }
        .distinctBy { it.language }
        .take(MAX_PHONE_LOCALES).mapNotNull(::overlay)
    private val english: Context? = overlay(java.util.Locale.ENGLISH)

    private fun overlay(locale: java.util.Locale?): Context? {
        if (locale == null || locale.language == appLanguage) return null
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(locale)
        return runCatching { context.createConfigurationContext(config) }
            .onFailure { android.util.Log.d(TAG, "search locale overlay failed for $locale", it) }
            .getOrNull()
    }

    fun variants(@StringRes resId: Int): List<String> =
        (sequenceOf(app) + phone.asSequence() + listOfNotNull(english))
            .mapNotNull { ctx -> runCatching { ctx.getString(resId) }.getOrNull() }
            .distinct().toList()

    private companion object {
        const val TAG = "LocaleVariantResolver"
        const val MAX_PHONE_LOCALES = 2
    }
}

/** TASK-629: one card's match vocabulary, all locale variants in. */
private fun localizedVocabulary(resolver: LocaleVariantResolver, card: SettingsSearchCard, state: SettingsSearchState): List<String> =
    cardVocabulary(card, state).flatMap(resolver::variants)

/**
 * TASK-731: the strings a card matches on: its own vocabulary plus its
 * group label (see [SettingsSearchGroup]).
 */

internal fun cardVocabulary(card: SettingsSearchCard, state: SettingsSearchState): List<Int> =
    card.res(state) + listOfNotNull(card.group?.labelRes)

/**
 * TASK-731: a group's header renders only while at least one member card
 * does (see [SettingsSearchGroup]).
 */
internal fun groupHasVisibleMember(group: SettingsSearchGroup, state: SettingsSearchState): Boolean =
    SETTINGS_SEARCH_CARDS.any { it.group == group && it.visible(state) }

/**
 * TASK-731: one in-section group label (see [SettingsSearchGroup]); the
 * flat-search suppression lives in [GroupHeader].
 */
@Composable
private fun SettingsGroupLabel(group: SettingsSearchGroup, state: SettingsSearchState) {
    // TASK-733: the member scan runs on state flips, not on every body
    // recomposition (Compose skipping already covers the keystroke path).
    if (remember(group, state) { groupHasVisibleMember(group, state) }) {
        GroupHeader(group.labelRes)
    }
}

/**
 * TASK-542: card-level gate for the settings search. Renders [content] only
 * when [matchesQuery] accepts the query. TASK-689: callers pass their card's
 * registry identity ([id] + [state]) and the texts resolve inside from the
 * SAME registry the count line derives from, so the gate and the count read
 * one source.
 */
@Composable
private fun SearchFilterRow(
    query: String,
    id: SettingsSearchId,
    state: SettingsSearchState,
    content: @Composable () -> Unit,
) {
    // Simplify F2: the gate texts resolve INSIDE the row (the lookup was the
    // 39-site spread boilerplate; the signature now carries only identity).
    // TASK-731: the vocabulary includes the card's group label. TASK-733:
    // remembered per row (keys mirror the tab-level derivation's: context
    // plus state); before, every keystroke re-scanned the registry
    // (first{}) and re-resolved the strings through stringResource.
    val context = LocalContext.current
    val matchTexts = remember(id, state, context) {
        val resolver = LocaleVariantResolver(context)
        SETTINGS_SEARCH_CARDS
            .first { card -> card.id == id }
            .let { card -> localizedVocabulary(resolver, card, state) }
    }
    if (matchesQuery(query, matchTexts)) {
        content()
    }
}

/**
 * TASK-670 (GH #83): the Speaker identities card. Enrollment is a file
 * pick of a short clip of the person's voice (the app has no in-app
 * microphone capture by design); the clip is decoded, bounded, and
 * embedded BEFORE the name dialog appears, and nothing touches the store
 * until the user confirms the name. Replay plays the stored sample;
 * delete removes the voiceprint and the sample together.
 */
@Composable
internal fun SpeakerIdentitiesCard(viewModel: SettingsViewModel) {
    val identities by viewModel.speakerIdentities.collectAsState()
    val pending by viewModel.speakerEnrollPending.collectAsState()
    val error by viewModel.speakerEnrollError.collectAsState()
    val busy by viewModel.speakerEnrollBusy.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.enrollSpeakerSample(uri)
    }

    // One replay player: a new play stops the previous one; released on
    // leaving the composition (TASK-670).
    var replayPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            replayPlayer?.release()
            replayPlayer = null
        }
    }
    val replaySample: (File) -> Unit = { sample ->
        replayPlayer?.release()
        replayPlayer = runCatching {
            android.media.MediaPlayer().apply {
                setDataSource(sample.absolutePath)
                setOnPreparedListener { it.start() }
                // Review R8: a failed async prepare must release the player
                // (not linger in the error state holding its fd), and a
                // COMPLETED player releases too (previously it sat until
                // the next replay tap or leaving the composition).
                setOnErrorListener { mp, _, _ -> mp.release(); true }
                setOnCompletionListener { it.release() }
                prepareAsync()
            }
        }.getOrNull()
    }

    SectionCard(
        icon = Icons.Default.RecordVoiceOver,
        title = stringResource(R.string.speaker_id_title),
        description = stringResource(R.string.speaker_id_description),
    ) {
        if (identities.isEmpty()) {
            Text(
                text = stringResource(R.string.speaker_id_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (identity in identities) {
            SpeakerIdentityRow(
                identity = identity,
                onReplay = { viewModel.speakerSampleFile(identity.id)?.let(replaySample) },
                onDelete = { viewModel.deleteSpeakerIdentity(identity.id) },
            )
        }
        error?.let { enrollmentError ->
            Text(
                text = stringResource(enrollmentError.labelRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(
            onClick = { picker.launch(arrayOf("audio/*")) },
            enabled = !busy,
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.speaker_id_add))
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    pending?.let { sample ->
        SpeakerNameDialog(
            sampleSeconds = sample.sampleSeconds,
            onConfirm = { name -> viewModel.confirmSpeakerEnrollment(name) },
            onDismiss = { viewModel.cancelSpeakerEnrollment() },
        )
    }
}

@Composable
private fun SpeakerIdentityRow(
    identity: com.antivocale.app.transcription.diarization.SpeakerIdentity,
    onReplay: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.weight(1f)) {
            Text(identity.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(
                    R.string.speaker_id_sample_seconds, identity.sampleSeconds.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onReplay) {
            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.speaker_id_replay))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.speaker_id_delete))
        }
    }
}

@Composable
private fun SpeakerNameDialog(
    sampleSeconds: Float,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speaker_id_name_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.speaker_id_sample_seconds, sampleSeconds.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.speaker_id_name_label)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.speaker_id_name_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.speaker_id_name_cancel)) }
        },
    )
}

/** TASK-670: the enrollment failure -> its localized message (the enum
 *  moved to the diarization package with the pipeline, simplify F2). */
@androidx.annotation.StringRes
private fun SpeakerEnrollError.labelRes(): Int = when (this) {
    SpeakerEnrollError.TOO_SHORT -> R.string.speaker_id_error_too_short
    SpeakerEnrollError.TOO_LONG -> R.string.speaker_id_error_too_long
    SpeakerEnrollError.DECODE -> R.string.speaker_id_error_decode
    SpeakerEnrollError.EXTRACT -> R.string.speaker_id_error_extract
    SpeakerEnrollError.SAVE -> R.string.speaker_id_error_save
    SpeakerEnrollError.MODEL_DOWNLOAD -> R.string.speaker_id_error_model
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

/** App-language dropdown sentinel (the per-app "System Default" entry). */
private val appLanguageSentinelLabels = mapOf("system" to R.string.language_system)


/** TASK-647: the free-text signature (blank = the localized default); inherits EditablePromptCard's cap and focus-loss commit. */
@Composable
private fun SignatureTextCard(
    text: String,
    onSave: (String) -> Unit,
) = EditablePromptCard(
    prompt = text,
    onSave = onSave,
    titleRes = R.string.signature_text_title,
    descriptionRes = R.string.signature_text_description,
    placeholderRes = R.string.signature_default_text,
)

@Composable
private fun signaturePositionLabel(value: String): String = when (value) {
    "prepend" -> stringResource(R.string.signature_position_prepend)
    else -> stringResource(R.string.signature_position_append)
}


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

/**
 * TASK-275: the Automation guide card's search vocabulary, ONE list feeding
 * both the Advanced search group and the card's SearchFilterRow matchTexts.
 * The count line and the per-card gate must read the same string set or a
 * query matching only one side reports a ghost card (review F1).
 */

/**
 * TASK-689: the sections of the Settings tab the live search filters. The
 * registry is grouped by this to derive one group list per section: a
 * section shows when any of its cards matches, and the count line reports
 * matching cards across all sections.
 */
internal enum class SettingsSearchSection { TRANSCRIPTION, APPEARANCE, ADVANCED, FEEDBACK }

/**
 * TASK-689: one id per searchable Settings card. Every id must have exactly
 * one [SETTINGS_SEARCH_CARDS] entry (SettingsSearchRegistryTest pins it), so
 * a gate written for a card that is missing from the count side fails loudly
 * instead of silently drifting.
 */
internal enum class SettingsSearchId {
    // Transcription
    MODEL_STATUS, ACTIVE_MODEL, TRANSCRIPTION_LANGUAGE, AUTO_COPY, EXPORT_SETTINGS,
    REFINEMENT, DIARIZATION_HUB, VAD, PROGRESSIVE, EARLY_PREVIEW,
    INTERRUPTED_RUN_NOTIFICATIONS,
    PUNCTUATION_MODE, PUNCTUATION_PROMPT, SUMMARIZE, SUMMARY_PROMPT, SIGNATURE,
    DEFAULT_PROMPT, KEEP_ALIVE_TIMEOUT,
    // Appearance
    THEME, APP_ICON, APP_LANGUAGE, SWIPE_ACTION, CONVERSATION_GROUPING,
    COMPACT_RESULT_ACTIONS, TECHNICAL_DETAILS, LANGUAGE_CHIP, RETRANSCRIBE,
    // Advanced
    BATTERY_EXEMPTION, HUGGINGFACE_AUTH, PERFORMANCE_HUB,
    SHARE_TARGETS, SUBTITLE_TIMEOUT, AUTOMATION_HUB, VOICE_NOTE_IDENTITY, PER_APP_SETTINGS,
    SHARE_SHORTCUT_ICONS,
    // Feedback
    FEEDBACK,
}

/**
 * TASK-731: the in-section sub-group labels (the maintainer's 2026-10-01
 * diagnosis: thematic intersections inside one section). Membership lives
 * on the registry entries (`group = ...`), and BOTH search concerns derive
 * from it: a group's label matches in search through its member cards
 * (the label is tree text; a query naming it must find the cards), and
 * the tree's [SettingsGroupLabel] renders a group's header only while at
 * least one member is visible, so no hand-maintained visibility mirror
 * can drift from the member gates.
 */
internal enum class SettingsSearchGroup(val labelRes: Int) {
    DECODING(R.string.settings_group_decoding),
    GEMMA_TEXT(R.string.settings_group_gemma),
    OUTPUT(R.string.settings_group_output),
    LOOK_AND_FEEL(R.string.settings_group_appearance),
    HISTORY(R.string.settings_group_history),
    INTEGRATIONS(R.string.settings_group_integrations),
}

/**
 * TASK-689: the runtime conditions the registry reads, collected at the tab
 * level (the TASK-542 pattern: the count must mirror the tree's conditions
 * before the sections compose, because a hidden section's rows never
 * compose). One data class so the derived groups remember() on a single key
 * that changes exactly when a condition the registry reads flips.
 */
internal data class SettingsSearchState(
    val isLlmBackend: Boolean,
    val isModelLoaded: Boolean,
    val gemmaConfigured: Boolean,
    /**
     * The prompt OVERRIDE is honored (PREF_ALWAYS only): its card renders.
     * TASK-666: the name predates CONSERVATIVE; that mode also forces the
     * pass but PINS the fenced prompt (the override is ignored by
     * design), so it must stay out of this flag - including it would
     * render a card whose edits are silently discarded.
     */
    val punctuationPromptForced: Boolean,
    val summarizeOn: Boolean,
    /** A background kill was swept: the battery-exemption card offers itself. */
    val batteryExemptionOffered: Boolean,
    /** The TASK-670 privacy switch: the speaker-identities card exists. */
    val speakerIdEnabled: Boolean,
    /** The one hint line the transcription-language card renders, if any. */
    val transcriptionHintRes: Int?,
)

/**
 * TASK-689: one searchable card: its section, its match vocabulary as a
 * function of [SettingsSearchState] (two cards pick strings at runtime),
 * and the runtime condition mirroring the compositional if that wraps its
 * SearchFilterRow in the tree. The tree stays the render authority; the
 * count derivation and the row gate both read THIS entry, so the string
 * sets can no longer drift apart.
 */
internal class SettingsSearchCard(
    val id: SettingsSearchId,
    val section: SettingsSearchSection,
    val res: (SettingsSearchState) -> List<Int>,
    val visible: (SettingsSearchState) -> Boolean = { true },
    /** TASK-731: the in-section group whose header renders above this card. */
    val group: SettingsSearchGroup? = null,
) {
    /** Static vocabulary: most cards never vary with state. */
    internal constructor(
        id: SettingsSearchId,
        section: SettingsSearchSection,
        res: List<Int>,
        visible: (SettingsSearchState) -> Boolean = { true },
        group: SettingsSearchGroup? = null,
    ) : this(id, section, { res }, visible, group)
}

/**
 * TASK-689: the ONE list both sides of the settings search read: the match
 * count and the section visibility derive from it (each card filtered by
 * its visible(), grouped by section, resolved against the current locale),
 * and every SearchFilterRow gate resolves its match texts from its entry
 * (by id, inside the row). This generalizes the TASK-275
 * AUTOMATION_GUIDE_SEARCH_RES fix to every card. Entries sit in tree order
 * within their section; a new card needs one entry here plus its natural
 * SearchFilterRow wrap at the tree site, with the entry's visible()
 * mirroring the compositional if around that wrap.
 */
internal val SETTINGS_SEARCH_CARDS: List<SettingsSearchCard> = listOf(
    // --- Transcription ---
    SettingsSearchCard(
        SettingsSearchId.MODEL_STATUS, SettingsSearchSection.TRANSCRIPTION,
        // Only the live status title, mirroring the card: listing both
        // variants would count a match the tree never renders.
        res = { s -> listOf(if (s.isModelLoaded) R.string.model_loaded else R.string.model_not_loaded) },
        visible = { s -> s.isLlmBackend },
    ),
    SettingsSearchCard(
        SettingsSearchId.ACTIVE_MODEL, SettingsSearchSection.TRANSCRIPTION,
        listOf(R.string.active_model),
    ),
    SettingsSearchCard(
        SettingsSearchId.TRANSCRIPTION_LANGUAGE, SettingsSearchSection.TRANSCRIPTION,
        // At most one hint renders (see transcriptionHintRes in the tree);
        // the vocabulary must not match text the tree does not show.
        res = { s -> listOfNotNull(R.string.transcription_language_title, s.transcriptionHintRes) },
    ),
    SettingsSearchCard(
        SettingsSearchId.VAD, SettingsSearchSection.TRANSCRIPTION,
        listOf(R.string.vad_title, R.string.vad_description),
        group = SettingsSearchGroup.DECODING,
    ),
    SettingsSearchCard(
        SettingsSearchId.PROGRESSIVE, SettingsSearchSection.TRANSCRIPTION,
        listOf(R.string.progressive_title, R.string.progressive_description),
        group = SettingsSearchGroup.DECODING,
    ),
    // TASK-186: the early-preview toggle, right after its sibling.
    SettingsSearchCard(
        SettingsSearchId.EARLY_PREVIEW, SettingsSearchSection.TRANSCRIPTION,
        listOf(R.string.early_preview_title, R.string.early_preview_description),
        group = SettingsSearchGroup.DECODING,
    ),
    // TASK-689: closed a real gap in the old count groups (the gate
    // existed, the count entry did not): the GH #43 two-pass
    // refinement card.
    SettingsSearchCard(
        SettingsSearchId.REFINEMENT, SettingsSearchSection.TRANSCRIPTION,
        listOf(R.string.refinement_title, R.string.refinement_description),
        group = SettingsSearchGroup.DECODING,
    ),
    SettingsSearchCard(
        SettingsSearchId.PUNCTUATION_MODE, SettingsSearchSection.TRANSCRIPTION,
        // TASK-666: the option label joins the vocabulary so "paragraph"
        // / "conservative" queries find the card.
        listOf(
            R.string.punctuation_mode_title, R.string.punctuation_mode_description,
            R.string.punctuation_mode_conservative),
        visible = { s -> s.gemmaConfigured && !s.isLlmBackend },
        group = SettingsSearchGroup.GEMMA_TEXT,
    ),
    SettingsSearchCard(
        SettingsSearchId.PUNCTUATION_PROMPT, SettingsSearchSection.TRANSCRIPTION,
        listOf(R.string.punctuation_prompt_title, R.string.punctuation_prompt_description),
        visible = { s -> s.gemmaConfigured && !s.isLlmBackend && s.punctuationPromptForced },
        group = SettingsSearchGroup.GEMMA_TEXT,
    ),
    SettingsSearchCard(
        SettingsSearchId.SUMMARIZE, SettingsSearchSection.TRANSCRIPTION,
        listOf(R.string.summarize_title, R.string.summarize_description),
        visible = { s -> s.gemmaConfigured },
        group = SettingsSearchGroup.GEMMA_TEXT,
    ),
    SettingsSearchCard(
        SettingsSearchId.SUMMARY_PROMPT, SettingsSearchSection.TRANSCRIPTION,
        listOf(R.string.summary_prompt_title, R.string.summary_prompt_description),
        visible = { s -> s.gemmaConfigured && s.summarizeOn },
        group = SettingsSearchGroup.GEMMA_TEXT,
    ),
    SettingsSearchCard(
        SettingsSearchId.DEFAULT_PROMPT, SettingsSearchSection.TRANSCRIPTION,
        listOf(R.string.default_prompt_title, R.string.default_prompt_description),
        visible = { s -> s.isLlmBackend },
        group = SettingsSearchGroup.GEMMA_TEXT,
    ),
    // The GH #83 speaker-labels toggle.
    // Maintainer decision 2026-09-30: the diarization hub. Union
    // vocabulary (the search-compat contract): the hub's own strings plus
    // both children's; the identities strings join only while the privacy
    // gate would render that card, so the count stays honest.
    SettingsSearchCard(
        SettingsSearchId.DIARIZATION_HUB, SettingsSearchSection.TRANSCRIPTION,
        res = { s ->
            if (s.speakerIdEnabled) listOf(
                R.string.speaker_settings_title, R.string.speaker_settings_summary,
                R.string.speaker_labels_title, R.string.speaker_labels_description,
                R.string.speaker_id_title, R.string.speaker_id_description)
            else listOf(
                R.string.speaker_settings_title, R.string.speaker_settings_summary,
                R.string.speaker_labels_title, R.string.speaker_labels_description)
        },
    ),
    SettingsSearchCard(
        SettingsSearchId.AUTO_COPY, SettingsSearchSection.TRANSCRIPTION,
        listOf(R.string.auto_copy_title, R.string.auto_copy_description),
        group = SettingsSearchGroup.OUTPUT,
    ),
    SettingsSearchCard(
        SettingsSearchId.EXPORT_SETTINGS, SettingsSearchSection.TRANSCRIPTION,
        listOf(R.string.export_settings_title, R.string.export_settings_description),
        group = SettingsSearchGroup.OUTPUT,
    ),
    // TASK-647: the card renders unconditionally, so its entry must too
    // (review F3: bundling it with the Gemma-gated summarize group hid it
    // for non-Gemma users).
    SettingsSearchCard(
        SettingsSearchId.SIGNATURE, SettingsSearchSection.TRANSCRIPTION,
        listOf(R.string.signature_setting_title, R.string.signature_setting_description),
        group = SettingsSearchGroup.OUTPUT,
    ),
    SettingsSearchCard(
        SettingsSearchId.INTERRUPTED_RUN_NOTIFICATIONS, SettingsSearchSection.TRANSCRIPTION,
        listOf(R.string.interrupted_run_notifications_title, R.string.interrupted_run_notifications_description),
    ),
    SettingsSearchCard(
        SettingsSearchId.KEEP_ALIVE_TIMEOUT, SettingsSearchSection.TRANSCRIPTION,
        listOf(R.string.auto_unload_timeout, R.string.timeout_description),
    ),
    // --- Appearance ---
    // TASK-689: text size is part of the vocabulary; TASK-576 added the
    // dropdown to this card and its gate but not the count group, so
    // "text size" queries reported 0 matches over the owning card.
    SettingsSearchCard(
        SettingsSearchId.THEME, SettingsSearchSection.APPEARANCE,
        listOf(
            R.string.theme_title, R.string.theme_description,
            R.string.theme_mode_title, R.string.theme_mode_description,
            R.string.text_size_title, R.string.text_size_description,
        ),
        group = SettingsSearchGroup.LOOK_AND_FEEL,
    ),
    SettingsSearchCard(
        SettingsSearchId.APP_ICON, SettingsSearchSection.APPEARANCE,
        listOf(R.string.app_icon_title),
        group = SettingsSearchGroup.LOOK_AND_FEEL,
    ),
    SettingsSearchCard(
        SettingsSearchId.APP_LANGUAGE, SettingsSearchSection.APPEARANCE,
        listOf(R.string.language_title, R.string.language_description),
        group = SettingsSearchGroup.LOOK_AND_FEEL,
    ),
    SettingsSearchCard(
        SettingsSearchId.SWIPE_ACTION, SettingsSearchSection.APPEARANCE,
        listOf(R.string.swipe_action_title, R.string.swipe_action_description),
        group = SettingsSearchGroup.HISTORY,
    ),
    SettingsSearchCard(
        SettingsSearchId.CONVERSATION_GROUPING, SettingsSearchSection.APPEARANCE,
        listOf(R.string.conversation_grouping_title, R.string.conversation_grouping_description),
        group = SettingsSearchGroup.HISTORY,
    ),
    SettingsSearchCard(
        SettingsSearchId.COMPACT_RESULT_ACTIONS, SettingsSearchSection.APPEARANCE,
        listOf(R.string.compact_result_actions_title, R.string.compact_result_actions_description),
        group = SettingsSearchGroup.HISTORY,
    ),
    SettingsSearchCard(
        SettingsSearchId.TECHNICAL_DETAILS, SettingsSearchSection.APPEARANCE,
        listOf(R.string.technical_details_title, R.string.technical_details_description),
        group = SettingsSearchGroup.HISTORY,
    ),
    SettingsSearchCard(
        SettingsSearchId.LANGUAGE_CHIP, SettingsSearchSection.APPEARANCE,
        listOf(R.string.language_chip_setting_title, R.string.language_chip_setting_description),
        group = SettingsSearchGroup.HISTORY,
    ),
    SettingsSearchCard(
        SettingsSearchId.RETRANSCRIBE, SettingsSearchSection.APPEARANCE,
        listOf(R.string.retranscribe_setting_title, R.string.retranscribe_setting_description),
        group = SettingsSearchGroup.HISTORY,
    ),
    // --- Advanced ---
    SettingsSearchCard(
        SettingsSearchId.BATTERY_EXEMPTION, SettingsSearchSection.ADVANCED,
        listOf(R.string.battery_exemption_title, R.string.battery_exemption_description),
        visible = { s -> s.batteryExemptionOffered },
    ),
    // Maintainer decision 2026-09-30: the performance-and-memory hub.
    // Static union vocabulary: the hub's strings plus all five
    // children's (none of the five is state-gated).
    SettingsSearchCard(
        SettingsSearchId.PERFORMANCE_HUB, SettingsSearchSection.ADVANCED,
        listOf(
            R.string.performance_settings_title, R.string.performance_settings_summary,
            R.string.thread_count_title, R.string.thread_count_description,
            R.string.inference_provider_title, R.string.inference_provider_description,
            R.string.memory_protection, R.string.memory_protection_desc,
            R.string.performance_stats_title, R.string.performance_stats_subtitle,
            R.string.memory_diagnostics_title, R.string.memory_diagnostics_subtitle),
    ),
    SettingsSearchCard(
        SettingsSearchId.HUGGINGFACE_AUTH, SettingsSearchSection.ADVANCED,
        listOf(R.string.huggingface_auth, R.string.huggingface_auth_description),
    ),
    SettingsSearchCard(
        SettingsSearchId.PER_APP_SETTINGS, SettingsSearchSection.ADVANCED,
        listOf(R.string.per_app_settings_title, R.string.per_app_settings_description),
    ),
    SettingsSearchCard(
        SettingsSearchId.SHARE_TARGETS, SettingsSearchSection.ADVANCED,
        listOf(R.string.share_targets_title, R.string.share_targets_description, R.string.advanced_sharing_toggle),
        group = SettingsSearchGroup.INTEGRATIONS,
    ),
    SettingsSearchCard(
        SettingsSearchId.SUBTITLE_TIMEOUT, SettingsSearchSection.ADVANCED,
        listOf(R.string.subtitle_timeout_title, R.string.subtitle_timeout_description),
        group = SettingsSearchGroup.INTEGRATIONS,
    ),
    // Maintainer decision 2026-09-30: the automation-and-offload hub.
    // Static union vocabulary: the hub's strings plus all three
    // children's (the remote config card rides the offload child).
    SettingsSearchCard(
        SettingsSearchId.AUTOMATION_HUB, SettingsSearchSection.ADVANCED,
        listOf(
            R.string.automation_settings_title, R.string.automation_settings_summary,
            R.string.external_automation_title, R.string.external_automation_description,
            R.string.automation_guide_title, R.string.automation_guide_description,
            R.string.remote_offload_title, R.string.remote_offload_description),
        group = SettingsSearchGroup.INTEGRATIONS,
    ),
    SettingsSearchCard(
        SettingsSearchId.VOICE_NOTE_IDENTITY, SettingsSearchSection.ADVANCED,
        listOf(R.string.voice_note_identity_title, R.string.voice_note_identity_description),
        group = SettingsSearchGroup.INTEGRATIONS,
    ),
    SettingsSearchCard(
        SettingsSearchId.SHARE_SHORTCUT_ICONS, SettingsSearchSection.ADVANCED,
        listOf(R.string.share_shortcut_icons_title, R.string.share_shortcut_icons_description),
        group = SettingsSearchGroup.INTEGRATIONS,
    ),
    // --- Feedback ---
    // One entry for one Card: the count reports cards, and the Feedback
    // rows do not filter individually (the section-level visibility check
    // consumes this entry; there is no per-row gate in FeedbackSection).
    // The replay-tour button and the privacy note are part of the same
    // card, so their strings match it too.
    SettingsSearchCard(
        SettingsSearchId.FEEDBACK, SettingsSearchSection.FEEDBACK,
        listOf(
            R.string.settings_feedback_send_title, R.string.settings_feedback_version_title,
            R.string.settings_feedback_license_title, R.string.settings_feedback_source_title,
            R.string.settings_feedback_translation_title,
            R.string.settings_replay_tour, R.string.settings_feedback_privacy_note,
            R.string.faq_section_title, R.string.faq_card_calls_title,
            R.string.faq_card_models_title, R.string.faq_card_queue_title,
            R.string.faq_card_results_title, R.string.faq_card_trouble_title,
            R.string.faq_full_link,
        ),
    ),
)

/**
 * TASK-625/275: ONE focused Settings row: its captured layout position, its
 * border-flash flag, and the flash itself (converge on the row, highlight,
 * decay after 2.5s). Two instances exist (memory protection via the
 * notification action; the TASK-274 toggle via TASK-275's Automation card);
 * the timing and color contracts live here so they cannot drift between
 * rows. The row captures its Y via [capture] from onGloballyPositioned and
 * draws its border with [highlightColor]; a trigger site clears any live
 * search query (it keeps rows out of composition) and calls [flashIn] on a
 * scope that carries the composition's frame clock.
 */
internal class SettingsRowFocus {
    var rowY by mutableStateOf<Int?>(null)
        private set
    var highlighted by mutableStateOf(false)
        private set

    fun capture(y: Int) {
        rowY = y
    }

    /** The row's border color: primary while flashing, transparent idle. */
    @Composable
    fun highlightColor(label: String): Color = animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primary else Color.Transparent,
        tween(durationMillis = 400),
        label = label,
    ).value

    /** Converge the scroll on the row, flash its border, decay after 2.5s. */
    fun flashIn(
        scope: CoroutineScope,
        scrollState: ScrollState,
        scrollContentRootY: () -> Int,
    ) {
        scope.launch {
            // The row's captured position is re-derived from the LIVE rowY
            // each pass (rowY - contentRoot is invariant to scrolling and
            // tracks only real layout changes) because the expand and the
            // async cards above the target row (battery, share targets) keep
            // shifting it; the root Y is read live too, so an inset change
            // mid-converge (gesture-nav hide, split-screen) cannot leave the
            // flash settling on a stale offset (review F2). Reaching a
            // CLAMPED cap is not convergence while the list can still grow:
            // an in-flight expand keeps maxValue small, so clamped passes
            // count as settled only once maxValue has stopped moving.
            var attempts = 0
            var settled = false
            var lastMax = -1
            var stableMaxFrames = 0
            while (attempts < 48 && !settled) {
                val target = rowY
                if (target == null) {
                    withFrameNanos { }
                } else {
                    val wanted = maxOf(0, target - scrollContentRootY() - 32)
                    val cap = minOf(wanted, scrollState.maxValue)
                    val clamped = wanted > scrollState.maxValue
                    if (clamped) {
                        stableMaxFrames =
                            if (scrollState.maxValue == lastMax) stableMaxFrames + 1 else 0
                        lastMax = scrollState.maxValue
                        scrollState.animateScrollTo(cap)
                        settled = stableMaxFrames >= 3
                        if (!settled) withFrameNanos { }
                    } else if (kotlin.math.abs(scrollState.value - cap) <= 4) {
                        settled = true
                    } else {
                        scrollState.animateScrollTo(cap)
                        withFrameNanos { }
                    }
                }
                attempts++
            }
            highlighted = true
            delay(2_500)
            highlighted = false
        }
    }
}

/**
 * TASK-275: the in-app automation wizard card (Settings > Advanced). The
 * broadcast API (PROCESS_REQUEST + PRELOAD_MODEL, docs/TASKER_GUIDE.md) is
 * the shipped full-auto path; this card is its only in-app surface: the
 * consent state with a deep-link to the TASK-274 toggle row, the staging
 * constraint in one line, a copyable adb command with the runtime package
 * filled in, and the full guide link. The copy follows HistoryTab
 * (ClipboardWriter + ToastCompat, TASK-688); the command carries no
 * transcript, so the TASK-650 signature does not apply to it.
 */
@Composable
internal fun AutomationGuideCard(
    title: String,
    description: String,
    enabled: Boolean,
    onShowToggle: () -> Unit,
) {
    val context = LocalContext.current
    SectionCard(
        icon = Icons.Default.Bolt,
        title = title,
        description = description,
    ) {
        Text(
            text = stringResource(
                if (enabled) R.string.automation_guide_status_on
                else R.string.automation_guide_status_off),
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!enabled) {
            TextButton(onClick = onShowToggle) {
                Text(stringResource(R.string.automation_guide_show_toggle))
            }
        }
        Text(
            text = stringResource(R.string.automation_guide_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Long localized labels do not fit side-by-side halves: stacked
        // full-width buttons keep every locale on one line (device trial
        // 2026-09-27, TASK-275: equal halves wrapped the it/de labels into
        // 2-3 stacked lines, and a single weight collapsed the other button
        // to a sliver).
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    ClipboardWriter.copy(
                        context,
                        context.getString(R.string.automation_guide_title),
                        AutomationBroadcastSnippet.adbTextRequest(context.packageName))
                    ToastCompat.show(context, context.getString(R.string.copied_to_clipboard))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.automation_guide_copy))
            }
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(AutomationBroadcastSnippet.TASKER_GUIDE_URL)))
                    }.onFailure {
                        // A de-Googled fdroid install can have no https
                        // viewer: the tap must not be a silent no-op.
                        ToastCompat.show(
                            context, context.getString(R.string.automation_guide_no_browser))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.automation_guide_open))
            }
        }
    }
}

/**
 * TASK-681: the LAN-offload config card: endpoint, API key (password
 * style), the pass-through model name, Save, and the connection probe.
 * Test uses the TYPED field values, so a configuration can be verified
 * before it is saved.
 */
@Composable
internal fun RemoteOmnivoiceConfigCard(viewModel: SettingsViewModel) {
    val endpoint by viewModel.remoteEndpointInput.collectAsState()
    val apiKey by viewModel.remoteApiKeyInput.collectAsState()
    val model by viewModel.remoteModelInput.collectAsState()
    val testing by viewModel.remoteConnectionTesting.collectAsState()
    val testResult by viewModel.remoteConnectionTest.collectAsState()
    var keyVisible by remember { mutableStateOf(false) }
    val clipboardManager = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = endpoint,
                onValueChange = { viewModel.remoteEndpointInput.value = it },
                label = { Text(stringResource(R.string.remote_offload_endpoint_label)) },
                placeholder = { Text(stringResource(R.string.remote_offload_endpoint_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TokenInputField(
                value = apiKey,
                onValueChange = { viewModel.remoteApiKeyInput.value = it },
                tokenPasswordVisible = keyVisible,
                onPasswordVisibilityToggle = { keyVisible = !keyVisible },
                clipboardManager = clipboardManager,
                modifier = Modifier.fillMaxWidth(),
                labelRes = R.string.remote_offload_key_label,
            )
            OutlinedTextField(
                value = model,
                onValueChange = { viewModel.remoteModelInput.value = it },
                label = { Text(stringResource(R.string.remote_offload_model_label)) },
                supportingText = { Text(stringResource(R.string.remote_offload_model_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { viewModel.saveRemoteOmnivoiceConfig() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.remote_offload_save))
                }
                Button(
                    onClick = { viewModel.testRemoteConnection() },
                    enabled = endpoint.isNotBlank() && !testing,
                    modifier = Modifier.weight(1f),
                ) {
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(
                        if (testing) R.string.remote_offload_test_running
                        else R.string.remote_offload_test))
                }
            }
            testResult?.let { result ->
                val success = result is RemoteOmnivoiceBackend.ConnectionTestResult.Success
                Text(
                    text = remoteTestReasonText(result),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (success) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** TASK-681: the probe verdict in the user's language; codes stay as-is. */
@Composable
private fun remoteTestReasonText(
    result: com.antivocale.app.transcription.RemoteOmnivoiceBackend.ConnectionTestResult,
): String = when (result) {
    is RemoteOmnivoiceBackend.ConnectionTestResult.Success ->
        stringResource(R.string.remote_offload_test_success)
    is RemoteOmnivoiceBackend.ConnectionTestResult.Unreachable ->
        stringResource(R.string.remote_test_reason_unreachable)
    is RemoteOmnivoiceBackend.ConnectionTestResult.AuthRejected ->
        stringResource(R.string.remote_test_reason_auth)
    is RemoteOmnivoiceBackend.ConnectionTestResult.Timeout ->
        stringResource(
            R.string.remote_test_reason_timeout,
            (RemoteOmnivoiceBackend.TEST_BUDGET_MS / 1000L).toInt())
    is RemoteOmnivoiceBackend.ConnectionTestResult.ServerError ->
        stringResource(R.string.remote_test_reason_server, result.statusCode)
}

/** TASK-276: pref value -> localized label, one fallback for unknown values. */
@Composable
private fun punctuationModeLabel(pref: String): String = when (pref) {
    PunctuationPolicy.PREF_OFF -> stringResource(R.string.punctuation_mode_off)
    PunctuationPolicy.PREF_ALWAYS -> stringResource(R.string.punctuation_mode_always)
    // TASK-666: the bounded-cleanup mode (fenced prompt, strict validation).
    PunctuationPolicy.PREF_CONSERVATIVE -> stringResource(R.string.punctuation_mode_conservative)
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

/**
 * TASK-490: per-share-shortcut custom icons. Each share-capable backend
 * (registry order) gets a gallery pick; the image is copied app-side at
 * pick time and masked into the adaptive canvas at build time, with the
 * generated family icon as the fallback. The rows re-derive on entry and
 * after every pick/reset, so the buttons always reflect the stored state.
 */
@Composable
internal fun ShareShortcutIconsCard(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val backends by viewModel.shareIconBackends.collectAsState()
    val advancedSharingEnabled by viewModel.advancedSharingEnabled.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshShareIconBackends() }
    // Saveable (review): the picker can outlive a configuration change; a
    // plain remember would silently drop the pick on recreation.
    var pendingBackendId by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val backendId = pendingBackendId
        pendingBackendId = null
        if (uri != null && backendId != null) {
            viewModel.onShortcutIconPicked(backendId, uri) { saved ->
                com.antivocale.app.util.ToastCompat.show(
                    context,
                    context.getString(
                        if (saved) R.string.shortcut_icon_saved else R.string.shortcut_icon_error),
                )
            }
        }
    }
    SectionCard(
        icon = Icons.Default.Image,
        title = stringResource(R.string.share_shortcut_icons_title),
        description = stringResource(R.string.share_shortcut_icons_description),
    ) {
        // The sibling Share targets card gates its backend rows behind the
        // toggle the same way: with sharing off there are no dynamic
        // shortcuts to icon.
        if (advancedSharingEnabled) backends.forEach { backend ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(backend.label, style = MaterialTheme.typography.bodyLarge)
                    if (backend.hasCustomIcon) {
                        Text(
                            text = stringResource(R.string.shortcut_icon_custom),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                TextButton(onClick = {
                    pendingBackendId = backend.backendId
                    picker.launch(PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Text(stringResource(R.string.shortcut_icon_choose))
                }
                if (backend.hasCustomIcon) {
                    TextButton(onClick = { viewModel.clearShortcutIcon(backend.backendId) }) {
                        Text(stringResource(R.string.shortcut_icon_reset))
                    }
                }
            }
        }
    }
}
