package com.antivocale.app.transcription

import android.media.MediaExtractor
import android.media.MediaFormat

/**
 * A readable text subtitle track inside a video container.
 *
 * @param trackIndex The [MediaExtractor] track index this entry refers to.
 * @param language ISO-639-1/2 language tag when the container declares one (e.g. `"en"`),
 *                 or `null` when absent — callers fall back to the first track.
 * @param mime The track MIME as reported by [MediaFormat.KEY_MIME] (e.g. `application/x-subrip`).
 */
data class SubtitleTrack(val trackIndex: Int, val language: String?, val mime: String)

/**
 * Pure utility for detecting and reading embedded **text** subtitle tracks from video files
 * via Android's [MediaExtractor].
 *
 * This is declared as a stateless Kotlin `object` (intentionally not Hilt-injected): there is
 * no state to scope and no dependency to provide, so DI would add ceremony without value. The
 * cited precedent [com.antivocale.app.audio.AudioPreprocessor] is `@Singleton @Inject` because
 * it owns state; this object does not.
 *
 * All entry points are best-effort: [probe] never throws (returns `emptyList()` on any failure),
 * and [extractToText] returns `null` on failure or blank output. Callers always have the ASR
 * fallback path.
 */
object SubtitleExtractor {

    /**
     * MIME types (and the `text/` prefix family) we treat as readable text subtitle tracks.
     *
     * Tunable: extend this set from on-device findings if a container reports a new text MIME
     * (MKV subrip / MP4 mov-text / WebVTT MIMEs vary by encoder). Image-based formats
     * (VobSub `image/x-mpsub`, PGS) are intentionally absent — they do not decode to plain text.
     */
    private val TEXT_SUBTITLE_MIME_HINTS: Set<String> = setOf(
        "application/x-subrip",
        "application/x-srt",
        "text/vtt",
        "application/webvtt",
        // MP4 mov-text family: encoders sometimes report these for the 3GPP TX3G subtitle track.
        "application/x-mpeghtml",
    )

    /**
     * True when [mime] is non-null and refers to a readable text subtitle track.
     *
     * Matches the explicit [TEXT_SUBTITLE_MIME_HINTS] whitelist OR any MIME whose first
     * segment is `text` (i.e. the `text` slash-something family) OR the
     * MP4 mov-text family. `null` and blank inputs are rejected.
     */
    fun isTextSubtitleMime(mime: String?): Boolean {
        if (mime.isNullOrBlank()) return false
        if (mime in TEXT_SUBTITLE_MIME_HINTS) return true
        if (mime.startsWith("text/")) return true
        return false
    }

