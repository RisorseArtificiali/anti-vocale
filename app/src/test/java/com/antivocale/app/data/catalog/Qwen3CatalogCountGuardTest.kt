package com.antivocale.app.data.catalog

import com.antivocale.app.testing.RepoFiles
import com.antivocale.app.testing.ShippedLocales
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
import java.util.Locale

/**
 * TASK-760: the qwen3 language count is DERIVED from the catalog at every
 * render site (the catalog displays carry countPlaceholder and the sites
 * format entry.languages.size). model_info_best_for_qwen3 started as a
 * hand-pinned literal (the drift class that shipped 52/53/59 in one
 * release) and TASK-947 converted it to a %1$d count placeholder rendered
 * by the model-info overlay; this guard still closes that drift class: in
 * EVERY shipped locale the placeholder strings hold the %1$d specifier (a
 * locale that kept a literal is an unformatted site) and formatting with
 * the catalog's count yields exactly that count.
 */
@RunWith(AndroidJUnit4::class)
class Qwen3CatalogCountGuardTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val count: Int =
        BundledCatalog.byId(BuiltInBackendIds.QWEN3_ASR)?.languages?.size
            ?: error("bundled catalog is missing qwen3-asr")

    private fun sizeOf060b(): Long =
        BundledCatalog.byId(BuiltInBackendIds.QWEN3_ASR)?.variant("0.6b-int4")?.estimatedSizeMB
            ?: error("bundled catalog is missing qwen3-asr/0.6b-int4")

    private fun shippedLocales(): List<String> = ShippedLocales.tags()

    private fun contextFor(tag: String): Context = ShippedLocales.contextFor(context, tag)

    @Test
    fun `the placeholder strings format the catalog count in every locale`() {
        shippedLocales().forEach { tag ->
            val c = contextFor(tag)
            // TASK-942: the 0.6b description gained a size slot (%1$d) ahead
            // of the count slot (%2$d); the other two stay count-only.
            val size = sizeOf060b()
            val sizedCount = R.string.qwen3_asr_0_6b_description to size
            listOf(
                R.string.qwen3_asr_title to null,
                R.string.qwen3_asr_description to null,
                sizedCount,
            ).forEach { (res, sizeArg) ->
                val raw = c.getString(res)
                assertTrue("$tag: $res lost the %1\$d placeholder: $raw", raw.contains("%1\$d"))
                // fa and friends render the FORMATTED value in the locale's
                // own digit system ("۵۹"), so the expectation goes through
                // the same String.format the app renders with.
                val digits = String.format(Locale.forLanguageTag(tag), "%d", count)
                val formatted = if (sizeArg != null) c.getString(res, sizeArg, count)
                                else c.getString(res, count)
                assertTrue("$tag: $res formatted without the catalog count ($digits): $formatted",
                    formatted.contains(digits))
            }
        }
    }

    @Test
    fun `the model-info best-for placeholder formats the catalog count`() {
        shippedLocales().forEach { tag ->
            val s = contextFor(tag).getString(R.string.model_info_best_for_qwen3, count)
            // fa and friends render the formatted value in their own digits.
            val digits = String.format(Locale.forLanguageTag(tag), "%d", count)
            assertTrue("$tag: model_info_best_for_qwen3 lost the count ($digits): $s",
                s.contains(digits))
        }
    }
}
