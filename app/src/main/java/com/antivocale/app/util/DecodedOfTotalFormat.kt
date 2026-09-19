package com.antivocale.app.util

import android.content.Context
import com.antivocale.app.R

/**
 * TASK-568: the decoded-of-total sentence for failed long runs ("Decoded
 * 23:12 of 1:17:00"), one definition for both consumers: the failure
 * notification (the orchestrator builds it from the stream context) and the
 * History ERROR row (built from the row's decoded-at-failure durationMs and
 * audio-length column). Same consolidation pattern as
 * [AudioDurationFormat] (TASK-487). Null when nothing was decoded: callers
 * render nothing rather than a bare total.
 */
object DecodedOfTotalFormat {

    fun format(context: Context, decodedSeconds: Double, totalSeconds: Double): String? =
        if (decodedSeconds <= 0.0) null
        else if (totalSeconds > decodedSeconds)
            context.getString(
                R.string.decoded_of_total,
                AudioDurationFormat.format(decodedSeconds),
                AudioDurationFormat.format(totalSeconds))
        else
            context.getString(
                R.string.decoded_so_far,
                AudioDurationFormat.format(decodedSeconds))
}
