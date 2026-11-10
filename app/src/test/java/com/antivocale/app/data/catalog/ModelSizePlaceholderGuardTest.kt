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
 * TASK-942/946: the model-description size/count prefix is a format argument
 * fed from the catalog at render time. This guard pins the same facts the
 * bash guard (scripts/check-model-sizes.sh) checks statically, plus the
 * round-trip bash cannot do: in EVERY shipped locale every slot-bearing
 * description keeps one positional specifier per declared slot (the slot at
 * position i owns %i+1$d; size=%1$d, count=%2$d) and no stray specifier
 * beyond them, and formatting with the REAL catalog values renders those
 * exact values. A translation that hardcodes a number, or drops a specifier
 * (a runtime MissingFormatArgumentException class), fails here.
 *
 * The target list is DERIVED from the bundled catalog's ONE slot list
 * (CatalogDisplay.Resource.placeholders; same flags the bash guard reads
 * and the parser normalizes): a description or title the catalog gives any
 * slot is covered here the moment the catalog declares it. TASK-947 extended
 * the walk to titles (whistle_name's size slot joined qwen3_asr_title's
 * count); Qwen3CatalogCountGuardTest stays as the older qwen3 pin. The
 * non-catalog slotted claim (model_info_best_for_whistle, slots carried by
 * ModelInfo.bestForPlaceholders) remains the bash guard's static entry.
 *
 * TASK-949 invariant: every resolve of a slot-bearing catalog title or
 * description goes through the shared resolvers (Context.placeholderAwareString
 * / Context.variantTitle in transcription/CatalogPlaceholders.kt and
 * CatalogEntry.resourceTitlePair in CatalogVariantUi.kt; the @Composable
 * twins placeholderAwareStringResource / titleRes in ui/components/
 * PlaceholderStrings.kt). The bash guard (scripts/check-model-sizes.sh,
 * call-site section) fails the build on a bare getString over a
 * TitleResId/DescriptionResId identifier; this note names the resolver homes
 * so a fix lands in the right file.
 */
