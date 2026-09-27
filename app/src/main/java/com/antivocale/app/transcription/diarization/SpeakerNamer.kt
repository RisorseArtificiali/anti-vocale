package com.antivocale.app.transcription.diarization

/**
 * TASK-670 (GH #83): the identification layer above diarization. Each
 * diarized cluster's audio is embedded and compared against the enrolled
 * voiceprints by cosine similarity; only a similarity at or above
 * [MATCH_THRESHOLD] earns the person's name, everything else keeps the
 * generic SPEAKER N label. The feature never invents a name: an
 * unrecognized voice, a dimension mismatch, or a failed extraction all
 * fall through to the generic label.
 */
object SpeakerNamer {

    /**
     * CALIBRATION PLACEHOLDER (TASK-670): 0.60 is the sherpa
     * speaker-verification line's mid default for titanet-scale embeddings,
     * NOT a measured value on our device matrix. It is a named constant so
     * the maintainer's device trial (AC #6, two enrolled voices on a real
     * diarized clip) can tune it in one place; until then it errs high,
     * preferring the generic label over a wrong name.
     */
    const val MATCH_THRESHOLD = 0.60f

    /** Review R1: the embedded-audio ceiling per cluster (the enrollment
     *  bound: more adds nothing and a dominant speaker would OOM the run). */
    const val MAX_EMBEDDED_CLUSTER_SECONDS = 30f

    /**
     * Minimum total speech a cluster must carry before it is worth
     * embedding: under this the voiceprint would be noise, so the cluster
     * keeps its generic label.
     */
    const val MIN_CLUSTER_SECONDS = 1f

    /**
     * Cosine similarity of two embeddings. Null (not a number to compare)
     * on a dimension mismatch or a zero-norm vector: both mean "no honest
     * comparison possible", which the callers treat as no match.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float? {
        if (a.size != b.size) return null
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0f || normB == 0f) return null
        val similarity = dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
        // A rounding overshoot past 1 or below -1 would rank impossibly.
        return similarity.coerceIn(-1f, 1f)
    }

    /**
     * The best enrolled identity at or above [threshold], or null. Ties
     * resolve to the first-listed identity (the store lists by name), a
     * deterministic rule that never guesses silently.
     */
    fun bestMatch(
        query: FloatArray,
        identities: List<SpeakerIdentity>,
        threshold: Float = MATCH_THRESHOLD,
    ): SpeakerIdentity? {
        var best: SpeakerIdentity? = null
        var bestSimilarity = Float.NEGATIVE_INFINITY
        for (identity in identities) {
            val similarity = cosineSimilarity(query, identity.embedding) ?: continue
            // Review R7: the threshold and the running best are SEPARATE
            // conditions: >= threshold keeps the boundary inclusive, the
            // STRICT > against the running best keeps ties on the
            // FIRST-listed identity exactly as the KDoc promises.
            if (similarity >= threshold && similarity > bestSimilarity) {
                bestSimilarity = similarity
                best = identity
            }
        }
        return best
    }

    /**
     * Names each diarized cluster: the cluster's segments are sliced out of
     * the merged sample timeline, concatenated, embedded through [embed],
     * and matched. Pure aside from the [embed] call, so tests drive it with
     * a fake embedder.
     *
     * @param segments dense diarized segments (the SpeakerDiarizer output)
     * @param samples the same merged contiguous waveform the diarizer ran on
     * @param embed one buffer -> one embedding (the SpeakerEmbeddings session)
     * @return dense cluster id -> enrolled name, only the matched clusters
     */
    fun nameClusters(
        segments: List<DiarizedSegment>,
        samples: FloatArray,
        sampleRate: Int,
        identities: List<SpeakerIdentity>,
        embed: (FloatArray, Int) -> FloatArray,
        threshold: Float = MATCH_THRESHOLD,
    ): Map<Int, String> {
        if (identities.isEmpty() || segments.isEmpty() || samples.isEmpty()) return emptyMap()
        val names = HashMap<Int, String>()
        val clusters = segments.groupBy { it.speaker }
        for ((cluster, clusterSegments) in clusters) {
            val speechSeconds = clusterSegments.sumOf { (it.endSec - it.startSec).toDouble() }
            if (speechSeconds < MIN_CLUSTER_SECONDS) continue
            // Review R1: a dominant speaker's whole-cluster concat would
            // allocate a third full-file-sized copy (a 2h single-speaker
            // file OOMs a run that completed with generic labels). The
            // enrollment bound is the evidence more than ~30s adds nothing:
            // cap the embedded audio at the same ceiling, taking the FIRST
            // capped seconds of the cluster's speech.
            val buffer = concatSlices(clusterSegments, samples, sampleRate,
                maxSeconds = MAX_EMBEDDED_CLUSTER_SECONDS)
            if (buffer.isEmpty()) continue
            // An extraction failure on ONE cluster must not name it nor
            // break the others: runCatching per cluster, generic fallback.
            val embedding = runCatching { embed(buffer, sampleRate) }.getOrNull() ?: continue
            val match = bestMatch(embedding, identities, threshold)
            if (match != null) names[cluster] = match.name
        }
        return names
    }

    /** Concatenates each segment's sample slice, clamped to the buffer's real extent. */
    private fun concatSlices(
        segments: List<DiarizedSegment>,
        samples: FloatArray,
        sampleRate: Int,
        maxSeconds: Float,
    ): FloatArray {
        val maxSamples = (maxSeconds * sampleRate).toInt()
        var total = 0
        val ranges = ArrayList<Pair<Int, Int>>(segments.size)
        for (segment in segments) {
            if (total >= maxSamples) break
            val from = (segment.startSec * sampleRate).toInt().coerceIn(0, samples.size)
            val to = (segment.endSec * sampleRate).toInt().coerceIn(from, samples.size)
            if (to > from) {
                ranges.add(from to to)
                total += to - from
            }
        }
        if (total == 0) return FloatArray(0)
        // Review R1: the merged buffer is capped at the embedded ceiling;
        // the tail of the last range over the cap is dropped.
        val cappedTotal = total.coerceAtMost(maxSamples)
        val merged = FloatArray(cappedTotal)
        var offset = 0
        for ((from, to) in ranges) {
            if (offset >= cappedTotal) break
            val length = (to - from).coerceAtMost(cappedTotal - offset)
            System.arraycopy(samples, from, merged, offset, length)
            offset += length
        }
        return merged
    }
}
