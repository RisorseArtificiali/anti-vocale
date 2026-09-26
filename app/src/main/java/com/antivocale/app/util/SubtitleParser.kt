package com.antivocale.app.util

import com.antivocale.app.transcription.SubtitleExtractor.CUE_TIME_SEPARATOR
import com.antivocale.app.transcription.TimedSegment
import java.io.File

/**
 * TASK-677 (GH #92, import half): the lenient parse side of the
 * [SubtitleFormatter] read/write pair. A handed .srt/.vtt file becomes
 * [TimedSegment] cues with the file's own segmentation preserved verbatim
 * (AC#3: cues in, cues out; no re-segmentation engine beside
 * SentenceCueBuilder).
 *
 * Lenient by contract, mirroring the reference parser's never-throw stance:
 * a UTF-8 BOM, CR/LF/CRLF line endings, missing or duplicated cue numbers,
 * comma and dot milliseconds, an optional WEBVTT header, NOTE blocks, VTT
 * cue identifiers and settings, hours-less clocks, junk around the --> arrow,
 * and inverted ranges are all absorbed. What cannot be salvaged is counted
 * in [Result.skippedCues] and named in [Result.errorNote]; garbage input
 * yields an empty segment list, never an exception.
 */
object SubtitleParser {

    /** The parse outcome: the cues that parsed, plus what did not. */
    data class Result(
        val segments: List<TimedSegment>,
        /** Cues dropped: a timing line that did not parse, or a cue with no text. */
        val skippedCues: Int,
        /** First thing that went wrong (diagnostics for the log; null on a clean parse). */
        val errorNote: String?,
    )