    /**
     * Scan [videoPath] for text-MIME subtitle tracks. Empty list if none, or if the container
     * cannot be opened. **Never throws.**
     */
    fun probe(videoPath: String): List<SubtitleTrack> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(videoPath)
            val tracks = mutableListOf<SubtitleTrack>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                // Log every track's MIME so on-device findings can extend TEXT_SUBTITLE_MIME_HINTS
                // (e.g. a container that reports a subtitle track with a MIME we don't yet match).
                android.util.Log.d("SubtitleExtractor", "probe track $i mime=$mime matches=${isTextSubtitleMime(mime)}")
                if (isTextSubtitleMime(mime)) {
                    val language = format.getString(MediaFormat.KEY_LANGUAGE)
                    tracks.add(SubtitleTrack(i, language, mime!!))
                }
            }
            tracks
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Best-effort probe: unreadable container, I/O error, or any MediaExtractor
            // failure → treat as "no subtitle tracks" so callers fall back to ASR.
            emptyList()
        } finally {
            extractor.release()
        }
    }

    /**
     * Read [trackIndex] sample data, strip timestamps/markup → plain text.
     *
     * @return the concatenated plain-text subtitle content, or `null` if the track cannot be
     *         read or yields only blank output.
     */
    fun extractToText(videoPath: String, trackIndex: Int): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(videoPath)
            if (trackIndex < 0 || trackIndex >= extractor.trackCount) return null

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            if (!isTextSubtitleMime(mime)) return null

            extractor.selectTrack(trackIndex)

            val buffer = java.nio.ByteBuffer.allocate(BUFFER_BYTES)
            val output = java.io.ByteArrayOutputStream()
            // 3GPP TX3G (mov_text) samples are [uint16 textLength][textLength bytes UTF-8 text]
            // followed by optional style/modifier boxes — NOT newline-separated like SRT. Parse
            // each sample's length-prefixed text instead of concatenating raw bytes (which leaks
            // the length + style bytes into the output — the stray *,=,7 the user saw were the
            // per-cue text-length low bytes). Subrip/WebVTT are plain text per sample → append
            // raw and let stripTimestampsAndMarkup handle the line-based cleanup.
            val isTx3g = mime.equals("text/3gpp-tt", ignoreCase = true)
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break // end of stream
                val chunk = ByteArray(size)
                buffer.get(chunk)
                if (isTx3g) {
                    tx3gSampleText(chunk)?.takeIf { it.isNotBlank() }?.let { text ->
                        if (output.size() > 0) output.write(' '.code) // space-join cues
                        output.write(text.toByteArray(Charsets.UTF_8))
                    }
                } else {
                    output.write(chunk)
                }
                buffer.clear()
                if (!extractor.advance()) break
            }

            val rawText = output.toByteArray().toString(Charsets.UTF_8)
            val plain = stripTimestampsAndMarkup(rawText, mime).trim()
            // Log size/MIME only — never the body (subtitle text can be private; Log.d survives R8
            // into release logcat without an -assumenosideeffects rule).
            android.util.Log.d("SubtitleExtractor", "extracted subtitle ($mime): ${plain.length} chars")
            if (plain.isEmpty()) null else plain
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Flaky format, I/O failure, or malformed sample data: signal failure so the
            // orchestrator falls back to ASR.
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * Extract the text portion of a 3GPP TX3G (mov_text) sample. Each sample is:
     *   [uint16 big-endian textLength][textLength bytes UTF-8 text][optional style/modifier boxes]
     * Returns only the text (trailing style boxes are ignored), or null if the sample is malformed.
     */
    private fun tx3gSampleText(sample: ByteArray): String? {
        if (sample.size < 2) return null
        val textLen = ((sample[0].toInt() and 0xff) shl 8) or (sample[1].toInt() and 0xff)
        if (textLen <= 0 || textLen > sample.size - 2) return null
        return String(sample, 2, textLen, Charsets.UTF_8)
    }

    /** Per-sample read buffer for [extractToText]. Subtitle samples are small text payloads. */
    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Strip SRT/WebVTT/tx3g timestamps, cue headers, cue indices and `<>` markup tags from
     * [raw] subtitle text and return the concatenated plain text.
     *
     * Pure and deterministic; exposed `internal` for unit testing.
     *
     * @param mime the track MIME — reserved for format-specific quirks; currently unused but
     *             kept on the signature so callers (and future ASS/SSA handling) don't need to
     *             change when format-specific stripping is added.
     */
    internal fun stripTimestampsAndMarkup(raw: String, mime: String): String {
        // 1. Strip all <...> markup tags: HTML/tx3g styling (<i>, </i>, <b>) and WebVTT
        //    inline cue-time tags (<00:00:01.000>).
        val withoutTags = raw.replace(MARKUP_TAG_REGEX, "")

        // 2. Keep only real subtitle text lines: drop cue timestamp ranges (lines
        //    containing "-->"), standalone timestamp lines, SRT cue indices (lines that
        //    are purely digits), the WebVTT header, and blank lines.
        val cues = withoutTags.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { line ->
                !line.contains("-->") &&
                    !CUE_INDEX_REGEX.matches(line) &&
                    !STANDALONE_TIMESTAMP_REGEX.matches(line) &&
                    line != "WEBVTT"
            }
            .toList()

        // 3. Join cues with a single space, collapse any internal double-spaces the tag
        //    stripping may have introduced, and trim. Empty input → "".
        return cues.joinToString(" ")
            .replace(MULTIPLE_SPACES_REGEX, " ")
            .trim()
    }

    // Matches <...> (non-greedy, no nested '>'): covers <i>, </i>, <00:00:01.500>, <b>, etc.
    private val MARKUP_TAG_REGEX = Regex("<[^>]*>")

    // A pure SRT cue index line: one or more digits and nothing else (e.g. "1", "23").
    private val CUE_INDEX_REGEX = Regex("^\\d+$")

    // A standalone timestamp line, SRT (',') or WebVTT ('.'), 1- or 2-digit hour.
    private val STANDALONE_TIMESTAMP_REGEX =
        Regex("^\\d?\\d:\\d\\d:\\d\\d[,.]\\d{3}$")

    // Two or more spaces, collapsed to one after tag stripping.
    private val MULTIPLE_SPACES_REGEX = Regex(" {2,}")
}
