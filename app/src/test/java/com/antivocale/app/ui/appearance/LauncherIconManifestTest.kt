package com.antivocale.app.ui.appearance

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Pins the launcher activity-alias block of app/src/main/AndroidManifest.xml
 * (TASK-392), the same way BackendRegistryTest pins the share-target alias
 * literals: four Launcher* aliases, exactly one enabled (LauncherDefault), all
 * exported with a MAIN/LAUNCHER filter pointing at MainActivity, and no other
 * component carrying the LAUNCHER category.
 *
 * Reads the manifest from disk like StringResourceParityTest: unit tests run
 * with the module directory as working directory, and the repo root is probed
 * as a fallback. The main manifest is the only launcher declaration site: no
 * flavor overlay re-declares activities or aliases.
 */
class LauncherIconManifestTest {

    private fun manifestFile(): File {
        val moduleRelative = File("src/main/AndroidManifest.xml")
        val rootRelative = File("app/src/main/AndroidManifest.xml")
        return when {
            moduleRelative.exists() -> moduleRelative
            rootRelative.exists() -> rootRelative
            else -> throw IllegalStateException("Cannot locate src/main/AndroidManifest.xml from ${File(".").absolutePath}")
        }
    }

    private fun launcherAliases(): List<Element> =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(manifestFile())
            .getElementsByTagName("activity-alias")
            .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
            .filter { it.getAttribute("android:name").startsWith(".Launcher") }

    private fun Element.hasLauncherFilter(): Boolean =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == "intent-filter" }
            .any { filter ->
                val categories = filter.getElementsByTagName("category")
                (0 until categories.length).any {
                    categories.item(it).attributes.getNamedItem("android:name").nodeValue ==
                        "android.intent.category.LAUNCHER"
                }
            }

    @Test
    fun `exactly the four curated launcher aliases exist`() {
        assertEquals(
            listOf(".LauncherDefault", ".LauncherTeal", ".LauncherInk", ".LauncherAmber"),
            launcherAliases().map { it.getAttribute("android:name") },
        )
    }

    @Test
    fun `launcher aliases match the manager's pinned component names`() {
        val expected = LauncherIconVariant.entries.map { ".Launcher" + it.name.lowercase().replaceFirstChar { c -> c.uppercaseChar() } }
        assertEquals(expected, launcherAliases().map { it.getAttribute("android:name") })
        assertEquals(
            expected.map { "com.antivocale.app$it" },
            LauncherIconVariant.entries.map { it.aliasComponentName },
        )
    }

    @Test
    fun `all aliases target MainActivity, are exported, keep the app label and own a launcher icon`() {
        launcherAliases().forEach { alias ->
            assertEquals(".MainActivity", alias.getAttribute("android:targetActivity"))
            assertEquals("true", alias.getAttribute("android:exported"))
            assertEquals("@string/app_name", alias.getAttribute("android:label"))
            assertTrue(
                "alias ${alias.getAttribute("android:name")} needs its own icon",
                alias.getAttribute("android:icon").startsWith("@mipmap/"),
            )
            assertTrue(
                "alias ${alias.getAttribute("android:name")} needs the MAIN/LAUNCHER filter",
                alias.hasLauncherFilter(),
            )
        }
    }

    @Test
    fun `exactly one alias is enabled and it is LauncherDefault`() {
        val enabled = launcherAliases().filter { it.getAttribute("android:enabled") == "true" }
        assertEquals(listOf(".LauncherDefault"), enabled.map { it.getAttribute("android:name") })
        assertEquals(
            3,
            launcherAliases().count { it.getAttribute("android:enabled") == "false" },
        )
    }

    @Test
    fun `no activity carries a LAUNCHER filter outside the aliases`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile())
        val activities = doc.getElementsByTagName("activity")
        val offenders = (0 until activities.length)
            .map { activities.item(it) as Element }
            .filter { it.hasLauncherFilter() }
        assertTrue("activities must not own the LAUNCHER filter: $offenders", offenders.isEmpty())
    }

    @Test
    fun `alias icons are pairwise distinct`() {
        val icons = launcherAliases().map { it.getAttribute("android:icon") }
        assertEquals(
            "every alias must ship its own icon (pairwise distinct): $icons",
            icons.size,
            icons.toSet().size,
        )
    }

    @Test
    fun `every referenced adaptive icon carries a monochrome layer`() {
        // Default + the three variants: each alias icon resolves to one
        // mipmap-anydpi-v26 adaptive XML, which needs <monochrome> for themed
        // (Android 13+) launcher icons.
        val resDir = File(manifestFile().parentFile, "res")
        launcherAliases().forEach { alias ->
            val icon = alias.getAttribute("android:icon")
            assertTrue("unexpected icon reference $icon", icon.startsWith("@mipmap/"))
            val xml = File(resDir, "mipmap-anydpi-v26/${icon.removePrefix("@mipmap/")}.xml")
            assertTrue("missing adaptive-icon XML ${xml.path}", xml.exists())
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
            assertTrue(
                "$icon needs a <monochrome> layer for themed icons",
                doc.getElementsByTagName("monochrome").length > 0,
            )
        }
    }
}
