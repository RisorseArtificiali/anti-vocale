package com.antivocale.app.transcription

import com.antivocale.app.testing.RepoFiles
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.antivocale.app.data.catalog.BundledCatalog
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import io.mockk.every
import io.mockk.mockk

/**
 * TASK-767/946: pins the placeholder policy owners. The derivation (which
 * display formats which catalog values, and in WHICH order) lives in these
 * extensions alone; a change here changes every render site at once, so
 * the mapping is pinned against the REAL catalog instead of through
 * string snapshots (the ModelViewModelUseModelPersistenceTest attach idiom).
 * The arg lists also pin the SLOT ORDER (size first, count second) that the
 * locale strings' %1$d/%2$d positions require.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CountArgPolicyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        val catalogJson = RepoFiles.readText("src/main/assets/models_catalog.json")
        val assetManager = mockk<android.content.res.AssetManager>(relaxed = true)
        every { assetManager.open(any()) } answers {
            ByteArrayInputStream(catalogJson.toByteArray(Charsets.UTF_8))
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
        assertEquals(listOf<Any>(expected), qwen3.titleFormatArgs())
        assertEquals(listOf<Any>(expected), qwen3.descriptionFormatArgs())
    }

    @Test
    fun `plain entries resolve without arguments`() {
        val parakeet = BundledCatalog.byId(BuiltInBackendIds.PARAKEET)!!
        assertEquals(emptyList<Any>(), parakeet.titleFormatArgs())
        assertEquals(emptyList<Any>(), parakeet.descriptionFormatArgs())
    }

    @Test
    fun `a variant's args list is size first and count second`() {
        val variant = CatalogVariantUi.forEntry(BuiltInBackendIds.QWEN3_ASR).first()
        // The 0.6b description declares both slots: the args order must match
        // the string's %1$d (size) then %2$d (count) positions, so the pinned
        // pair IS the order proof.
        assertEquals(
            listOf<Any>(variant.estimatedSizeMB, variant.supportedLanguageCodes.size),
            variant.descriptionFormatArgs)
        assertEquals(emptyList<Any>(), variant.titleFormatArgs)
    }

    @Test
    fun `a sizePlaceholder description yields the catalog size`() {
        // TASK-942: entry-level prefix (the default variant's size)…
        val nemotron = BundledCatalog.byId(BuiltInBackendIds.NEMOTRON)!!
        assertEquals(
            listOf<Any>(nemotron.defaultVariant.estimatedSizeMB), nemotron.descriptionFormatArgs())
        // A plain description carries no slot at all.
        val whisper = BundledCatalog.byId(BuiltInBackendIds.WHISPER)!!
        assertEquals(emptyList<Any>(), whisper.descriptionFormatArgs())
    }
}
