package com.antivocale.app.receiver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.antivocale.app.util.ShareBackHelper
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for NotificationActionReceiver.
 *
 * Tests the three notification action handlers (copy, share, share back)
 * plus the blank-text guard and unknown-action safety net.
 *
 * Note: Intent content verification (type, extras, flags) is covered
 * by integration testing. These unit tests verify interaction correctness
 * (clipboard used, activity started, ShareBackHelper delegated to).
 */
class NotificationActionReceiverTest {

    private lateinit var receiver: NotificationActionReceiver
    private lateinit var context: Context
    private lateinit var intent: Intent
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var packageManager: PackageManager

    private val transcriptionText = "This is a test transcription"

    @Before
    fun setup() {
        receiver = NotificationActionReceiver()
        context = mockk(relaxed = true)
        intent = mockk(relaxed = true)
        clipboardManager = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)

        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboardManager
        every { context.packageManager } returns packageManager

        // Mock Android framework statics
        mockkStatic(android.os.Looper::class)
        every { android.os.Looper.getMainLooper() } returns mockk(relaxed = true)

        mockkStatic(android.widget.Toast::class)
        every { android.widget.Toast.makeText(any(), any<Int>(), any()) } returns mockk(relaxed = true)

        mockkStatic(ClipData::class)
        every { ClipData.newPlainText(any(), any<String>()) } returns mockk(relaxed = true)

        // Mock Intent.createChooser to return a non-null Intent
        mockkStatic(Intent::class)
        every { Intent.createChooser(any(), any()) } returns mockk(relaxed = true)

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
    fun `ACTION_COPY with valid text copies to clipboard`() {
        every { intent.action } returns NotificationActionReceiver.ACTION_COPY_TRANSCRIPTION
        every { intent.getStringExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT) } returns transcriptionText

        receiver.onReceive(context, intent)

        verify(exactly = 1) { clipboardManager.setPrimaryClip(any()) }
        verify { ClipData.newPlainText(any<String>(), transcriptionText) }
    }

    @Test
    fun `ACTION_SHARE with valid text starts chooser activity`() {
        every { intent.action } returns NotificationActionReceiver.ACTION_SHARE_TRANSCRIPTION
        every { intent.getStringExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT) } returns transcriptionText

        receiver.onReceive(context, intent)

        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun `ACTION_SHARE_BACK with valid text calls ShareBackHelper`() {
        mockkObject(ShareBackHelper)
        every { ShareBackHelper.shareBack(any(), any(), any(), any(), any(), any()) } just Runs

        val sourcePackage = "com.whatsapp"
        every { intent.action } returns NotificationActionReceiver.ACTION_SHARE_BACK
        every { intent.getStringExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT) } returns transcriptionText
        every { intent.getStringExtra(NotificationActionReceiver.EXTRA_SOURCE_PACKAGE) } returns sourcePackage

        val appInfo = mockk<ApplicationInfo>(relaxed = true)
        every { packageManager.getApplicationInfo(sourcePackage, 0) } returns appInfo
        every { packageManager.getApplicationLabel(appInfo) } returns "WhatsApp"

        receiver.onReceive(context, intent)

        verify(exactly = 1) {
            ShareBackHelper.shareBack(
                context = context,
                packageName = sourcePackage,
                appName = "WhatsApp",
                transcriptionText = transcriptionText,
                onSuccess = any(),
                onError = any()
            )
        }
    }

    @Test
    fun `blank text on any action returns early without side effects`() {
        // Copy with blank text
        every { intent.action } returns NotificationActionReceiver.ACTION_COPY_TRANSCRIPTION
        every { intent.getStringExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT) } returns "   "
        receiver.onReceive(context, intent)

        // Share with null text
        every { intent.action } returns NotificationActionReceiver.ACTION_SHARE_TRANSCRIPTION
        every { intent.getStringExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT) } returns null
        receiver.onReceive(context, intent)

        // ShareBack with empty text
        every { intent.action } returns NotificationActionReceiver.ACTION_SHARE_BACK
        every { intent.getStringExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT) } returns ""
        receiver.onReceive(context, intent)

        verify(exactly = 0) { clipboardManager.setPrimaryClip(any()) }
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `unknown action completes without error or side effects`() {
        every { intent.action } returns "com.unknown.action"

        receiver.onReceive(context, intent)

        verify(exactly = 0) { clipboardManager.setPrimaryClip(any()) }
        verify(exactly = 0) { context.startActivity(any()) }
    }
}
