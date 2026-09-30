package com.antivocale.app.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * TASK-727: the 1.13.2 Play crash (UnknownFormatConversionException 'v',
 * retraced to stringResource(R.string.benchmark_progress, pct) inside
 * BenchmarkRunningContent) threw because SOME format template reached
 * String.format with a bad specifier. Every shipped string is clean
 * today; this pin formats EVERY positional-format string and EVERY
 * plural item through the REAL Android resources pipeline
 * (aapt2-compiled resources, Resources.getString/getQuantityString) in
 * EVERY shipped locale, so a translation introducing a broken specifier
 * fails HERE, not on a user device. The locale set is DERIVED from the
 * res tree (the StringResourceParityTest lesson: hardcoded locale lists
 * drift; an earlier sweep covered values-it only while the app shipped
 * 12 locales).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FormattedResourcesPipelineTest {

    private fun repoFile(relative: String): File {
        val module = File(relative)
        val root = File("app/$relative")
        return when {
            module.exists() -> module
            root.exists() -> root
            else -> throw IllegalStateException("cannot locate $relative")
        }
    }

    /** The shipped locale set, derived from values-* dirs (plus the default). */
    private fun shippedLocales(): List<String> {
        val dirs = repoFile("src/main/res").listFiles { f -> f.isDirectory }
            ?.map { it.name } ?: emptyList()
        val tags = dirs.filter { it.startsWith("values-") }
            .map { it.removePrefix("values-").replace("-r", "-") }
        return (listOf("en") + tags).distinct()
    }

    private fun contextFor(tag: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }

    /**
     * Representative args for a template's specifiers, positional or not
     * (s -> text, d -> int, f -> float); the %% literal is not a specifier.
     */
    private fun argsFor(template: String): Array<Any> {
        // Optional positional index: one regex covers %1$s AND plain %s,
        // and mixed templates get an arg per specifier in order.
        val anySpec = Regex("%(?:\\d+\\$)?([-#+ 0,(]*)(?:\\d+)?(?:\\.\\d+)?([sdf])")
        val specs = anySpec.findAll(template).map { it.groupValues[2] }.toList()
        return specs.map { spec ->
            when (spec) {
                "s" -> "x"
                "d" -> 1
                else -> 1.0f
            }
        }.toTypedArray()
    }

    @Test
    fun `every positional-format string formats without throwing in every shipped locale`() {
        var checked = 0
        val stringIds = R.string::class.java.fields
        for (field in stringIds) {
            val id = field.getInt(null)
            for (tag in shippedLocales()) {
                val template = contextFor(tag).resources.getString(id)
                if ('%' !in template) continue
                // The pipeline call itself is the pin: a bad specifier in
                // any locale throws UnknownFormatConversionException here.
                contextFor(tag).getString(id, *argsFor(template))
                checked++
            }
        }
        assertTrue("sweep checked nothing; the derivation broke", checked > 500)
    }

    @Test
    fun `every plural item formats without throwing in every shipped locale`() {
        var checked = 0
        val pluralIds = R.plurals::class.java.fields
        for (field in pluralIds) {
            val id = field.getInt(null)
            for (tag in shippedLocales()) {
                val ctx = contextFor(tag)
                // quantity 1 and 5 cover the one/other (and ru/uk/pl few/many) arms.
                for (quantity in intArrayOf(1, 5)) {
                    val template = runCatching { ctx.resources.getQuantityText(id, quantity)?.toString() }
                        .getOrNull() ?: continue
                    if ('%' !in template) continue
                    ctx.resources.getQuantityString(id, quantity, *argsFor(template))
                    checked++
                }
            }
        }
        assertTrue("plural sweep checked nothing; the derivation broke", checked > 20)
    }

    @Test
    fun `benchmark_progress renders its number and the literal percent per locale`() {
        // The crash-site string pinned by shape, not just by not-throwing:
        // the number appears in the LOCALE'S OWN digits (fa renders ۱; a
        // first-draft assertion tripped on exactly this).
        for (tag in shippedLocales()) {
            val rendered = contextFor(tag).getString(R.string.benchmark_progress, 42)
            val expectedNumber = java.text.NumberFormat.getIntegerInstance(
                Locale.forLanguageTag(tag)).format(42)
            assertTrue("locale $tag rendered '$rendered' without its number",
                rendered.contains(expectedNumber))
            assertTrue("locale $tag rendered '$rendered' without the literal percent",
                rendered.contains("%"))
        }
    }
}
