package com.antivocale.app.data.local

import com.antivocale.app.transcription.TimedSegment
import org.json.JSONArray
import org.json.JSONObject

/**
 * GH #92: JSON (de)serialization of [TimedSegment] lists for the logs table's
 * `segments` TEXT column. Kept as an explicit converter rather than a Room
 * @TypeConverter so the entity column stays a plain nullable string (same
 * shape as every other nullable TEXT column in the schema).
 */
object TimedSegmentsConverter {

    fun toJson(segments: List<TimedSegment>): String? {
        if (segments.isEmpty()) return null
        val array = JSONArray()
        for (segment in segments) {
            array.put(
                JSONObject()
                    .put("startMs", segment.startMs)
                    .put("endMs", segment.endMs)
                    .put("text", segment.text)
            )
        }
        return array.toString()
    }

    fun fromJson(json: String?): List<TimedSegment> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        TimedSegment(
                            startMs = item.getLong("startMs"),
                            endMs = item.getLong("endMs"),
                            text = item.getString("text"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
