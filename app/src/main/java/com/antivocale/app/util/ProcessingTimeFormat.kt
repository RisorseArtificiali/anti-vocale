package com.antivocale.app.util

/**
 * TASK-497: the one processing-elapsed formatter for user-facing surfaces
 * (entry details row, notification subtext). Locale-neutral h/m/s suffixes,
 * same class as the "ms"/"s" suffixes that were already hardcoded at the
 * call sites; translatable prose stays in string resources.
 *
 * Scale: "823ms" under a second, "4.3s" with tenths below 9.95s, "42s" up
 * to a minute, then "17m 49s" (and "1h 2m 3s" past the hour). The total is
 * rounded before decomposition so 1068.7s reads "17m 49s", not 17m 48s.
 *
 * Deliberately NOT used by: the benchmark dialog (ms-resolution model
 * comparison is its purpose) and the feedback email body (support triage
 * needs the raw seconds for calibrator math).
 */
fun formatProcessingTime(durationMs: Long): String {
    if (durationMs < 1_000) return "${durationMs}ms"
    // The tenths branch ends before 9_950ms (the last value rendering "9.9s"
    // is 9_949): 9_950 and up render "10s", never a two-digit "10.0s"
    // leaking over the branch boundary.
    if (durationMs < 9_950) {
        return String.format(java.util.Locale.ROOT, "%.1fs", durationMs / 1000.0)
    }
    val totalSeconds = Math.round(durationMs / 1000.0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
