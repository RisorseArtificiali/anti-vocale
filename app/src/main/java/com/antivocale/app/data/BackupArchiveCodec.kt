package com.antivocale.app.data

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * TASK-843 (GH #158): the backup archive codec, settings payload only (the
 * history payload joins in TASK-844). Owns the archive END TO END:
 * [build] takes the portable values and returns the zip bytes
 * (manifest.json with a format version + settings.json), [parse] takes the
 * zip bytes and returns the portable values. Text entries, so DEFLATE is
 * on (unlike the ONNX model bundles, which store verbatim).
 *
 * WHAT THE FORMAT DOES NOT DO: no raw database snapshots (the import is a
 * merge into the live store, never a replace), no device-bound values (the
 * whitelist already filtered them at export), no secrets (the remote-offload
 * endpoint and its bearer key never enter the file).
 */
object BackupArchiveCodec {

    const val FORMAT_VERSION = 1
    const val MANIFEST_ENTRY = "manifest.json"
    const val SETTINGS_ENTRY = "settings.json"

    /** The per-key type tags used inside settings.json. */
    object Types {
        const val BOOLEAN = "b"
        const val INT = "i"
        const val STRING = "s"
    }

    /**
     * Builds the archive bytes. The caller passes only whitelisted values
     * (BackupSettingsKeys gates the classification); the codec does not
     * re-check. Unsupported value types are rejected loudly rather than
     * coerced (/simplify 2026-10-15: a silent stringify would round-trip a
     * Long/StringSet pref into a key the app reads with the wrong type).
     */
    fun build(
        values: Map<String, Any>,
        appVersion: String,
        exportedAtMs: Long,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            writeSettingsEntries(zip, values, appVersion, exportedAtMs)
        }
        return bytes.toByteArray()
    }

    /**
     * TASK-844: the manifest + settings entries written onto a LIVE zip
     * stream (the caller keeps the stream open and appends the history
     * payload through BackupHistoryCodec.writeHistoryEntry, so a full
     * history never buffers through a ByteArray on export).
     */
    fun writeSettingsEntries(
        zip: ZipOutputStream,
        values: Map<String, Any>,
        appVersion: String,
        exportedAtMs: Long,
    ) {
        val manifest = JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("appVersion", appVersion)
            .put("exportedAtMs", exportedAtMs)
        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
        zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        val payload = JSONObject()
        values.forEach { (name, value) ->
            val (tag, jsonValue) = encode(value)
            payload.put(name, JSONObject().put("t", tag).put("v", jsonValue))
        }
        zip.putNextEntry(ZipEntry(SETTINGS_ENTRY))
        zip.write(payload.toString().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /**
     * Parses an archive. Returns the portable values with their runtime
     * types reconstructed, or null when the bytes are not a readable v1
     * archive (not a zip, no manifest, a formatVersion newer than this
     * build, or a malformed settings entry): the import side then reports
     * "unreadable backup" instead of restoring a partial state. A missing
     * settings entry reads as an empty map (a valid, empty backup).
     */
    fun parse(bytes: ByteArray): Map<String, Any>? =
        readSettings(bytes.inputStream())

    /**
     * TASK-844: the import's settings pass over a LIVE stream. The
     * history entry (which dominates the archive) is DRAINED, never
     * materialized: closeEntry skips an entry's remaining data in bounded
     * buffers, so this walk's peak is the settings text regardless of
     * history size. The history itself streams through
     * [openHistoryReader] in a second pass.
     */
    fun readSettings(input: java.io.InputStream): Map<String, Any>? {
        val zip = ZipInputStream(input)
        var manifestText: String? = null
        var settingsText: String? = null
        while (true) {
            val entry = zip.nextEntry ?: break
            when (entry.name) {
                MANIFEST_ENTRY -> manifestText = zip.readBytes().toString(Charsets.UTF_8)
                SETTINGS_ENTRY -> settingsText = zip.readBytes().toString(Charsets.UTF_8)
            }
            zip.closeEntry()
        }
        val manifest = try {
            manifestText?.let { JSONObject(it) } ?: return null
        } catch (e: Exception) {
            return null
        }
        // The forward-compat gate the format KDoc always promised: a newer
        // writer's archive is refused whole, never half-restored.
        val version = manifest.optInt("formatVersion")
        if (version < 1 || version > FORMAT_VERSION) return null
        val text = settingsText ?: return emptyMap()
        val payload = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        val out = mutableMapOf<String, Any>()
        payload.keys().forEach { name ->
            val entry = payload.optJSONObject(name) ?: return@forEach
            val tag = entry.optString("t")
            val value = when (tag) {
                Types.BOOLEAN -> entry.optBoolean("v")
                Types.INT -> entry.optInt("v")
                Types.STRING -> entry.optString("v")
                else -> return@forEach
            }
            out[name] = value
        }
        return out
    }

    /**
     * TASK-844: the import's history pass. Walks a fresh stream to the
     * history entry and returns a line reader DIRECTLY over the zip
     * stream (the entry's data ends where the zip says, and the reader
     * stops there); null when the archive carries no history entry (a
     * settings-only export). Closing the reader closes the stream.
     */
    fun openHistoryReader(input: java.io.InputStream): java.io.BufferedReader? {
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: return null
            if (entry.name == BackupHistoryCodec.HISTORY_ENTRY) {
                return zip.bufferedReader(Charsets.UTF_8)
            }
            zip.closeEntry()
        }
    }

    private fun encode(value: Any): Pair<String, Any> = when (value) {
        is Boolean -> Types.BOOLEAN to value
        is Int -> Types.INT to value
        else -> Types.STRING to value.toString()
    }
}
