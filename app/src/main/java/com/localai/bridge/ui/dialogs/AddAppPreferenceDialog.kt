package com.localai.bridge.ui.dialogs

import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.localai.bridge.R
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dialog for adding manual app preferences.
 *
 * Shows a searchable list of installed apps, allowing users to
 * select any app to configure per-app notification preferences.
 *
 * @param onDismiss Callback when dialog is dismissed
 * @param onAppSelected Callback when an app is selected (package name, app name)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAppPreferenceDialog(
    onDismiss: () -> Unit,
    onAppSelected: (String, String) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Load installed apps
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .mapNotNull { appInfo ->
                        try {
                            val label = pm.getApplicationLabel(appInfo).toString()
                            val icon = appInfo.loadIcon(pm)
                            AppInfo(
                                packageName = appInfo.packageName,
                                appName = label,
                                icon = icon
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }
                    .sortedBy { it.appName.lowercase() }

                installedApps = apps
                isLoading = false
            } catch (e: Exception) {
                isLoading = false
            }
        }
    }

    // Filter apps based on search query
    val filteredApps = remember(searchQuery, installedApps) {
        if (searchQuery.isBlank()) {
            installedApps
        } else {
            installedApps.filter { app ->
                app.appName.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.per_app_settings_add_app),
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.per_app_settings_close))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.per_app_settings_search)) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    singleLine = true
                )

                HorizontalDivider()

                // App list
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (filteredApps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) stringResource(R.string.per_app_settings_no_apps) else stringResource(R.string.per_app_settings_no_matching_apps),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.height(400.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredApps) { app ->
                            AppListItem(
                                appName = app.appName,
                                packageName = app.packageName,
                                icon = app.icon,
                                onClick = {
                                    onAppSelected(app.packageName, app.appName)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

/**
 * Data class representing an installed app.
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: android.graphics.drawable.Drawable
)

/**
 * List item displaying an installed app.
 */
@Composable
private fun AppListItem(
    appName: String,
    packageName: String,
    icon: android.graphics.drawable.Drawable,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(appName) },
        supportingContent = {
            Text(
                packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            androidx.compose.foundation.Image(
                bitmap = icon.toBitmap(
                    icon.intrinsicWidth.coerceAtMost(48),
                    icon.intrinsicHeight.coerceAtMost(48)
                ).asImageBitmap(),
                contentDescription = "$appName icon",
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )

    HorizontalDivider()
}
