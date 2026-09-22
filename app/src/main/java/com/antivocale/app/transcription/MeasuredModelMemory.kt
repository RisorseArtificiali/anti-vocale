package com.antivocale.app.transcription

import org.json.JSONObject

/**
 * TASK-575 / GH #106: the measured per-model memory footprint, replacing the
 * disk-size estimate in the load pre-flight once a model has run on this
 * device. The estimate over-refuses (issue #63: a user was told 3056MB was
 * needed, bypassed the check, and the model ran fine on 1853MB free)
 * because the real cost depends on the allocator, the arena, and what the
 * vendor keeps resident, not on the bytes on disk.
 *
 * What is measured: across the load in [TranscriptionOrchestrator.configureBackend],
 * availMem before (A0) and after (A1) the backend became ready. The delta
 * A0-A1 is the model's true resident cost including arena slack; the max
 * observed delta plus the standing headroom becomes the required bar.
 *
 * Sample hygiene (review round, all load-bearing):
 * - A non-positive delta never creates a record: a warm no-op load (backend
 *   already initialized, e.g. after a benchmark warmed the singleton) has
 *   delta ~0 and would otherwise collapse the bar to headroom-only.
 * - A changed model size resets the record: a re-import or updated download
 *   at the same path is a different model.
 * - The max decays 10% per merge, so one noisy sample (another process
 *   allocating during the load window) does not raise the bar forever; with
 *   protection opt-in (TASK-631), turning it off remains the escape.
 * - The record keys on backendId + provider + threads + path: the same model
 *   under a different inference provider (NNAPI driver buffers vs CPU arena,
 *   issue #26) is a different footprint.
 */
object MeasuredModelMemory {

    /** Per-merge decay of the retained max delta (review F4). */
    const val DELTA_DECAY = 0.9

    data class Record(
        val maxLoadDeltaBytes: Long,
        val modelSizeBytes: Long,
        val runs: Int,
        val tsMs: Long,
    )

    /**
     * The required bar for the next load of a model with a measured record:
     * the measured load delta replaces the on-disk size; the standing
     * headroom (same constant the estimate used) covers the decode side,
     * which this v1 does not measure per-chunk.
     */
    fun requiredBytes(record: Record, headroomBytes: Long): Long =
        record.maxLoadDeltaBytes + headroomBytes

    /**
     * Merges a fresh sample. Returns null when the sample proves nothing
     * (non-positive delta with no existing record: a warm no-op load) so the
     * caller skips persisting. A model-size change resets the record: the
     * bytes at this key are a different model now.
     */
    fun merge(
        existing: Record?,
        loadDeltaBytes: Long,
        modelSizeBytes: Long,
        nowMs: Long,
    ): Record? {
        val prior = existing?.takeIf { it.modelSizeBytes == modelSizeBytes }
        if (loadDeltaBytes <= 0L) {
            // Noise or a warm no-op: a record's delta would only weaken.
            return prior?.copy(runs = prior.runs + 1, tsMs = nowMs)
        }
        if (prior == null) {
            return Record(loadDeltaBytes, modelSizeBytes, 1, nowMs)
        }
        val decayedMax = (prior.maxLoadDeltaBytes * DELTA_DECAY).toLong()
        return Record(
            maxLoadDeltaBytes = maxOf(decayedMax, loadDeltaBytes),
            modelSizeBytes = modelSizeBytes,
            runs = prior.runs + 1,
            tsMs = nowMs,
        )
    }

    /**
     * Extracts the model-dir path back out of a record key (the inverse of
     * the orchestrator's key builder: `backendId@provider@threads@path`).
     * Null when the key does not carry the path segment.
     */
    fun pathOfKey(key: String): String? {
        val parts = key.split('@', limit = 4)
        return parts.getOrNull(3)
    }

    /** Decodes the persisted JSON map (model key -> record); corrupt input yields an empty map. */
    fun decode(json: String?): Map<String, Record> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(json)
            val out = LinkedHashMap<String, Record>()
            for (key in root.keys()) {
                val o = root.optJSONObject(key) ?: continue
                out[key] = Record(
                    maxLoadDeltaBytes = o.optLong("maxLoadDeltaBytes"),
                    modelSizeBytes = o.optLong("modelSizeBytes"),
                    runs = o.optInt("runs"),
                    tsMs = o.optLong("tsMs"),
                )
            }
            out
        }.getOrDefault(emptyMap())
    }

    fun encode(records: Map<String, Record>): String {
        val root = JSONObject()
        for ((key, r) in records) {
            root.put(key, JSONObject()
                .put("maxLoadDeltaBytes", r.maxLoadDeltaBytes)
                .put("modelSizeBytes", r.modelSizeBytes)
                .put("runs", r.runs)
                .put("tsMs", r.tsMs))
        }
        return root.toString()
    }
}
