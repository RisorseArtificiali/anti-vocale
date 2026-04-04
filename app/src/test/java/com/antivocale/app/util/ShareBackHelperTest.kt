package com.antivocale.app.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.antivocale.app.data.PerAppPreferencesManager
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ShareBackHelper.
 *
 * Tests the share routing logic that directs transcriptions back to
 * messaging apps (WhatsApp, Telegram, Signal) or generic apps,
 * with clipboard fallback on failure.
 *
 * Note: Intent content verification (type, extras, package) is covered
 * by integration testing. These unit tests verify interaction correctness
 * (startActivity called, clipboard used, callbacks invoked).
 */
class ShareBackHelperTest {

    private lateinit var context: Context
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var packageManager: PackageManager

    private val transcriptionText = "This is a test transcription"

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        clipboardManager = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)

        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboardManager
        every { context.packageManager } returns packageManager

        // Mock Looper.getMainLooper() to avoid Android framework dependency
        mockkStatic(android.os.Looper::class)
        every { android.os.Looper.getMainLooper() } returns mockk(relaxed = true)

        // Mock Toast.makeText to avoid Android framework dependency
        mockkStatic(android.widget.Toast::class)
        every { android.widget.Toast.makeText(any(), any<Int>(), any()) } returns mockk(relaxed = true)

        // Mock Handler.post to execute immediately
        mockkConstructor(android.os.Handler::class)
        every { anyConstructed<android.os.Handler>().post(any()) } answers {
            firstArg<Runnable>().run()
            true
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `shareBack with WhatsApp starts activity`() {
        every { context.startActivity(any()) } just Runs

        var successCalled = false
        ShareBackHelper.shareBack(
            context,
            PerAppPreferencesManager.WHATSAPP,
            "WhatsApp",
            transcriptionText,
            onSuccess = { successCalled = true },
            onError = { fail("Should not call onError") }
        )

        assertTrue("onSuccess should be called", successCalled)
        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun `shareBack with Telegram starts activity`() {
        every { context.startActivity(any()) } just Runs

        var successCalled = false
        ShareBackHelper.shareBack(
            context,
            PerAppPreferencesManager.TELEGRAM,
            "Telegram",
            transcriptionText,
            onSuccess = { successCalled = true },
            onError = { fail("Should not call onError") }
        )

        assertTrue("onSuccess should be called", successCalled)
        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun `shareBack with Signal copies to clipboard and opens app`() {
        val launchIntent = Intent()
        every { packageManager.getLaunchIntentForPackage(PerAppPreferencesManager.SIGNAL) } returns launchIntent
        every { context.startActivity(any()) } just Runs

        var successCalled = false
        ShareBackHelper.shareBack(
            context,
            PerAppPreferencesManager.SIGNAL,
            "Signal",
            transcriptionText,
            onSuccess = { successCalled = true },
            onError = { fail("Should not call onError") }
        )

        assertTrue("onSuccess should be called", successCalled)
        verify(exactly = 1) { clipboardManager.setPrimaryClip(any()) }
        verify(exactly = 1) { packageManager.getLaunchIntentForPackage(PerAppPreferencesManager.SIGNAL) }
    }

    @Test
    fun `shareBack with null packageName calls onError`() {
        var errorMessage: String? = null
        ShareBackHelper.shareBack(
            context,
            null,
            null,
            transcriptionText,
            onSuccess = { fail("Should not call onSuccess") },
            onError = { errorMessage = it }
        )

        assertEquals("No source app detected", errorMessage)
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `shareBack with unknown package routes to generic share`() {
        every { context.startActivity(any()) } just Runs

        var successCalled = false
        ShareBackHelper.shareBack(
            context,
            "com.unknown.app",
            "Unknown App",
            transcriptionText,
            onSuccess = { successCalled = true },
            onError = { fail("Should not call onError") }
        )

        assertTrue("onSuccess should be called", successCalled)
        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun `shareBack with WhatsApp falls back to clipboard on ActivityNotFoundException`() {
        every { context.startActivity(any()) } throws ActivityNotFoundException()

        var successCalled = false
        ShareBackHelper.shareBack(
            context,
            PerAppPreferencesManager.WHATSAPP,
            "WhatsApp",
            transcriptionText,
            onSuccess = { successCalled = true },
            onError = { fail("Should not call onError for fallback") }
        )

        assertTrue("onSuccess should be called via clipboard fallback", successCalled)
        verify(exactly = 1) { clipboardManager.setPrimaryClip(any()) }
    }

    @Test
    fun `shareBack with unknown package falls back to clipboard on exception`() {
        every { context.startActivity(any()) } throws ActivityNotFoundException()

        var successCalled = false
        ShareBackHelper.shareBack(
            context,
            "com.unknown.app",
            "Unknown App",
            transcriptionText,
            onSuccess = { successCalled = true },
            onError = { fail("Should not call onError for fallback") }
        )

        assertTrue("onSuccess should be called via clipboard fallback", successCalled)
        verify(exactly = 1) { clipboardManager.setPrimaryClip(any()) }
    }

    @Test
    fun `clipboard fallback calls ClipData newPlainText with correct arguments`() {
        every { context.startActivity(any()) } throws ActivityNotFoundException()

        // Mock ClipData.newPlainText to verify arguments
        mockkStatic(ClipData::class)
        every { ClipData.newPlainText(any(), any<String>()) } returns mockk(relaxed = true)

        ShareBackHelper.shareBack(
            context,
            PerAppPreferencesManager.WHATSAPP,
            "WhatsApp",
            transcriptionText,
            onSuccess = {},
            onError = {}
        )

        verify { ClipData.newPlainText("Transcription", transcriptionText) }
    }
}
