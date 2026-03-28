package com.antivocale.app.data.download

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Typed download errors with explicit HTTP status codes.
 */
sealed class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unauthorized(message: String) : DownloadException(message)
    class Forbidden(message: String) : DownloadException(message)
    class RangeNotSatisfiable(message: String) : DownloadException(message)
    class HttpError(val statusCode: Int, message: String) : DownloadException(message)
    class NetworkError(message: String, cause: Throwable? = null) : DownloadException(message, cause)
    class Cancelled(message: String) : DownloadException(message)
}

/**
 * Configuration for a resumable download.
 */
data class DownloadConfig(
    val url: String,
    val tempFile: File,
    val targetFile: File,
    val estimatedSizeBytes: Long,
    val authHeader: String? = null,
    val connectTimeoutMs: Int = 30_000,
    val readTimeoutMs: Int = 60_000,
    val isCancelled: () -> Boolean = { false }
)

/**
 * Shared download helper with HTTP Range resume support.
 *
 * Uses [HttpURLConnection] for reliable range handling and integrates
 * [ProgressThrottler] and [DownloadRateTracker] for UI-friendly reporting.
 */
object ResumeDownloadHelper {

    private const val TAG = "ResumeDownloadHelper"
    private const val BUFFER_SIZE = 8192
    private const val SIZE_SIDECAR_SUFFIX = ".size"

    /** Returns the `.size` sidecar file path for a given tar/temp file. */
    fun sizeSidecar(tarFile: File): File = File("${tarFile.path}$SIZE_SIDECAR_SUFFIX")

    /**
     * Reads the actual total file size from the `.size` sidecar written during download.
     * Falls back to [estimatedBytes] if the sidecar is missing or unreadable.
     */
    fun readStoredTotalBytes(tarFile: File, estimatedBytes: Long): Long {
        return try {
            sizeSidecar(tarFile).readText().trim().toLongOrNull() ?: estimatedBytes
        } catch (e: Exception) {
            estimatedBytes
        }
    }

    /** Deletes both a tar/temp file and its `.size` sidecar. */
    fun clearTarDownload(tarFile: File) {
        sizeSidecar(tarFile).delete()
        tarFile.delete()
    }

