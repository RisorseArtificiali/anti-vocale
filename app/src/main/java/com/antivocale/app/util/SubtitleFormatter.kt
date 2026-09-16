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
     * and type; [timed] marks the formats that need honest cue data.
     */
    enum class Format(val extension: String, val mime: String, val timed: Boolean) {
        TXT("txt", "text/plain", timed = false),
        TXT_TIMED("txt", "text/plain", timed = true),
        SRT("srt", MIME_SUBRIP, timed = true),
        VTT("vtt", MIME_VTT, timed = true);

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
            Format.VTT -> vtt(segments)
            Format.TXT -> transcript
        })
    }

    // One StringBuilder per render: a long file is on the order of the
    // transcript itself, and a map-then-join would copy every byte twice.

    fun srt(segments: List<TimedSegment>): String = with(renderBuffer(segments)) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) append('\n')
            append(index + 1).append('\n')
            append(fullClock(segment.startMs, ",")).append(' ').append(CUE_TIME_SEPARATOR)
                .append(' ').append(fullClock(segment.endMs, ",")).append('\n')
            append(segment.text).append('\n')
        }
        toString()
    }

    fun vtt(segments: List<TimedSegment>): String = with(renderBuffer(segments)) {
        append(VTT_HEADER).append('\n').append('\n')
        segments.forEachIndexed { index, segment ->
            if (index > 0) append('\n')
            append(fullClock(segment.startMs, ".")).append(' ').append(CUE_TIME_SEPARATOR)
                .append(' ').append(fullClock(segment.endMs, ".")).append('\n')
            append(segment.text).append('\n')
        }
        toString()
    }

    fun timedTxt(segments: List<TimedSegment>): String = with(renderBuffer(segments)) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) append('\n')
            append(shortTime(segment.startMs)).append('-').append(shortTime(segment.endMs))
                .append(' ').append(segment.text)
        }
        toString()
    }

    /** SRT and VTT clock: HH:MM:SS with [decimal] (comma or dot) plus milliseconds. */
    private fun fullClock(ms: Long, decimal: String): String = String.format(
        Locale.US, "%02d:%02d:%02d%s%03d",
        ms.coerceAtLeast(0L) / 3_600_000L,
        ms.coerceAtLeast(0L) / 60_000L % 60L,
        ms.coerceAtLeast(0L) / 1000L % 60L,
        decimal,
        ms.coerceAtLeast(0L) % 1000L,
    )

    /** Timestamped-txt clock: the shared human duration clock ([AudioDurationFormat]). */
    private fun shortTime(ms: Long): String = AudioDurationFormat.format(ms / 1000.0)

    private fun renderBuffer(segments: List<TimedSegment>): StringBuilder =
        StringBuilder(segments.sumOf { it.text.length } + segments.size * 48)
}
