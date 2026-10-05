package com.antivocale.app.data.catalog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.antivocale.app.R
import com.antivocale.app.transcription.BuiltInBackendIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * TASK-760: the qwen3 language count is DERIVED from the catalog at every
 * render site (the catalog displays carry countPlaceholder and the sites
 * format entry.languages.size), except model_info_best_for_qwen3, whose
 * overlay renders it as a hand-pinned literal. This guard closes the drift
 * class that shipped 52/53/59 in one release: in EVERY shipped locale the
 * three placeholder strings hold the %1$d specifier (a locale that kept a
 * literal is an unformatted site) and formatting with the catalog's count
 * yields exactly that count; the literal string keeps carrying it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Qwen3CatalogCountGuardTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val count: Int =
        BundledCatalog.byId(BuiltInBackendIds.QWEN3_ASR)?.languages?.size
            ?: error("bundled catalog is missing qwen3-asr")

    /** The shipped locale set, derived from values-* dirs (the FormattedResourcesPipelineTest idiom). */
    private fun shippedLocales(): List<String> {
        val module = File("src/main/res")
        val root = File("app/src/main/res")
        val res = when {
            module.exists() -> module
            root.exists() -> root
            else -> throw IllegalStateException("cannot locate src/main/res")
        }
        val localeShape = Regex("[a-z]{2,3}(-[A-Za-z0-9]{2,8})?")
        val tags = res.listFiles { f -> f.isDirectory }
            ?.map { it.name }
            ?.filter { it.startsWith("values-") }
            ?.map { it.removePrefix("values-").replace("-r", "-") }
            // only locale dirs: a future values-night overlay is not a locale
            ?.filter { localeShape.matches(it) } ?: emptyList()
        return (listOf("en") + tags).distinct()
    }

    private fun contextFor(tag: String): Context {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(config)
    }

    @Test
    fun `the placeholder strings format the catalog count in every locale`() {
        shippedLocales().forEach { tag ->
            val c = contextFor(tag)
            listOf(
                R.string.qwen3_asr_title,
                R.string.qwen3_asr_description,
                R.string.qwen3_asr_0_6b_description,
            ).forEach { res ->
                val raw = c.getString(res)
                assertTrue("$tag: $res lost the %1\$d placeholder: $raw", raw.contains("%1\$d"))
                // fa and friends render the FORMATTED value in the locale's
                // own digit system ("۵۹"), so the expectation goes through
                // the same String.format the app renders with.
                val digits = String.format(Locale.forLanguageTag(tag), "%d", count)
                val formatted = c.getString(res, count)
                assertTrue("$tag: $res formatted without the catalog count ($digits): $formatted",
                    formatted.contains(digits))
            }
        }
    }

    @Test
    fun `the model-info best-for literal keeps carrying the catalog count`() {
        shippedLocales().forEach { tag ->
            // the literal is hand-written in the XML (Latin digits by convention)
            val digits = count.toString()
            val s = contextFor(tag).getString(R.string.model_info_best_for_qwen3)
            assertTrue("$tag: model_info_best_for_qwen3 lost the count ($digits): $s",
                s.contains(digits))
        }
    }
}
