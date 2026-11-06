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

    /**
     * TASK-966: the document id must address the file at its REAL depth.
     * WhatsApp's PTT layout nests one counter folder below the tree root;
     * the watched-folder source's files sit directly in the picked folder,
     * so a flat docId there (and a counter-shaped one for WhatsApp, whose
     * picked folder NAME would otherwise masquerade as the counter).
     */
    @Test
    fun `note docIds follow each source's tree depth`() {
        val treeDocId = "primary:Music/Signal"
        assertEquals(
            "$treeDocId/Signal-2026-11-05-1030.m4a",
            VoiceNoteTreeGrant.noteDocumentIdWithinTree(
                treeDocId, "/storage/emulated/0/Music/Signal/Signal-2026-11-05-1030.m4a",
                VoiceNoteSource.WATCHED_FOLDER))
        assertEquals(
            "$treeDocId/202642/PTT-1.opus",
            VoiceNoteTreeGrant.noteDocumentIdWithinTree(
                treeDocId, "/storage/emulated/0/WhatsApp Voice Notes/202642/PTT-1.opus",
                VoiceNoteSource.WHATSAPP))
        assertEquals(
            "$treeDocId/202642/PTT-1.opus",
            VoiceNoteTreeGrant.noteDocumentIdWithinTree(
                treeDocId, "/w/202642/PTT-1.opus", VoiceNoteSource.WHATSAPP_BUSINESS))
    }

    @Test
    fun `a watched-folder note resolves and dedups under its own sandbox name`() = runBlocking {
        // The namespaced sandbox copy (watched_ prefix) answers for the
        // watched source's note: same file name, never either WhatsApp
        // client's copy (the existing-copy shortcut, pinned per source).
        val copy = sandboxCopy("voicenote_watched_note.m4a")
        val watchedTree = android.provider.DocumentsContract.buildTreeDocumentUri(
            com.antivocale.app.util.TreeUris.EXTERNAL_STORAGE_AUTHORITY, "primary:Music/Signal").toString()
        val grant = VoiceNoteTreeGrant(context, FakePreferencesManager())
        assertEquals(
            copy.absolutePath,
            grant.resolve(watchedTree, "/storage/emulated/0/Music/Signal/note.m4a",
                VoiceNoteSource.WATCHED_FOLDER))
    }

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
