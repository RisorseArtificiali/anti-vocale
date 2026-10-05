package com.antivocale.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
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
}
