package com.antivocale.app.util

/**
 * The one human duration clock for audio lengths: m:ss, and h:mm:ss past one
 * hour. Hoisted from LogsTab (TASK-487): SubtitleFormatter's timestamped-txt
 * export renders the same clock, and PerformanceStatsDialog carried a second
 * private copy. Same consolidation pattern as [ProcessingTimeFormat]
 * (TASK-497). Truncates fractional seconds; never rounds up.
 */
object AudioDurationFormat {

    /** 83.5 seconds renders "1:23"; 3661.9 renders "1:01:01"; <= 0 renders "0:00". */
    fun format(seconds: Double): String =
        if (seconds <= 0) "0:00" else fromTotalSeconds(seconds.toLong())

    /** Long-seconds overload for callers that already counted whole seconds. */
    fun format(totalSeconds: Long): String =
        if (totalSeconds <= 0) "0:00" else fromTotalSeconds(totalSeconds)

    private fun fromTotalSeconds(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return if (hours > 0) "$hours:${pad(minutes)}:${pad(secs)}"
        else "$minutes:${pad(secs)}"
    }

    private fun pad(value: Long): String = value.toString().padStart(2, '0')
}