@RunWith(AndroidJUnit4::class)
class ModelSizePlaceholderGuardTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** One derived target: the resource, its entry/variant, the slot sources. */
    private data class Target(
        val res: Int,
        /** The catalog's own key for this resource (the anti-vacuity pin). */
        val key: String,
        val entryId: String,
        val variantName: String,
        /** The count slot's catalog source: languagesFor(variant), the same
         *  set the render site's count arg carries. */
        val languages: List<String>,
        /** The declared slots in format order (see PlaceholderSlot). */
        val slots: List<PlaceholderSlot>,
    )

    private val prefixed: List<Target> = run {
        val all = BundledCatalog.entries().flatMap { entry ->
        buildList {
            fun add(key: String, variantName: String, languages: List<String>, slots: List<PlaceholderSlot>) =
                add(Target(CatalogStringKeys.resolve(key), key, entry.id, variantName, languages, slots))
            // Entry-level description: the render site's count source is the
            // ENTRY's language list (CatalogEntry.descriptionFormatArgs).
            (entry.description as? CatalogDisplay.Resource)
                ?.takeIf { it.placeholders.isNotEmpty() }?.let { d ->
                add(d.key, entry.defaultVariant.name, entry.languages, d.placeholders)
            }
            // Variant-level description: the variant's OWN set when declared
            // (languagesFor), matching CatalogVariantUi.descriptionFormatArgs.
            entry.variants.forEach { v ->
                (v.description as? CatalogDisplay.Resource)
                    ?.takeIf { it.placeholders.isNotEmpty() }?.let { d ->
                    add(d.key, v.name, v.languages.ifEmpty { entry.languages }, d.placeholders)
                }
            }
            // TASK-947: titles join the derivation. Entry-level display (the
            // family label; the registry and the orchestrator format it with
            // entry.titleFormatArgs) and variant title (the cards, the
            // overlay, the notification names), both matching
            // CatalogVariantUi.titleFormatArgs.
            (entry.display as? CatalogDisplay.Resource)
                ?.takeIf { it.placeholders.isNotEmpty() }?.let { d ->
                add(d.key, entry.defaultVariant.name, entry.languages, d.placeholders)
            }
            entry.variants.forEach { v ->
                (v.title as? CatalogDisplay.Resource)
                    ?.takeIf { it.placeholders.isNotEmpty() }?.let { d ->
                    add(d.key, v.name, v.languages.ifEmpty { entry.languages }, d.placeholders)
                }
            }
        }
    }

        // A key declared on two holders (whistle_name: entry display + the
        // variant's own title) must carry the SAME slot list on both arms,
        // or one arm silently guards a different contract than the other.
        val byKey = all.groupBy { it.key }
        byKey.forEach { (key, targets) ->
            require(targets.all { it.slots == targets.first().slots }) {
                "catalog placeholder $key declares disagreeing slot lists"
            }
        }
        // Keep EVERY arm: two holders sharing a key can have different
        // language sources, and each count must be window-audited on its
        // own source (the bash guard audits holders, not deduped keys).
        all
    }

    /** The derivation's expected coverage: the pins catch a flag edit that
     *  silently moves a description or title off the guarded set. */
    private val expectedKeys = setOf(
        "parakeet_stock_description", "parakeet_smoothquant_description",
        "whisper_small_description", "whisper_turbo_description",
        "whisper_medium_description", "whisper_distil_large_v3_description",
        "qwen3_asr_0_6b_description", "qwen3_asr_description",
        "nemotron_description", "gigaam_description", "whistle_description",
        "qwen3_asr_title", "whistle_name",
    )

    private fun sizeOf(entryId: String, variantName: String): Long =
        BundledCatalog.byId(entryId)?.variant(variantName)?.estimatedSizeMB
            ?: error("bundled catalog is missing $entryId/$variantName")

    @Test
    fun `the derivation covers exactly the shipped slot-bearing descriptions`() {
        // A catalog flag edit that drops or adds a description off the
        // derivation must fail loudly, not pass vacuously.
        assertEquals(expectedKeys, prefixed.map { it.key }.toSet())
    }

    @Test
    fun `the slot-bearing descriptions keep their specifiers and format the catalog values`() {
        ShippedLocales.tags().forEach { tag ->
            val c = ShippedLocales.contextFor(context, tag)
            prefixed.forEach { target ->
                val size = sizeOf(target.entryId, target.variantName)
                val raw = c.getString(target.res)
                // One positional specifier per declared slot, none beyond.
                target.slots.forEachIndexed { i, slot ->
                    assertTrue("$tag: ${target.res} lost the %${i + 1}\$d ${slot} specifier: $raw",
                        raw.contains("%${i + 1}\$d"))
                }
                val stray = "%${target.slots.size + 1}\$d"
                assertTrue("$tag: ${target.res} carries a stray $stray specifier the catalog " +
                    "does not declare (renders as literal text): $raw", !raw.contains(stray))
                // fa and friends render the FORMATTED values in the locale's
                // own digit system, so the expectation goes through the same
                // String.format the app renders with.
                // The shared mapping (TASK-946/947): the test certifies
                // strings against exactly what production formats with.
                val args: List<Any> = target.slots.formatArgs(size, target.languages.size)
                val formatted = c.getString(target.res, *args.toTypedArray())
                target.slots.forEachIndexed { i, slot ->
                    val digits = String.format(Locale.forLanguageTag(tag), "%d", args[i])
                    assertTrue("$tag: ${target.res} formatted without the catalog $slot value " +
                        "$digits: $formatted", formatted.contains(digits))
                }
            }
        }
    }

    @Test
    fun `every count-slot language count stays in the baked genitive-plural band`() {
        // TASK-948: the count noun baked next to %2$d in the ru/pl/uk locales
        // ("языков" family) is the genitive plural, correct only where the CLDR
        // cardinal rules pick that form (residue 0 or 5-9, or 11-14). A catalog
        // edit moving any countPlaceholder languages array into 1-4, 21-24,
        // 31-34, ... ships a grammatical error in every affected locale, so this
        // is the JVM twin of the bash guard's TASK-948 check: fail HERE (before
        // CI's guard run) with the audit instruction, never silently.
        fun countSlotUnsafe(n: Int): Boolean = n < 5 ||
            (n % 10 == 1 && n % 100 != 11) ||
            (n % 10 in 2..4 && n % 100 !in 12..14)
        prefixed.filter { PlaceholderSlot.COUNT in it.slots }.forEach { target ->
            val n = target.languages.size
            assertTrue(
                "TASK-948: ${target.key} renders $n languages in a one/few band where " +
                    "ru/pl/uk do not use the baked genitive plural noun; run the plurals " +
                    "audit (plurals resources or natively reviewed copy) before any release",
                !countSlotUnsafe(n),
            )
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
