package com.localai.bridge.receiver

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ChooserBroadcastReceiver
 *
 * Tests the package name extraction logic from ComponentInfo strings
 * returned by Android's share chooser.
 */
class ChooserBroadcastReceiverTest {

    private lateinit var receiver: ChooserBroadcastReceiver

    @Before
    fun setup() {
        receiver = ChooserBroadcastReceiver()
    }

    /**
     * Test extracting package name from valid ComponentInfo format
     *
     * Input: "ComponentInfo{com.whatsapp/com.whatsapp.ShareActivity}"
     * Expected: "com.whatsapp"
     */
    @Test
    fun `extractPackageName handles valid WhatsApp ComponentInfo`() {
        val componentInfo = "ComponentInfo{com.whatsapp/com.whatsapp.ShareActivity}"
        val packageName = extractPackageNameFromComponentInfo(componentInfo)

        assertEquals("com.whatsapp", packageName)
    }

    /**
     * Test extracting package name from valid Telegram ComponentInfo
     *
     * Input: "ComponentInfo{org.telegram.messenger/org.telegram.ui.LaunchActivity}"
     * Expected: "org.telegram.messenger"
     */
    @Test
    fun `extractPackageName handles valid Telegram ComponentInfo`() {
        val componentInfo = "ComponentInfo{org.telegram.messenger/org.telegram.ui.LaunchActivity}"
        val packageName = extractPackageNameFromComponentInfo(componentInfo)

        assertEquals("org.telegram.messenger", packageName)
    }

    /**
     * Test extracting package name from valid Signal ComponentInfo
     *
     * Input: "ComponentInfo{org.thoughtcrime.securesms/org.thoughtcrime.securesms.RoutingActivity}"
     * Expected: "org.thoughtcrime.securesms"
     */
    @Test
    fun `extractPackageName handles valid Signal ComponentInfo`() {
        val componentInfo = "ComponentInfo{org.thoughtcrime.securesms/org.thoughtcrime.securesms.RoutingActivity}"
        val packageName = extractPackageNameFromComponentInfo(componentInfo)

        assertEquals("org.thoughtcrime.securesms", packageName)
    }

    /**
     * Test handling malformed ComponentInfo without ComponentInfo prefix
     *
     * Input: "com.whatsapp/com.whatsapp.ShareActivity" (missing prefix)
     * Expected: null (graceful failure)
     */
    @Test
    fun `extractPackageName returns null for malformed ComponentInfo missing prefix`() {
        val componentInfo = "com.whatsapp/com.whatsapp.ShareActivity"
        val packageName = extractPackageNameFromComponentInfo(componentInfo)

        assertNull(packageName)
    }

    /**
     * Test handling ComponentInfo without slash separator
     *
     * Input: "ComponentInfo{com.whatsapp}"
     * Expected: null (invalid format)
     */
    @Test
    fun `extractPackageName returns null for ComponentInfo without slash`() {
        val componentInfo = "ComponentInfo{com.whatsapp}"
        val packageName = extractPackageNameFromComponentInfo(componentInfo)

        assertNull(packageName)
    }

    /**
     * Test handling empty string
     *
     * Input: ""
     * Expected: null (graceful failure)
     */
    @Test
    fun `extractPackageName returns null for empty string`() {
        val packageName = extractPackageNameFromComponentInfo("")

        assertNull(packageName)
    }

    /**
     * Test handling ComponentInfo with closing brace before slash
     *
     * Input: "ComponentInfo{com.whatsapp}"
     * Expected: null (no slash, invalid format)
     */
    @Test
    fun `extractPackageName returns null for ComponentInfo with only package name`() {
        val componentInfo = "ComponentInfo{com.whatsapp}"
        val packageName = extractPackageNameFromComponentInfo(componentInfo)

        assertNull(packageName)
    }

    /**
     * Test extracting package name with trailing brace after activity name
     *
     * Input: "ComponentInfo{com.whatsapp/com.whatsapp.ShareActivity}"
     * Expected: "com.whatsapp" (brace after activity name handled)
     */
    @Test
    fun `extractPackageName handles trailing brace in ComponentInfo`() {
        val componentInfo = "ComponentInfo{com.whatsapp/com.whatsapp.ShareActivity}"
        val packageName = extractPackageNameFromComponentInfo(componentInfo)

        assertEquals("com.whatsapp", packageName)
    }

    /**
     * Test handling package name without dots (invalid format)
     *
     * Input: "ComponentInfo{WhatsApp/com.whatsapp.ShareActivity}"
     * Expected: null (package names must contain dots)
     */
    @Test
    fun `extractPackageName returns null for package name without dots`() {
        val componentInfo = "ComponentInfo{WhatsApp/com.whatsapp.ShareActivity}"
        val packageName = extractPackageNameFromComponentInfo(componentInfo)

        // "WhatsApp" doesn't contain dots, so it's invalid
        assertNull(packageName)
    }

    /**
     * Helper method to test the private extractPackageName function using reflection
     *
     * This allows us to test the private extractPackageName method without
     * exposing it publicly.
     */
    private fun extractPackageNameFromComponentInfo(componentInfo: String): String? {
        // Use reflection to access the private method
        val method = ChooserBroadcastReceiver::class.java.getDeclaredMethod(
            "extractPackageName",
            String::class.java
        )
        method.isAccessible = true

        return method.invoke(receiver, componentInfo) as? String
    }
}
