package com.antivocale.app.data.download

/**
 * Tracks download speed using a sliding window of recent samples.
 *
 * Maintains the last [windowSize] (bytes, timestamp) pairs and computes
 * an average rate from them.
 */
class DownloadRateTracker(
    private val windowSize: Int = 3
) {
    private data class Sample(val bytesDownloaded: Long, val timestampMs: Long)

    private val samples = ArrayDeque<Sample>(windowSize)

    fun reset() {
        samples.clear()
    }

    /**
     * Record the current cumulative bytes downloaded.
     */
    fun record(bytesDownloaded: Long) {
        val now = System.currentTimeMillis()
        if (samples.size >= windowSize) {
            samples.removeFirst()
        }
        samples.addLast(Sample(bytesDownloaded, now))
    }

    /**
     * Returns the average download rate in bytes/second, or 0f if not enough data.
     */
    fun getRateBytesPerSec(): Float {
        if (samples.size < 2) return 0f
        val first = samples.first()
        val last = samples.last()
        val elapsedSec = (last.timestampMs - first.timestampMs) / 1000.0
        if (elapsedSec <= 0) return 0f
        val bytesDelta = last.bytesDownloaded - first.bytesDownloaded
        return (bytesDelta / elapsedSec).toFloat()
    }

    /**
     * Returns estimated seconds remaining, or -1 if not computable.
     * Call [getRateBytesPerSec] first and pass the result to avoid double computation.
     */
    fun getEtaSeconds(bytesDownloaded: Long, totalBytes: Long, rateBytesPerSec: Float): Long {
        if (rateBytesPerSec <= 0f || totalBytes <= 0) return -1L
        val remaining = totalBytes - bytesDownloaded
        if (remaining <= 0) return 0L
        return (remaining / rateBytesPerSec).toLong()
    }
}
