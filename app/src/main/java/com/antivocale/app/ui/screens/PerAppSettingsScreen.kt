package com.antivocale.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.antivocale.app.data.AppNotificationPreferences
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.ui.components.AppPreferenceListItem
import com.antivocale.app.ui.components.AppPreferencePanel
import com.antivocale.app.ui.dialogs.AddAppPreferenceDialog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/**
 * Screen for managing per-app notification preferences.
 *
 * Shows a list of detected apps with their current settings.
 * Each app can be tapped to open a detailed settings panel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppSettingsScreen(
    preferencesManager: PerAppPreferencesManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // List of known apps (can be extended to detect from shared history)
    val knownApps = remember {
        listOf(
            PerAppPreferencesManager.WHATSAPP to "WhatsApp",
            PerAppPreferencesManager.TELEGRAM to "Telegram",
            PerAppPreferencesManager.SIGNAL to "Signal"
        )
    }

    // State for preferences
    val preferencesState = remember { mutableStateMapOf<String, AppNotificationPreferences>() }

    // State for manually added apps
    val manualApps = remember { mutableStateMapOf<String, String>() }

    // State for currently selected app
    var selectedApp by remember { mutableStateOf<Pair<String, String>?>(null) }

    // State for add app dialog
    var showAddAppDialog by remember { mutableStateOf(false) }

    // Track if initial load is complete
    var isLoading by remember { mutableStateOf(true) }

    // Load preferences on first composition
    LaunchedEffect(Unit) {
        // Load initial preferences for all known apps
        val jobs = knownApps.map { (packageName, _) ->
            scope.async {
                val prefs = preferencesManager.getCurrentPreferences(packageName)
                preferencesState[packageName] = prefs
            }
        }

        // Wait for all to load
        jobs.awaitAll()
        isLoading = false

        // Now set up continuous observation for updates
        knownApps.forEach { (packageName, _) ->
            launch {
                preferencesManager.getPreferencesForPackage(packageName).collect { prefs ->
                    preferencesState[packageName] = prefs
                }
            }
        }
    }

    // Selected app preferences
    val selectedPrefs = selectedApp?.let { (packageName, _) ->
        preferencesState[packageName]
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.per_app_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddAppDialog = true }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add app")
            }
        }
    ) { paddingValues ->
        if (isLoading) {
            // Loading state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.per_app_settings_loading))
                }
            }
        } else {
            // Preferences loaded
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.per_app_settings_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = stringResource(R.string.per_app_settings_info),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                items(knownApps) { (packageName, appName) ->
                    val prefs = preferencesState[packageName]
                    if (prefs != null) {
                        val settingsSummary = buildString {
                            if (prefs.autoCopy) append(stringResource(R.string.per_app_settings_auto_copy_on))
                            else append(stringResource(R.string.per_app_settings_auto_copy_off))

                            if (prefs.showShareAction) {
                                if (isNotEmpty()) append(", ")
                                append(stringResource(R.string.per_app_settings_share_on))
                            } else {
                                if (isNotEmpty()) append(", ")
                                append(stringResource(R.string.per_app_settings_share_off))
                            }

                            if (prefs.notificationSound != "default") {
                                if (isNotEmpty()) append(", ")
                                append(stringResource(R.string.per_app_settings_sound, prefs.notificationSound))
                            }
                        }

                        AppPreferenceListItem(
                            packageName = packageName,
                            appName = appName,
                            settingsSummary = settingsSummary.ifEmpty { stringResource(R.string.per_app_settings_using_defaults) },
                            onClick = { selectedApp = packageName to appName }
                        )
                    }
                }

                // Manually added apps
                if (manualApps.isNotEmpty()) {
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = stringResource(R.string.per_app_settings_manually_added),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    items(manualApps.toList()) { (packageName, appName) ->
                        val prefs = preferencesState[packageName]
                        if (prefs != null) {
                            val settingsSummary = buildString {
                                if (prefs.autoCopy) append(stringResource(R.string.per_app_settings_auto_copy_on))
                                else append(stringResource(R.string.per_app_settings_auto_copy_off))

                                if (prefs.showShareAction) {
                                    if (isNotEmpty()) append(", ")
                                    append(stringResource(R.string.per_app_settings_share_on))
                                } else {
                                    if (isNotEmpty()) append(", ")
                                    append(stringResource(R.string.per_app_settings_share_off))
                                }

                                if (prefs.notificationSound != "default") {
                                    if (isNotEmpty()) append(", ")
                                    append(stringResource(R.string.per_app_settings_sound, prefs.notificationSound))
                                }
                            }

                            AppPreferenceListItem(
                                packageName = packageName,
                                appName = appName,
                                settingsSummary = settingsSummary.ifEmpty { stringResource(R.string.per_app_settings_using_defaults) },
                                onClick = { selectedApp = packageName to appName }
                            )
                        }
                    }
                }
            }
        }

        // Settings panel for selected app
        if (selectedApp != null && selectedPrefs != null) {
            val (packageName, appName) = selectedApp!!

            AppPreferencePanel(
                packageName = packageName,
                appName = appName,
                autoCopy = selectedPrefs.autoCopy,
                showShareAction = selectedPrefs.showShareAction,
                quickShareBack = selectedPrefs.quickShareBack,
                notificationSound = selectedPrefs.notificationSound,
                onAutoCopyChanged = { enabled ->
                    scope.launch {
                        preferencesManager.updatePreferencesForPackage(packageName) {
                            copy(autoCopy = enabled)
                        }
                    }
                },
                onShowShareActionChanged = { enabled ->
                    scope.launch {
                        preferencesManager.updatePreferencesForPackage(packageName) {
                            copy(showShareAction = enabled)
                        }
                    }
                },
                onQuickShareBackChanged = { enabled ->
                    scope.launch {
                        preferencesManager.updatePreferencesForPackage(packageName) {
                            copy(quickShareBack = enabled)
                        }
                    }
                },
                onNotificationSoundChanged = { sound ->
                    scope.launch {
                        preferencesManager.updatePreferencesForPackage(packageName) {
                            copy(notificationSound = sound)
                        }
                    }
                },
                onDismiss = { selectedApp = null }
            )
        }

        // Add app dialog
        if (showAddAppDialog) {
            AddAppPreferenceDialog(
                onDismiss = { showAddAppDialog = false },
                onAppSelected = { packageName, appName ->
                    // Add to manual apps
                    manualApps[packageName] = appName

                    // Load initial preference for this app and observe for updates
                    scope.launch {
                        val prefs = preferencesManager.getCurrentPreferences(packageName)
                        preferencesState[packageName] = prefs

                        // Open preference panel for the newly added app
                        selectedApp = packageName to appName

                        // Then observe for updates
                        preferencesManager.getPreferencesForPackage(packageName).collect { updatedPrefs ->
                            preferencesState[packageName] = updatedPrefs
                        }
                    }
                }
            )
        }
    }
}
