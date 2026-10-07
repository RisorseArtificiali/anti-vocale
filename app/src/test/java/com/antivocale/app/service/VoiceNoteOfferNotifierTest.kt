package com.antivocale.app.service

import android.content.Context
import com.antivocale.app.data.VoiceNoteOffer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * TASK-271: the offer notification's text derivation (Robolectric for the
 * string resources; the notification post itself is device-trial territory).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceNoteOfferNotifierTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `offer text degrades by the available facts`() {
        val notifier = VoiceNoteOfferNotifier(context)
        assertEquals(
            "From Anna (0:42)",
            notifier.offerText(VoiceNoteOffer("/p", "Anna", "com.whatsapp", 42L)))
        assertEquals(
            "Voice message (1:30)",
            notifier.offerText(VoiceNoteOffer("/p", null, "com.whatsapp", 90L)))
        assertEquals(
            "Voice message",
            notifier.offerText(VoiceNoteOffer("/p", null, "com.whatsapp", null)))
    }
}
