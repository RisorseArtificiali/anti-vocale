package com.antivocale.app.data.download

import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Shared retry logic for downloads that use [ResumeDownloadHelper].
 *
 * @param config Download configuration
 * @param maxRetries Maximum retry attempts (default 3)
 * @param backoffBase Exponential backoff base (e.g., 2.0 or 3.0)
 * @param isCancelled Checked before each attempt
 * @param tag Log tag
 * @param onProgress Called with download progress (0.0 to 1.0)
 * @param onStateChange Called with each [DownloadState] transition
 * @param stateMapper Optional mapper to transform states (e.g., remap progress to 0-60% range)
 * @return [Result] containing the downloaded file or an error
 */
suspend fun downloadWithRetry(
    config: DownloadConfig,
    maxRetries: Int = 3,
    backoffBase: Double = 2.0,
    tag: String = "DownloadRetry",
    onProgress: (Float) -> Unit = {},
    onStateChange: (DownloadState) -> Unit = {},
    stateMapper: ((DownloadState) -> DownloadState) = { it }
): Result<File> = withContext(Dispatchers.IO) {
    var lastError: Throwable? = null

    for (attempt in 1..maxRetries) {
        if (config.isCancelled()) {
            onStateChange(DownloadState.Cancelled("User cancelled"))
            return@withContext Result.failure(DownloadException.Cancelled("Download cancelled"))
        }

        try {
            val result = ResumeDownloadHelper.downloadWithResume(
                config = config,
                onProgress = { _, _, progress ->
                    onProgress(progress)
                },
                onStateChange = { state -> onStateChange(stateMapper(state)) }
            )

            if (result.isSuccess) return@withContext result

            val error = result.exceptionOrNull()!!
            lastError = error

            // Don't retry auth/forbidden errors — these require user action
            if (error is DownloadException.Unauthorized || error is DownloadException.Forbidden) {
                onStateChange(stateMapper(DownloadState.Error(error.message ?: "Error")))
                return@withContext result
            }

            // 416 Range Not Satisfiable: temp file is stale. Delete it and retry
            // without Range header so the next attempt gets a clean 200 response.
            if (error is DownloadException.RangeNotSatisfiable) {
                if (config.tempFile.exists()) {
                    Log.i(tag, "Deleting stale temp file for retry: ${config.tempFile.path}")
                    config.tempFile.delete()
                }
                if (attempt < maxRetries) {
                    val delayMs = 500L
                    Log.i(tag, "Range not satisfiable (attempt $attempt/$maxRetries), retrying in ${delayMs}ms without resume")
                    onStateChange(stateMapper(DownloadState.Retrying(attempt, maxRetries, error.message ?: "Range error")))
                    delay(delayMs)
                } else {
                    onStateChange(stateMapper(DownloadState.Error(error.message ?: "Range error")))
                    return@withContext result
                }
                continue
            }

            if (attempt < maxRetries) {
                val delayMs = (1000L * Math.pow(backoffBase, (attempt - 1).toDouble())).toLong()
                Log.i(tag, "Download failed (attempt $attempt/$maxRetries), retrying in ${delayMs}ms: ${error.message}")
                onStateChange(stateMapper(DownloadState.Retrying(attempt, maxRetries, error.message ?: "Unknown error")))
                delay(delayMs)
            }

        } catch (e: Exception) {
            lastError = e
            if (attempt < maxRetries) {
                val delayMs = (1000L * Math.pow(backoffBase, (attempt - 1).toDouble())).toLong()
                Log.i(tag, "Download crashed (attempt $attempt/$maxRetries), retrying in ${delayMs}ms: ${e.message}")
                onStateChange(stateMapper(DownloadState.Retrying(attempt, maxRetries, e.message ?: "Unknown error")))
                delay(delayMs)
            }
        }
    }

    val errorMsg = "Download failed after $maxRetries attempts: ${lastError?.message}"
    onStateChange(stateMapper(DownloadState.Error(errorMsg)))
    Result.failure(Exception(errorMsg, lastError))
}
