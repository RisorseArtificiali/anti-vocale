package com.antivocale.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-756: the read-only dropdown field's contract. The one-line guard
 * is the regression pin (TASK-755, the maintainer's report of a wrapped
 * value growing the field vertically): a value far longer than the
 * field's width must not change the field's height. Compose semantics
 * expose no maxLines, so the guard is pinned GEOMETRICALLY: the measured
 * pixel height is identical for a short and a wrapped-long value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadOnlyDropdownFieldTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * ONE composition per test (the rule allows a single setContent): the
     * value rides a state the test flips between the two measurements.
     */
    private fun measureBothHeights(first: String, second: String): Pair<Int, Int> {
        val value = androidx.compose.runtime.mutableStateOf(first)
        var heightPx = -1
        compose.setContent {
            androidx.compose.runtime.key(value.value) {
                ReadOnlyDropdownField(
                    value = value.value,
                    label = { Text("Sound") },
                    expanded = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { heightPx = it.height })
            }
        }
        compose.onNodeWithText("Sound").assertIsDisplayed()
        compose.waitForIdle()
        assertTrue("first measurement never ran", heightPx > 0)
        val firstHeight = heightPx
        heightPx = -1
        compose.runOnUiThread { value.value = second }
        compose.waitForIdle()
        assertTrue("second measurement never ran", heightPx > 0)
        return firstHeight to heightPx
    }

    @Test
    fun `a wrapped-long value grows the field no taller than a short one`() {
        val (short, long) = measureBothHeights(
            "Bell",
            "Campanella della sera con una etichetta localizzata molto lunga che una volta " +
                "faceva crescere il campo verticalmente su piu righe wrap wrap wrap")
        assertTrue(
            "the one-line guard regressed: short=" + short + "px long=" + long + "px (TASK-755 class)",
            kotlin.math.abs(short - long) <= 1)
    }

    @Test
    fun `the value text renders in the field`() {
        compose.setContent {
            ReadOnlyDropdownField(
                value = "Squilla",
                label = { Text("Sound") },
                expanded = false,
                modifier = Modifier.fillMaxWidth())
        }
        compose.onNodeWithText("Squilla").assertIsDisplayed()
    }
}
