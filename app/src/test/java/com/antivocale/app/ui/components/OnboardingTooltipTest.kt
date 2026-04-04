package com.antivocale.app.ui.components

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.R
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
 * Unit tests for the OnboardingTooltip composable.
 *
 * Uses Robolectric for Android Context access (string resources)
 * and Compose UI testing for layout and interaction verification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OnboardingTooltipTest {

    /**
     * Registers ComponentActivity in Robolectric's shadow PackageManager
     * before createComposeRule() tries to launch it.
     *
     * createComposeRule() internally uses ActivityScenario to launch
     * ComponentActivity, but Robolectric cannot resolve it unless it is
     * registered. See https://github.com/robolectric/robolectric/pull/4736
     */
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

    private val titleText: String
        get() = context.getString(R.string.per_app_onboarding_title)

    private val messageText: String
        get() = context.getString(R.string.per_app_onboarding_message)

    private val gotItText: String
        get() = context.getString(R.string.per_app_onboarding_got_it)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun tooltip_showsContent_whenVisible() {
        val onDismiss = mockk<(Unit) -> Unit>(relaxed = true)

        composeTestRule.setContent {
            OnboardingTooltip(
                visible = true,
                onDismiss = { onDismiss(Unit) }
            )
        }

        composeTestRule.mainClock.advanceTimeBy(500L)

        composeTestRule
            .onNodeWithText(titleText)
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText(messageText)
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText(gotItText)
            .assertIsDisplayed()
    }

    @Test
    fun tooltip_hidesContent_whenNotVisible() {
        val onDismiss = mockk<(Unit) -> Unit>(relaxed = true)

        composeTestRule.setContent {
            OnboardingTooltip(
                visible = false,
                onDismiss = { onDismiss(Unit) }
            )
        }

        composeTestRule.mainClock.advanceTimeBy(500L)

        composeTestRule
            .onNodeWithText(titleText)
            .assertIsNotDisplayed()

        composeTestRule
            .onNodeWithText(messageText)
            .assertIsNotDisplayed()
    }

    @Test
    fun tooltip_callsOnDismiss_whenGotItClicked() {
        val onDismiss = mockk<(Unit) -> Unit>(relaxed = true)

        composeTestRule.setContent {
            OnboardingTooltip(
                visible = true,
                onDismiss = { onDismiss(Unit) }
            )
        }

        composeTestRule.mainClock.advanceTimeBy(500L)

        composeTestRule
            .onNodeWithText(gotItText)
            .performClick()

        verify(exactly = 1) { onDismiss(Unit) }
    }
}
