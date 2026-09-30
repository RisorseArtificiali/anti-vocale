package com.antivocale.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antivocale.app.R
import com.antivocale.app.ui.components.SettingsHubCard
import com.antivocale.app.ui.components.ToggleSettingCard
import com.antivocale.app.ui.tabs.AutomationGuideCard
import com.antivocale.app.ui.tabs.RemoteOmnivoiceConfigCard
import com.antivocale.app.ui.tabs.SettingsRowFocus
import com.antivocale.app.ui.viewmodel.SettingsViewModel

/**
 * The automation-and-offload secondary page (maintainer decision
 * 2026-09-30). Holds the exported-automation consent toggle (TASK-274),
 * its explainer card (TASK-275), and the LAN-offload experiment
 * (TASK-681) with its config. The cards are the SAME composables the
 * main tree used (moved, not duplicated).
 *
 * FOCUS CONTRACT (TASK-275, the same shape as the memory-protection
 * one on the performance page): the toggle's onShowToggle and any
 * external deep-link converge on THIS page's scroll state via its own
 * [SettingsRowFocus]; the flash pattern (capture, converge, 2.5s decay)
 * is unchanged.
 *
 * SEARCH COMPATIBILITY CONTRACT: the main-tree hub's registry
 * vocabulary is the static union of its own and all three children's
 * strings (the remote config card's strings ride the offload child).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val externalAutomationEnabled by viewModel.externalAutomationEnabled.collectAsStateWithLifecycle()
    val remoteOffloadEnabled by viewModel.remoteOmnivoiceEnabled.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val toggleFocus = remember { SettingsRowFocus() }
    var scrollContentRootY by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.automation_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .onGloballyPositioned { scrollContentRootY = it.positionInRoot().y.toInt() }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // TASK-274: consent gate for the exported automation receivers
            // (Tasker surface); while off they answer with the error that
            // names this toggle.
            ToggleSettingCard(
                icon = Icons.Default.Build,
                title = stringResource(R.string.external_automation_title),
                description = stringResource(R.string.external_automation_description),
                checked = externalAutomationEnabled,
                onCheckedChange = { enabled ->
                    viewModel.saveExternalAutomationEnabled(enabled)
                },
                modifier = Modifier
                    .onGloballyPositioned {
                        toggleFocus.capture(it.positionInRoot().y.toInt())
                    }
                    .border(
                        2.dp,
                        toggleFocus.highlightColor("external_automation_highlight"),
                        MaterialTheme.shapes.medium,
                    )
            )

            // TASK-275: the explainer card; its onShowToggle converges on
            // this page's own scroll state (the flash pattern unchanged).
            AutomationGuideCard(
                title = stringResource(R.string.automation_guide_title),
                description = stringResource(R.string.automation_guide_description),
                enabled = externalAutomationEnabled,
                onShowToggle = {
                    toggleFocus.flashIn(scope, scrollState) { scrollContentRootY }
                },
            )

            // TASK-681: LAN offload (experimental); the supporting text IS
            // the privacy contract and stays visible while off too.
            ToggleSettingCard(
                icon = Icons.Default.Lan,
                title = stringResource(R.string.remote_offload_title),
                description = stringResource(R.string.remote_offload_description),
                supportingText = stringResource(R.string.remote_offload_disclosure),
                checked = remoteOffloadEnabled,
                onCheckedChange = { enabled ->
                    viewModel.saveRemoteOmnivoiceEnabled(enabled)
                }
            )
            if (remoteOffloadEnabled) {
                RemoteOmnivoiceConfigCard(viewModel)
            }
        }
    }
}

