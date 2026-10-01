package com.antivocale.app.data

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-490 AC3: picks persist app-side as copied bytes (no expiring URI
 * grants), keyed per backend id (sanitized: the external: prefix), and a
 * failed decode leaves no partial file behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShortcutIconStoreTest {

    private lateinit var store: ShortcutIconStore

    private fun pngBytes(size: Int = 64): ByteArray {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.RED)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    @Before
    fun setUp() {
        store = ShortcutIconStore(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `a saved pick round-trips as a decodable file`() = runTest {
        assertTrue(store.saveBytes("sherpa-onnx", pngBytes()))
        val file = store.iconFile("sherpa-onnx")
        assertNotNull(file)
        assertNotNull(store.decode("sherpa-onnx"))
        assertTrue(file!!.length() > 0)
    }

    // NOT pinned here: the garbage-bytes rejection path. Robolectric's
    // BitmapFactory shadow decodes ANY byte array into a bitmap, so a false
    // return is unreachable under Robolectric; the rejection (real decode
    // failure leaves no file) is device-verified in the TASK-490 trial.

    @Test
    fun `external colon ids sanitize to a safe flat name`() = runTest {
        assertTrue(store.saveBytes("external:abc123", pngBytes()))
        assertNotNull(store.iconFile("external:abc123"))
        // The sanitized file stays inside the icons dir.
        assertTrue(store.iconFile("external:abc123")!!.path.contains("shortcut_icons"))
    }

    @Test
    fun `clear removes the pick and absent ids read as none`() = runTest {
        assertTrue(store.saveBytes("llm", pngBytes()))
        assertNotNull(store.iconFile("llm"))
        store.clear("llm")
        assertNull(store.iconFile("llm"))
        assertNull(store.iconFile("never-saved"))
    }

    @Test
    fun `a re-pick overwrites the previous file`() = runTest {
        val first = pngBytes(32)
        assertTrue(store.saveBytes("llm", first))
        val firstLen = store.iconFile("llm")!!.length()
        assertTrue(store.saveBytes("llm", pngBytes(200)))
        val second = store.iconFile("llm")!!
        assertTrue("a re-pick must replace, not append", second.length() != firstLen)
        assertEquals(store.decode("llm")!!.width, 200)
    }

    @Test
    fun `an oversized source is downscaled into the storage budget`() = runTest {
        // 3000px source decodes with inSampleSize down toward the 1024 cap.
        assertTrue(store.saveBytes("llm", pngBytes(3000)))
        val decoded = store.decode("llm")!!
        assertTrue("stored image must fit the budget, got ${decoded.width}",
            maxOf(decoded.width, decoded.height) <= 1024)
    }
}
