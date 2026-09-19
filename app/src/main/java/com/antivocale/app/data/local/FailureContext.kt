package com.antivocale.app.data.local

import org.json.JSONObject

/**
 * TASK-570: structured failure diagnostics persisted on the ERROR history
 * row so the next "different errors every time" report is diagnosable from
 * the screenshot alone (the Tim Veles report needed git archaeology to
 * attribute to v1.5.2). Serialized to the failureContext column with the
 * same org.json converter pattern as [TimedSegmentsConverter].
 */
data class FailureContext(
    /** Exception class at the failure point ("PipelineFailure", "OutOfMemoryError"). */
    val errorClass: String,
    /** Active backend id at failure ("whisper", "external:<id>"). */
    val backendId: String? = null,
    /** Resolved inference provider ("cpu"/"nnapi"). */
    val provider: String? = null,
    /** App version at failure time. */
    val appVersion: String? = null,
    /** Chunks decoded before the failure, when the run was chunked. */
    val processedChunks: Int? = null,
    /** Chunks that failed (skipped after retry) before the run died. */
    val failedChunks: Int? = null,
    /** Container-metadata audio length in seconds (0 when absent/lying). */
    val metadataSeconds: Double? = null,
    /** Audio decoded before the failure in seconds. */
    val decodedSeconds: Double? = null,
)

object FailureContextJson {

    fun toJson(context: FailureContext?): String? = context?.let { c ->
        JSONObject().apply {
            put("errorClass", c.errorClass)
            c.backendId?.let { put("backendId", it) }
            c.provider?.let { put("provider", it) }
            c.appVersion?.let { put("appVersion", it) }
            c.processedChunks?.let { put("processedChunks", it) }
            c.failedChunks?.let { put("failedChunks", it) }
            c.metadataSeconds?.let { put("metadataSeconds", it) }
            c.decodedSeconds?.let { put("decodedSeconds", it) }
        }.toString()
    }

    fun fromJson(raw: String?): FailureContext? =
        raw?.takeIf { it.isNotBlank() }?.let {
            runCatching {
                val o = JSONObject(it)
                FailureContext(
                    errorClass = o.getString("errorClass"),
                    backendId = o.optString("backendId").takeIf { it.isNotEmpty() },
                    provider = o.optString("provider").takeIf { it.isNotEmpty() },
                    appVersion = o.optString("appVersion").takeIf { it.isNotEmpty() },
                    processedChunks = if (o.has("processedChunks")) o.getInt("processedChunks") else null,
                    failedChunks = if (o.has("failedChunks")) o.getInt("failedChunks") else null,
                    metadataSeconds = if (o.has("metadataSeconds")) o.getDouble("metadataSeconds") else null,
                    decodedSeconds = if (o.has("decodedSeconds")) o.getDouble("decodedSeconds") else null,
                )
            }.getOrNull()
        }

    /** One-line human rendering for the History error view and the report email. */
    fun render(context: FailureContext?): String? = context?.let { c ->
        buildList {
            add(c.errorClass)
            c.backendId?.let { add("backend=$it") }
            c.provider?.let { add("provider=$it") }
            c.appVersion?.let { add("v$it") }
            if (c.processedChunks != null) add("chunks=${c.processedChunks}" +
                (c.failedChunks?.takeIf { it > 0 }?.let { " (failed $it)" } ?: ""))
            c.metadataSeconds?.takeIf { it > 0.0 }?.let { add("total=${it}s") }
            c.decodedSeconds?.takeIf { it > 0.0 }?.let { add("decoded=${it}s") }
        }.joinToString(" ")
    }
}