    /**
     * Downloads a file with resume support.
     *
     * - If [DownloadConfig.tempFile] exists and has content, sends an HTTP Range
     *   header to resume from that byte offset.
     * - On HTTP 200 the server ignored the range → starts fresh (deletes temp).
     * - On HTTP 206 the server supports range → appends to the temp file.
     * - On completion, renames temp → target.
     *
     * @param config Download configuration
     * @param onProgress Called with (bytesDownloaded, totalBytes, progressPercent)
     * @param onStateChange Called with each [DownloadState] transition
     * @return [Result] containing the final target file or an error
     */
    fun downloadWithResume(
        config: DownloadConfig,
        onProgress: (Long, Long, Float) -> Unit = { _, _, _ -> },
        onStateChange: (DownloadState) -> Unit = {}
    ): Result<File> {
        val throttler = ProgressThrottler()
        val rateTracker = DownloadRateTracker()
        rateTracker.reset()

        val url = URL(config.url)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = config.connectTimeoutMs
        connection.readTimeout = config.readTimeoutMs
        connection.setRequestProperty("Accept-Encoding", "identity")

        if (config.authHeader != null) {
            connection.setRequestProperty("Authorization", config.authHeader)
        }

        // Resume: check for partial download
        var downloadedBytes = 0L
        val partialSize = config.tempFile.length()
        if (partialSize > 0) {
            Log.i(TAG, "Resuming download from ${partialSize} bytes: ${config.url}")
            connection.setRequestProperty("Range", "bytes=$partialSize-")
        }

        connection.connect()

        val totalBytes = when (connection.responseCode) {
            HttpURLConnection.HTTP_OK -> {
                // Server doesn't support range (ignored our Range header).
                // Start fresh by deleting the partial temp file.
                if (partialSize > 0) {
                    Log.i(TAG, "Starting fresh (HTTP 200): server ignored Range header, temp file was ${partialSize} bytes")
                    config.tempFile.delete()
                }
                downloadedBytes = 0L
                // Use estimated size if Content-Length is missing
                connection.contentLengthLong.takeIf { it > 0 } ?: config.estimatedSizeBytes
            }
            HttpURLConnection.HTTP_PARTIAL -> {
                val contentRange = connection.getHeaderField("Content-Range")
                if (contentRange != null) {
                    // Parse "bytes 12345-67890/67891"
                    val rangeParts = contentRange.substringAfter("bytes ").split("/")
                    val byteRange = rangeParts[0].split("-")
                    downloadedBytes = byteRange[0].toLong()
                    Log.i(TAG, "Resuming from byte $downloadedBytes")
                    // Total from Content-Range: "bytes start-end/total"
                    val total = rangeParts.getOrNull(1)?.toLongOrNull()
                    total ?: config.estimatedSizeBytes
                } else {
                    downloadedBytes = partialSize
                    config.estimatedSizeBytes
                }
            }
            HttpURLConnection.HTTP_UNAUTHORIZED -> {
                val errorMsg = "HTTP 401 Unauthorized"
                onStateChange(DownloadState.Error(errorMsg))
                connection.disconnect()
                return Result.failure(DownloadException.Unauthorized(errorMsg))
            }
            HttpURLConnection.HTTP_FORBIDDEN -> {
                val errorMsg = "HTTP 403 Forbidden"
                onStateChange(DownloadState.Error(errorMsg))
                connection.disconnect()
                return Result.failure(DownloadException.Forbidden(errorMsg))
            }
            416 -> { // HTTP_REQUESTED_RANGE_NOT_SATISFIABLE
                val errorMsg = "HTTP 416 Range Not Satisfiable"
                Log.w(TAG, "$errorMsg — temp file was ${partialSize} bytes, deleting and restarting fresh")
                config.tempFile.delete()
                connection.disconnect()
                return Result.failure(DownloadException.RangeNotSatisfiable(errorMsg))
            }
            else -> {
                val code = connection.responseCode
                val errorMsg = "Download failed: HTTP $code"
                onStateChange(DownloadState.Error(errorMsg))
                connection.disconnect()
                return Result.failure(DownloadException.HttpError(code, errorMsg))
            }
        }

        // Persist actual total bytes so downloaders can reliably detect
        // download completion across app restarts (instead of relying on
        // hard-coded ESTIMATED_SIZES which may not match the real file).
        try {
            sizeSidecar(config.tempFile).writeText(totalBytes.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write .size sidecar: ${e.message}")
        }

        val inputStream = connection.inputStream
        val outputStream = FileOutputStream(config.tempFile, true) // append for resume
        val buffer = ByteArray(BUFFER_SIZE)

        // Record initial position for rate tracking
        rateTracker.record(downloadedBytes)

        try {
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (config.isCancelled()) {
                    onStateChange(DownloadState.Cancelled("User cancelled"))
                    return Result.failure(DownloadException.Cancelled("Download cancelled"))
                }

                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                if (throttler.shouldReport()) {
                    rateTracker.record(downloadedBytes)
                    val progress = (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                    val rate = rateTracker.getRateBytesPerSec()
                    val eta = rateTracker.getEtaSeconds(downloadedBytes, totalBytes, rate)

                    onProgress(downloadedBytes, totalBytes, progress)
                    onStateChange(
                        DownloadState.Downloading(
                            bytesDownloaded = downloadedBytes,
                            totalBytes = totalBytes,
                            progressPercent = progress * 100,
                            downloadRateBytesPerSec = rate,
                            etaSeconds = eta
                        )
                    )
                }
            }
        } finally {
            outputStream.close()
            inputStream.close()
            connection.disconnect()
        }

        // Emit final 100% progress — the throttle may have skipped the last update
        // so the UI could be stuck at 96-99% without this.
        onProgress(totalBytes, totalBytes, 1f)
        onStateChange(
            DownloadState.Downloading(
                bytesDownloaded = totalBytes,
                totalBytes = totalBytes,
                progressPercent = 100f
            )
        )

        // Rename temp → target (skip if they're the same file)
        if (config.tempFile != config.targetFile) {
            if (!config.tempFile.renameTo(config.targetFile)) {
                config.tempFile.delete()
                val error = "Failed to rename temp file to ${config.targetFile.name}"
                onStateChange(DownloadState.Error(error))
                return Result.failure(Exception(error))
            }
        }

        Log.i(TAG, "Download complete: ${config.targetFile.path} (${config.targetFile.length()} bytes)")
        return Result.success(config.targetFile)
    }
}
