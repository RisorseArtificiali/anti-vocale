package com.antivocale.app.util

import com.antivocale.app.transcription.TimedSegment
import java.util.Locale

/**
 * GH #92: renders [TimedSegment] cues as SRT, WebVTT, or timestamped plain text.
 * Pure Kotlin: timestamps are durations from the audio's start, never wall clock.
 * The parse-side vocabulary lives in [SubtitleExtractor]; this object owns the
 * write side.
 */
object SubtitleFormatter {

    /**
     * The auto-save file format. [extension] and [mime] drive the SAF file name
     * and type; [timed] marks the formats that need honest cue data.
     */
    enum class Format(val extension: String, val mime: String, val timed: Boolean) {
        TXT("txt", "text/plain", timed = false),
        TXT_TIMED("txt", "text/plain", timed = true),
        SRT("srt", "application/x-subrip", timed = true),
        VTT("vtt", "text/vtt", timed = true);

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

    fun srt(segments: List<TimedSegment>): String = segments.mapIndexed { index, segment ->
        "${index + 1}\n${srtTime(segment.startMs)} --> ${srtTime(segment.endMs)}\n${segment.text}\n"
    }.joinToString(SEPARATOR)

    fun vtt(segments: List<TimedSegment>): String =
        "WEBVTT\n\n" + segments.joinToString(SEPARATOR) { segment ->
            "${vttTime(segment.startMs)} --> ${vttTime(segment.endMs)}\n${segment.text}\n"
        }

    fun timedTxt(segments: List<TimedSegment>): String = segments.joinToString("\n") { segment ->
        "${shortTime(segment.startMs)}-${shortTime(segment.endMs)} ${segment.text}"
    }

    /** SRT clock: HH:MM:SS,mmm with a comma decimal separator. */
    private fun srtTime(ms: Long): String =
        "${clockParts(ms, hoursPadded = true, decimal = ",")}"

    /** WebVTT clock: HH:MM:SS.mmm with a dot decimal separator. */
    private fun vttTime(ms: Long): String =
        clockParts(ms, hoursPadded = true, decimal = ".")

    /** Short human clock: m:ss, or h:mm:ss past one hour. */
    private fun shortTime(ms: Long): String =
        clockParts(ms, hoursPadded = false, decimal = null)

    private fun clockParts(ms: Long, hoursPadded: Boolean, decimal: String?): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val millis = ms.coerceAtLeast(0L) % 1000
        val hourPart = if (hoursPadded) String.format(Locale.US, "%02d:", hours)
            else if (hours > 0) "$hours:"
            else ""
        val minutePart = if (hoursPadded || hours > 0) String.format(Locale.US, "%02d:", minutes)
            else "$minutes:"
        val secondPart = String.format(Locale.US, "%02d", seconds)
        val millisPart = decimal?.let { "${it}${String.format(Locale.US, "%03d", millis)}" } ?: ""
        return "$hourPart$minutePart$secondPart$millisPart"
    }

    private const val SEPARATOR = "\n"
}
