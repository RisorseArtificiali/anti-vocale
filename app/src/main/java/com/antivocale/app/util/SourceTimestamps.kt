package com.antivocale.app.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * TASK-1040: the ORIGINAL message moment behind a content URI, read from
 * the provider's last-modified column. Documents-provider URIs (what
 * messengers typically hand to a share target) expose it; media-store and
 * pipe-based providers usually do not, and every miss answers null: the
 * value is never guessed, and never the staging copy's time (that is the
 * copy moment, not the message's).
 */
object SourceTimestamps {
    fun lastModifiedOrNull(context: Context, uri: Uri): Long? = try {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null,
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    } catch (e: Exception) {
        null
    }
}
