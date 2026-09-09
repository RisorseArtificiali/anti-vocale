package com.antivocale.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.antivocale.app.ui.appearance.LauncherIconVariant
import com.antivocale.app.ui.viewmodel.SettingsViewModel

/**
 * The launcher-icon picker (TASK-473 rework, maintainer decision 2026-09-09):
 * a dedicated sub-page instead of the inline Appearance section, tiles without
 * visible labels so they line up as a regular grid (the localized names live
 * in the content description), Default plus derei's six concepts. The derei
 * glyphs are vectors whose 108dp viewport carries the launcher's safe-zone
 * framing, so a full-bleed render is WYSIWYG; Default keeps the color-plus-
 * shared-foreground composite.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LauncherIconScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val current by viewModel.currentLauncherIcon.collectAsState()
    // The same XML colors the adaptive-icon background drawable reads: one
    // source for the launcher layer and the on-screen preview.
    val dereiGradient = Brush.verticalGradient(
        listOf(
            colorResource(R.color.launcher_icon_derei_gradient_top),
            colorResource(R.color.launcher_icon_derei_gradient_bottom),
        )
    )
    BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
                Text(
                    text = stringResource(R.string.app_icon_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_icon_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup()
                            .padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        LauncherIconVariant.entries.forEach { variant ->
                            val selected = variant == current
                            val label = stringResource(variant.nameRes)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .then(
                                        if (variant.glyphRes != null) Modifier.background(dereiGradient)
                                        else Modifier.background(
                                            colorResource(R.color.launcher_icon_default))
                                    )
                                    .then(
                                        if (selected) Modifier.border(
                                            width = 3.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape,
                                        ) else Modifier
                                    )
                                    .selectable(
                                        selected = selected,
                                        role = Role.Button,
                                        onClick = { viewModel.selectLauncherIcon(variant) },
                                    )
                                    .semantics { contentDescription = label },
                            ) {
                                val painter = variant.glyphRes?.let { painterResource(it) }
                                    ?: painterResource(R.mipmap.ic_launcher_foreground)
                                Image(
                                    painter = painter,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
