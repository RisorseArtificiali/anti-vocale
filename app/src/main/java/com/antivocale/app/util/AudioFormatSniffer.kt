package com.antivocale.app.util

/**
 * TASK-519 (GH #95): pure magic-byte audio format detection. Reads the
 * first bytes of a stream and matches known audio/video container
 * signatures. Returns the file extension (matching SharedAudioHandler's
 * SUPPORTED_EXTENSIONS) or null when the header matches nothing.
 *
 * Pure Kotlin, no Android imports, so the whole table is JVM-testable.
 * The caller (SharedAudioHandler.sniffExtension) opens the stream and
 * reads the header bytes; this object only interprets them.
 */
object AudioFormatSniffer {

    /**
     * Detects the audio/video container from the first [header] bytes of
     * the stream. Returns the extension ("mp3", "m4a", ...) or null.
     */
    fun detect(header: ByteArray): String? {
        if (header.size < 4) return null
        return when {
            // MP3: ID3v2 tag
            matchesAt(header, 0, "ID3") -> "mp3"
            // MP3: MPEG audio sync word (0xFF Ex/Fx)
            header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            // MP4 family: ftyp box at offset 4, brand at offset 8
            header.size >= 8 && matchesAt(header, 4, "ftyp") -> mp4Brand(header)
            // OGG (includes Opus-in-Ogg and Vorbis)
            matchesAt(header, 0, "OggS") -> "ogg"
            // WAV: RIFF container with WAVE form type
            header.size >= 12 && matchesAt(header, 0, "RIFF") && matchesAt(header, 8, "WAVE") -> "wav"
            // FLAC
            matchesAt(header, 0, "fLaC") -> "flac"
            // AMR (narrowband and wideband both start with #!AMR)
            header.size >= 5 && matchesAt(header, 0, "#!AMR") -> "amr"
            // WebM/Matroska: EBML magic bytes
            header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte() -> "webm"
            else -> null
        }
    }

    /** True when [header] starting at [offset] contains [text] as ASCII. */
    private fun matchesAt(header: ByteArray, offset: Int, text: String): Boolean {
        if (offset + text.length > header.size) return false
        for (i in text.indices) {
            if (header[offset + i] != text[i].code.toByte()) return false
        }
        return true
    }

    /**
     * MP4-family brand discrimination: the 4-byte brand at offset 8 tells
     * 3GP (3gp*) and QuickTime (qt) apart from generic MP4/M4A. Audio-first
     * default for unknown brands: the audio track is what the pipeline
     * consumes anyway.
     */
    private fun mp4Brand(header: ByteArray): String {
        if (header.size < 12) return "m4a"
        val brand = String(header, 8, 4, Charsets.US_ASCII)
        return when {
            brand.startsWith("3g") -> "3gp"
            brand.startsWith("qt") -> "mov"
            else -> "m4a"
        }
    }
}
