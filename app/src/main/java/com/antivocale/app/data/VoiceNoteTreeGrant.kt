package com.antivocale.app.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.firstOrNull

/**
 * TASK-271 (the 2026-10-13 spike verdict): WhatsApp never indexes voice
 * notes into MediaStore, and a direct open of its files is EACCES even
 * with READ_MEDIA_AUDIO (probe 3); on-demand indexing lands the file in
 * the FILES collection whose read is still denied (probe 4). The ONE
 * read route is a persisted SAF tree grant on the WhatsApp Voice Notes
 * folder (the same "USE THIS FOLDER" gesture the auto-save output picker
 * uses). This class owns the resolution half: take the caller's live
 * grant URI, find the note's document, copy it into the shared_audio
 * sandbox, and hand back a plain local path. The acceptance policy
 * (take, validate, release-old, save: [acceptGrant]) lives here too; the
 * picker PRESENTATION lives in the Settings card and the debug SPI grant
 * activity, and the arm/disarm gating in the coordinator.
 */
@Singleton
class VoiceNoteTreeGrant @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
) {

    /**
     * Resolves one detected WhatsApp path into a readable sandbox copy,
     * or null when anything along the way fails (no grant, folder moved,
     * file deleted, unreadable). The caller holds the live tree URI
     * (TASK-832: the coordinator's own collected value, not a per-note
     * preference-flow collection; each was cache-served and cheap, but a
     * burst cost N of them). The wins: zero flow collections per note and
     * ONE source shared with the arming gate. LATEST-WINS semantics: the
     * caller's field advances on the next emission even mid-burst, so a
     * note gated under the previous URI resolves against the NEW tree or
     * fails, never against the old one (a same-layout re-pick can even
     * resolve to a DIFFERENT file's content under the new tree; do NOT
     * "fix" this by capturing the URI at arm time: a live-to-live re-pick
     * releases the old grant and never re-arms, so arm-time capture would
     * strand every later note on a released grant).
     */
    suspend fun resolve(
        treeUri: String?,
        whatsappPath: String,
        source: VoiceNoteSource = VoiceNoteSource.WHATSAPP,
    ): String? = try {
        if (treeUri == null) {
            Log.w(TAG, "resolve called with no live tree URI supplied")
            null
        } else {
            resolveToSandbox(Uri.parse(treeUri), whatsappPath, source)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Disarm cancels the collector mid-copy: cancellation is control
        // flow, not a resolve failure (the coordinator's own discipline).
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "tree-grant resolve failed for $whatsappPath", e)
        null
    }

    /**
     * Whether the persisted READ grant for [uriString] is actually held
     * right now. The coordinator's arm gate: a dead grant (folder deleted
     * and recreated, OEM cleanup) must disarm detection, not arm offers
     * whose every tap cancels.
     */
    fun holdsGrant(uriString: String): Boolean =
        com.antivocale.app.util.TreeUris.holdsPersistableReadGrant(context, Uri.parse(uriString))

    /**
     * TASK-826: the grant-acceptance policy in ONE place. The Settings
     * picker callback and the debug SPI's grant activity both run this
     * exact sequence (a second copy would drift: the release-old step is
     * the F9 leak class, invisible until a later pick fails on the
     * platform's grant cap). Binder work on IO.
     */
    sealed interface GrantOutcome {
        data class Saved(val uriString: String) : GrantOutcome

        /** The OEM picker returned a non-persistable grant; nothing stored. */
        object TakeFailed : GrantOutcome

        /** Not a voice-notes tree; the taken grant slot was given back. */
        object WrongFolder : GrantOutcome
    }

    suspend fun acceptGrant(
        uri: Uri,
        source: VoiceNoteSource = VoiceNoteSource.WHATSAPP,
    ): GrantOutcome = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO,
    ) {
        // The take must precede the validation query or the query itself
        // is denied (the Settings callback's ordering, unchanged).
        if (!com.antivocale.app.util.TreeUris.takePersistableReadGrant(context, uri)) {
            return@withContext GrantOutcome.TakeFailed
        }
        if (!looksLikeVoiceNotesTree(context, uri, source)) {
            com.antivocale.app.util.TreeUris.releasePersistableReadGrant(context, uri)
            return@withContext GrantOutcome.WrongFolder
        }
        // A replacement pick abandons the old URI: release its grant slot
        // (platform cap 128/512) before overwriting. The STORED value, not
        // a flow's .value (still initialValue before DataStore's first
        // emission; review F9).
        // Per source: each client's slot releases only its OWN previous
        // grant (the other client's live grant must survive this pick).
        val old = preferencesManager.voiceNoteTreeUri(source).firstOrNull()
        if (old != null && old != uri.toString()) {
            com.antivocale.app.util.TreeUris.releasePersistableReadGrant(context, Uri.parse(old))
        }
        preferencesManager.saveVoiceNoteTreeUri(source, uri.toString())
        GrantOutcome.Saved(uri.toString())
    }

    /**
     * Copies [whatsappPath] (the detector's absolute path, unreadable
     * directly) into shared_audio through the granted tree, returning the
     * LOCAL path. The detector observed the exact counter/name pair, so
     * the note's document URI is built directly instead of two linear
     * findFile scans through the provider (per-note cost now that
     * detection, not a user tap, drives this). The child docId MUST extend
     * the TREE's own docId (code review F1, verified against AOSP:
     * buildDocumentUriUsingTree appends its argument verbatim and
     * ExternalStorageProvider requires "volume:path" docIds; a bare
     * "counter/name" throws inside the provider). A stale note fails the
     * copy, which maps to the same honest null.
     */
    private fun resolveToSandbox(treeUri: Uri, whatsappPath: String, source: VoiceNoteSource): String? {
        val name = whatsappPath.substringAfterLast('/')
        val counter = whatsappPath.substringBeforeLast('/').substringAfterLast('/')
        val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val noteUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            treeUri, "$treeDocId/$counter/$name")
        // The source namespace keeps two clients' same-named PTT files from
        // reusing each other's sandbox copy (empty for WhatsApp: unchanged).
        val localName = "voicenote_${source.idNamespace}$name"
        val existing = File(File(context.filesDir, "shared_audio"), localName)
        if (existing.isFile && existing.length() > 0) {
            return existing.absolutePath
        }
        return when (
            val result = com.antivocale.app.util.SharedAudioHandler.copyToAppStorage(
                context, noteUri, outputName = localName)
        ) {
            is com.antivocale.app.util.SharedAudioHandler.CopyResult.Success -> result.path
            else -> null
        }
    }

    companion object {
        private const val TAG = "VoiceNoteTreeGrant"

        /** WhatsApp's folder name under Android/media (not localized there). */
        // Review F6: EXPECTED_FOLDER_NAME deleted (its last production
        // reader went with the multi-source rework); VoiceNoteSource's
        // folderName is the live contract, pinned by VoiceNoteSourceTest.

        /**
         * The pick-time check the Settings card rejects a wrong folder
         * with: true when the tree is named like WhatsApp's folder OR
         * holds a numeric counter child (locale-independent; the name arm
         * covers a fresh install whose folder is still empty, the child
         * arm covers a renamed folder). Static: a pure predicate the
         * activity-result callback calls without DI.
         *
         * Per source (WhatsApp Business support): the name arm checks
         * [source]'s folder name, and a tree inside ANOTHER client's storage
         * is rejected before either arm. Both clients use numeric counter
         * folders, so without that check the child arm would accept the
         * WhatsApp folder in the Business slot (or the reverse), and every
         * resolve would then read same-named files from the wrong app.
         */
        fun looksLikeVoiceNotesTree(
            context: Context,
            treeUri: Uri,
            source: VoiceNoteSource = VoiceNoteSource.WHATSAPP,
        ): Boolean = try {
            if (belongsToOtherSource(treeUri, source) || isOtherFolderOfSource(treeUri, source)) return false
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            root.name == source.folderName ||
                root.listFiles().any { it.name?.toIntOrNull() != null }
        } catch (e: Exception) {
            Log.w(TAG, "tree validation failed", e)
            false
        }

        /**
         * The folder picker's EXTRA_INITIAL_URI, in DOCUMENT form
         * (content://com.android.externalstorage.documents/document/
         * primary:Android/...): TASK-272 found DocumentsUI honors this
         * shape more reliably than a file:// URI. Null when the document
         * URI cannot be built (the caller then opens the picker plain).
         */
        /**
         * The pure half of the cross-client check: the tree's document path
         * (after the volume colon) lies in another source's storage. A
         * non-ExternalStorage tree (no colon) belongs to no source.
         */
        internal fun belongsToOtherSource(treeUri: Uri, source: VoiceNoteSource): Boolean {
            val docPath = treeDocumentPath(treeUri) ?: return false
            if (docPath.isEmpty() || source.ownsDocumentPath(docPath)) return false
            return VoiceNoteSource.entries.any { it != source && it.ownsDocumentPath(docPath) }
        }

        /**
         * A tree inside the source's OWN scoped media dir that is not its
         * voice-notes root. Device evidence (Galaxy S23, 2026-10-20): a
         * WhatsApp Business install migrated from WhatsApp carries a stale
         * "WhatsApp Voice Notes" folder with numeric counter children
         * inside its own Media dir; the child arm would accept it, and the
         * detector (which watches the real root) would never see a note
         * resolve. Inside the scoped dir the root's path is known exactly,
         * so anything else is the wrong folder.
         */
        internal fun isOtherFolderOfSource(treeUri: Uri, source: VoiceNoteSource): Boolean {
            val docPath = treeDocumentPath(treeUri) ?: return false
            return docPath.startsWith("Android/media/${source.packageName}/") &&
                docPath.trimEnd('/') != source.relativeRootPath
        }

        /** The tree's document path after the volume colon, or null. */
        private fun treeDocumentPath(treeUri: Uri): String? = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull()?.substringAfter(':', missingDelimiterValue = "")

        fun pickerInitialUri(source: VoiceNoteSource = VoiceNoteSource.WHATSAPP): Uri? = try {
            val storageRoot = Environment.getExternalStorageDirectory().absolutePath
            val relative = voiceNotesRoot(source).absolutePath
                .removePrefix(storageRoot)
                .trimStart('/')
            android.provider.DocumentsContract.buildDocumentUri(
                com.antivocale.app.util.TreeUris.EXTERNAL_STORAGE_AUTHORITY, "primary:$relative")
        } catch (e: Exception) {
            Log.w(TAG, "picker initial URI build failed", e)
            null
        }
    }
}
