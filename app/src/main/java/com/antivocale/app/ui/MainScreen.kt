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
import com.antivocale.app.ui.tabs.LogsTab
import com.antivocale.app.ui.tabs.ModelTab
import com.antivocale.app.ui.tabs.SettingsTab
import com.antivocale.app.ui.viewmodel.LogsViewModel
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

    // Logs tab is first since it's the primary use case (viewing transcription history)
    val tabs = listOf(
        TabItem(R.string.logs_tab, Icons.Default.History) { LogsTab(highlightTaskId = highlightTaskId) },
        TabItem(R.string.model_tab, Icons.Default.Storage) { ModelTab(onNavigateToSettings = { navigateToTab(2) }, navRequest = modelsNavRequest, onNavConsumed = { modelsNavRequest = null }) },
        TabItem(R.string.settings_tab, Icons.Default.Settings) { SettingsTab(onNavigateToModelTab = { navigateToTab(1) }, navRequest = settingsNavRequest, onNavConsumed = { settingsNavRequest = null }) }
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name)) },
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
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(stringResource(tab.titleResId)) },
                    icon = { Icon(tab.icon, contentDescription = stringResource(tab.titleResId)) }
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

data class TabItem(
    val titleResId: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val content: @Composable () -> Unit
)
