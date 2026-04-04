package com.antivocale.app.receiver

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.antivocale.app.data.PerAppPreferencesManager
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ChooserBroadcastReceiver.
 *
 * Tests the onReceive() method which extracts package names from
 * Android's share chooser ComponentInfo and re-broadcasts them
 * to the app with appropriate restrictions.
 *
 * Note: Intent content verification (extras, action, flags) is done in
 * ChooserBroadcastReceiverInstrumentedTest. These unit tests verify
 * interaction correctness (sendBroadcast called/not called).
 */
class ChooserBroadcastReceiverTest {

    private lateinit var receiver: ChooserBroadcastReceiver
    private lateinit var context: Context
    private lateinit var intent: Intent
    private lateinit var bundle: Bundle

    @Before
    fun setup() {
        receiver = ChooserBroadcastReceiver()
        context = mockk(relaxed = true)
        intent = mockk(relaxed = true)
        bundle = mockk(relaxed = true)

        every { intent.extras } returns bundle
        every { bundle.getSerializable(any(), eq(String::class.java)) } returns null
        every { context.packageName } returns "com.antivocale.app"
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun componentInfo(packageName: String) = "ComponentInfo{$packageName/$packageName.SomeActivity}"

    @Test
    fun `onReceive with WhatsApp ComponentInfo broadcasts detected package`() {
        every { bundle.getSerializable(ChooserBroadcastReceiver.EXTRA_CHOSEN_COMPONENT, String::class.java) } returns componentInfo(PerAppPreferencesManager.WHATSAPP)

        receiver.onReceive(context, intent)

        verify(exactly = 1) { context.sendBroadcast(any()) }
    }

    @Test
    fun `onReceive with Telegram ComponentInfo broadcasts detected package`() {
        every { bundle.getSerializable(ChooserBroadcastReceiver.EXTRA_CHOSEN_COMPONENT, String::class.java) } returns componentInfo(PerAppPreferencesManager.TELEGRAM)

        receiver.onReceive(context, intent)

        verify(exactly = 1) { context.sendBroadcast(any()) }
    }

    @Test
    fun `onReceive with Signal ComponentInfo broadcasts detected package`() {
        every { bundle.getSerializable(ChooserBroadcastReceiver.EXTRA_CHOSEN_COMPONENT, String::class.java) } returns componentInfo(PerAppPreferencesManager.SIGNAL)

        receiver.onReceive(context, intent)

        verify(exactly = 1) { context.sendBroadcast(any()) }
    }

    @Test
    fun `onReceive with null context returns early without broadcasting`() {
        receiver.onReceive(null, intent)

        verify(exactly = 0) { context.sendBroadcast(any()) }
    }

    @Test
    fun `onReceive with null intent returns early without broadcasting`() {
        receiver.onReceive(context, null)

        verify(exactly = 0) { context.sendBroadcast(any()) }
    }

    @Test
    fun `onReceive with missing componentInfo extra does not broadcast`() {
        every { bundle.getSerializable(ChooserBroadcastReceiver.EXTRA_CHOSEN_COMPONENT, String::class.java) } returns null

        receiver.onReceive(context, intent)

        verify(exactly = 0) { context.sendBroadcast(any()) }
    }

    @Test
    fun `onReceive with blank componentInfo does not broadcast`() {
        every { bundle.getSerializable(ChooserBroadcastReceiver.EXTRA_CHOSEN_COMPONENT, String::class.java) } returns "   "

        receiver.onReceive(context, intent)

        verify(exactly = 0) { context.sendBroadcast(any()) }
    }

    @Test
    fun `onReceive with malformed componentInfo does not broadcast`() {
        every { bundle.getSerializable(ChooserBroadcastReceiver.EXTRA_CHOSEN_COMPONENT, String::class.java) } returns "not-a-valid-component-info"

        receiver.onReceive(context, intent)

        verify(exactly = 0) { context.sendBroadcast(any()) }
    }
}
