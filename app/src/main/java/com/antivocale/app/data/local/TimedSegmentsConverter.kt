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
            val cue = JSONObject()
                .put("startMs", segment.startMs)
                .put("endMs", segment.endMs)
                .put("text", segment.text)
            // GH #83: speaker rides only when labeled; JSONObject.put with
            // null REMOVES the key, so the guard is load-bearing.
            segment.speaker?.let { cue.put("speaker", it) }
            // TASK-670: the matched person's name rides only when present
            // (same null-removes-key trap as the speaker id).
            segment.speakerName?.let { cue.put("speakerName", it) }
            // TASK-678: the honest mixed marker rides only when set.
            if (segment.mixedSpeakers) cue.put("mixedSpeakers", true)
            array.put(cue)
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
                            // optInt default -1: rows written before GH #83
                            // carry no key and read back unlabeled.
                            speaker = item.optInt("speaker", -1).takeIf { it >= 0 },
                            // TASK-670: blank-safe; rows before the named
                            // labels carry no key and stay generic.
                            speakerName = item.optString("speakerName").takeIf { it.isNotBlank() },
                            mixedSpeakers = item.optBoolean("mixedSpeakers", false),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
