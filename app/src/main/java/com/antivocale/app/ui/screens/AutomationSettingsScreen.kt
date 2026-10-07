package com.antivocale.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.Card
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import com.antivocale.app.util.TreeUris
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antivocale.app.R
import com.antivocale.app.ui.components.SectionCard
import com.antivocale.app.ui.components.SettingsDropdown
import com.antivocale.app.ui.components.SettingsHubCard
import com.antivocale.app.ui.components.ToggleSettingCard
import com.antivocale.app.ui.tabs.AutomationGuideCard
import com.antivocale.app.ui.tabs.RemoteOmnivoiceConfigCard
import com.antivocale.app.ui.tabs.SettingsRowFocus
import com.antivocale.app.ui.viewmodel.SettingsViewModel
import com.antivocale.app.ui.components.SettingsSubPageHeader

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
 * strings (the remote config card rides the offload child; the folder watch is the fourth child).
 */
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

    // TASK-748: the sub-pages' shared chrome (plain Column + back-header
    // Row, no Scaffold); the host tab already applies the status-bar inset.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(scrollState)
            .onGloballyPositioned { scrollContentRootY = it.positionInRoot().y.toInt() }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsSubPageHeader(titleRes = R.string.automation_settings_title, onBack = onBack)
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

        // The offload configuration sits contextually under its own
        // toggle (maintainer road test 2026-10-07: it used to appear after
        // the folder-watch card, detached from the switch that reveals it).
        if (remoteOffloadEnabled) {
            RemoteOmnivoiceConfigCard(viewModel)
        }

        // TASK-741 (GH #125): the scheduled folder watch. The third
        // automation input source; the honest ColorOS contract lives in the
        // description (runs when the system allows; the manual scan is
        // always available).
        ScheduledFolderWatchCard(viewModel)
    }
}


/**
 * TASK-741 (GH #125): the watched-folder list. One card on the automation
 * page: pick folders (the persistable grant is taken at pick time, the
 * same contract as the export folder), set each one's interval, scan now,
 * or remove. Every mutation routes through the ViewModel, which pairs the
 * store write with the scheduler reconcile.
 */
/** Road test 2026-10-07: watched folders show the FULL path (two folders can
 *  share a name on different volumes); cloud picks degrade to the stored
 *  display name inside TreeUris.displayPath. */
@Composable
private fun rememberFolderLabel(folder: com.antivocale.app.data.WatchedFolder): String {
    val context = LocalContext.current
    return remember(folder.treeUri) {
        runCatching { TreeUris.displayPath(context, android.net.Uri.parse(folder.treeUri)) }
            .getOrNull() ?: folder.displayName
    }
}

@Composable
private fun ScheduledFolderWatchCard(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val folders by viewModel.scheduledFolders.collectAsStateWithLifecycle(initialValue = emptyList())
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // The persistable grant is taken at pick time, the same contract as
        // the export folder (it survives reboots; the worker checks it).
        // A picker that returns a grant WITHOUT the persistable flag would
        // leave a permanently dead watch (the worker's grantHeld check
        // fails forever, silently), so the record is not added at all.
        if (!com.antivocale.app.util.TreeUris.takePersistableReadGrant(context, uri)) {
            com.antivocale.app.util.ToastCompat.show(context, R.string.folder_watch_grant_failed)
            return@rememberLauncherForActivityResult
        }
        viewModel.addWatchedFolder(uri)
    }

    SectionCard(
        icon = Icons.Default.FolderOpen,
        title = stringResource(R.string.folder_watch_title),
        description = stringResource(R.string.folder_watch_description),
    ) {
        if (folders.isEmpty()) {
            Text(
                text = stringResource(R.string.folder_watch_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        folders.forEach { folder ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    text = rememberFolderLabel(folder),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The interval owns the full row and the actions wrap to
                // their own: beside two full-text buttons a weight(1f)
                // dropdown starves to unreadable on narrow screens and long
                // locale labels (review F8).
                SettingsDropdown(
                    currentValue = folder.periodHours,
                    options = com.antivocale.app.data.WatchedFolder.PERIOD_CHOICES,
                    currentValueDisplay = androidx.compose.ui.res.pluralStringResource(
                        R.plurals.folder_watch_period_value, folder.periodHours, folder.periodHours,
                    ),
                    optionDisplay = { hours ->
                        androidx.compose.ui.res.pluralStringResource(
                            R.plurals.folder_watch_period_value, hours, hours,
                        )
                    },
                    onOptionSelected = { hours -> viewModel.updateWatchedFolderPeriod(folder.treeUri, hours) },
                    label = stringResource(R.string.folder_watch_period_label),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = { viewModel.scanWatchedFolderNow(folder.treeUri) }) {
                        Text(stringResource(R.string.folder_watch_scan_now))
                    }
                    TextButton(onClick = { viewModel.removeWatchedFolder(folder.treeUri) }) {
                        Text(stringResource(R.string.folder_watch_remove))
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { picker.launch(null) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.folder_watch_add))
        }
    }
}
