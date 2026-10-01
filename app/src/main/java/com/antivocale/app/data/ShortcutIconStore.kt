package com.antivocale.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TASK-490: app-side storage for the user-chosen share-shortcut icons, one
 * PNG per backend id under filesDir/shortcut_icons. The bytes are COPIED at
 * pick time (never a content URI: grants expire; TASK-490 AC3), so a
 * ShortcutManager republish, a reboot, or a reinstall-with-data all keep
 * the pick. Absent file = the generated family icon (the fallback is the
 * caller's, [com.antivocale.app.data.ShareShortcutIcons]).
 *
 * Reads are synchronous file checks by design: the only reader is
 * ShareShortcutManager's build path, which already runs off the main
 * thread (Dispatchers.Default) inside refresh(). Writes suspend on the
 * injected dispatcher and land atomically (tmp file + rename), so a
 * republish racing a save either sees the old state or the new file,
 * never a half-written PNG.
 */
class ShortcutIconStore constructor(
    @ApplicationContext private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dir: File get() = File(context.filesDir, DIR_NAME)

    /** Longest side a stored source image keeps; the adaptive mask crops from there. */
    private val maxStoredDimension = 1024

    private fun file(backendId: String): File {
        // backend ids carry colon prefixes (external:<uuid>): sanitized to a
        // flat safe filename; collisions between a sanitized id and another
        // real id are impossible for our id alphabet (colons only).
        val name = backendId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "$name.png")
    }

    /** The stored pick, or null when the backend has none (or it vanished). */
    fun iconFile(backendId: String): File? = file(backendId).takeIf { it.isFile }

    /** Decoded pick for the build path; null when absent or undecodable (the
     *  caller falls back to the generated icon rather than failing the push). */
    fun decode(backendId: String): Bitmap? =
        iconFile(backendId)?.let { BitmapFactory.decodeFile(it.absolutePath) }

    /**
     * Copies the picked image app-side, downscaled to [maxStoredDimension]
     * on the long side (the canvas needs 324px; the headroom keeps a
     * re-mask honest if the geometry ever changes). Returns false when the
     * source cannot be read or decoded: the caller surfaces that, and no
     * partial file is left behind.
     */
    suspend fun save(backendId: String, bytes: ByteArray): Boolean = withContext(ioDispatcher) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext false
        val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / maxStoredDimension)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@withContext false
        val scaled = if (maxOf(decoded.width, decoded.height) > maxStoredDimension) {
            val scale = maxStoredDimension.toFloat() / maxOf(decoded.width, decoded.height)
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }
        dir.mkdirs()
        val target = file(backendId)
        val tmp = File(dir, "${target.name}.tmp")
        try {
            tmp.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@withContext false
            }
            true
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    /** Removes the pick; the generated icon returns on the next republish. */
    suspend fun clear(backendId: String) = withContext(ioDispatcher) {
        file(backendId).delete()
    }

    companion object {
        private const val DIR_NAME = "shortcut_icons"
    }
}
