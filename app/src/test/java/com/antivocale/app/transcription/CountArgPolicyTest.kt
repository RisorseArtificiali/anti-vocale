package com.antivocale.app.transcription

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.antivocale.app.data.catalog.BundledCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import io.mockk.every
import io.mockk.mockk

/**
 * TASK-767: pins the countPlaceholder policy owners. The derivation (which
 * display formats the language count, and WHICH count) lives in these
 * extensions alone; a change here changes every render site at once, so
 * the mapping is pinned against the REAL catalog instead of through
 * string snapshots (the ModelViewModelUseModelPersistenceTest attach idiom).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CountArgPolicyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        val asset = File("src/main/assets/models_catalog.json")
            .takeIf { it.exists() } ?: File("app/src/main/assets/models_catalog.json")
        val assetManager = mockk<android.content.res.AssetManager>(relaxed = true)
        every { assetManager.open(any()) } answers {
            ByteArrayInputStream(asset.readText().toByteArray(Charsets.UTF_8))
        }
        context = mockk(relaxed = true) {
            every { assets } returns assetManager
            every { applicationContext } returns this@mockk
        }
        BundledCatalog.attach(context)
    }

    @Test
    fun `a countPlaceholder display yields the entry's language count`() {
        val qwen3 = BundledCatalog.byId(BuiltInBackendIds.QWEN3_ASR)!!
        val expected = qwen3.languages.size
        assertEquals(expected, qwen3.titleCountArg())
        assertEquals(expected, qwen3.descriptionCountArg())
    }

    @Test
    fun `plain entries resolve without an argument`() {
        val parakeet = BundledCatalog.byId(BuiltInBackendIds.PARAKEET)!!
        assertNull(parakeet.titleCountArg())
        assertNull(parakeet.descriptionCountArg())
    }

    @Test
    fun `a variant's own language set is the variant-scoped count`() {
        val variant = CatalogVariantUi.forEntry(BuiltInBackendIds.QWEN3_ASR).first()
        assertEquals(variant.supportedLanguageCodes.size, variant.descriptionFormatArg())
        assertNull(variant.titleFormatArg())
    }
}
