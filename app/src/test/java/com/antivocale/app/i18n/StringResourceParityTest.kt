package com.antivocale.app.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-319 guard: every string key in the default locale must exist in the
 * Italian locale (and vice versa: no dead translations). A key missing from
 * values-it renders English inside the Italian UI, which is exactly the bug
 * class this test exists to prevent ("Save transcripts to folder" shipped
 * untranslated; 70 keys were recovered in the first audit).
 *
 * Reads the res files from disk. Unit tests run with the module directory as
 * working directory; both that and the repo root are probed so the test also
 * works when launched from the root.
 */
class StringResourceParityTest {

    private fun stringsFile(path: String): File {
        val moduleRelative = File(path)
        val rootRelative = File("app/$path")
        return when {
            moduleRelative.exists() -> moduleRelative
            rootRelative.exists() -> rootRelative
            else -> throw IllegalStateException("Cannot locate $path from ${File(".").absolutePath}")
        }
    }

    private fun keys(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it).attributes }
            // TASK-604: a translatable="false" key is deliberately
            // locale-invariant (symbols, ids); demanding a copy in every
            // locale laundered English through values-it once.
            .filterNot { attrs -> attrs.getNamedItem("translatable")?.nodeValue == "false" }
            .map { attrs -> attrs.getNamedItem("name").nodeValue }
            .toSet()
    }

    @Test
    fun `every default-locale key has an Italian translation`() {
        val missing = keys(stringsFile("src/main/res/values/strings.xml")) -
            keys(stringsFile("src/main/res/values-it/strings.xml"))
        assertTrue(
            "Keys missing from values-it/strings.xml (Italian UI shows English): $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `no dead Italian translations without a default-locale key`() {
        val dead = keys(stringsFile("src/main/res/values-it/strings.xml")) -
            keys(stringsFile("src/main/res/values/strings.xml"))
        assertTrue("Dead translations in values-it (key absent from default locale): $dead", dead.isEmpty())
    }

    /**
     * TASK-604 F3: the translatable="false" exemption is one-way; without a
     * pinned allowlist, a user-visible key wrongly marked untranslatable
     * silently bypasses parity AND lint, re-enabling the laundering this
     * task removed. Empty today: adding an entry must be a conscious,
     * reviewed decision.
     */
    @Test
    fun `untranslatable keys match the reviewed allowlist`() {
        val expected = setOf<String>()
        val actual = rawKeys(stringsFile("src/main/res/values/strings.xml"))
            .filterValues { it == "false" }.keys
        assertEquals(
            "values/strings.xml carries untranslatable keys outside the allowlist " +
                "(each must be locale-invariant by nature, not to dodge translation): $actual",
            expected, actual,
        )
    }

    /**
     * TASK-604 F4: the laundering sat invisible for two days because parity
     * covered only values-it while the app ships 12 complete locales. Every
     * bundled locale now carries the full default key set.
     */
    @Test
    fun `every bundled locale carries the full default key set`() {
        val defaults = keys(stringsFile("src/main/res/values/strings.xml"))
        val locales = stringsFile("src/main/res").listFiles { f ->
            f.isDirectory && f.name.startsWith("values-")
        }.orEmpty().sortedBy { it.name }
        assertTrue("no bundled locales found under src/main/res", locales.isNotEmpty())
        val offenders = locales.mapNotNull { dir ->
            val file = dir.resolve("strings.xml")
            if (!file.exists()) return@mapNotNull null
            val missing = defaults - keys(file)
            if (missing.isEmpty()) null else "${dir.name}: $missing"
        }
        assertTrue("Locales missing default-locale keys (their UI shows English): $offenders", offenders.isEmpty())
    }

    /** Raw name-to-translatable map, before the exemption filter. */
    private fun rawKeys(file: File): Map<String, String?> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val attrs = nodes.item(it).attributes
            attrs.getNamedItem("name").nodeValue to attrs.getNamedItem("translatable")?.nodeValue
        }
    }
}
