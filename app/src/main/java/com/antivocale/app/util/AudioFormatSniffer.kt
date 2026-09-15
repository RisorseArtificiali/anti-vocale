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
            // MP3 or AAC: MPEG sync word (0xFF Ex/Fx), discriminated by the
            // version/layer fields (see [mpegAudio]).
            header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0 ->
                mpegAudio(header[1])
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

    /**
     * Discriminates the streams sharing the MPEG sync word by the second
     * header byte's version (bits 4-3) and layer (bits 2-1) fields:
     * version 01 is reserved (not MPEG audio), layer 00 is reserved in
     * MPEG audio but is exactly the ADTS AAC signature, and a valid
     * version+layer pair is MPEG audio (mp3). Anything else: null, which
     * rejects ADTS-shaped garbage and arbitrary 0xFF-leading binary.
     */
    private fun mpegAudio(secondByte: Byte): String? = when {
        (secondByte.toInt() and 0x18) == 0x08 -> null   // version reserved
        (secondByte.toInt() and 0x06) == 0x00 -> "aac"  // ADTS AAC
        else -> "mp3"
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
     * 3GP (3gp*), QuickTime (qt) and the M4A audio brands apart from generic
     * MP4. Ordinary MP4 video brands (isom, mp42, ...) must resolve to
     * "mp4", not an audio-only extension: callers branch on the video set
     * (subtitle probe, video badge), and an audio label silently disables
     * both for a video the user shared.
     */
    private fun mp4Brand(header: ByteArray): String {
        if (header.size < 12) return "mp4"
        val brand = String(header, 8, 4, Charsets.US_ASCII)
        return when {
            // 3GPP2 brands (3g2*) carry video; lumping them under the audio
            // "3gp" label would strip the video badge and subtitle probe.
            brand.startsWith("3g2") -> "3g2"
            brand.startsWith("3g") -> "3gp"
            brand.startsWith("qt") -> "mov"
            // M4A/M4B/M4P are the audio brands; M4V (video) must fall through.
            brand.startsWith("M4A") || brand.startsWith("M4B") || brand.startsWith("M4P") -> "m4a"
            else -> "mp4"
        }
    }
}
