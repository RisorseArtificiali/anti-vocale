package com.antivocale.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
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
import com.antivocale.app.ui.onboarding.TourOverlayCard
import com.antivocale.app.ui.onboarding.tourCardModifier
import com.antivocale.app.ui.tabs.LogsTab
import com.antivocale.app.ui.tabs.ModelTab
import com.antivocale.app.ui.tabs.SettingsTab
import com.antivocale.app.ui.viewmodel.LogsViewModel
import com.antivocale.app.ui.viewmodel.SettingsViewModel
import com.svenjacobs.reveal.Reveal
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

    var selectedTabIndex by remember { mutableIntStateOf(if (startOnModelTab) 1 else 0) }
    val viewModel: LogsViewModel = hiltViewModel()
    val highlightTaskId by viewModel.highlightTaskId.collectAsState()

    // TASK-491: the first-install welcome tour (reveal coach marks over the
    // real UI). The preference is never version-keyed, so an update never
    // replays it; a transcription starting while the tour is up dismisses
    // it (AC#3) - the History list underneath keeps working regardless,
    // the overlay is visual only.
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

    // A transcription ARRIVING while the tour is up dismisses it (AC#3).
    // Transition-gated: only an entry that appears AFTER the tour started
    // kills it, so a stale PROCESSING/QUEUED row from a previous session
    // does not flash-kill the replay row or a fresh-install restore.
    val activeEntry by viewModel.activeTranscription.collectAsState()
    var tourStartTimeMs by remember { mutableStateOf(0L) }
    LaunchedEffect(tourStep) {
        if (tourStep != null && tourStartTimeMs == 0L) {
            tourStartTimeMs = System.currentTimeMillis()
        } else if (tourStep == null) {
            tourStartTimeMs = 0L
        }
    }
    LaunchedEffect(activeEntry?.taskId, tourStep) {
        val entry = activeEntry ?: return@LaunchedEffect
        if (tourStep != null && tourStartTimeMs > 0 &&
            System.currentTimeMillis() - tourStartTimeMs > 1000
        ) {
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
                selectedTabIndex = 1
                revealState.reveal(TourStep.ModelsTab.key)
            }
            TourStep.HistoryTab, TourStep.BrowseFab -> {
                selectedTabIndex = 0
                revealState.reveal(step.key)
            }
        }
    }

    // Force Logs tab when a highlight signal arrives
    LaunchedEffect(highlightTaskId) {
        if (highlightTaskId != null) {
            selectedTabIndex = 0
        }
    }

    // Switch to Model tab when a late navigation signal arrives
    // (e.g. user tapped "Go to Model tab" in the native-crash dialog).
    LaunchedEffect(navigateToModel) {
        if (navigateToModel) {
            selectedTabIndex = 1
        }
    }

    // TASK-486: the debug-SPI navigation signal (consumed exactly once; the
    // settings-scoped remainder is handed to the Settings tab).
    val testNav by TestNavigation.pending.collectAsState()
    var settingsNavRequest by remember { mutableStateOf<TestNavigation.NavRequest?>(null) }
    var modelsNavRequest by remember { mutableStateOf<TestNavigation.NavRequest?>(null) }
    LaunchedEffect(testNav) {
        val dest = testNav ?: return@LaunchedEffect
        TestNavigation.pending.value = null
        when (val parsed = TestNavigation.parse(dest)) {
            is TestNavigation.Destination.Tab -> selectedTabIndex = parsed.index
            is TestNavigation.Destination.ModelTarget -> {
                selectedTabIndex = 1
                modelsNavRequest = TestNavigation.NavRequest.next(parsed)
            }
            is TestNavigation.Destination.SettingsSubPage,
            is TestNavigation.Destination.SettingsSection -> {
                selectedTabIndex = 2
                settingsNavRequest = TestNavigation.NavRequest.next(parsed)
            }
            null -> Unit
        }
    }

    // Navigation callback to switch tabs
    fun navigateToTab(index: Int) {
        selectedTabIndex = index
    }

    // Logs tab is first since it is the primary use case (viewing transcription history)
    val tabs = listOf(
        TabItem(R.string.logs_tab, Icons.Default.History) { LogsTab(highlightTaskId = highlightTaskId, tourRevealState = revealState) },
        TabItem(R.string.model_tab, Icons.Default.Storage) { ModelTab(onNavigateToSettings = { navigateToTab(2) }, navRequest = modelsNavRequest, onNavConsumed = { modelsNavRequest = null }) },
        TabItem(R.string.settings_tab, Icons.Default.Settings) { SettingsTab(onNavigateToModelTab = { navigateToTab(1) }, navRequest = settingsNavRequest, onNavConsumed = { settingsNavRequest = null }) }
    )

    RevealCanvas(
        modifier = Modifier.fillMaxSize(),
        revealCanvasState = revealCanvasState,
    ) {
        Reveal(
            revealCanvasState = revealCanvasState,
            revealState = revealState,
            onOverlayClick = { finishTour() },
            overlayContent = { key ->
                val step = TourStep.entries.firstOrNull { it.key == key }
                if (step != null) {
                    val isLast = step == TourStep.entries.last()
                    val nextStep = TourStep.entries.getOrNull(TourStep.entries.indexOf(step) + 1)
                    TourOverlayCard(
                        step = step,
                        isLast = isLast,
                        modifier = tourCardModifier(this, step),
                        onNext = {
                            if (nextStep != null) tourStep = nextStep else finishTour()
                        },
                        onSkip = { finishTour() },
                    )
                }
            },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.app_name),
                            modifier = Modifier.revealable(
                                key = TourStep.Welcome.key,
                                state = revealState,
                            ),
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val tourKey = when (index) {
                            0 -> TourStep.HistoryTab.key
                            1 -> TourStep.ModelsTab.key
                            else -> null
                        }
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(stringResource(tab.titleResId)) },
                            icon = { Icon(tab.icon, contentDescription = stringResource(tab.titleResId)) },
                            modifier = if (tourKey != null) {
                                Modifier.revealable(key = tourKey, state = revealState)
                            } else {
                                Modifier
                            },
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
