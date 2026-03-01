package com.localai.bridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.localai.bridge.ui.tabs.LogsTab
import com.localai.bridge.ui.tabs.ModelTab
import com.localai.bridge.ui.tabs.SettingsTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    // Navigation callback to switch tabs
    fun navigateToTab(index: Int) {
        selectedTabIndex = index
    }

    val tabs = listOf(
        TabItem("Model", Icons.Default.Storage) { ModelTab(onNavigateToSettings = { navigateToTab(2) }) },
        TabItem("Logs", Icons.Default.History) { LogsTab() },
        TabItem("Settings", Icons.Default.Settings) { SettingsTab(onNavigateToModelTab = { navigateToTab(0) }) }
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("LocalAI Bridge") },
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
                    text = { Text(tab.title) },
                    icon = { Icon(tab.icon, contentDescription = tab.title) }
                )
            }
        }

        tabs[selectedTabIndex].content()
    }
}

data class TabItem(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val content: @Composable () -> Unit
)
