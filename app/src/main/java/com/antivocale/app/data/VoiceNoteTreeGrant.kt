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
 * uses). This class owns the resolution half end to end: fetch the grant
 * preference, find the note's document, copy it into the shared_audio
 * sandbox, and hand back a plain local path. The grant half (picker,
 * persistence, gating) lives in the Settings card and the coordinator.
 */
@Singleton
class VoiceNoteTreeGrant @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
) {

    /**
     * Resolves one detected WhatsApp path into a readable sandbox copy,
     * or null when anything along the way fails (no grant, folder moved,
     * file deleted, unreadable). The receiver's only resolution call:
     * fetch, parse, resolve, and error mapping all live here, once.
     */
    suspend fun resolve(whatsappPath: String): String? = try {
        val uri = preferencesManager.voiceNoteTreeUri.firstOrNull()
        if (uri == null) {
            Log.w(TAG, "resolve called with no tree grant saved")
            null
        } else {
            resolveToSandbox(Uri.parse(uri), whatsappPath)
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
    private fun resolveToSandbox(treeUri: Uri, whatsappPath: String): String? {
        val name = whatsappPath.substringAfterLast('/')
        val counter = whatsappPath.substringBeforeLast('/').substringAfterLast('/')
        val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val noteUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            treeUri, "$treeDocId/$counter/$name")
        val localName = "voicenote_$name"
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
        const val EXPECTED_FOLDER_NAME = "WhatsApp Voice Notes"

        /**
         * The pick-time check the Settings card rejects a wrong folder
         * with: true when the tree is named like WhatsApp's folder OR
         * holds a numeric counter child (locale-independent; the name arm
         * covers a fresh install whose folder is still empty, the child
         * arm covers a renamed folder). Static: a pure predicate the
         * activity-result callback calls without DI.
         */
        fun looksLikeVoiceNotesTree(context: Context, treeUri: Uri): Boolean = try {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            root.name == EXPECTED_FOLDER_NAME ||
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
        fun pickerInitialUri(): Uri? = try {
            val storageRoot = Environment.getExternalStorageDirectory().absolutePath
            val relative = voiceNotesRoot().absolutePath
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
