package com.antivocale.app.data

import android.os.Environment
import android.provider.DocumentsContract
import com.antivocale.app.util.TreeUris
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WhatsApp Business support: the source descriptor is the whole per-client
 * difference, so its values are pinned here. The WhatsApp entry must stay
 * byte-identical to the pre-Business constants (path, folder name, empty
 * id namespace) or existing setups and completed-row dedup would break.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceNoteSourceTest {

    @Test
    fun `the two clients are identified by their exact package ids`() {
        assertEquals("com.whatsapp", VoiceNoteSource.WHATSAPP.packageName)
        assertEquals("com.whatsapp.w4b", VoiceNoteSource.WHATSAPP_BUSINESS.packageName)
        assertEquals(VoiceNoteSource.WHATSAPP, VoiceNoteSource.forPackage("com.whatsapp"))
        assertEquals(VoiceNoteSource.WHATSAPP_BUSINESS, VoiceNoteSource.forPackage("com.whatsapp.w4b"))
        assertNull(VoiceNoteSource.forPackage("com.whatsapp.clone"))
        assertNull(VoiceNoteSource.forPackage(null))
    }

    @Test
    fun `the WhatsApp entry keeps the original path and an empty id namespace`() {
        val storage = Environment.getExternalStorageDirectory()
        assertEquals(
            "${storage.absolutePath}/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
            voiceNotesRoot().absolutePath)
        assertEquals(voiceNotesRoot(), VoiceNoteSource.WHATSAPP.root())
        // Review F6: pin the ENUM value directly (the constant alias is deleted);
        // WhatsApp never localizes the folder name, so this string is the
        // on-disk contract both clients' detectors key on.
        assertEquals("WhatsApp Voice Notes", VoiceNoteSource.WHATSAPP.folderName)
        assertEquals("WhatsApp Business Voice Notes", VoiceNoteSource.WHATSAPP_BUSINESS.folderName)
        assertEquals("", VoiceNoteSource.WHATSAPP.idNamespace)
    }

    @Test
    fun `the Business entry watches its own media tree`() {
        assertEquals(
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes",
            VoiceNoteSource.WHATSAPP_BUSINESS.relativeRootPath)
        assertEquals("WhatsApp Business Voice Notes", VoiceNoteSource.WHATSAPP_BUSINESS.folderName)
        assertTrue(VoiceNoteSource.WHATSAPP_BUSINESS.idNamespace.isNotEmpty())
        assertFalse(voiceNotesRoot(VoiceNoteSource.WHATSAPP_BUSINESS) == voiceNotesRoot(VoiceNoteSource.WHATSAPP))
    }

    @Test
    fun `document path ownership never confuses the two package prefixes`() {
        val wa = VoiceNoteSource.WHATSAPP
        val w4b = VoiceNoteSource.WHATSAPP_BUSINESS
        val waPath = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"
        val w4bPath = "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes"
        assertTrue(wa.ownsDocumentPath(waPath))
        assertFalse("com.whatsapp is a prefix of com.whatsapp.w4b", wa.ownsDocumentPath(w4bPath))
        assertTrue(w4b.ownsDocumentPath(w4bPath))
        assertFalse(w4b.ownsDocumentPath(waPath))
        // The pre-scoped-storage trees: "WhatsApp/" is a prefix of
        // "WhatsApp Business/" only without the slash.
        assertTrue(wa.ownsDocumentPath("WhatsApp/Media/WhatsApp Voice Notes"))
        assertFalse(wa.ownsDocumentPath("WhatsApp Business/Media/WhatsApp Business Voice Notes"))
        assertTrue(w4b.ownsDocumentPath("WhatsApp Business/Media/WhatsApp Business Voice Notes"))
        assertFalse(wa.ownsDocumentPath("Download/notes"))
    }

    private fun tree(path: String) = DocumentsContract.buildTreeDocumentUri(
        TreeUris.EXTERNAL_STORAGE_AUTHORITY, "primary:$path")

    @Test
    fun `a tree in the other client's storage is rejected for this slot`() {
        val waTree = tree(VoiceNoteSource.WHATSAPP.relativeRootPath)
        val w4bTree = tree(VoiceNoteSource.WHATSAPP_BUSINESS.relativeRootPath)
        // Both have numeric counter children on device, so the child arm
        // alone would accept either tree in either slot.
        assertTrue(VoiceNoteTreeGrant.belongsToOtherSource(waTree, VoiceNoteSource.WHATSAPP_BUSINESS))
        assertTrue(VoiceNoteTreeGrant.belongsToOtherSource(w4bTree, VoiceNoteSource.WHATSAPP))
        assertFalse(VoiceNoteTreeGrant.belongsToOtherSource(waTree, VoiceNoteSource.WHATSAPP))
        assertFalse(VoiceNoteTreeGrant.belongsToOtherSource(w4bTree, VoiceNoteSource.WHATSAPP_BUSINESS))
        // A tree belonging to no client (a renamed copy, a cloud tree)
        // is left to the original name/child arms.
        assertFalse(VoiceNoteTreeGrant.belongsToOtherSource(tree("Download/notes"), VoiceNoteSource.WHATSAPP))
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse(
            "the cross-client check runs before any provider query",
            VoiceNoteTreeGrant.looksLikeVoiceNotesTree(context, waTree, VoiceNoteSource.WHATSAPP_BUSINESS))
    }

    @Test
    fun `a stale folder inside the source's own media dir is rejected`() {
        // Device evidence (Galaxy S23): a Business install migrated from
        // WhatsApp keeps "WhatsApp Voice Notes" with counter folders under
        // its own Media dir; only the exact voice-notes root is valid there.
        val stale = tree("Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Voice Notes")
        assertTrue(VoiceNoteTreeGrant.isOtherFolderOfSource(stale, VoiceNoteSource.WHATSAPP_BUSINESS))
        assertTrue("and it belongs to Business, so the WhatsApp slot refuses it too",
            VoiceNoteTreeGrant.belongsToOtherSource(stale, VoiceNoteSource.WHATSAPP))
        val counter = tree("${VoiceNoteSource.WHATSAPP_BUSINESS.relativeRootPath}/202643")
        assertTrue(VoiceNoteTreeGrant.isOtherFolderOfSource(counter, VoiceNoteSource.WHATSAPP_BUSINESS))
        VoiceNoteSource.entries.filter { it.usesPttLayout }.forEach { source ->
            assertFalse(VoiceNoteTreeGrant.isOtherFolderOfSource(tree(source.relativeRootPath), source))
        }
        // Outside the scoped dir (legacy layout, renamed copy) the rule
        // does not apply; the name/child arms decide as before.
        assertFalse(VoiceNoteTreeGrant.isOtherFolderOfSource(
            tree("WhatsApp Business/Media/WhatsApp Business Voice Notes"), VoiceNoteSource.WHATSAPP_BUSINESS))
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse(VoiceNoteTreeGrant.looksLikeVoiceNotesTree(context, stale, VoiceNoteSource.WHATSAPP_BUSINESS))
    }

    /**
     * TASK-966 (GH #184): the generic watched-folder source. It owns no
     * package (no History stamp, no notification identity, forPackage can
     * never resolve to it), has no fixed root (the watch root is the
     * user's picked tree), keeps its own id namespace, and its pick
     * validation must NOT be the WhatsApp path check (the reporter's exact
     * failure pointing the app at Music/Signal).
     */
    @Test
    fun `the watched-folder entry owns no package no path and its own namespace`() {
        val watched = VoiceNoteSource.WATCHED_FOLDER
        assertNull(watched.packageName)
        assertFalse(watched.resolvesSenderLabels)
        assertFalse(watched.usesPttLayout)
        assertEquals("watched_", watched.idNamespace)
        assertTrue(watched.idNamespace.isNotEmpty())
        // No notification package can resolve to it (the identity listener's
        // keying stays WhatsApp-only).
        assertNull(VoiceNoteSource.forPackage("org.thoughtcrime.securems"))
        assertTrue("relativeRootPath must fail loudly for the watched source",
            runCatching { watched.relativeRootPath }.isFailure)
        assertTrue("root() must fail loudly for the watched source",
            runCatching { watched.root() }.isFailure)
        // It owns no document path, so the WhatsApp trees stay the WhatsApp
        // slots' property and the generic slot refuses them.
        assertFalse(watched.ownsDocumentPath("Music/Signal"))
        assertFalse(watched.ownsDocumentPath("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"))
    }

    @Test
    fun `the generic slot accepts Music-Signal and refuses WhatsApp and cloud trees`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        // The reporter's exact pick (Signal 8.34.2 saves to Music/Signal):
        // accepted even though it is nothing like WhatsApp's layout.
        assertTrue(VoiceNoteTreeGrant.looksLikeVoiceNotesTree(context, tree("Music/Signal"), VoiceNoteSource.WATCHED_FOLDER))
        // Any other local folder passes too: the folder choice IS the filter.
        assertTrue(VoiceNoteTreeGrant.looksLikeVoiceNotesTree(context, tree("Download/notes"), VoiceNoteSource.WATCHED_FOLDER))
        // The WhatsApp trees have their own slots; picking one into the
        // generic source would double-transcribe every note.
        assertFalse(VoiceNoteTreeGrant.looksLikeVoiceNotesTree(
            context, tree(VoiceNoteSource.WHATSAPP.relativeRootPath), VoiceNoteSource.WATCHED_FOLDER))
        assertFalse(VoiceNoteTreeGrant.looksLikeVoiceNotesTree(
            context, tree(VoiceNoteSource.WHATSAPP_BUSINESS.relativeRootPath), VoiceNoteSource.WATCHED_FOLDER))
        // The bare volume root and non-external-storage (cloud) trees have
        // no watchable path: refused as unwatchable.
        val volumeRoot = DocumentsContract.buildTreeDocumentUri(
            TreeUris.EXTERNAL_STORAGE_AUTHORITY, "primary:").toString()
        assertFalse(VoiceNoteTreeGrant.looksLikeVoiceNotesTree(
            context, android.net.Uri.parse(volumeRoot), VoiceNoteSource.WATCHED_FOLDER))
        val cloud = android.net.Uri.parse(
            "content://com.cloud.docs.documents/tree/uuid-with-no-path-separator")
        assertFalse(VoiceNoteTreeGrant.looksLikeVoiceNotesTree(context, cloud, VoiceNoteSource.WATCHED_FOLDER))
    }

    @Test
    fun `the watched-folder root derives from the live tree grant`() {
        // The ExternalStorageProvider's primary-volume mapping (Robolectric's
        // Environment root differs; the provider path is the contract).
        // The live pick: the raw path of the picked tree.
        val uri = DocumentsContract.buildTreeDocumentUri(
            TreeUris.EXTERNAL_STORAGE_AUTHORITY, "primary:Music/Signal").toString()
        assertEquals("/storage/emulated/0/Music/Signal", watchedFolderRoot(uri).absolutePath)
        // A secondary volume keeps its own root.
        val sd = DocumentsContract.buildTreeDocumentUri(
            TreeUris.EXTERNAL_STORAGE_AUTHORITY, "17FB-1E20:Notes").toString()
        assertEquals("/storage/17FB-1E20/Notes", watchedFolderRoot(sd).absolutePath)
        // No grant (or a path-less cloud tree): a placeholder that never
        // exists, so the detector attaches no watcher. It sits under the
        // REAL storage root (where the picked folders live), and the raw:
        // docId shape resolves too.
        val placeholder = watchedFolderRoot(null)
        assertEquals(
            Environment.getExternalStorageDirectory().absolutePath + "/__antivocale_watched_folder__",
            placeholder.absolutePath)
        assertFalse(placeholder.isDirectory)
        val raw = DocumentsContract.buildTreeDocumentUri(
            TreeUris.EXTERNAL_STORAGE_AUTHORITY, "raw:/storage/emulated/0/Download/notes").toString()
        assertEquals("/storage/emulated/0/Download/notes", watchedFolderRoot(raw).absolutePath)
    }

    @Test
    fun `the watched-folder name filter accepts audio files and rejects the rest`() {
        val watched = VoiceNoteSource.WATCHED_FOLDER
        assertTrue(VoiceNoteFilter.isVoiceNoteName(watched, "Signal-2026-11-05-1030.m4a"))
        assertTrue(VoiceNoteFilter.isVoiceNoteName(watched, "note.opus"))
        assertTrue(VoiceNoteFilter.isVoiceNoteName(watched, "note.AMR"))
        assertFalse("hidden files are not notes", VoiceNoteFilter.isVoiceNoteName(watched, ".hidden.m4a"))
        assertFalse("video containers are not notes", VoiceNoteFilter.isVoiceNoteName(watched, "clip.mp4"))
        assertFalse("documents are not notes", VoiceNoteFilter.isVoiceNoteName(watched, "note.txt"))
        // The WhatsApp pair keeps its PTT rule through the same entry point.
        assertTrue(VoiceNoteFilter.isVoiceNoteName(VoiceNoteSource.WHATSAPP, "PTT-20261105-WA12.opus"))
        assertFalse("a plain audio name is not a WhatsApp note",
            VoiceNoteFilter.isVoiceNoteName(VoiceNoteSource.WHATSAPP, "note.m4a"))
    }

    @Test
    fun `the preference accessors route the watched source to its own keys`() = runBlocking {
        val prefs = FakePreferencesManager()
        prefs.saveVoiceNoteDetectionEnabled(VoiceNoteSource.WATCHED_FOLDER, true)
        prefs.saveVoiceNoteTreeUri(VoiceNoteSource.WATCHED_FOLDER, "content://tree/watched")
        assertTrue(prefs.voiceNoteDetectionEnabled(VoiceNoteSource.WATCHED_FOLDER).first())
        assertEquals("content://tree/watched", prefs.voiceNoteTreeUri(VoiceNoteSource.WATCHED_FOLDER).first())
        // The WhatsApp pair is untouched.
        assertFalse(prefs.voiceNoteDetectionEnabled(VoiceNoteSource.WHATSAPP).first())
        assertFalse(prefs.voiceNoteDetectionEnabled(VoiceNoteSource.WHATSAPP_BUSINESS).first())
        assertNull(prefs.voiceNoteTreeUri(VoiceNoteSource.WHATSAPP).first())
        assertNull(prefs.voiceNoteTreeUri(VoiceNoteSource.WHATSAPP_BUSINESS).first())
        // No keyed catch-up for this source: the watermark accessors stay
        // total but empty (and the writes are no-ops).
        assertNull(prefs.voiceNoteWatermark(VoiceNoteSource.WATCHED_FOLDER).first())
        prefs.bumpVoiceNoteWatermark(VoiceNoteSource.WATCHED_FOLDER, "999/20261105/0000000001")
        assertNull(prefs.voiceNoteWatermark(VoiceNoteSource.WATCHED_FOLDER).first())
    }

    @Test
    fun `the preference accessors route each source to its own keys`() = runBlocking {
        val prefs = FakePreferencesManager()
        prefs.saveVoiceNoteDetectionEnabled(VoiceNoteSource.WHATSAPP_BUSINESS, true)
        prefs.saveVoiceNoteTreeUri(VoiceNoteSource.WHATSAPP_BUSINESS, "content://tree/w4b")
        assertTrue(prefs.voiceNoteDetectionEnabled(VoiceNoteSource.WHATSAPP_BUSINESS).first())
        assertEquals("content://tree/w4b", prefs.voiceNoteTreeUri(VoiceNoteSource.WHATSAPP_BUSINESS).first())
        // The WhatsApp pair is untouched.
        assertFalse(prefs.voiceNoteDetectionEnabled(VoiceNoteSource.WHATSAPP).first())
        assertNull(prefs.voiceNoteTreeUri(VoiceNoteSource.WHATSAPP).first())

        prefs.saveVoiceNoteTreeUri(VoiceNoteSource.WHATSAPP, "content://tree/wa")
        assertEquals("content://tree/wa", prefs._voiceNoteTreeUri.value)
        assertEquals("content://tree/w4b", prefs._voiceNoteBusinessTreeUri.value)
    }
}
