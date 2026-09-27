package com.antivocale.app.util

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-688: pins the shared clipboard write itself: the text lands as the
 * primary clip, the caller's label rides the clip description, and a
 * second write replaces the first. Toast and threading behavior belong to
 * the call sites and are pinned by their own suites (ShareBackHelperTest,
 * FeedbackHelperTest, NotificationActionReceiverTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardWriterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun clipboard(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Test
    fun `copy lands the text as the primary clip`() {
        ClipboardWriter.copy(context, "Transcription", "hello world")

        assertEquals("hello world", clipboard().primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `copy sets the caller's label on the clip description`() {
        ClipboardWriter.copy(context, "email", "feedback@example.org")

        assertEquals("email", clipboard().primaryClip?.description?.label?.toString())
    }

    @Test
    fun `a second copy replaces the previous clip`() {
        ClipboardWriter.copy(context, "first", "one")
        ClipboardWriter.copy(context, "second", "two")

        assertEquals("two", clipboard().primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals("second", clipboard().primaryClip?.description?.label?.toString())
    }

    @Test
    fun `the primary clip starts empty and copy fills it`() {
        assertNull(clipboard().primaryClip)

        ClipboardWriter.copy(context, "label", "text")

        assertEquals("text", clipboard().primaryClip?.getItemAt(0)?.text?.toString())
    }
}
