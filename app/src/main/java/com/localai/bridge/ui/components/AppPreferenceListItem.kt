package com.localai.bridge.ui.components

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/**
 * List item displaying an app with its current preference settings.
 *
 * Shows app icon, name, and a summary of current settings.
 * Clickable to expand the full settings panel.
 *
 * @param packageName Package name of the app
 * @param appName Display name of the app
 * @param settingsSummary Summary of current preference settings
 * @param onClick Callback when item is clicked
 */
@Composable
fun AppPreferenceListItem(
    packageName: String,
    appName: String,
    settingsSummary: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var appIcon: Drawable? by remember { mutableStateOf(null) }

    // Load app icon
    if (appIcon == null) {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            appIcon = appInfo.loadIcon(pm)
        } catch (e: PackageManager.NameNotFoundException) {
            // Will use fallback icon
        }
    }

    ListItem(
        headlineContent = { Text(appName) },
        supportingContent = { Text(settingsSummary) },
        leadingContent = {
            if (appIcon != null) {
                androidx.compose.foundation.Image(
                    bitmap = appIcon!!.toBitmap(
                        appIcon!!.intrinsicWidth.coerceAtMost(48),
                        appIcon!!.intrinsicHeight.coerceAtMost(48)
                    ).asImageBitmap(),
                    contentDescription = "$appName icon",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.small)
                )
            } else {
                // Fallback to generic app icon
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "$appName icon",
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "View settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )

    HorizontalDivider()
}
