package com.antivocale.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.antivocale.app.data.PerAppPreferencesManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented tests for ChooserBroadcastReceiver.
 *
 * Uses real Android Context and Intent objects to verify the
 * broadcast chain end-to-end without mocks.
 */
@RunWith(AndroidJUnit4::class)
class ChooserBroadcastReceiverInstrumentedTest {

    private lateinit var context: Context
    private lateinit var receiver: ChooserBroadcastReceiver

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        receiver = ChooserBroadcastReceiver()
    }

    /**
     * Sends a chooser intent with the given ComponentInfo and waits for the
     * resulting broadcast. Returns the detected package name, or null if
     * no broadcast was received within [timeoutSeconds].
     */
    private fun sendChooserAndWait(
        componentInfo: String?,
        timeoutSeconds: Long = 2
    ): String? {
        val latch = CountDownLatch(1)
        var receivedPackage: String? = null

        val filter = IntentFilter(ChooserBroadcastReceiver.ACTION_SHARE_CHOSEN)
        val listener = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                receivedPackage = intent.getStringExtra(ChooserBroadcastReceiver.EXTRA_DETECTED_PACKAGE)
                latch.countDown()
            }
        }

        context.registerReceiver(listener, filter)
        try {
            val chooserIntent = Intent().apply {
                componentInfo?.let { putExtra(ChooserBroadcastReceiver.EXTRA_CHOSEN_COMPONENT, it) }
            }
            receiver.onReceive(context, chooserIntent)
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) return null
        } finally {
            context.unregisterReceiver(listener)
        }
        return receivedPackage
    }

    private fun componentInfo(packageName: String) =
        "ComponentInfo{$packageName/$packageName.SomeActivity}"

    @Test
    fun `WhatsApp ComponentInfo sends broadcast with correct package`() {
        val result = sendChooserAndWait(componentInfo(PerAppPreferencesManager.WHATSAPP))

        assertEquals(PerAppPreferencesManager.WHATSAPP, result)
    }

    @Test
    fun `Telegram ComponentInfo sends broadcast with correct package`() {
        val result = sendChooserAndWait(componentInfo(PerAppPreferencesManager.TELEGRAM))

        assertEquals(PerAppPreferencesManager.TELEGRAM, result)
    }

    @Test
    fun `blank componentInfo does not send broadcast`() {
        val result = sendChooserAndWait("   ", timeoutSeconds = 1)

        assertNull("No broadcast should be received for blank componentInfo", result)
    }

    @Test
    fun `null intent does not crash`() {
        receiver.onReceive(context, null)
    }
}
