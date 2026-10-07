package com.antivocale.app.util

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

/**
 * TASK-780 road-test follow-up: the output-folder card shows the FULL path.
 * [TreeUris.displayPath] decodes the local-provider shapes and degrades to
 * the display name for opaque (cloud) ids.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TreeUrisDisplayPathTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `raw tree ids are absolute paths already`() {
        val uri = Uri.parse(
            "content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Frec%20(1)")
        assertEquals("/storage/emulated/0/Download/rec (1)", TreeUris.displayPath(context, uri))
    }

    @Test
    fun `externalstorage ids map primary to the emulated root`() {
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3ADownload%2Frec")
        assertEquals("/storage/emulated/0/Download/rec", TreeUris.displayPath(context, uri))
    }

    @Test
    fun `volume ids other than primary keep their storage root`() {
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AMusic")
        assertEquals("/storage/1A2B-3C4D/Music", TreeUris.displayPath(context, uri))
    }

    @Test
    fun `opaque cloud ids fall back to the display name`() {
        val uri = Uri.parse(
            "content://com.google.android.apps.docs.storage/tree/abc12notapath")
        // No ':' separator and no raw: prefix: not a path shape; the
        // DocumentFile name is the honest answer (the provider is not
        // backed by Robolectric, so the name resolves to the last segment
        // of the uri through the fallback chain).
        assertEquals("abc12notapath", TreeUris.displayPath(context, uri))
    }
}
