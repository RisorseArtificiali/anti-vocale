package com.antivocale.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/**
 * TASK-1052: the documents-UI launch guard. On devices without a
 * documents UI the ACTION_OPEN_DOCUMENT* family resolves to nothing and a
 * bare launch crashes with ActivityNotFoundException (two Crashlytics
 * fatals from one Honor device in 24h). The pins hold both halves of the
 * helper's contract: a throwing launch surfaces the localized toast and
 * never rethrows, and a healthy launch passes the input through untouched.
 */
@RunWith(RobolectricTestRunner::class)
class SafeDocumentLaunchTest {

    @Test
    fun `a launcher rejected by the device surfaces the toast and never rethrows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launcher = mockk<ActivityResultLauncher<String>>()
        every { launcher.launch("audio/*") } throws ActivityNotFoundException("no documents UI")

        launchDocumentPick(context, launcher, "audio/*")

        verify(exactly = 1) { launcher.launch("audio/*") }
        val toast = ShadowToast.getTextOfLatestToast()
        assertTrue(
            "the toast is the no-picker message",
            toast!!.contains(context.getString(R.string.error_no_document_picker)))
    }

    @Test
    fun `a non-ANF launch failure still propagates to the caller`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launcher = mockk<ActivityResultLauncher<String>>()
        every { launcher.launch(any()) } throws IllegalStateException("malformed contract input")

        val thrown = kotlin.runCatching {
            launchDocumentPick(context, launcher, "audio/*")
        }.exceptionOrNull()

        assertTrue(
            "the helper must not swallow failures outside the no-picker class " +
                "(the voice-note fallback's F6 log depends on this half)",
            thrown is IllegalStateException)
        assertEquals("no toast on the propagated-failure path", null, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a healthy launcher passes the input through`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launcher = mockk<ActivityResultLauncher<String>>(relaxed = true)

        launchDocumentPick(context, launcher, "audio/*")

        verify(exactly = 1) { launcher.launch("audio/*") }
        assertEquals("no toast on the healthy path", null, ShadowToast.getTextOfLatestToast())
    }
}
