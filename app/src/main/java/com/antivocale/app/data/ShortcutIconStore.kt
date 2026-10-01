package com.antivocale.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
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
 * Reads are synchronous file checks by design, and every reader runs off
 * the main thread: ShareShortcutManager's build path (Dispatchers.Default
 * inside refresh) and the settings card's row refresh (its IO
 * dispatcher). Writes suspend on the
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
        val name = backendId.replace(ID_SANITIZER, "_")
        return File(dir, "$name.png")
    }

    /** The stored pick, or null when the backend has none (or it vanished). */
    fun iconFile(backendId: String): File? = file(backendId).takeIf { it.isFile }

    /** Decoded pick for the build path; null when absent or undecodable (the
     *  caller falls back to the generated icon rather than failing the push). */
    fun decode(backendId: String): Bitmap? =
        iconFile(backendId)?.let { BitmapFactory.decodeFile(it.absolutePath) }

    /**
     * Copies the picked gallery image app-side: the raw read, the decode,
     * and the write all run on the injected IO dispatcher (TASK-490 review:
     * a photo-picker result serves original bytes; reading them on the
     * caller's dispatcher would block it for hundreds of ms). Returns false
     * when the source cannot be opened, read, or decoded; the caller
     * surfaces that, and no partial file is left behind.
     */
    suspend fun save(backendId: String, uri: Uri): Boolean = withContext(ioDispatcher) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext false
        saveBytes(backendId, bytes)
    }

    /**
     * The bytes path (the test seam): decodes EXIF-rotated (portrait camera
     * photos carry their orientation in EXIF, not in the pixels; an
     * unrotated store would mask sideways), downscales to
     * [maxStoredDimension] on the long side (the canvas needs 324px; the
     * headroom keeps a re-mask honest if the geometry ever changes), and
     * lands atomically. False on any read/decode/write failure (a full
     * disk throws FileOutputStream, it does not return false by itself).
     */
    internal suspend fun saveBytes(backendId: String, bytes: ByteArray): Boolean = withContext(ioDispatcher) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext false
        val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / maxStoredDimension)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: return@withContext false
        // The camera norm: portrait shots store landscape sensor data plus
        // an EXIF orientation. BitmapFactory ignores EXIF; honoring it here
        // once beats rotating at every build.
        val rotation = runCatching { ExifInterface(bytes.inputStream()).rotationDegrees }.getOrDefault(0)
        var rotated = if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } else {
            decoded
        }
        val scaled = if (maxOf(rotated.width, rotated.height) > maxStoredDimension) {
            val scale = maxStoredDimension.toFloat() / maxOf(rotated.width, rotated.height)
            Bitmap.createScaledBitmap(
                rotated,
                (rotated.width * scale).toInt().coerceAtLeast(1),
                (rotated.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== rotated) rotated.recycle() }
        } else {
            rotated
        }
        dir.mkdirs()
        val target = file(backendId)
        val tmp = File(dir, "${target.name}.tmp")
        val written = runCatching {
            tmp.outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.PNG, 100, out) }
            tmp.renameTo(target)
        }.getOrDefault(false)
        if (!written) tmp.delete()
        if (scaled !== rotated) scaled.recycle()
        if (rotated !== decoded) rotated.recycle()
        decoded.recycle()
        written
    }

    /** Removes the pick; the generated icon returns on the next republish. */
    suspend fun clear(backendId: String) = withContext(ioDispatcher) {
        file(backendId).delete()
    }

    companion object {
        private const val DIR_NAME = "shortcut_icons"
        private val ID_SANITIZER = Regex("[^A-Za-z0-9._-]")
    }
}
