package com.antivocale.app.ui.appearance

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Pins the launcher activity-alias block of app/src/main/AndroidManifest.xml
 * (TASK-392, TASK-473), the same way BackendRegistryTest pins the share-target
 * alias literals: seven Launcher* aliases (Default + derei's six concepts; the
 * recolor set was retired in the TASK-473 rework),
 * exactly one enabled (LauncherDefault), all exported with a MAIN/LAUNCHER
 * filter pointing at MainActivity, and no other component carrying the LAUNCHER
 * category.
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
        parse(manifestFile())
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
    fun `exactly the seven curated launcher aliases exist`() {
        assertEquals(
            listOf(
                ".LauncherDefault",
                ".LauncherWavecut", ".LauncherCrossed", ".LauncherTextblock",
                ".LauncherMonogram", ".LauncherCapsule", ".LauncherMutebar",
            ),
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
            6,
            launcherAliases().count { it.getAttribute("android:enabled") == "false" },
        )
    }

    @Test
    fun `no activity carries a LAUNCHER filter outside the aliases`() {
        val doc = parse(manifestFile())
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
        // Each alias icon resolves to one mipmap-anydpi-v26 adaptive XML, which
        // needs <monochrome> for themed (Android 13+) launcher icons.
        adaptiveIconDocs().forEach { (aliasName, icon, doc) ->
            assertTrue(
                "$icon ($aliasName) needs a <monochrome> layer for themed icons",
                doc.getElementsByTagName("monochrome").length > 0,
            )
        }
    }

    /**
     * Pins the variant-to-artwork seam: every alias's adaptive XML must
     * reference its own variant's layers. A copy-paste swap between variants
     * (between two derei concepts) compiles and passes every
     * other test, surfacing only as a wrong icon on a device (found in the
     * TASK-473 review).
     */
    @Test
    fun `adaptive icons reference their own variant artwork`() {
        adaptiveIconDocs().forEach { (aliasName, icon, doc) ->
            if (aliasName == ".LauncherDefault") {
                // The original icon: the PNG background the launcher shows and
                // the shared foreground PNG for both layers.
                layer(doc, "foreground").let {
                    assertTrue("$icon default foreground: $it", it.endsWith("ic_launcher_foreground"))
                }
                layer(doc, "monochrome").let {
                    assertTrue("$icon default monochrome: $it", it.endsWith("ic_launcher_foreground"))
                }
                layer(doc, "background").let {
                    assertTrue("$icon default background: $it", it.endsWith("ic_launcher_background"))
                }
            } else {
                // The slug is the alias name minus its Launcher prefix: the
                // enum's variant name, lowercased (LauncherIconVariantResourcesTest
                // pins the same derivation from the other side of the seam).
                val slug = aliasName.removePrefix(".Launcher").lowercase()
                layer(doc, "foreground").let {
                    assertTrue("$icon ($aliasName) foreground must be the $slug layer: $it",
                        it.endsWith("fg_derei_$slug"))
                }
                layer(doc, "monochrome").let {
                    assertTrue("$icon ($aliasName) monochrome must reuse the foreground's alpha: $it",
                        it.endsWith("fg_derei_$slug"))
                }
                layer(doc, "background").let {
                    assertTrue("$icon ($aliasName) background must be the shared gradient: $it",
                        it.endsWith("launcher_icon_derei_bg"))
                }
            }
        }
    }

    private fun adaptiveIconDocs(): List<Triple<String, String, Document>> {
        val resDir = File(manifestFile().parentFile, "res")
        return launcherAliases().map { alias ->
            val icon = alias.getAttribute("android:icon")
            assertTrue("unexpected icon reference $icon", icon.startsWith("@mipmap/"))
            val xml = File(resDir, "mipmap-anydpi-v26/${icon.removePrefix("@mipmap/")}.xml")
            assertTrue("missing adaptive-icon XML ${xml.path}", xml.exists())
            Triple(alias.getAttribute("android:name"), icon, parse(xml))
        }
    }

    private fun layer(doc: Document, tag: String): String {
        val node = doc.getElementsByTagName(tag).item(0)
            ?: error("adaptive icon is missing its <$tag> layer")
        return node.attributes?.getNamedItem("android:drawable")?.nodeValue
            ?: error("<$tag> layer is missing android:drawable")
    }

    private fun parse(file: File): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
}
