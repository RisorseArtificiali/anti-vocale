package com.antivocale.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antivocale.app.R
import com.antivocale.app.service.InferenceService
import com.antivocale.app.ui.components.SettingsHubCard
import com.antivocale.app.ui.components.ToggleSettingCard
import com.antivocale.app.ui.dialogs.PerformanceStatsDialog
import com.antivocale.app.ui.viewmodel.SettingsViewModel
import com.antivocale.app.ui.tabs.SettingsRowFocus
import com.antivocale.app.ui.components.SectionCard
import com.antivocale.app.ui.components.SettingsDropdown
import com.antivocale.app.ui.tabs.MemoryDiagnosticsCard
import com.antivocale.app.transcription.InferenceProvider
import com.antivocale.app.data.TranscriptionCalibrator.CalibrationProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The performance-and-memory secondary page (maintainer decision
 * 2026-09-30). Holds thread count, inference provider, memory
 * protection, the performance-stats dialog trigger, and memory
 * diagnostics; these are the cards the Advanced section grouped loosely. The
 * cards are the SAME composables the main tree used (moved, not
 * duplicated).
 *
 * FOCUS CONTRACT (the memory-failure notification deep-link, TASK-625):
 * the main tab's focus handler now OPENS this page with
 * [focusMemoryProtection] true instead of scrolling the main tree; the
 * page owns its scroll state and runs the same capture/flash/decay
 * pattern on the memory-protection card, so the notification's "open
 * settings" action still lands on a flashing card.
 *
 * SEARCH COMPATIBILITY CONTRACT (maintainer constraint): the main-tree
 * hub card's registry vocabulary is the UNION of the hub's strings and
 * all five children's (none of these children is state-gated, so the
 * union is static).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceSettingsScreen(
    viewModel: SettingsViewModel,
    focusMemoryProtection: Boolean,
    onFocusConsumed: () -> Unit,
    onBack: () -> Unit,
) {
    val threadCount by viewModel.threadCount.collectAsStateWithLifecycle()
    val inferenceProvider by viewModel.inferenceProvider.collectAsStateWithLifecycle()
    val memoryProtection by viewModel.memoryProtection.collectAsStateWithLifecycle()
    val isTranscribing by InferenceService.isTranscribing.collectAsStateWithLifecycle()
    // The same derivation SettingsViewModel exposes for the main-tree
    // label; read from the VM so a future change has one home.
    val autoDetectedThreads = viewModel.autoDetectedThreadCount

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val memoryProtectionFocus = remember { SettingsRowFocus() }
    var scrollContentRootY by remember { mutableStateOf(0) }

    LaunchedEffect(focusMemoryProtection) {
        if (focusMemoryProtection) {
            onFocusConsumed()
            // Await layout (first frames deliver positions), then flash on
            // THIS page's scroll state.
            delay(150)
            memoryProtectionFocus.flashIn(scope, scrollState) { scrollContentRootY }
        }
    }

    var showStats by remember { mutableStateOf(false) }
    var statsProfiles by remember {
        mutableStateOf<List<CalibrationProfile>>(emptyList())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.performance_settings_title)) },
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
            val threadTitle = stringResource(R.string.thread_count_title)
            SectionCard(
                icon = Icons.Default.Memory,
                title = threadTitle,
                description = stringResource(R.string.thread_count_description)
            ) {
                SettingsDropdown(
                    currentValue = threadCount,
                    // The manual range deliberately exceeds the auto default's cap of 4
                    options = (1..8).toList(),
                    currentValueDisplay = if (threadCount == autoDetectedThreads)
                        stringResource(R.string.thread_count_auto, autoDetectedThreads)
                    else
                        stringResource(R.string.thread_count_value, threadCount),
                    optionDisplay = { threads ->
                        if (threads == autoDetectedThreads)
                            stringResource(R.string.thread_count_auto, threads)
                        else
                            stringResource(R.string.thread_count_value, threads)
                    },
                    onOptionSelected = { viewModel.saveThreadCount(it) },
                    label = threadTitle
                )
            }

            val providerTitle = stringResource(R.string.inference_provider_title)
            SectionCard(
                icon = Icons.Default.Bolt,
                title = providerTitle,
                description = stringResource(R.string.inference_provider_description)
            ) {
                SettingsDropdown(
                    currentValue = inferenceProvider,
                    options = InferenceProvider.options,
                    currentValueDisplay = when (inferenceProvider) {
                        InferenceProvider.AUTO -> stringResource(R.string.inference_provider_auto)
                        InferenceProvider.NNAPI -> stringResource(R.string.inference_provider_nnapi)
                        InferenceProvider.CPU -> stringResource(R.string.inference_provider_cpu)
                        else -> inferenceProvider
                    },
                    optionDisplay = { option ->
                        when (option) {
                            InferenceProvider.AUTO -> stringResource(R.string.inference_provider_auto)
                            InferenceProvider.NNAPI -> stringResource(R.string.inference_provider_nnapi)
                            InferenceProvider.CPU -> stringResource(R.string.inference_provider_cpu)
                            else -> option
                        }
                    },
                    onOptionSelected = { viewModel.saveInferenceProvider(it) },
                    label = providerTitle
                )
            }

            // The TASK-625 deep-link target: the notification action opens
            // this page with the focus flag; the border flash is the same
            // pattern (idle is transparent).
            ToggleSettingCard(
                icon = Icons.Default.Memory,
                title = stringResource(R.string.memory_protection),
                description = stringResource(R.string.memory_protection_desc),
                checked = memoryProtection,
                onCheckedChange = { viewModel.saveMemoryProtection(it) },
                modifier = Modifier
                    .onGloballyPositioned {
                        memoryProtectionFocus.capture(it.positionInRoot().y.toInt())
                    }
                    .border(
                        2.dp,
                        memoryProtectionFocus.highlightColor("memory_protection_highlight"),
                        MaterialTheme.shapes.medium,
                    )
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) {
                        scope.launch {
                            statsProfiles = viewModel.transcriptionCalibrator.getAllProfiles()
                            showStats = true
                        }
                    },
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.performance_stats_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.performance_stats_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.open_performance_stats),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            MemoryDiagnosticsCard(viewModel)
        }

        if (showStats) {
            PerformanceStatsDialog(
                profiles = statsProfiles,
                isTranscribing = isTranscribing,
                onDismiss = { showStats = false },
                onReset = {
                    scope.launch {
                        viewModel.transcriptionCalibrator.resetAll()
                        statsProfiles = emptyList()
                    }
                }
            )
        }
    }
}

