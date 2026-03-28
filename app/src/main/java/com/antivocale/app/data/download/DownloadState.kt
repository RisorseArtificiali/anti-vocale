package com.antivocale.app.data.download

import java.io.File

/**
 * Unified download state shared across all downloaders (Gemma, Whisper, Parakeet).
 */
sealed class DownloadState {
    /** No download activity. */
    data object Idle : DownloadState()

    /** Checking if URL is publicly accessible (Gemma auth pre-flight). */
    data class CheckingAccess(val url: String) : DownloadState()

    /** Opening connection to the download server. */
    data class Connecting(val url: String) : DownloadState()

    /** Actively downloading bytes. */
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progressPercent: Float,
        val downloadRateBytesPerSec: Float = 0f,
        val etaSeconds: Long = -1L
    ) : DownloadState()

    /** Transient retry in progress. */
    data class Retrying(val attempt: Int, val maxRetries: Int, val reason: String) : DownloadState()

    /** Extracting an archive (Whisper / Parakeet tar.bz2). */
    data class Extracting(
        val fileIndex: Int,
        val totalFiles: Int,
        val fileName: String = "",
        val bytesExtracted: Long = 0,
        val currentFileSize: Long = 0
    ) : DownloadState()

    /** A partial download was detected on app start (resume available). */
    data class PartiallyDownloaded(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progressPercent: Int
    ) : DownloadState()

    /** Download finished successfully. */
    data class Complete(val file: File) : DownloadState()

    /** Download failed. */
    data class Error(val message: String, val throwable: Throwable? = null) : DownloadState()

    /** Download was cancelled by the user. */
    data class Cancelled(val reason: String) : DownloadState()
}
