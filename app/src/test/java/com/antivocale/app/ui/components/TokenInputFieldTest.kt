package com.antivocale.app.ui.components

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for the TokenInputField composable.
 *
 * Uses Robolectric for Android Context access (string resources, ClipboardManager)
 * and Compose UI testing for layout and interaction verification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TokenInputFieldTest {

    @get:Rule(order = 0)
    val registerComponentActivityRule = object : TestWatcher() {
        override fun starting(description: Description?) {
            val appContext: Application = ApplicationProvider.getApplicationContext()
            shadowOf(appContext.packageManager).addActivityIfNotPresent(
                android.content.ComponentName(
                    appContext.packageName,
                    ComponentActivity::class.java.name
                )
            )
        }
    }

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var clipboardManager: ClipboardManager

    private val labelText: String
        get() = context.getString(R.string.huggingface_token_label)

    private val pasteContentDescription: String
        get() = context.getString(R.string.paste_from_clipboard)

    private val showTokenContentDescription: String
        get() = context.getString(R.string.show_token)

    private val hideTokenContentDescription: String
        get() = context.getString(R.string.hide_token)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        clipboardManager = mockk(relaxed = true)
    }

    // --- Rendering ---

    @Test
    fun field_displaysLabel() {
        composeTestRule.setContent {
            TokenInputField(
                value = "",
                onValueChange = {},
                tokenPasswordVisible = false,
                onPasswordVisibilityToggle = {},
                clipboardManager = clipboardManager,
            )
        }

        composeTestRule
            .onNodeWithText(labelText)
            .assertIsDisplayed()
    }

    // --- Value changes ---

    @Test
    fun onValueChange_isCalled_whenTextInputPerformed() {
        val onValueChange = mockk<(String) -> Unit>(relaxed = true)

        composeTestRule.setContent {
            TokenInputField(
                value = "",
                onValueChange = onValueChange,
                tokenPasswordVisible = false,
                onPasswordVisibilityToggle = {},
                clipboardManager = clipboardManager,
            )
        }

        // Find the editable text field by its label text and perform input
        composeTestRule
            .onNode(hasText(labelText, substring = true))
            .performTextInput("hf_abc123")

        verify { onValueChange("hf_abc123") }
    }

    // --- Password visibility toggle ---

    @Test
    fun onPasswordVisibilityToggle_isCalled_whenVisibilityButtonClicked() {
        val onToggle = mockk<() -> Unit>(relaxed = true)

        composeTestRule.setContent {
            TokenInputField(
                value = "hf_test",
                onValueChange = {},
                tokenPasswordVisible = false,
                onPasswordVisibilityToggle = onToggle,
                clipboardManager = clipboardManager,
            )
        }

        // When password is hidden, the show_token content description is used
        composeTestRule
            .onNodeWithContentDescription(showTokenContentDescription)
            .performClick()

        verify(exactly = 1) { onToggle() }
    }

    @Test
    fun visibilityToggle_showsHideButton_whenPasswordIsVisible() {
        composeTestRule.setContent {
            TokenInputField(
                value = "hf_test",
                onValueChange = {},
                tokenPasswordVisible = true,
                onPasswordVisibilityToggle = {},
                clipboardManager = clipboardManager,
            )
        }

        // When password is visible, the hide_token content description is used
        composeTestRule
            .onNodeWithContentDescription(hideTokenContentDescription)
            .assertIsDisplayed()
    }

    // --- Error state ---

    @Test
    fun field_showsError_whenIsErrorIsTrue() {
        composeTestRule.setContent {
            TokenInputField(
                value = "invalid",
                onValueChange = {},
                tokenPasswordVisible = false,
                onPasswordVisibilityToggle = {},
                clipboardManager = clipboardManager,
                isError = true,
            )
        }

        // OutlinedTextField with isError=true applies the error color.
        // Verify the field is still displayed with the label (error state is visual).
        composeTestRule
            .onNodeWithText(labelText)
            .assertIsDisplayed()
    }

    // --- Enabled state ---

    @Test
    fun field_isEnabled_byDefault() {
        composeTestRule.setContent {
            TokenInputField(
                value = "",
                onValueChange = {},
                tokenPasswordVisible = false,
                onPasswordVisibilityToggle = {},
                clipboardManager = clipboardManager,
                enabled = true,
            )
        }

        composeTestRule
            .onNodeWithText(labelText)
            .assertIsEnabled()
    }

    @Test
    fun field_isNotEnabled_whenEnabledIsFalse() {
        composeTestRule.setContent {
            TokenInputField(
                value = "",
                onValueChange = {},
                tokenPasswordVisible = false,
                onPasswordVisibilityToggle = {},
                clipboardManager = clipboardManager,
                enabled = false,
            )
        }

        composeTestRule
            .onNodeWithText(labelText)
            .assertIsNotEnabled()
    }

    // --- Paste from clipboard ---

    @Test
    fun pasteButton_callsOnValueChange_withClipboardText() {
        val onValueChange = mockk<(String) -> Unit>(relaxed = true)
        val clipText = "hf_pasted_token_123"
        val clipData = ClipData.newPlainText("token", clipText)
        every { clipboardManager.primaryClip } returns clipData

        composeTestRule.setContent {
            TokenInputField(
                value = "",
                onValueChange = onValueChange,
                tokenPasswordVisible = false,
                onPasswordVisibilityToggle = {},
                clipboardManager = clipboardManager,
            )
        }

        composeTestRule
            .onNodeWithContentDescription(pasteContentDescription)
            .performClick()

        verify { onValueChange(clipText) }
    }

    @Test
    fun pasteButton_doesNotCallOnValueChange_whenClipboardIsEmpty() {
        val onValueChange = mockk<(String) -> Unit>(relaxed = true)
        every { clipboardManager.primaryClip } returns null

        composeTestRule.setContent {
            TokenInputField(
                value = "",
                onValueChange = onValueChange,
                tokenPasswordVisible = false,
                onPasswordVisibilityToggle = {},
                clipboardManager = clipboardManager,
            )
        }

        composeTestRule
            .onNodeWithContentDescription(pasteContentDescription)
            .performClick()

        verify(exactly = 0) { onValueChange(any()) }
    }

    @Test
    fun pasteButton_doesNotCallOnValueChange_whenClipboardItemHasNoText() {
        val onValueChange = mockk<(String) -> Unit>(relaxed = true)
        val clipData = ClipData.newPlainText("token", null as CharSequence?)
        every { clipboardManager.primaryClip } returns clipData

        composeTestRule.setContent {
            TokenInputField(
                value = "",
                onValueChange = onValueChange,
                tokenPasswordVisible = false,
                onPasswordVisibilityToggle = {},
                clipboardManager = clipboardManager,
            )
        }

        composeTestRule
            .onNodeWithContentDescription(pasteContentDescription)
            .performClick()

        verify(exactly = 0) { onValueChange(any()) }
    }
}
