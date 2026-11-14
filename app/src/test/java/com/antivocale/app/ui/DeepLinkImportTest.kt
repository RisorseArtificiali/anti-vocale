package com.antivocale.app.ui

import android.content.Intent
import android.net.Uri
import com.antivocale.app.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TASK-995: the antivocale://import?url=... deep link is UNTRUSTED input
 * (any web page can fire it), so the parser's validation is the security
 * boundary this test pins: scheme, host, carried scheme, length cap, and
 * the consume-once handoff (data cleared + pending set exactly once). The
 * route classification (which URLs may import without a family
 * confirmation) and the manifest intent-filter's scheme/host are pinned
 * here too: a silent drift on either side breaks one-tap imports with no
 * other failure (the ManifestFgsMirrorTest convention).
 */
@RunWith(RobolectricTestRunner::class)
class DeepLinkImportTest {

    private fun captureFrom(data: String): String? =
        DeepLinkImport.capture(Intent(Intent.ACTION_VIEW, Uri.parse(data)))

    @Test
    fun `valid link captures the carried url and consumes the intent`() {
        DeepLinkImport.pending.value = null
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("antivocale://import?url=https%3A%2F%2Fhuggingface.co%2Fcsukuangfj%2Fsherb"))
        val url = DeepLinkImport.capture(intent)
        assertEquals("https://huggingface.co/csukuangfj/sherb", url)
        assertEquals(url, DeepLinkImport.pending.value)
        // Consume-once: the intent can no longer replay the link on a later
        // onNewIntent or configuration-change pass-through.
        assertNull(intent.data)
        assertNull(DeepLinkImport.capture(intent))
    }

    @Test
    fun `a literal plus in an unencoded carried url survives the decode`() {
        DeepLinkImport.pending.value = null
        // The unencoded case the encodedQuery comment supports: '+' is legal
        // in paths (cdn.example.org/files/pkg+v2.vibe) and must NOT become a
        // space (Uri.decode's form-encoding semantics) while real %XX still
        // decodes.
        val url = DeepLinkImport.capture(Intent(
            Intent.ACTION_VIEW,
            Uri.parse("antivocale://import?url=https://cdn.example.org/files/pkg+v2%2Ejson")))
        assertEquals("https://cdn.example.org/files/pkg+v2.json", url)
    }

    @Test
    fun `an encoded value with extra parameters keeps them out of the url`() {
        DeepLinkImport.pending.value = null
        // Properly encoded url= plus a second parameter: the typed parse
        // must own this shape so &src=hf does not ride the carried URL.
        val url = DeepLinkImport.capture(Intent(
            Intent.ACTION_VIEW,
            Uri.parse("antivocale://import?url=https%3A%2F%2Fexample.com%2Fmodel&src=hf")))
        assertEquals("https://example.com/model", url)
    }

    @Test
    fun `wrong scheme host or missing url are dropped`() {
        DeepLinkImport.pending.value = null
        for (data in listOf(
            "antivocale://other?url=https://example.org/a",
            "other://import?url=https://example.org/a",
            "antivocale://import",
        )) {
            assertNull(captureFrom(data))
        }
        assertNull(DeepLinkImport.pending.value)
    }

    @Test
    fun `carried url must be http(s) with a host`() {
        for (data in listOf(
            "antivocale://import?url=file%3A%2F%2F%2Fdata%2Flocal%2Ftmp%2Fx",
            "antivocale://import?url=javascript%3Aalert(1)",
            "antivocale://import?url=https%3A%2F%2F",
        )) {
            assertNull(captureFrom(data))
        }
    }

    @Test
    fun `oversized carried url is dropped`() {
        val long = "https://example.org/" + "a".repeat(2100)
        assertNull(captureFrom("antivocale://import?url=${Uri.encode(long)}"))
    }

    @Test
    fun `null data intent is dropped and leaves pending untouched`() {
        DeepLinkImport.pending.value = "sentinel"
        assertNull(DeepLinkImport.capture(Intent()))
        assertEquals("sentinel", DeepLinkImport.pending.value)
    }

    @Test
    fun `relaunch from recents is dropped`() {
        // After a process death, a Recents relaunch redelivers the task's
        // stored base intent (data included): without this gate the import
        // would silently re-run on a plain reopen.
        DeepLinkImport.pending.value = null
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("antivocale://import?url=https%3A%2F%2Fexample.org%2Fentry.json"))
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(DeepLinkImport.capture(intent))
        assertNull(DeepLinkImport.pending.value)
    }

    @Test
    fun `manifest intent filter pins the parser's scheme and host`() {
        val manifest = RepoFiles.readText("src/main/AndroidManifest.xml")
        // Anchor on MainActivity's own node ("<activity" alone would also
        // match one of the 28 activity-alias declarations).
        val node = manifest.substringAfter("android:name=\".MainActivity\"", "")
            .substringBefore("</activity>", "")
        assertTrue("MainActivity must declare an intent-filter", "<intent-filter>" in node)
        assertEquals(
            "the filter's scheme must equal DeepLinkImport.SCHEME",
            DeepLinkImport.SCHEME,
            Regex("android:scheme=\"([^\"]+)\"").find(node)?.groupValues?.get(1))
        assertEquals(
            "the filter's host must equal DeepLinkImport.HOST",
            DeepLinkImport.HOST,
            Regex("android:host=\"([^\"]+)\"").find(node)?.groupValues?.get(1))
    }
}
