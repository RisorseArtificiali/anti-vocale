package com.antivocale.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile

/**
 * The shared SAF tree helpers. The guarded persistable-grant call lives here
 * once because several pickers need it (the external-model import sites and
 * the folder watch): some OEM/third-party pickers return a grant WITHOUT the
 * persistable flag, and the unguarded call throws directly inside the picker
 * callback. Every site used to hand-roll the same runCatching with its own
 * drift (some swallowed the failure). The export folder still takes its own
 * READ|WRITE grant (its writability probe and toast are export-specific).
 */
object TreeUris {
    private const val TAG = "TreeUris"

    /**
     * The external-storage documents provider: the authority whose tree
     * docIds ARE paths, so path display and document-URI building only
     * make sense for it. One constant, three consumers (displayPath, the
     * voice-note picker's initial URI, legacy-grant checks).
     */
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /**
     * Takes the persistable READ grant for [uri]. Returns success; failures
     * are logged (the OEM-picker trap), never thrown.
     */
    fun takePersistableReadGrant(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "persistable read grant failed for $uri", e)
        false
    }

    /**
     * Releases the persistable READ grant. The platform caps persisted
     * grants (128 before API 30, 512 after), so a watched folder being
     * removed must give its slot back or every later pick silently fails.
     */
    fun releasePersistableReadGrant(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "persistable read grant release failed for $uri", it) }
    }

    /**
     * Whether the persisted READ grant is actually HELD right now (the live
     * artifact, not the saved URI string). A grant can die while the
     * preference survives (folder deleted and recreated, OEM grant
     * cleanup), and a consumer that gates only on the string would arm on
     * a dead grant with no re-pick path. Same probe shape as
     * ScheduledFolderWorker.grantHeld.
     */
    fun holdsPersistableReadGrant(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.isReadPermission && it.uri == uri
        }

    /**
     * The display name of a tree, falling back through the path segment to
     * the raw string (never null, so callers can render it directly).
     */
    fun displayName(context: Context, uri: Uri): String =
        DocumentFile.fromTreeUri(context, uri)?.name
            ?: uri.lastPathSegment ?: uri.toString()

    /**
     * The FULL path of a picked tree when the URI carries one: local
     * providers encode it in the document id ("raw:/storage/..." or
     * "primary:Download/sub"). Cloud providers use opaque ids, where a
     * path does not exist; there the display name is the honest answer.
     * Maintainer direction (road test 2026-10-07): the folder shown by
     * name alone does not say WHERE it is.
     */
    fun displayPath(context: Context, uri: Uri): String {
        // Path synthesis is honest ONLY for the local providers whose docIds
        // ARE paths; a colon inside a cloud provider's opaque id would
        // fabricate a nonexistent directory (code review F1).
        val authority = uri.authority
        if (authority == EXTERNAL_STORAGE_AUTHORITY) {
            // The volume root keeps its path here (display callers picked
            // SOMETHING and deserve its location), unlike the watch-root
            // helper below.
            return externalStorageRawPath(uri, allowVolumeRoot = true)
                ?: displayName(context, uri)
        }
        if (authority != "com.android.providers.downloads.documents") {
            return displayName(context, uri)
        }
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        if (docId != null) {
            if (docId.startsWith("raw:")) {
                return docId.removePrefix("raw:").ifBlank { displayName(context, uri) }
            }
            val sep = docId.indexOf(':')
            if (sep > 0) {
                val volume = docId.substring(0, sep)
                val rest = docId.substring(sep + 1)
                val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
                return if (rest.isBlank()) root else "$root/$rest"
            }
        }
        return displayName(context, uri)
    }

    /**
     * TASK-966: the raw filesystem path of an external-storage tree, or
     * null when the URI is not one (cloud providers use opaque ids, where
     * no path exists). Pure docId work, no provider query: the
     * watched-folder detector's FileObserver root consumes it (an observer
     * needs a real path, which only the external-storage provider's
     * "volume:path" and "raw:" docIds carry). The empty tail ("primary:",
     * the whole volume root) is rejected by default: a WATCH root must
     * name a folder, not a volume, while [displayPath] keeps rendering the
     * volume root for picks that legitimately selected it.
     */
    fun externalStorageRawPathOrNull(uri: Uri): String? =
        externalStorageRawPath(uri, allowVolumeRoot = false)

    private fun externalStorageRawPath(uri: Uri, allowVolumeRoot: Boolean): String? =
        runCatching {
            if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
            val docId = DocumentsContract.getTreeDocumentId(uri)
            if (docId.startsWith("raw:")) {
                return docId.removePrefix("raw:").ifBlank { null }
            }
            val sep = docId.indexOf(':')
            if (sep <= 0) return null
            val volume = docId.substring(0, sep)
            val rest = docId.substring(sep + 1)
            if (rest.isBlank() && !allowVolumeRoot) return null
            val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
            if (rest.isBlank()) root else "$root/$rest"
        }.getOrNull()
}
