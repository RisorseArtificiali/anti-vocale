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
import com.antivocale.app.di.AppContainer
import com.antivocale.app.ui.components.PipTranscriptionView
import com.antivocale.app.ui.tabs.LogsTab
import com.antivocale.app.ui.tabs.ModelTab
import com.antivocale.app.ui.tabs.SettingsTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    startOnModelTab: Boolean = false,
    isInPipMode: Boolean = false
) {
    // PiP mode: show compact transcription view
    if (isInPipMode) {
        PipTranscriptionView()
        return
    }

    var selectedTabIndex by remember { mutableIntStateOf(if (startOnModelTab) 1 else 0) }
    val viewModel = AppContainer.logsViewModel
    val highlightTaskId by viewModel.highlightTaskId.collectAsState()

    // Force Logs tab when a highlight signal arrives
    LaunchedEffect(highlightTaskId) {
        if (highlightTaskId != null) {
            selectedTabIndex = 0
        }
    }

    // Navigation callback to switch tabs
    fun navigateToTab(index: Int) {
        selectedTabIndex = index
    }

    // Logs tab is first since it's the primary use case (viewing transcription history)
    val tabs = listOf(
        TabItem(R.string.logs_tab, Icons.Default.History) { LogsTab(highlightTaskId = highlightTaskId) },
        TabItem(R.string.model_tab, Icons.Default.Storage) { ModelTab(onNavigateToSettings = { navigateToTab(2) }) },
        TabItem(R.string.settings_tab, Icons.Default.Settings) { SettingsTab(onNavigateToModelTab = { navigateToTab(1) }) }
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
