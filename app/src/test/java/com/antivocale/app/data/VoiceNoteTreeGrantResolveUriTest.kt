package com.antivocale.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-832: the caller-held-uri contract. The null arm (no live URI) must
 * return null WITHOUT touching the resolver, and the preference store is
 * no longer read at all (the coordinator's live value is the only source).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceNoteTreeGrantResolveUriTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a null live uri answers null without resolving`() = runBlocking {
        // The house fixture, not a mock-of-the-fake: the null arm returns
        // before touching preferences at all (review 3a).
        val grant = VoiceNoteTreeGrant(context, FakePreferencesManager())
        assertNull(grant.resolve(treeUri = null, whatsappPath = "/w/PTT-1.opus"))
    }

    @Test
    fun `resolve uses the caller's uri, never the preference store`() = runBlocking {
        // Non-relaxed: any unexpected member access throws, which is the
        // strictness the assertion wants (review 3b).
        val prefs = mockk<FakePreferencesManager>()
        val grant = VoiceNoteTreeGrant(context, prefs)
        grant.resolve(treeUri = "content://tree/live", whatsappPath = "/gone/PTT-x.opus")
        // The property GET is the prerequisite of any store read, so its
        // absence covers the whole removed firstOrNull path.
        verify(exactly = 0) { prefs.voiceNoteTreeUri }
    }

    private fun sandboxCopy(name: String): java.io.File =
        java.io.File(java.io.File(context.filesDir, "shared_audio").apply { mkdirs() }, name)
            .apply { writeText("opus") }

    private val tree = android.provider.DocumentsContract.buildTreeDocumentUri(
        com.antivocale.app.util.TreeUris.EXTERNAL_STORAGE_AUTHORITY,
        "primary:${VoiceNoteSource.WHATSAPP_BUSINESS.relativeRootPath}").toString()

    @Test
    fun `sandbox copies are namespaced per source`() = runBlocking {
        // WhatsApp Business support: an already-copied WhatsApp note must
        // never answer for a same-named Business note (the existing-copy
        // shortcut keys on the local name), and the WhatsApp name is the
        // pre-Business one.
        val grant = VoiceNoteTreeGrant(context, FakePreferencesManager())
        val waCopy = sandboxCopy("voicenote_PTT-20261020-WA0001.opus")
        val path = "/x/202642/PTT-20261020-WA0001.opus"

        assertEquals(waCopy.absolutePath, grant.resolve(tree, path, VoiceNoteSource.WHATSAPP))
        assertNotEquals(waCopy.absolutePath, grant.resolve(tree, path, VoiceNoteSource.WHATSAPP_BUSINESS))

        val w4bCopy = sandboxCopy("voicenote_w4b_PTT-20261020-WA0001.opus")
        assertEquals(w4bCopy.absolutePath, grant.resolve(tree, path, VoiceNoteSource.WHATSAPP_BUSINESS))
    }
}
