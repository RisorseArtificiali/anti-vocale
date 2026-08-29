package com.antivocale.app.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

enum class ModelFamily { TRANSDUCER, WHISPER, CTC, SENSE_VOICE, CANARY }

enum class ExternalModelSource { LOCAL, URL, CATALOG }

/**
 * Internal helper: parse a JSONObject from a JSON object, returning emptyMap if absent or null.
 * Used by both ExternalModelRecord.fromJson and ExternalModelEntryJson.parse.
 */
internal fun JSONObject.optStringMap(key: String): Map<String, String> {
    val obj = optJSONObject(key) ?: return emptyMap()
    return buildMap {
        for (k in obj.keys()) {
            put(k, obj.getString(k))
        }
    }
}

/**
 * String elements of a JSONArray as a List (empty-mapping tolerant, like
 * [optStringMap]). Single definition for the catalog index and entry-JSON
 * language arrays.
 */
internal fun JSONArray.optStringList(): List<String> =
    buildList { for (i in 0 until length()) add(optString(i)) }

data class FilePin(val sha256: String, val verified: Boolean)

data class ExternalModelRecord(
    val id: String,                 // uuid (independent of the dir fragment; both draw separate uuids)
    val displayName: String,
    val dir: String,                // models/external/<sanitized-name>-<random-fragment>/
    val family: ModelFamily,
    val modelType: String,          // sherpa modelType: nemo_transducer, "", conformer_transducer
    val languages: List<String>,
    val source: ExternalModelSource,
    val sourceUrl: String?,
    val files: Map<String, FilePin>,
    val sizeBytes: Long,
    val importedAt: Long,
    val options: Map<String, String> = emptyMap(),
    /** TASK-368: streaming zipformer transducer (decoded via OnlineRecognizer). */
    val streaming: Boolean = false,
) {
    val backendId: String get() = BACKEND_ID_PREFIX + id

    /**
     * Human-facing type label for cards. WHISPER, SENSE_VOICE and CANARY records
     * carry a blank modelType by design; the label falls back to the family name
     * so a whisper import is never shown as "zipformer".
     */
    val typeLabel: String get() = modelType.ifBlank { family.name.lowercase() }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("displayName", displayName); put("dir", dir)
        put("family", family.name); put("modelType", modelType)
        put("languages", JSONArray(languages)); put("source", source.name)
        put("sourceUrl", sourceUrl ?: JSONObject.NULL)
        put("files", JSONObject().apply { files.forEach { (n, p) -> put(n, JSONObject().put("sha256", p.sha256).put("verified", p.verified)) } })
        put("sizeBytes", sizeBytes); put("importedAt", importedAt)
        val optsJson = JSONObject()
        options.forEach { (k, v) -> optsJson.put(k, v) }
        put("options", optsJson)
        put("streaming", streaming)
    }

    companion object {
        private const val TAG = "ExternalModelRecord"

        /**
         * The routing prefix every external backend id carries. The single definition:
         * dispatch sites (manager, orchestrator, share manager) match on it instead of
         * retyping the literal, so an id-scheme change stays a one-file edit.
         */
        const val BACKEND_ID_PREFIX = "external:"

        fun fromJson(o: JSONObject): ExternalModelRecord? = try {
            val filesObj = o.getJSONObject("files")
            val files = buildMap {
                for (name in filesObj.keys()) {
                    val p = filesObj.getJSONObject(name)
                    put(name, FilePin(p.getString("sha256"), p.getBoolean("verified")))
                }
            }
            ExternalModelRecord(
                id = o.getString("id"), displayName = o.getString("displayName"), dir = o.getString("dir"),
                family = ModelFamily.valueOf(o.getString("family")), modelType = o.getString("modelType"),
                languages = buildList { val a = o.getJSONArray("languages"); for (i in 0 until a.length()) add(a.getString(i)) },
                source = ExternalModelSource.valueOf(o.getString("source")),
                sourceUrl = if (o.isNull("sourceUrl")) null else o.getString("sourceUrl"),
                files = files, sizeBytes = o.getLong("sizeBytes"), importedAt = o.getLong("importedAt"),
                options = o.optStringMap("options"),
                streaming = o.optBoolean("streaming", false),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Malformed ExternalModelRecord; whole list will be rejected", e)
            null
        }
    }
}

object ExternalModelListJson {
    private const val TAG = "ExternalModelListJson"

    fun encode(records: List<ExternalModelRecord>): String =
        JSONArray(records.map { it.toJson() }).toString()

    fun decode(raw: String?): List<ExternalModelRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            // Element-granularity rejection: a malformed record is dropped, the
            // valid remainder survives. Whole-list rejection combined with the
            // store's read-modify-write would destroy the surviving records on
            // the next mutation (data loss flagged by code review).
            buildList {
                for (i in 0 until a.length()) {
                    ExternalModelRecord.fromJson(a.getJSONObject(i))?.let { add(it) }
                        ?: Log.w(TAG, "Dropping malformed external model record at index $i")
                }
            }
        }.onFailure { Log.w(TAG, "Failed to decode external models JSON", it) }
         .getOrDefault(emptyList())
    }
}
