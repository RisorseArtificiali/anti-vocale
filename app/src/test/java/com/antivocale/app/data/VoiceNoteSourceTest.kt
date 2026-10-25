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
        VoiceNoteSource.entries.forEach { source ->
            assertFalse(VoiceNoteTreeGrant.isOtherFolderOfSource(tree(source.relativeRootPath), source))
        }
        // Outside the scoped dir (legacy layout, renamed copy) the rule
        // does not apply; the name/child arms decide as before.
        assertFalse(VoiceNoteTreeGrant.isOtherFolderOfSource(
            tree("WhatsApp Business/Media/WhatsApp Business Voice Notes"), VoiceNoteSource.WHATSAPP_BUSINESS))
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse(VoiceNoteTreeGrant.looksLikeVoiceNotesTree(context, stale, VoiceNoteSource.WHATSAPP_BUSINESS))
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
