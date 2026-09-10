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

    /**
     * Validates every file in [modelDir] that matters to sherpa-onnx:
     * .onnx files must exist, exceed the floor, and carry the ONNX leading byte;
     * .txt token files must exist and be non-trivial. Returns the failures
     * (empty = healthy). Files with other extensions are only existence-checked.
     */
    fun validate(modelDir: File): List<Finding> {
        if (!modelDir.isDirectory) return listOf(Finding(modelDir, "not a directory"))
        val files = modelDir.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) return listOf(Finding(modelDir, "directory is empty"))
        return files.mapNotNull { f ->
            when {
                f.name.endsWith(".onnx", ignoreCase = true) -> when {
                    f.length() < MIN_ONNX_BYTES -> Finding(f, "suspiciously small (${f.length()}B) - download truncated?")
                    f.inputStream().use { it.read() } != ONNX_FIRST_BYTE ->
                        Finding(f, "missing ONNX header - file is not a model graph")
                    else -> null
                }
                f.name.endsWith(".txt", ignoreCase = true) && f.length() < MIN_TOKENS_BYTES ->
                    Finding(f, "token file too small (${f.length()}B) - download truncated?")
                else -> null
            }
        }
    }
}
