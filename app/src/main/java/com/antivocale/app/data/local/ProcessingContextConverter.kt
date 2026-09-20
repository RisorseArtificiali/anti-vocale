package com.antivocale.app.data.local

import com.antivocale.app.transcription.ProcessingContext
import org.json.JSONObject

/**
 * TASK-512: JSON (de)serialization of [ProcessingContext] for the logs
 * table's `processingContext` TEXT column, and its one-line rendering for
 * the History card and the report email. Same converter pattern as
 * [TimedSegmentsConverter] and [FailureContextJson].
 */
object ProcessingContextConverter {

    fun toJson(context: ProcessingContext?): String? = context?.let { c ->
        JSONObject().apply {
            put("decodePath", c.decodePath)
            c.totalChunks?.let { put("totalChunks", it) }
            c.failedChunks?.let { put("failedChunks", it) }
            c.transcribedSeconds?.let { put("transcribedSeconds", it) }
            c.chunkCapSeconds?.let { put("chunkCapSeconds", it) }
            c.availableRamBytes?.let { put("availableRamBytes", it) }
            c.vadRequested?.let { put("vadRequested", it) }
            c.backendId?.let { put("backendId", it) }
            c.refinementPhase?.let { put("refinementPhase", toJson(it)) }
            c.refinementSkipReason?.let { put("refinementSkipReason", it) }
            c.refinementLoopMetrics?.let { put("refinementLoopMetrics", it) }
        }.toString()
    }

    fun fromJson(raw: String?): ProcessingContext? =
        raw?.takeIf { it.isNotBlank() }?.let {
            runCatching {
                val o = JSONObject(it)
                ProcessingContext(
                    decodePath = o.getString("decodePath"),
                    // has() alone is true for explicit JSON nulls; isNull guards
                    // both, so one null field cannot downgrade the whole context.
                    totalChunks = o.optIntOrNull("totalChunks"),
                    failedChunks = o.optIntOrNull("failedChunks"),
                    transcribedSeconds = o.optDoubleOrNull("transcribedSeconds"),
                    chunkCapSeconds = o.optIntOrNull("chunkCapSeconds"),
                    availableRamBytes = o.optLongOrNull("availableRamBytes"),
                    vadRequested = if (o.has("vadRequested") && !o.isNull("vadRequested")) o.getBoolean("vadRequested") else null,
                    backendId = o.optStringOrNull("backendId"),
                    refinementPhase = fromJson(o.optStringOrNull("refinementPhase")),
                    refinementSkipReason = o.optStringOrNull("refinementSkipReason"),
                    refinementLoopMetrics = o.optStringOrNull("refinementLoopMetrics"),
                )
            }.getOrNull()
        }

    /** One-line human rendering ("pipeline chunks=157 (failed 3) decoded=4620s cap=60s ram=5531MB"). */
    fun render(context: ProcessingContext?): String? = context?.let { c ->
        buildList {
            add(c.decodePath)
            if (c.totalChunks != null) add("chunks=${c.totalChunks}" +
                (c.failedChunks?.takeIf { it > 0 }?.let { " (failed $it)" } ?: ""))
            c.transcribedSeconds?.takeIf { it > 0.0 }?.let { add("decoded=${it}s") }
            c.chunkCapSeconds?.let { add("cap=${it}s") }
            // Integer MB, the app-wide RAM unit (the low-memory toasts): no
            // locale-sensitive formatting, no GB/MB drift between surfaces.
            c.availableRamBytes?.let { add("ram=${it / (1024L * 1024L)}MB") }
            c.vadRequested?.let { add("vad=${if (it) "on" else "off"}") }
            // GH #43: the refinement facts ride the metadata line (and the
            // report email) without a new field anywhere.
            c.refinementPhase?.backendId?.let { add("refined=$it") }
            c.refinementSkipReason?.let { add("skip=$it") }
            c.refinementLoopMetrics?.let { add("loop=$it") }
        }.joinToString(" ")
    }
}

private fun org.json.JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) getInt(key) else null

private fun org.json.JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) getDouble(key) else null

private fun org.json.JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

private fun org.json.JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
