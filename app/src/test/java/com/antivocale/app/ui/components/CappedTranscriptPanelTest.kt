package com.antivocale.app.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.antivocale.app.ui.MAX_RENDERED_TRANSCRIPT_CHARS
import com.antivocale.app.util.RegisterComponentActivityRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TASK-1034 (GH #190), AC#4: a long transcript is fully rendered in the
 * panel, not truncated. The compose pin drives CappedTranscriptText with
 * a text well past the panel threshold whose LAST words are a unique
 * marker: before the fix the marker was beyond the 30k take and absent;
 * now the final block is composed and findable. The scroll hint replaces
 * the old cut label.
 */
@RunWith(RobolectricTestRunner::class)
class CappedTranscriptPanelTest {

    @get:Rule(order = 0)
    val registerComponentActivityRule = RegisterComponentActivityRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun `a past-threshold transcript renders its final characters in the panel`() {
        val tailMarker = "ZQTAIL-END-OF-TRANSCRIPT-ZQ"
        val text = buildString {
            repeat((MAX_RENDERED_TRANSCRIPT_CHARS / 20) + 40) { append("paragraph line $it with some ordinary words.\n\n") }
            append(tailMarker)
        }
        compose.setContent {
            CappedTranscriptText(text = text, searchQuery = "")
        }
        compose.waitForIdle()
        compose.onNodeWithText(tailMarker, substring = true).assertExists()
    }

    @Test
    fun `an active search still finds and styles text near the end of a long panel`() {
        // Code-review pin: the per-block highlight claim. With a query
        // matching the tail, the final block still renders (the node is
        // findable with the query active) and the marker occurrences stay
        // countable across the blocks that carry them.
        val marker = "needleword"
        val text = buildString {
            repeat((MAX_RENDERED_TRANSCRIPT_CHARS / 20) + 40) { append("paragraph line $it ordinary words.\n\n") }
            append("tail $marker and more $marker tail")
        }
        compose.setContent {
            CappedTranscriptText(text = text, searchQuery = marker)
        }
        compose.waitForIdle()
        // The final block renders with the query active (not dropped by
        // the split): the marker's node carries the whole tail phrase.
        // Both occurrences live in the one tail block, so exactly one
        // node mentions the marker.
        compose.onAllNodesWithText(marker, substring = true).assertCountEquals(1)
        compose.onNodeWithText("and more", substring = true).assertExists()
    }

    @Test
    fun `the scroll hint shows on the long panel`() {
        val text = "filler ".repeat(MAX_RENDERED_TRANSCRIPT_CHARS / 7 + 100)
        compose.setContent {
            CappedTranscriptText(text = text, searchQuery = "")
        }
        compose.waitForIdle()
        // The old "First N characters shown" label is gone with the full
        // render; the scroll hint (the new string) is what remains.
        val hint = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getString(com.antivocale.app.R.string.transcript_render_scrolls)
        compose.onNodeWithText(hint).assertExists()
    }
}
