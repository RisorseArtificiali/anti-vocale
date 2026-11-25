package com.antivocale.app.ui.onboarding

import android.app.Application

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.antivocale.app.util.RegisterComponentActivityRule
import com.svenjacobs.reveal.Reveal
import com.svenjacobs.reveal.RevealCanvas
import com.svenjacobs.reveal.RevealState
import com.svenjacobs.reveal.rememberRevealCanvasState
import com.svenjacobs.reveal.rememberRevealState
import com.svenjacobs.reveal.revealable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-1029: the welcome-tour step-mash crash. RevealState.reveal(key)
 * throws IllegalArgumentException the moment the key has not registered,
 * and a revealable registers at the frame's LAYOUT pass while the tour's
 * LaunchedEffect body runs in the apply phase: a tab switched this frame
 * has composed but not laid out, so two rapid step advances coalescing
 * into one frame window crashed the tour (Crashlytics 1.16.1, Huawei
 * Android 8.1). The pins hold both halves of revealWhenRegistered's
 * contract: the unregistered key waits (or skips, bounded) instead of
 * throwing, and the registered key still reveals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TourRevealWhenRegisteredTest {

    @get:Rule(order = 0)
    val registerComponentActivityRule = RegisterComponentActivityRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun `an unregistered key skips quietly after the bound instead of throwing`() = runTest {
        val state = RevealState()
        // The fix: the unregistered key waits. Virtual time burns the bound
        // instantly and the call returns quietly, revealed nothing.
        state.revealWhenRegistered(TourStep.BrowseFab.key)
        // The skip leaves a breadcrumb (the timeout arm's Log.w).
        val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("WelcomeTour")
        org.junit.Assert.assertTrue(
            "the skip must log the missing key",
            logs.any { it.msg!!.contains(TourStep.BrowseFab.key) })
        assertFalse(state.containsRevealable(TourStep.BrowseFab.key))
        assertEquals(null, state.currentRevealableKey)
    }

    @Test
    fun `a step flip inside one frame reveals the target key without throwing`() {
        lateinit var revealState: RevealState
        var step by mutableStateOf(TourStep.HistoryTab)

        compose.setContent {
            val canvas = rememberRevealCanvasState()
            revealState = rememberRevealState()
            RevealCanvas(revealCanvasState = canvas) {
                Reveal(revealCanvasState = canvas, revealState = revealState) {
                    // The MainScreen effect pattern: the step's key is
                    // revealed in the same effect body that swapped the
                    // host, i.e. in the apply phase, before the new
                    // host's first layout registers the key.
                    LaunchedEffect(step) {
                        revealState.revealWhenRegistered(step.key)
                    }
                    if (step == TourStep.HistoryTab) {
                        Box(
                            Modifier.fillMaxSize()
                                .revealable(key = TourStep.HistoryTab.key, state = revealState))
                    } else {
                        Box(
                            Modifier.fillMaxSize()
                                .revealable(key = TourStep.BrowseFab.key, state = revealState))
                    }
                }
            }
        }

        // First step settles normally.
        compose.waitForIdle()
        assertEquals(TourStep.HistoryTab.key, revealState.currentRevealableKey)

        // The mash: the flip lands while animations are frozen, so the new
        // revealable registers only at the next layout pass. Pre-fix this
        // threw inside the effect; now it waits and reveals.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { step = TourStep.BrowseFab }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(TourStep.BrowseFab.key, revealState.currentRevealableKey)
    }
}
