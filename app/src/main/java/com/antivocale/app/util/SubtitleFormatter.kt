package com.antivocale.app.util

import com.antivocale.app.transcription.SubtitleExtractor.CUE_TIME_SEPARATOR
import com.antivocale.app.transcription.SubtitleExtractor.MIME_SUBRIP
import com.antivocale.app.transcription.SubtitleExtractor.MIME_VTT
import com.antivocale.app.transcription.SubtitleExtractor.VTT_HEADER
import com.antivocale.app.transcription.TimedSegment
import java.util.Locale

/**
 * GH #92: renders [TimedSegment] cues as SRT, WebVTT, or timestamped plain text.
 * Pure Kotlin: timestamps are durations from the audio's start, never wall clock.
 * The subtitle literals (cue separator, VTT header, MIME types) are owned by
 * [com.antivocale.app.transcription.SubtitleExtractor], the parse side of this
 * read/write pair, so the emitted shape cannot drift from the recognized shape.
 */
object SubtitleFormatter {

    /**
     * The auto-save file format. [extension] and [mime] drive the SAF file name
     * and type; the three non-TXT entries need honest cue data, and the
     * resolveExport fail-safe degrades them to plain txt when it is missing.
     */
    enum class Format(val extension: String, val mime: String) {
        TXT("txt", "text/plain"),
        TXT_TIMED("txt", "text/plain"),
        SRT("srt", MIME_SUBRIP),
        VTT("vtt", MIME_VTT);

        companion object {
            /** Unknown stored values fall back to the TXT default. */
            fun fromStored(value: String?): Format =
                entries.firstOrNull { it.name == value } ?: TXT
        }
    }

    /** The effective write: [format] after the fail-safe, plus the file content. */
    data class ExportDecision(val format: Format, val content: String)

    /**
     * The fail-safe gate: a timed format without usable cues, or with failed
     * chunks (gaps the cues cannot represent), degrades to plain .txt so no
     * subtitle file is ever written with bogus timing.
     */
    fun resolveExport(
        selected: Format,
        transcript: String,
        segments: List<TimedSegment>,
        failedChunkCount: Int,
    ): ExportDecision {
        if (selected == Format.TXT) return ExportDecision(Format.TXT, transcript)
        if (segments.isEmpty() || failedChunkCount > 0) {
            val content = if (failedChunkCount > 0) {
                "$transcript\n[$failedChunkCount chunks failed; timestamps omitted]"
            } else transcript
            return ExportDecision(Format.TXT, content)
        }
        return ExportDecision(selected, when (selected) {
            Format.TXT_TIMED -> timedTxt(segments)
            Format.SRT -> srt(segments)
            else -> vtt(segments)
        })
    }

    // One StringBuilder per render: a long file is on the order of the
    // transcript itself, and a map-then-join would copy every byte twice.

    fun srt(segments: List<TimedSegment>): String =
        renderCues(segments, decimal = ",", header = null, numbered = true)

    fun vtt(segments: List<TimedSegment>): String =
        renderCues(segments, decimal = ".", header = VTT_HEADER, numbered = false)

    /** Shared SRT/VTT body: the two differ only in the decimal mark, the header, and cue numbering. */
    private fun renderCues(
        segments: List<TimedSegment>,
        decimal: String,
        header: String?,
        numbered: Boolean,
    ): String = with(renderBuffer(segments)) {
        if (header != null) append(header).append('\n').append('\n')
        segments.forEachIndexed { index, segment ->
            if (index > 0) append('\n')
            if (numbered) append(index + 1).append('\n')
            append(fullClock(segment.startMs, decimal)).append(' ').append(CUE_TIME_SEPARATOR)
                .append(' ').append(fullClock(segment.endMs, decimal)).append('\n')
            // GH #83: speaker label at each TURN (the readable form; a label
            // on every cue would repeat the same name down a monologue).
            speakerPrefix(segments, index)?.let { append(it) }
            append(segment.text).append('\n')
        }
        toString()
    }

    /** "SPEAKER k: " when this cue starts a labeled turn, else null. */
    private fun speakerPrefix(segments: List<TimedSegment>, index: Int): String? {
        val speaker = segments[index].speaker ?: return null
        val previous = segments.getOrNull(index - 1)?.speaker
        if (speaker == previous) return null
        return "SPEAKER ${speaker + 1}: "
    }

    /**
     * GH #83: the cue texts joined with SPEAKER prefixes at turn starts,
     * the same rendering the exports use, for in-app display. Returns null
     * when no cue carries a speaker label: the caller then shows the stored
     * transcript unchanged (single-model and unlabeled rows).
     */
    fun speakerAnnotated(segments: List<TimedSegment>): String? {
        if (segments.none { it.speaker != null }) return null
        return buildString {
            segments.forEachIndexed { index, segment ->
                if (index > 0) append('\n')
                speakerPrefix(segments, index)?.let { append(it) }
                append(segment.text)
            }
        }
    }

    fun timedTxt(segments: List<TimedSegment>): String = with(renderBuffer(segments)) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) append('\n')
            append(shortTime(segment.startMs)).append('-').append(shortTime(segment.endMs))
                .append(' ')
            speakerPrefix(segments, index)?.let { append(it) }
            append(segment.text)
        }
        toString()
    }

    /** SRT and VTT clock: HH:MM:SS with [decimal] (comma or dot) plus milliseconds. */
    private fun fullClock(ms: Long, decimal: String): String {
        val t = ms.coerceAtLeast(0L)
        return String.format(
            Locale.US, "%02d:%02d:%02d%s%03d",
            t / 3_600_000L, t / 60_000L % 60L, t / 1000L % 60L, decimal, t % 1000L,
        )
    }

    /** Timestamped-txt clock: the shared human duration clock ([AudioDurationFormat]). */
    private fun shortTime(ms: Long): String = AudioDurationFormat.format(ms / 1000.0)

    private fun renderBuffer(segments: List<TimedSegment>): StringBuilder =
        StringBuilder(segments.sumOf { it.text.length } + segments.size * 48)
}
