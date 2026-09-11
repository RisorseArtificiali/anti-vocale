package com.antivocale.app.ui.appearance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the enum half of the variant-to-artwork seam (TASK-473 review): the
 * Settings picker renders whatever [LauncherIconVariant.foregroundRes] points
 * at, so a copy-paste swap between two derei entries compiles, renders the
 * wrong artwork under the right label, and passes every file-based test.
 * Resolving the ids to resource names here and pinning them to the slug
 * convention closes the chain together with LauncherIconManifestTest's
 * alias-to-XML pin (enum id -> resource name <-> alias -> XML layer name).
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class LauncherIconVariantResourcesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `every derei variant's ids resolve to its own slug`() {
        LauncherIconVariant.entries
            .filter { it.foregroundRes != null }
            .forEach { variant ->
                val slug = variant.name.lowercase()
                assertEquals(
                    "foreground layer of ${variant.name} must be its own raster",
                    "ic_launcher_fg_derei_$slug",
                    context.resources.getResourceEntryName(variant.foregroundRes!!),
                )
            }
    }

    @Test
    fun `only the default carries no per-variant glyph`() {
        LauncherIconVariant.entries
            .filter { it.foregroundRes == null }
            .forEach { variant ->
                assertEquals("the glyph-less variant must be Default", LauncherIconVariant.DEFAULT, variant)
            }
    }
}
