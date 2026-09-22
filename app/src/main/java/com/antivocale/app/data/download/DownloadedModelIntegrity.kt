package com.antivocale.app.data.download

import java.io.File

/**
 * TASK-305: structural post-download validation, the fallback layer under the
 * SHA-256 pins. GigaAM and both Parakeet variants pin their files; the
 * remaining variants ship no checksums,
 * so a truncated/corrupt download previously surfaced later as an opaque native
 * model-format error inside OfflineRecognizer. These checks catch it at download
 * time with an actionable message.
 */
object DownloadedModelIntegrity {

    /** Protobuf field tag for ONNX ModelProto.ir_version: every valid ONNX file starts with 0x08. */
    private const val ONNX_FIRST_BYTE: Int = 0x08

    /** Anything smaller is certainly a stub/error page, never a model graph. */
    private const val MIN_ONNX_BYTES: Long = 1024L

    /** Tokens smaller than this are a stub (a real BPE/vocab file is >= hundreds of bytes). */
    private const val MIN_TOKENS_BYTES: Long = 64L

    data class Finding(val file: File, val reason: String)

    /** Bytes 4-7 read "ORTM" (the ort flatbuffer file identifier). */
    private fun ByteArray.or_tm(): Boolean =
        this[4] == 'O'.code.toByte() && this[5] == 'R'.code.toByte() &&
            this[6] == 'T'.code.toByte() && this[7] == 'M'.code.toByte()

    /**
     * One short-read-safe 8-byte head read shared by the .onnx and .ort
     * branches (both already past the size floor; review round: the two arms
     * carried a hand-rolled read each). Null when the file is shorter than
     * 8 bytes.
     */
    private fun head8(f: File): ByteArray? = f.inputStream().use { ins ->
        val buf = ByteArray(8)
        var off = 0
        while (off < 8) {
            val n = ins.read(buf, off, 8 - off)
            if (n < 0) break
            off += n
        }
        if (off == 8) buf else null
    }

    /**
     * Validates every file in [modelDir] that matters to sherpa-onnx:
     * .onnx files must exist, exceed the floor, and carry the ONNX leading
     * byte; .ort files (GH #89 moonshine v2) get the same floor plus the
     * renamed-split-file check (ONNX protobuf bytes under a .ort name);
     * .txt token files must exist and be non-trivial. Returns the failures
     * (empty = healthy). Files with other extensions are only existence-checked.
     */
    fun validate(modelDir: File): List<Finding> {
        if (!modelDir.isDirectory) return listOf(Finding(modelDir, "not a directory"))
        val files = modelDir.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) return listOf(Finding(modelDir, "directory is empty"))
        return files.mapNotNull { f ->
            when {
                // The size floor gates both container arms; past it, one
                // shared 8-byte head read answers both magic checks. A head
                // that cannot yield 8 bytes past the floor is reported as
                // truncation, not format (review round: the file shrank
                // between length() and the read).
                f.name.endsWith(".onnx", ignoreCase = true) -> when {
                    f.length() < MIN_ONNX_BYTES -> Finding(f, "suspiciously small (${f.length()}B): download truncated?")
                    else -> {
                        val h = head8(f)
                        when {
                            h == null -> Finding(f, "unreadable head: download truncated?")
                            h[0] != ONNX_FIRST_BYTE.toByte() ->
                                Finding(f, "missing ONNX header: file is not a model graph")
                            else -> null
                        }
                    }
                }
                // GH #89 moonshine v2 ships .ort (the ORT flatbuffer format).
                // Magic verified against a real artifact: bytes 4-7 after the
                // 4-byte root offset read "ORTM" (onnxruntime ort.fbs
                // file_identifier; the uk encoder_model.ort checked by hand).
                // The finding fires only when the bytes are NOT
                // identifier-carrying ORT AND look like protobuf ONNX (first
                // byte 0x08): the ORTM identifier is a producer convention,
                // and an identifier-less valid .ort must not be deleted as
                // corrupt by the downstream consumers.
                f.name.endsWith(".ort", ignoreCase = true) -> when {
                    f.length() < MIN_ONNX_BYTES -> Finding(f, "suspiciously small (${f.length()}B): download truncated?")
                    else -> {
                        val h = head8(f)
                        if (h != null && !h.or_tm() && h[0] == ONNX_FIRST_BYTE.toByte()) {
                            Finding(f, "ONNX bytes under a .ort name: wrong format or renamed split file")
                        } else {
                            null
                        }
                    }
                }
                f.name.endsWith(".txt", ignoreCase = true) && f.length() < MIN_TOKENS_BYTES ->
                    Finding(f, "token file too small (${f.length()}B): download truncated?")
                else -> null
            }
        }
    }
}
