package com.antivocale.app.transcription.diarization

/**
 * TASK-1060.4: what a decode hands the speaker-labeling pass, naming the
 * arm the pass takes. A decode that cannot hand either (VAD-segmented
 * whole-file runs, remote offload) hands nothing and the pass skips.
 */
sealed interface SpeakerTimeline {

    /** The decode's own contiguous chunks, kept in memory for the whole-file pass. */
    class Retained(val chunks: List<FloatArray>, val sampleRate: Int) : SpeakerTimeline

    /**
     * Holding the chunks was not possible, so the pass re-decodes the file
     * and runs window by window.
     *
     * @param totalSamples the decode's exact 16 kHz length when the run
     *        knows it, for an honest window count; null when unknown
     */
    data class Windowed(val reason: Reason, val totalSamples: Long?) : SpeakerTimeline

    enum class Reason(val logText: String) {
        /** The TASK-597 metadata gate: the source is longer than the whole-file budget. */
        OVER_BUDGET("the audio exceeds the whole-file memory budget (TASK-597 gate)"),

        /** The TASK-728 runtime guard: the retained chunks crossed the budget mid-run. */
        RETENTION_ABANDONED("the retained chunks crossed the memory budget mid-run (TASK-728 guard)"),

        /** A TASK-1037 resumed session: its earlier stints' audio was never retained. */
        RESUMED_SESSION("a resumed session holds no earlier stint's audio (TASK-1037)"),
    }
}
