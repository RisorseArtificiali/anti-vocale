package com.antivocale.app.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.svenjacobs.reveal.RevealOverlayArrangement
import com.svenjacobs.reveal.RevealOverlayScope
import com.svenjacobs.reveal.RevealState
import com.svenjacobs.reveal.revealable

/**
 * The welcome tour's step table (TASK-491, reveal-based coach marks over the
 * real UI): each step names the actual element it explains, in the order a
 * first-time user needs it - model first, then the share flow, then the
 * browse alternative. The tour is shown ONCE per install
 * (PreferencesManager.onboardingCompleted, never version-keyed).
 */
enum class TourStep(val key: String) {
    /** Overlay-only welcome: what the app is and the privacy stance. */
    Welcome("tour_welcome"),

    /** The Models tab: download a model first. */
    ModelsTab("tour_models_tab"),

    /** The History tab: share a voice message from any messenger. */
    HistoryTab("tour_history_tab"),

    /** The browse FAB on History: pick a local file instead. */
    BrowseFab("tour_browse_fab"),
}

/** The coach-mark card for one step: title, body, Next/Done + Skip. */
@Composable
fun TourOverlayCard(
    step: TourStep,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val (titleRes, bodyRes) = when (step) {
        TourStep.Welcome -> R.string.tour_title_welcome to R.string.tour_body_welcome
        TourStep.ModelsTab -> R.string.tour_title_models to R.string.tour_body_models
        TourStep.HistoryTab -> R.string.tour_title_history to R.string.tour_body_history
        TourStep.BrowseFab -> R.string.tour_title_browse to R.string.tour_body_browse
    }
    Surface(
        modifier = modifier.padding(16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Same row, deliberately different aspect: the filled primary
            // button advances the tour flow, the outlined one exits it
            // (maintainer request 2026-09-14 - the old Skip was a small text
            // button below Next and read as a second step of the same flow).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onNext,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (isLast) R.string.tour_done else R.string.tour_next
                        )
                    )
                }
                OutlinedButton(onClick = onSkip) {
                    Text(
                        stringResource(R.string.tour_skip),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * Placement for the explanatory card (the overlayContent scope's align
 * extension): under the tab-row revealables so the card sits in the wide
 * content area, and above the bottom FAB revealable. Horizontal centering
 * keeps the card readable on both tab positions; confineWidth=false lets
 * the card span the screen width rather than the cutout's width.
 */
fun tourCardModifier(scope: RevealOverlayScope, step: TourStep): Modifier = with(scope) {
    when (step) {
        // Bottom = the card's top edge lands at the revealable's bottom, so:
        // under the tab-row cutouts (they sit at the top of the screen),
        // which is also the Welcome step's cutout (TASK-508: the whole
        // TabRow, not the app title - the title's cutout made the card land
        // on top of the tabs). Horizontal centering keeps the card readable;
        // confineWidth=false lets it span the screen width instead of the
        // cutout's width.
        TourStep.Welcome, TourStep.ModelsTab, TourStep.HistoryTab ->
            Modifier.align(verticalArrangement = RevealOverlayArrangement.Bottom, confineWidth = false)
        // Top = the card's bottom edge lands at the revealable's top: above
        // the bottom-right FAB cutout, in the lower-center content area.
        TourStep.BrowseFab ->
            Modifier.align(verticalArrangement = RevealOverlayArrangement.Top, confineWidth = false)
    }
}

/** Shared cutout padding for every tour revealable. */
private val TOUR_CUTOUT_PADDING = PaddingValues(12.dp)

/**
 * The ONE cutout style for the tour's revealables (TASK-508 review): a 2dp
 * primary border + uniform padding, so the lit window reads identically on
 * every step. Three hand-copied BorderStroke blocks is exactly how the FAB
 * step ended up the only one without a border.
 */
@Composable
fun Modifier.tourRevealable(key: String, state: RevealState): Modifier {
    val primary = MaterialTheme.colorScheme.primary
    val border = remember(primary) { BorderStroke(2.dp, primary) }
    return this.revealable(
        key = key,
        state = state,
        borderStroke = border,
        padding = TOUR_CUTOUT_PADDING,
    )
}
