package com.antivocale.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.antivocale.app.R
import com.antivocale.app.ui.components.PipTranscriptionView
import com.antivocale.app.ui.onboarding.TourStep
import com.antivocale.app.ui.onboarding.tourCardModifier
import com.antivocale.app.ui.onboarding.TourOverlayCard
import com.antivocale.app.ui.onboarding.tourRevealable
import com.antivocale.app.ui.tabs.LogsTab
import com.antivocale.app.ui.tabs.ModelTab
import com.antivocale.app.ui.tabs.SettingsTab
import com.antivocale.app.ui.viewmodel.LogsViewModel
import com.antivocale.app.ui.viewmodel.SettingsViewModel
import com.svenjacobs.reveal.Reveal
import com.svenjacobs.reveal.effect.dim.DimRevealOverlayEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.svenjacobs.reveal.RevealCanvas
import com.svenjacobs.reveal.revealable
import com.svenjacobs.reveal.rememberRevealCanvasState
import com.svenjacobs.reveal.rememberRevealState
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    startOnModelTab: Boolean = false,
    navigateToModel: Boolean = false,
    isInPipMode: Boolean = false
) {
    // PiP mode: show compact transcription view
    if (isInPipMode) {
        PipTranscriptionView()
        return
    }

    var selectedTabIndex by remember {
        mutableIntStateOf(
            if (startOnModelTab) AppNavigation.TAB_INDEX_MODELS else AppNavigation.TAB_INDEX_HISTORY)
    }
    val viewModel: LogsViewModel = hiltViewModel()
    val highlightTaskId by viewModel.highlightTaskId.collectAsState()

    // TASK-491: the first-install welcome tour (reveal coach marks over the
    // real UI). The preference is never version-keyed, so an update never
    // replays it. The overlay intercepts all taps while visible: a
    // transcription arriving mid-tour dismisses it (AC#3).
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val onboardingCompleted by settingsViewModel.onboardingCompleted.collectAsState()
    val revealCanvasState = rememberRevealCanvasState()
    val revealState = rememberRevealState()
    var tourStep by remember { mutableStateOf<TourStep?>(null) }

    fun finishTour() {
        tourStep = null
        settingsViewModel.setOnboardingCompleted()
    }

    LaunchedEffect(onboardingCompleted) {
        if (!onboardingCompleted && tourStep == null) tourStep = TourStep.Welcome
    }

    // A transcription ARRIVING while the tour is up dismisses it (AC#3):
    // arrival-based, not time-based. Snapshot the active entry's taskId at
    // tour start; dismiss only when a DIFFERENT taskId appears.
    val activeEntry by viewModel.activeTranscription.collectAsState()
    var tourStartTaskId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tourStep) {
        if (tourStep != null) tourStartTaskId = activeEntry?.taskId
    }
    LaunchedEffect(activeEntry?.taskId, tourStep) {
        val entry = activeEntry
        if (tourStep != null && entry != null && entry.taskId != tourStartTaskId) {
            finishTour()
        }
    }

    // Drive the reveal: each step's key revealed on entry; steps that
    // target a specific tab switch to it first so the revealable exists.
    LaunchedEffect(tourStep) {
        when (val step = tourStep) {
            null -> revealState.hide()
            TourStep.Welcome -> revealState.reveal(TourStep.Welcome.key)
            TourStep.ModelsTab -> {
                selectedTabIndex = AppNavigation.TAB_INDEX_MODELS
                revealState.reveal(TourStep.ModelsTab.key)
            }
            TourStep.HistoryTab, TourStep.BrowseFab -> {
                selectedTabIndex = AppNavigation.TAB_INDEX_HISTORY
                revealState.reveal(step.key)
            }
        }
    }

    // Force Logs tab when a highlight signal arrives
    LaunchedEffect(highlightTaskId) {
        if (highlightTaskId != null) {
            selectedTabIndex = AppNavigation.TAB_INDEX_HISTORY
        }
    }

    // Switch to Model tab when a late navigation signal arrives
    // (e.g. user tapped "Go to Model tab" in the native-crash dialog).
    LaunchedEffect(navigateToModel) {
        if (navigateToModel) {
            selectedTabIndex = AppNavigation.TAB_INDEX_MODELS
        }
    }

    // TASK-486: the debug-SPI navigation signal (consumed exactly once; the
    // settings-scoped remainder is handed to the Settings tab).
    val testNav by TestNavigation.pending.collectAsState()
    var settingsNavRequest by remember { mutableStateOf<AppNavigation.NavRequest?>(null) }
    var modelsNavRequest by remember { mutableStateOf<AppNavigation.NavRequest?>(null) }

    // One routing rule for settings destinations, shared by the TEST_SPI
    // effect and production callers (the capped-transcript auto-save hint):
    // switch to the Settings tab and hand the destination to SettingsTab
    // through the consume-once NavRequest, never through the pending token
    // (its write side is debug-only by contract, AppNavigation KDoc). The
    // tab index DERIVES from TAB_KEYS (checked at load, AppNavigation), like
    // every other tab-switch site in this file. Accepted race (debug-only):
    // a TEST_SPI settings destination landing between a hint tap and
    // SettingsTab's composition overwrites the single in-flight NavRequest slot.
    // TASK-617 F9: navigateToTab is the switch site the callback-based
    // routings delegate to; the LaunchedEffect handlers below still assign
    // selectedTabIndex directly (idiomatic there, same constants). No
    // remember wrappers: strong skipping (on by default in the Compose
    // compiler we ship) already memoizes the capturing lambdas.
    fun navigateToTab(index: Int) {
        selectedTabIndex = index
    }

    fun openSettings(destination: AppNavigation.Destination) {
        navigateToTab(AppNavigation.TAB_INDEX_SETTINGS)
        settingsNavRequest = AppNavigation.NavRequest.next(destination)
    }
    LaunchedEffect(testNav) {
        val dest = testNav ?: return@LaunchedEffect
        TestNavigation.pending.value = null
        when (val parsed = AppNavigation.parse(dest)) {
            is AppNavigation.Destination.Tab -> selectedTabIndex = parsed.index
            is AppNavigation.Destination.ModelTarget -> {
                selectedTabIndex = AppNavigation.TAB_INDEX_MODELS
                modelsNavRequest = AppNavigation.NavRequest.next(parsed)
            }
            is AppNavigation.Destination.SettingsSubPage,
            is AppNavigation.Destination.SettingsSection -> openSettings(parsed)
            null -> Unit
        }
    }

    // Logs tab is first since it is the primary use case (viewing transcription history)
    val tabs = listOf(
        // GH #94 / TASK-548(B): the capped-transcript auto-save hint
        // navigates to the export sub-page (TASK-543), where the folder
        // and format cards live.
        TabItem(R.string.logs_tab, Icons.Default.History) {
            LogsTab(
                highlightTaskId = highlightTaskId,
                tourRevealState = revealState,
                // TASK-617: the chip's destination; rationale on the
                // LogsTab parameter it pairs with.
                onOpenLanguageSetting = {
                    openSettings(AppNavigation.Destination.SettingsSection("transcription"))
                },
                onNavigateToSettings = {
                    openSettings(
                        AppNavigation.Destination.SettingsSubPage(
                            AppNavigation.SUBPAGE_KEY_EXPORT
                        )
                    )
                },
            )
        },
        TabItem(R.string.model_tab, Icons.Default.Storage) { ModelTab(onNavigateToSettings = { navigateToTab(AppNavigation.TAB_INDEX_SETTINGS) }, navRequest = modelsNavRequest, onNavConsumed = { modelsNavRequest = null }) },
        TabItem(R.string.settings_tab, Icons.Default.Settings) { SettingsTab(onNavigateToModelTab = { navigateToTab(AppNavigation.TAB_INDEX_MODELS) }, navRequest = settingsNavRequest, onNavConsumed = { settingsNavRequest = null }) }
    )

    RevealCanvas(
        modifier = Modifier.fillMaxSize(),
        revealCanvasState = revealCanvasState,
    ) {
        Reveal(
            revealCanvasState = revealCanvasState,
            revealState = revealState,
            // Lighter than the default 80% black: in dark mode that
            // makes the underlying app almost invisible (maintainer
            // feedback 2026-09-12).
            overlayEffect = DimRevealOverlayEffect(
                color = Color.Black.copy(alpha = 0.55f),
            ),
            onOverlayClick = {
                // Overlay tap advances to the next step: a dead-end dismissal
                // (or worse, finishing + persisting) on an accidental tap
                // would be the most common way to lose the tour.
                tourStep?.let { step ->
                    val next = TourStep.entries.getOrNull(
                        TourStep.entries.indexOf(step) + 1
                    )
                    tourStep = next
                }
                if (tourStep == null) finishTour()
            },
            overlayContent = { key ->
                val step = TourStep.entries.firstOrNull { it.key == key }
                if (step != null) {
                    val isLast = step == TourStep.entries.last()
                    val nextStep = TourStep.entries.getOrNull(TourStep.entries.indexOf(step) + 1)
                    TourOverlayCard(
                        step = step,
                        isLast = isLast,
                        // TASK-508: the alignment modifier MUST be passed. It was
                        // dropped during the TASK-491 debug round and the card
                        // fell back to the library's default top-start placement,
                        // covering the tab row the tour teaches the user to tap.
                        modifier = tourCardModifier(this, step),
                        onNext = {
                            if (nextStep != null) tourStep = nextStep else finishTour()
                        },
                        onSkip = { finishTour() },
                    )
                }
            },
        ) {
            // TASK-565: the removed TopAppBar provided the status-bar inset; without
                // it the TabRow sits under the status-bar icons.
                Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                // TASK-565 (maintainer): the app-name bar is gone. The
                // launcher, recents, and Settings > About carry the name;
                // the TabRow says where you are. The freed space is where
                // the History search field and the Models filter sit.

                // TASK-508: the Welcome cutout is the WHOLE tab row, not the
                // app title. The title's cutout sits above the TabRow, so a
                // card placed "under" it landed on top of the tabs (verified
                // on device: card y440-1100 covered the tab row y505-575).
                // Revealing the tab row highlights all three tabs - what the
                // intro step actually introduces - and Bottom placement then
                // puts the card safely below them.
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier.tourRevealable(TourStep.Welcome.key, revealState),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val tourKey = when (index) {
                            AppNavigation.TAB_INDEX_HISTORY -> TourStep.HistoryTab.key
                            AppNavigation.TAB_INDEX_MODELS -> TourStep.ModelsTab.key
                            else -> null
                        }
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(stringResource(tab.titleResId)) },
                            icon = { Icon(tab.icon, contentDescription = stringResource(tab.titleResId)) },
                            modifier = tourKey?.let { Modifier.tourRevealable(it, revealState) } ?: Modifier,
                        )
                    }
                }

                Crossfade(
                    targetState = selectedTabIndex,
                    animationSpec = tween(durationMillis = 150)
                ) { index ->
                    tabs[index].content()
                }
            }
        }
    }
}

data class TabItem(
    val titleResId: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val content: @Composable () -> Unit
)
