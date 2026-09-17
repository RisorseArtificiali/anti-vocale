package com.antivocale.app.util

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-547 follow-up (found by /code-review 2026-09-17): the default-locale
 * language_phone_option shipped a bare "%1" placeholder. String.format with
 * that pattern throws UnknownFormatConversionException at render time, and
 * values/ is the fallback for every locale without a translation, so the
 * crash hit stock-English devices while all 11 translated copies were fine
 * (which is why the unit suite never formatted it).
 *
 * Source scan (same shape as TestNavigationTest's wiring checks): Android
 * positional placeholders are "%N$..."; a "%N" NOT followed by "$" is always
 * a typo in a string resource. Covers the strings.xml of every locale.
 */
class StringFormatPlaceholdersTest {

    @Test
    fun `no string resource carries a positional placeholder without the dollar`() {
        val resDir = java.io.File("src/main/res")
            .let { if (it.isDirectory) it else java.io.File("app/src/main/res") }
        val stringFiles = resDir.walkTopDown()
            .filter { it.isFile && it.parentFile?.name?.startsWith("values") == true && it.name == "strings.xml" }
            .toList()
        assertTrue("no strings.xml found under ${resDir.absolutePath}", stringFiles.isNotEmpty())

        // %1$s stays legal; a %1 NOT followed by $ is the typo class.
        val barePositional = Regex("""%\d+(?!\$)""")
        val offenders = stringFiles.flatMap { f ->
            f.readLines().withIndex()
                .filter { (_, line) -> barePositional.containsMatchIn(line) }
                .map { (i, line) -> "${f.parentFile?.name}/${f.name}:${i + 1}: ${line.trim()}" }
        }
        assertTrue(
            "Bare positional placeholder(s) without the dollar sign; String.format will throw:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
