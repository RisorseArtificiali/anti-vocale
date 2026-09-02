package com.antivocale.app.util

import android.content.Context
import android.content.pm.PackageManager
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-320: the known-package name map must cover the major share-source
 * apps so the Logs grouping never depends on PackageManager visibility
 * (Android 11+ package visibility intermittently fails for packages the
 * app has not queried, falling back to the raw "com.*" name).
 *
 * TASK-433: that map is now a prefix/family table that also drives the
 * notification Share Back targeting; the censused Telegram forks and the
 * uncensused-family fallback are pinned here.
 */
class AppInfoUtilsKnownNamesTest {

    @Test
    fun `known chat and file apps map to their logical names`() {
        assertEquals("WhatsApp", AppInfoUtils.knownAppName("com.whatsapp"))
        assertEquals("WhatsApp Business", AppInfoUtils.knownAppName("com.whatsapp.w4b"))
        assertEquals("Telegram", AppInfoUtils.knownAppName("org.telegram.messenger"))
        assertEquals("Signal", AppInfoUtils.knownAppName("org.thoughtcrime.securesms"))
        assertEquals("Files by Google", AppInfoUtils.knownAppName("com.google.android.apps.nbu.files"))
    }

    @Test
    fun `censused telegram forks map to their verified names`() {
        assertEquals("AyuGram", AppInfoUtils.knownAppName("com.radolyn.ayugram"))
        assertEquals("Nekogram", AppInfoUtils.knownAppName("tw.nekomimi.nekogram"))
        assertEquals("Nekogram", AppInfoUtils.knownAppName("tw.nekomimi.nekogram.beta"))
        assertEquals("exteraGram", AppInfoUtils.knownAppName("com.exteragram.messenger"))
        assertEquals("exteraGram", AppInfoUtils.knownAppName("com.exteragram.messenger.beta"))
        assertEquals("Telegram X", AppInfoUtils.knownAppName("org.thunderdog.challegram"))
        assertEquals("Plus Messenger", AppInfoUtils.knownAppName("org.telegram.plus"))
        assertEquals("OwlGram", AppInfoUtils.knownAppName("it.owlgram.android"))
        assertEquals("iMe", AppInfoUtils.knownAppName("com.iMe.android"))
    }

    @Test
    fun `official telegram flavor variants keep the Telegram name`() {
        assertEquals("Telegram", AppInfoUtils.knownAppName("org.telegram.messenger.web"))
        assertEquals("Telegram", AppInfoUtils.knownAppName("org.telegram.messenger.beta"))
    }

    @Test
    fun `uncensused telegram family packages keep the label fallback`() {
        // The bare org.telegram prefix normalizes only Share Back targeting;
        // tier-2 forks stay on the PackageManager-label fallback (TASK-433).
        assertNull(AppInfoUtils.knownAppName("org.telegram.BifToGram"))
    }

    @Test
    fun `unknown packages return null so the PackageManager fallback applies`() {
        assertNull(AppInfoUtils.knownAppName("com.example.unknown"))
    }

    @Test
    fun `share back targets the canonical client of the app family`() {
        assertEquals("com.whatsapp", AppInfoUtils.shareBackTarget("com.whatsapp"))
        assertEquals("com.whatsapp", AppInfoUtils.shareBackTarget("com.whatsapp.w4b"))
        assertEquals("org.telegram.messenger", AppInfoUtils.shareBackTarget("org.telegram.messenger"))
        assertEquals("org.telegram.messenger", AppInfoUtils.shareBackTarget("com.radolyn.ayugram"))
        assertEquals("org.telegram.messenger", AppInfoUtils.shareBackTarget("tw.nekomimi.nekogram.beta"))
        assertEquals("org.telegram.messenger", AppInfoUtils.shareBackTarget("com.exteragram.messenger.beta"))
        assertEquals("org.telegram.messenger", AppInfoUtils.shareBackTarget("org.thunderdog.challegram"))
        assertEquals("org.telegram.messenger", AppInfoUtils.shareBackTarget("org.telegram.plus"))
        assertEquals("org.telegram.messenger", AppInfoUtils.shareBackTarget("it.owlgram.android"))
        assertEquals("org.telegram.messenger", AppInfoUtils.shareBackTarget("com.iMe.android"))
        // Uncensused family members keep the pre-TASK-433 startsWith behavior.
        assertEquals("org.telegram.messenger", AppInfoUtils.shareBackTarget("org.telegram.BifToGram"))
    }

    @Test
    fun `share back keeps the exact source for family-less apps`() {
        assertEquals("org.thoughtcrime.securesms", AppInfoUtils.shareBackTarget("org.thoughtcrime.securesms"))
        assertEquals("com.example.unknown", AppInfoUtils.shareBackTarget("com.example.unknown"))
        assertNull(AppInfoUtils.shareBackTarget(null))
    }

    @Test
    fun `null and blank package names return null`() {
        assertNull(AppInfoUtils.knownAppName(null))
        assertNull(AppInfoUtils.knownAppName(""))
    }

    @Test
    fun `getAppName prefers known name over PackageManager`() {
        val context = mockk<Context>(relaxed = true)
        val pm = mockk<PackageManager>(relaxed = true)
        every { context.packageManager } returns pm
        assertEquals("Files by Google", AppInfoUtils.getAppName(context, "com.google.android.apps.nbu.files"))
        verify { pm wasNot Called }
    }

    @Test
    fun `getAppName falls back to package when label is blank or resolution fails`() {
        val context = mockk<Context>(relaxed = true)
        val pm = mockk<PackageManager>(relaxed = true)
        every { context.packageManager } returns pm

        // Blank label: must degrade to the raw package, never to empty text.
        every { pm.getApplicationLabel(any()) } returns ""
        assertEquals("com.example.unknown", AppInfoUtils.getAppName(context, "com.example.unknown"))

        // Resolution failure (NameNotFoundException on old devices / hidden packages).
        every { pm.getApplicationInfo(any<String>(), any<Int>()) } throws PackageManager.NameNotFoundException()
        assertEquals("com.example.unknown", AppInfoUtils.getAppName(context, "com.example.unknown"))
    }
}
