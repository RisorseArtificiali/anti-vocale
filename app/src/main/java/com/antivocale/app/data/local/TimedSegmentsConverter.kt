package com.antivocale.app.data.local

import com.antivocale.app.transcription.TimedSegment
import com.antivocale.app.transcription.TimedWord
import com.antivocale.app.transcription.TimedWords
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
            // TASK-928: per-word timing, flat [startMs,endMs,...]. The plain
            // (non-diarized) path still carries the cue's transient tokens
            // here, so the derivation happens at the write boundary; the
            // re-split attached its own (and dropped tokens). The key rides
            // only when every word is honestly timed.
            val words = segment.words.ifEmpty {
                segment.tokens.takeIf { it.isNotEmpty() }
                    ?.let { TimedWords.ofCue(segment.text, it) }
                    .orEmpty()
            }
            if (words.isNotEmpty()) {
                val flat = JSONArray()
                for (word in words) {
                    flat.put(word.startMs).put(word.endMs)
                }
                cue.put("words", flat)
            }
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
                            // TASK-928: flat [startMs,endMs,...]; absent on
                            // rows before the feature (and on rows with no
                            // honest word timing), read back as the empty
                            // list. A malformed or odd-length array drops the
                            // words rather than guessing an end.
                            words = readWords(item.optJSONArray("words")),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** TASK-928: the flat pair list back into [TimedWord]s; anything malformed is empty. */
    private fun readWords(array: JSONArray?): List<TimedWord> {
        if (array == null || array.length() == 0 || array.length() % 2 != 0) return emptyList()
        return buildList {
            for (i in 0 until array.length() step 2) {
                val startMs = array.optLong(i, -1L)
                val endMs = array.optLong(i + 1, -1L)
                if (startMs < 0 || endMs < startMs) return emptyList()
                add(TimedWord(startMs, endMs))
            }
        }
    }
}
