package com.antivocale.app.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import com.antivocale.app.data.PerAppPreferencesManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AppInfoUtils.getAppName().
 *
 * Verifies hardcoded app name lookups, graceful fallback when
 * an app is not installed, and display label resolution via PackageManager.
 */
class AppInfoUtilsTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        packageManager = mockk()
        every { context.packageManager } returns packageManager
    }

    @Test
    fun `getAppName returns empty string for null packageName`() {
        val result = AppInfoUtils.getAppName(context, null)

        assertEquals("", result)
    }

    @Test
    fun `getAppName returns WhatsApp for com whatsapp`() {
        val result = AppInfoUtils.getAppName(context, PerAppPreferencesManager.WHATSAPP)

        assertEquals("WhatsApp", result)
    }

    @Test
    fun `getAppName returns Telegram for org telegram messenger`() {
        val result = AppInfoUtils.getAppName(context, PerAppPreferencesManager.TELEGRAM)

        assertEquals("Telegram", result)
    }

    @Test
    fun `getAppName returns Signal for org thoughtcrime securesms`() {
        val result = AppInfoUtils.getAppName(context, PerAppPreferencesManager.SIGNAL)

        assertEquals("Signal", result)
    }

    @Test
    fun `getAppName returns package name when app is not installed`() {
        val unknownPackage = "com.unknown.app"
        every { packageManager.getApplicationInfo(unknownPackage, 0) } throws NameNotFoundException()

        val result = AppInfoUtils.getAppName(context, unknownPackage)

        assertEquals(unknownPackage, result)
    }

    @Test
    fun `getAppName returns display label for installed app`() {
        val installedPackage = "com.installed.app"
        val appInfo = mockk<ApplicationInfo>()
        every { packageManager.getApplicationInfo(installedPackage, 0) } returns appInfo
        every { packageManager.getApplicationLabel(appInfo) } returns "My App"

        val result = AppInfoUtils.getAppName(context, installedPackage)

        assertEquals("My App", result)
    }
}
