package com.antivocale.app.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ComponentInfoParser
 *
 * Tests the package name extraction logic from ComponentInfo strings
 * returned by Android's share chooser.
 *
 * This class has no Android dependencies, allowing clean unit testing.
 */
class ComponentInfoParserTest {

    /**
     * Test extracting package name from valid WhatsApp ComponentInfo
     *
     * Input: "ComponentInfo{com.whatsapp/com.whatsapp.ShareActivity}"
     * Expected: "com.whatsapp"
     */
    @Test
    fun `extractPackageName handles valid WhatsApp ComponentInfo`() {
        val componentInfo = "ComponentInfo{com.whatsapp/com.whatsapp.ShareActivity}"
        val packageName = ComponentInfoParser.extractPackageName(componentInfo)

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
        val packageName = ComponentInfoParser.extractPackageName(componentInfo)

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
        val packageName = ComponentInfoParser.extractPackageName(componentInfo)

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
        val packageName = ComponentInfoParser.extractPackageName(componentInfo)

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
        val packageName = ComponentInfoParser.extractPackageName(componentInfo)

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
        val packageName = ComponentInfoParser.extractPackageName("")

        assertNull(packageName)
    }

    /**
     * Test handling ComponentInfo with closing brace after activity name
     *
     * Input: "ComponentInfo{com.whatsapp/com.whatsapp.ShareActivity}"
     * Expected: "com.whatsapp" (brace after activity name handled)
     */
    @Test
    fun `extractPackageName handles trailing brace in ComponentInfo`() {
        val componentInfo = "ComponentInfo{com.whatsapp/com.whatsapp.ShareActivity}"
        val packageName = ComponentInfoParser.extractPackageName(componentInfo)

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
        val packageName = ComponentInfoParser.extractPackageName(componentInfo)

        // "WhatsApp" doesn't contain dots, so it's invalid
        assertNull(packageName)
    }

    /**
     * Test handling package name with spaces (invalid format)
     *
     * Input: "ComponentInfo{com .whatsapp/com.whatsapp.ShareActivity}"
     * Expected: null (package names shouldn't contain spaces)
     */
    @Test
    fun `extractPackageName returns null for package name with spaces`() {
        val componentInfo = "ComponentInfo{com .whatsapp/com.whatsapp.ShareActivity}"
        val packageName = ComponentInfoParser.extractPackageName(componentInfo)

        assertNull(packageName)
    }
}
