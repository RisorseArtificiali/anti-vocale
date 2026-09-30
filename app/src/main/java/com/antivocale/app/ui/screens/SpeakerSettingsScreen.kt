package com.antivocale.app.ui.screens

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
import androidx.compose.material.icons.filled.RecordVoiceOver
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antivocale.app.R
import com.antivocale.app.ui.viewmodel.SettingsViewModel
import com.antivocale.app.ui.tabs.SpeakerIdentitiesCard
import com.antivocale.app.ui.components.SettingsHubCard
import com.antivocale.app.ui.components.ToggleSettingCard

/**
 * The diarization secondary page (maintainer decision 2026-09-30: related
 * settings for one feature move off the main tree to reduce its length).
 * Holds the speaker-labels toggle and, while the default-off speaker-ID
 * privacy gate is on, the named-identities card. The cards are the SAME
 * composables the main tree used (moved, not duplicated). SEARCH
 * COMPATIBILITY CONTRACT (maintainer constraint, same decision): the
 * main-tree hub card's registry vocabulary is the UNION of the hub's own
 * strings and every child's strings, with res() state-gated to the
 * children's visibility, so a query that used to match a child card now
 * surfaces the hub that opens onto it, and a card the page would not
 * render (the identities gate off) never counts as a match.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakerSettingsScreen(
    viewModel: SettingsViewModel,
    speakerIdEnabled: Boolean,
    onBack: () -> Unit,
) {
    val speakerLabelsEnabled by viewModel.speakerLabelsEnabled.collectAsStateWithLifecycle()
    val speakerLabelsTitle = stringResource(R.string.speaker_labels_title)
    val speakerLabelsSummary = stringResource(R.string.speaker_labels_description)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.speaker_settings_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ToggleSettingCard(
                icon = Icons.Default.RecordVoiceOver,
                title = speakerLabelsTitle,
                description = speakerLabelsSummary,
                checked = speakerLabelsEnabled,
                onCheckedChange = { enabled ->
                    viewModel.saveSpeakerLabelsEnabled(enabled)
                }
            )
            // TASK-670 (GH #83): named speaker identities, still gated by
            // the default-off privacy gate; the gate's flag is threaded
            // from the tab so the visibility rule is unchanged.
            if (speakerIdEnabled) {
                SpeakerIdentitiesCard(viewModel)
            }
        }
    }
}

