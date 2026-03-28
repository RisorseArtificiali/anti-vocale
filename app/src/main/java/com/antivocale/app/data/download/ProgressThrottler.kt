package com.antivocale.app.data.download

/**
 * Throttles progress/state emissions to avoid excessive UI updates.
 *
 * Call [shouldReport] before each emission; it returns true only if enough
 * time has elapsed since the last report.
 */
class ProgressThrottler(
    private val intervalMs: Long = 1000L
) {
    private var lastReportTimeMs: Long = 0L

    fun shouldReport(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastReportTimeMs >= intervalMs) {
            lastReportTimeMs = now
            return true
        }
        return false
    }

    fun reset() {
        lastReportTimeMs = 0L
    }
}
