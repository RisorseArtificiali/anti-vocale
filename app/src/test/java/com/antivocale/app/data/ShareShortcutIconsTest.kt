package com.antivocale.app.data

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Raster contract of the per-model shortcut icon generator (TASK-393): the
 * 108dp adaptive canvas, and that the initial actually renders. Full pixel
 * equality is deliberately not asserted; antialiased font rasters differ
 * across densities, while "size + glyph present" pins what the launcher needs.
 *
 * NATIVE graphics: under Robolectric's default LEGACY mode Canvas ops are
 * no-ops and every pixel reads 0, which would make the pixel assertions pass
 * or fail vacuously.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.Config(sdk = [34])
class ShareShortcutIconsTest {

    private val background = Color.rgb(0x12, 0x8C, 0x7E)

    @Test
    fun `icon is the fixed 108dp-at-3x canvas`() {
        val icon = ShareShortcutIcons.createAdaptiveIcon("Whisper", background)
        assertEquals(ShareShortcutIcons.CANVAS_SIZE_PX, icon.width)
        assertEquals(ShareShortcutIcons.CANVAS_SIZE_PX, icon.height)
    }

    @Test
    fun `the model initial renders against the background`() {
        val icon = ShareShortcutIcons.createAdaptiveIcon("Whisper", background)
        // The glyph is white on the family color inside the safe zone: at least
        // one pixel there must differ from the background (an empty render
        // would leave the canvas a solid color).
        val center = ShareShortcutIcons.CANVAS_SIZE_PX / 2
        val probe = (center - 40..center + 40).flatMap { y ->
            (center - 40..center + 40).map { x -> icon.getPixel(x, y) }
        }
        assertTrue(
            "expected at least one pixel differing from the background inside the safe zone",
            probe.any { it != background },
        )
    }

    @Test
    fun `a label with no letter stays the plain background`() {
        val icon = ShareShortcutIcons.createAdaptiveIcon("3-2-1", background)
        for (y in 0 until icon.height) {
            for (x in 0 until icon.width) {
                assertEquals(background, icon.getPixel(x, y))
            }
        }
    }

    @Test
    fun `two renders of the same input are identical`() {
        val a = ShareShortcutIcons.createAdaptiveIcon("Whisper", background)
        val b = ShareShortcutIcons.createAdaptiveIcon("Whisper", background)
        assertEquals(a.width, b.width)
        for (y in 0 until a.height step 7) {
            for (x in 0 until a.width step 7) {
                assertEquals(a.getPixel(x, y), b.getPixel(x, y))
            }
        }
    }

    @Test
    fun `initial is the first letter uppercased`() {
        assertEquals("W", ShareShortcutIcons.initialFor("whisper Small"))
        assertEquals("Q", ShareShortcutIcons.initialFor("Qwen3-ASR"))
        assertEquals("G", ShareShortcutIcons.initialFor("GigaAM v3"))
        assertNull(ShareShortcutIcons.initialFor("123"))
        assertNull(ShareShortcutIcons.initialFor(""))
    }
}
