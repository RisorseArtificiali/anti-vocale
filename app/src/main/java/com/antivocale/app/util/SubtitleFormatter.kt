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

    /** TASK-598 F2: the word splitter both alignment sides share (the same whitespace idiom as PunctuationPolicy). */
    private val WHITESPACE = Regex("\\s+")

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
     * subtitle file is ever written with bogus timing. TASK-598 F5: both
     * plain-txt exits (the selected TXT and the degraded one) carry speaker
     * turns when the cues are labeled; the timed formats stay cue-derived,
     * the timing is theirs.
     */
    fun resolveExport(
        selected: Format,
        transcript: String,
        segments: List<TimedSegment>,
        failedChunkCount: Int,
    ): ExportDecision {
        val plainTxt = annotatedOrStored(transcript, segments)
        if (selected == Format.TXT) return ExportDecision(Format.TXT, plainTxt)
        if (segments.isEmpty() || failedChunkCount > 0) {
            val content = if (failedChunkCount > 0) {
                "$plainTxt\n[$failedChunkCount chunks failed; timestamps omitted]"
            } else plainTxt
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
     *
     * TASK-598 F2: [polishedText] is the stored transcript when it is not
     * already the cues' plain join (the punctuation pass rewrote it while
     * the cues keep the pre-polish texts); when [alignPolishedText]
     * succeeds the rendering uses the polished cue texts, so a diarized
     * row never silently regresses to the unpunctuated cues. Any mismatch
     * keeps the raw cue texts, today's behavior.
     */
    fun speakerAnnotated(segments: List<TimedSegment>, polishedText: String? = null): String? {
        if (segments.none { it.speaker != null }) return null
        val cues = polishedText?.let { alignPolishedText(it, segments) } ?: segments
        // TASK-600 F9: the shared cue-join body (renderBuffer convention like
        // renderCues/timedTxt), not a third hand-rolled loop.
        return with(renderBuffer(cues)) {
            appendCueBody(cues)
            toString()
        }
    }

    /**
     * TASK-598 F2: re-texts each cue with its word-count slice of
     * [polished]. The punctuation pass is punctuation/case-only by contract
     * (PunctuationPolicy's prompt; its length guard alone cannot prove it),
     * so word count is the invariant that lets the polished characters ride
     * along into the per-turn rendering. A count mismatch means alignment
     * is impossible: null, and the caller falls back to the raw cue texts,
     * the honest render. Timing and speaker labels are carried unchanged.
     */
    fun alignPolishedText(polished: String, segments: List<TimedSegment>): List<TimedSegment>? {
        val polishedWords = polished.split(WHITESPACE).filter { it.isNotBlank() }
        val counts = segments.map { it.text.split(WHITESPACE).count { word -> word.isNotBlank() } }
        if (counts.sum() != polishedWords.size) return null
        var from = 0
        val aligned = segments.mapIndexed { index, segment ->
            val slice = polishedWords.subList(from, from + counts[index])
            from += counts[index]
            // Code review F1: a compensating merge+split keeps the TOTAL count
            // while moving words across cues ("gonna stay" + "all right man" ->
            // "Going to stay. Alright, man?"); the total alone would re-attribute
            // words to the wrong SPEAKER silently. Within the pass's contract
            // (punctuation/case-only) every cue's slice IS the cue's own words:
            // letters-only comparison proves it, and any merge/split anywhere
            // fails the whole alignment into the raw-cue fallback.
            val cueLetters = segment.text.filter { it.isLetter() }.lowercase()
            val sliceLetters = slice.joinToString("").filter { it.isLetter() }.lowercase()
            if (cueLetters != sliceLetters) return null
            segment.copy(text = slice.joinToString(" "))
        }
        return aligned
    }

    /**
     * TASK-598 simplify F1: the ONE surface entry point (History rows, TXT
     * export, result notification): the annotated form when the row carries
     * speaker-labeled cues and the stored text aligns onto them, else the
     * stored text unchanged. Callers never compose the candidate check and
     * the fallback themselves; that incantation drifted across four sites
     * in the first implementation.
     */
    fun annotatedOrStored(storedText: String?, segments: List<TimedSegment>): String =
        nullableAnnotated(storedText, segments) ?: storedText.orEmpty()

    /**
     * The nullable half of [annotatedOrStored] for callers whose fallback is
     * not the stored text (the History row flow: no cues means no annotated
     * state at all, the list shows its own stored column).
     */
    fun nullableAnnotated(storedText: String?, segments: List<TimedSegment>): String? =
        storedText?.let { speakerAnnotated(segments, polishedCandidate(it, segments)) }

    /**
     * TASK-598 F2: the stored transcript as a [speakerAnnotated] polish
     * candidate: non-null only when it is NOT already the cues' plain join,
     * the shape an un-polished run stores, so an unchanged transcript never
     * re-texts its cues at all.
     */
    private fun polishedCandidate(storedText: String?, segments: List<TimedSegment>): String? =
        storedText?.takeIf { it != segments.joinToString(" ") { cue -> cue.text } }

    /**
     * TASK-600 F9: one cue-join body shared by speakerAnnotated and timedTxt:
     * newline-separated speaker-prefixed text, no timestamps (the caller
     * prepends its own timing columns).
     */
    private fun StringBuilder.appendCueBody(segments: List<TimedSegment>) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) append('\n')
            speakerPrefix(segments, index)?.let { append(it) }
            append(segment.text)
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
    // TASK-600 F9 note: timedTxt keeps its inline loop because its body
    // interleaves the timing columns; appendCueBody serves the two
    // no-timestamp joins (speakerAnnotated now, renderCues already shares
    // renderBuffer).

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