    /**
     * The share flow hands a local path; any IO failure degrades to an empty
     * result. Review F1: the read is BOUNDED and OOM-safe: a crafted or
     * mistaken giant file is a typed failure, never a process kill (an
     * OutOfMemoryError is an Error and would fly past catch(Exception)).
     * Review F5: a UTF-16 BOM switches the decode (real export option of
     * Windows subtitle editors); UTF-8 with its BOM stays the default.
     */
    fun parseFile(path: String?): Result {
        if (path.isNullOrBlank()) return Result(emptyList(), 0, "no file path")
        return try {
            val file = File(path)
            if (file.length() > MAX_SUBTITLE_FILE_BYTES) {
                return Result(emptyList(), 0, "file too large (${file.length()} bytes)")
            }
            val bytes = file.readBytes()
            val charset = when {
                bytes.size >= 2 && (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) -> Charsets.UTF_16LE
                bytes.size >= 2 && (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) -> Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
            parse(String(bytes, charset))
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            Result(emptyList(), 0, "unreadable file: ${e.message}")
        }
    }

    /** The pure parse. Never throws. */
    fun parse(raw: String): Result {
        // BOM first: it would otherwise glue itself to the WEBVTT header.
        val lines = raw.removePrefix(BOM).split(LINE_BREAK)
        val segments = mutableListOf<TimedSegment>()
        var skipped = 0
        var note: String? = null

        var open: Timing? = null
        var textLines = mutableListOf<String>()
        // GH #83 exports label only the FIRST cue of a speaker turn; a cue
        // without a prefix belongs to the turn already in progress, so the
        // last seen label propagates (that is what makes a labeled export
        // round-trip, TASK-677 AC#4).
        var speaker: Int? = null

        fun closeCue() {
            val timing = open ?: return
            open = null
            val text = textLines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            textLines = mutableListOf()
            val labeled = SPEAKER_PREFIX.find(text)
            var cueSpeaker = speaker
            var stripPrefix = false
            if (labeled != null) {
                val n = labeled.groupValues[1].toIntOrNull()
                // Our exports number speakers from 1; anything else ("SPEAKER 0:")
                // is foreign text and stays literal.
                if (n != null && n >= 1) {
                    cueSpeaker = n - 1
                    speaker = cueSpeaker
                    stripPrefix = true
                }
            }
            val body = if (stripPrefix) text.substring(labeled!!.range.last + 1).trim() else text
            if (body.isEmpty()) {
                skipped++
                if (note == null) note = "cue at ${timing.startMs}ms has no text"
                return
            }
            segments.add(TimedSegment(timing.startMs, timing.endMs, body, cueSpeaker))
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (open == null) {
                when {
                    // A VTT NOTE block runs to the next blank line; consuming
                    // it here keeps a commented-out cue (NOTE with a full
                    // timing range inside) from parsing as a real one.
                    trimmed.startsWith("NOTE") -> {
                        do { i++ } while (i < lines.size && lines[i].trim().isNotEmpty())
                    }
                    else -> {
                        val timing = parseTimingLine(line)
                        if (timing == null) {
                            // Everything else is seekable junk: the WEBVTT
                            // header, STYLE/REGION blocks, cue numbers and
                            // identifiers. A line that carries an arrow but
                            // no parseable clock is a dropped cue.
                            if (trimmed.contains(CUE_TIME_SEPARATOR)) {
                                skipped++
                                if (note == null) note = "unparseable timing line: ${trimmed.take(60)}"
                            }
                            i++
                        } else {
                            open = timing
                            i++
                        }
                    }
                }
                continue
            }
            when {
                trimmed.isEmpty() -> {
                    closeCue()
                    i++
                }
                // A pure-number line directly above the next timing line is
                // the next cue's index (SRT written without blank lines
                // between blocks): close here, the number is consumed.
                // Review F4: when the OPEN cue has no text yet, the number is
                // that cue's TEXT (countdowns, numbered lyrics), never an
                // index: consume it as text and stay in the cue.
                CUE_NUMBER.matches(trimmed) && i + 1 < lines.size &&
                    parseTimingLine(lines[i + 1]) != null -> {
                    if (textLines.isEmpty() && open != null) {
                        textLines.add(trimmed)
                    } else {
                        closeCue()
                    }
                    i++
                }
                else -> {
                    val timing = parseTimingLine(line)
                    if (timing != null) {
                        // Back-to-back cues with no blank separator.
                        closeCue()
                        open = timing
                    } else {
                        textLines.add(line)
                    }
                    i++
                }
            }
        }
        closeCue()

        if (segments.isEmpty() && note == null) note = "no cue timing lines found"
        return Result(segments, skipped, note)
    }

    /** One parsed cue timing. */
    private data class Timing(val startMs: Long, val endMs: Long)

    /**
     * The clock on a timing line: hours optional (VTT allows MM:SS.mmm),
     * up to 4 hour digits (the export clock never truncates hours, and a
     * capped pattern would silently misparse past it instead of failing),
     * 1-3 fractional digits, comma or dot separator.
     */
    private val TIMESTAMP_REGEX = Regex("(?:(\\d{1,4}):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})")

    /** A standalone SRT cue index line: digits and nothing else. */
    private val CUE_NUMBER = Regex("\\d+")

    /** Review F1: a real subtitle of any duration is far below this; bigger = wrong file. */
    private const val MAX_SUBTITLE_FILE_BYTES = 5L * 1024 * 1024

    /** The app's own speaker-turn prefix (SubtitleFormatter writes "SPEAKER k: "). */
    private val SPEAKER_PREFIX = Regex(com.antivocale.app.transcription.SubtitleExtractor.SPEAKER_PREFIX_PATTERN)

    private const val BOM = "\uFEFF"

    private val LINE_BREAK = Regex("\r\n|\r|\n")

    /**
     * Parses one timing line. The timestamp CLOSEST to the arrow wins on each
     * side, absorbing junk tokens around the arrow and VTT cue settings
     * ("align:start position:10%") after the end clock. An inverted range is
     * clamped to a zero-length cue (lenient), never dropped.
     */
    private fun parseTimingLine(line: String): Timing? {
        val arrow = line.indexOf(CUE_TIME_SEPARATOR)
        if (arrow < 0) return null
        val start = TIMESTAMP_REGEX.findAll(line.substring(0, arrow)).lastOrNull()?.value
            ?: return null
        val end = TIMESTAMP_REGEX.findAll(line.substring(arrow + CUE_TIME_SEPARATOR.length))
            .firstOrNull()?.value ?: return null
        val startMs = parseTimestamp(start) ?: return null
        val endMs = parseTimestamp(end) ?: return null
        return Timing(startMs, maxOf(endMs, startMs))
    }

    internal fun parseTimestamp(raw: String): Long? {
        val match = TIMESTAMP_REGEX.matchEntire(raw) ?: return null
        val (hours, minutes, seconds, fraction) = match.destructured
        // Fractional milliseconds: "5" is 500ms, "05" is 50ms.
        val millis = fraction.padEnd(3, '0').toLong()
        val h = hours.ifEmpty { "0" }.toLong()
        return ((h * 60L + minutes.toLong()) * 60L + seconds.toLong()) * 1000L + millis
    }
}
