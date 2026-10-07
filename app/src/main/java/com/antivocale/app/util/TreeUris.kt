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
        if (authority != "com.android.externalstorage.documents" &&
            authority != "com.android.providers.downloads.documents"
        ) {
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
}
