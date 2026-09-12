package com.antivocale.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.svenjacobs.reveal.RevealOverlayArrangement
import com.svenjacobs.reveal.RevealOverlayScope

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
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onNext,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (isLast) R.string.tour_done else R.string.tour_next
                        )
                    )
                }
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.tour_skip))
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
        // Under the tab-row revealables (their cutouts sit at the top) and
        // above the bottom FAB: the card lands in the wide content area.
        TourStep.ModelsTab, TourStep.HistoryTab ->
            Modifier.align(verticalArrangement = RevealOverlayArrangement.Bottom, confineWidth = false)
        TourStep.Welcome, TourStep.BrowseFab ->
            Modifier.align(verticalArrangement = RevealOverlayArrangement.Top, confineWidth = false)
    }
}
