package com.antivocale.app.data.catalog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.antivocale.app.R
import com.antivocale.app.testing.ShippedLocales
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * TASK-942: the model-description size/count prefix is a format argument fed
 * from the catalog at render time. This guard pins the same two facts the
 * bash guard (scripts/check-model-sizes.sh) checks statically, plus the
 * round-trip bash cannot do: in EVERY shipped locale the prefixed
 * descriptions keep their %1$d (size) and %2$d (count) specifiers, and
 * formatting them with the REAL catalog values renders those exact values.
 * A translation that hardcodes a number, or drops a specifier (a runtime
 * MissingFormatArgumentException class), fails here.
 *
 * The target list is DERIVED from the bundled catalog's placeholder flags
 * (same walk as the bash guard): a description the catalog declares
 * sizePlaceholder is covered here the moment the catalog declares it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelSizePlaceholderGuardTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** One derived target: the resource, its entry/variant, the count source. */
    private data class Target(
        val res: Int,
        /** The catalog's own key for this resource (the anti-vacuity pin). */
        val key: String,
        val entryId: String,
        val variantName: String,
        /** The count slot's catalog source: languagesFor(variant), the same
         *  set the render site's count arg carries. */
        val languages: List<String>,
        val wantsCount: Boolean,
    )

    private val prefixed: List<Target> = BundledCatalog.entries().flatMap { entry ->
        buildList {
            (entry.description as? CatalogDisplay.Resource)?.takeIf { it.sizePlaceholder }?.let { d ->
                add(Target(CatalogStringKeys.resolve(d.key), d.key, entry.id,
                    entry.defaultVariant.name, entry.defaultVariant.languages.ifEmpty { entry.languages },
                    d.countPlaceholder))
            }
            entry.variants.forEach { v ->
                (v.description as? CatalogDisplay.Resource)?.takeIf { it.sizePlaceholder }?.let { d ->
                    add(Target(CatalogStringKeys.resolve(d.key), d.key, entry.id,
                        v.name, v.languages.ifEmpty { entry.languages }, d.countPlaceholder))
                }
            }
        }
    }

    /** The derivation's expected coverage: the pins catch a flag edit that
     *  silently moves a description off the guarded set. */
    private val expectedKeys = setOf(
        "parakeet_stock_description", "parakeet_smoothquant_description",
        "whisper_small_description", "whisper_turbo_description",
        "whisper_medium_description", "whisper_distil_large_v3_description",
        "qwen3_asr_0_6b_description", "nemotron_description",
        "gigaam_description", "whistle_description",
    )

    private fun sizeOf(entryId: String, variantName: String): Long =
        BundledCatalog.byId(entryId)?.variant(variantName)?.estimatedSizeMB
            ?: error("bundled catalog is missing $entryId/$variantName")

    @Test
    fun `the derivation covers exactly the shipped prefixed descriptions`() {
        // A catalog flag edit that drops or adds a description off the
        // derivation must fail loudly, not pass vacuously.
        assertEquals(expectedKeys, prefixed.map { it.key }.toSet())
    }

    @Test
    fun `the prefixed descriptions keep their specifiers and format the catalog values`() {
        ShippedLocales.tags().forEach { tag ->
            val c = ShippedLocales.contextFor(context, tag)
            prefixed.forEach { target ->
                val size = sizeOf(target.entryId, target.variantName)
                val raw = c.getString(target.res)
                assertTrue("$tag: ${target.res} lost the %1\$d size specifier: $raw",
                    raw.contains("%1\$d"))
                if (target.wantsCount) {
                    val count = target.languages.size
                    assertTrue("$tag: ${target.res} lost the %2\$d count specifier: $raw",
                        raw.contains("%2\$d"))
                    // fa and friends render the FORMATTED values in the locale's
                    // own digit system, so the expectation goes through the same
                    // String.format the app renders with.
                    val formatted = c.getString(target.res, size, count)
                    val sizeDigits = String.format(Locale.forLanguageTag(tag), "%d", size)
                    val countDigits = String.format(Locale.forLanguageTag(tag), "%d", count)
                    assertTrue("$tag: ${target.res} formatted without catalog size $sizeDigits: $formatted",
                        formatted.contains(sizeDigits))
                    assertTrue("$tag: ${target.res} formatted without catalog count $countDigits: $formatted",
                        formatted.contains(countDigits))
                } else {
                    // A stray %2$d on a size-only string renders the literal
                    // text to the user (one-arg format never throws).
                    assertTrue("$tag: ${target.res} carries a stray %2\$d count specifier: $raw",
                        !raw.contains("%2\$d"))
                    val formatted = c.getString(target.res, size)
                    val sizeDigits = String.format(Locale.forLanguageTag(tag), "%d", size)
                    assertTrue("$tag: ${target.res} formatted without catalog size $sizeDigits: $formatted",
                        formatted.contains(sizeDigits))
                }
            }
        }
    }

    @Test
    fun `the speed dialog rows all resolve a catalog size`() {
        // ModelTab's catalogSpeedSize hardcodes these pairs (review F2): a
        // catalog rename must fail HERE, not crash the dialog composition.
        val rows = listOf(
            "whisper" to "turbo", "whisper" to "medium", "whisper" to "small",
            "whisper" to "distil-large-v3-it", "sherpa-onnx" to "stock-int8",
            "qwen3-asr" to "0.6b-int8", "gigaam" to "v3",
            "nemotron-streaming" to "1120ms-int8",
        )
        rows.forEach { (entryId, variantName) ->
            BundledCatalog.byId(entryId)?.variant(variantName)?.estimatedSizeMB
                ?: error("speed dialog row missing from the catalog: $entryId/$variantName")
        }
    }

    @Test
    fun `the speed size format exists untranslatable and formats the catalog size`() {
        val size = sizeOf("sherpa-onnx", "stock-int8")
        ShippedLocales.tags().forEach { tag ->
            // The format is default-only (translatable=false): every locale
            // resolves to the same "~%1$dMB" template. The formatted digits
            // may use the locale's own numeral system (fa ۶۴۰), so compare
            // through the same String.format the app renders with.
            val expected = String.format(Locale.forLanguageTag(tag), "~%dMB", size)
            val formatted = ShippedLocales.contextFor(context, tag).getString(R.string.speed_comparison_size_format, size)
            assertEquals(expected, formatted)
        }
    }
}
