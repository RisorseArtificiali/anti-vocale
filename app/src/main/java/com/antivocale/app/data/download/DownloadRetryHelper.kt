package com.antivocale.app.data.download

import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Shared retry logic for downloads that use [ResumeDownloadHelper].
 *
 * TASK-969 adds two defaulted knobs so callers with a stricter taxonomy
 * (the external-model importer: connection-class failures only, a fixed
 * 5s/15s/30s ladder spanning the observed DNS-churn window) ride the SAME
 * loop instead of hand-rolling a second policy: [delaysMs] replaces the
 * exponential backoff with a fixed ladder (attempts = ladder size + 1),
 * and [isRetryable] filters which failures may re-attempt (consulted for
 * the typed, boxed failures AND the rare raw exception; cancellations and
 * auth failures stay non-retryable for everyone, and the 416
 * stale-temp recovery stays unconditional because it repairs local state,
 * not the network). Defaults preserve the historical behavior exactly.
 *
 * @param config Download configuration
 * @param maxRetries Maximum retry attempts (default 3)
 * @param backoffBase Exponential backoff base (e.g., 2.0 or 3.0)
 * @param tag Log tag
 * @param onProgress Called with (bytesSoFar, totalBytes, progress 0..1)
 * @param onStateChange Called with each [DownloadState] transition
 * @param stateMapper Optional mapper to transform states (e.g., remap progress to 0-60% range)
 * @param delaysMs Fixed backoff ladder; null = exponential from [backoffBase]
 * @param isRetryable May this failure be re-attempted (default: everything)
 * @return [Result] containing the downloaded file or an error
 */
suspend fun downloadWithRetry(
    config: DownloadConfig,
    maxRetries: Int = 3,
    backoffBase: Double = 2.0,
    tag: String = "DownloadRetry",
    onProgress: (Long, Long, Float) -> Unit = { _, _, _ -> },
    onStateChange: (DownloadState) -> Unit = {},
    stateMapper: ((DownloadState) -> DownloadState) = { it },
    delaysMs: LongArray? = null,
    isRetryable: (Throwable) -> Boolean = { true },
): Result<File> = withContext(Dispatchers.IO) {
    var lastError: Throwable? = null
    val attempts = if (delaysMs != null) delaysMs.size + 1 else maxRetries

    fun backoffFor(attempt: Int): Long =
        if (delaysMs != null) delaysMs[attempt - 1]
        else (1000L * Math.pow(backoffBase, (attempt - 1).toDouble())).toLong()

    for (attempt in 1..attempts) {
        if (config.isCancelled()) {
            onStateChange(DownloadState.Cancelled("User cancelled"))
            return@withContext Result.failure(DownloadException.Cancelled("Download cancelled"))
        }

        try {
            val result = ResumeDownloadHelper.downloadWithResume(
                config = config,
                onProgress = onProgress,
                onStateChange = { state -> onStateChange(stateMapper(state)) }
            )

            if (result.isSuccess) return@withContext result

            val error = result.exceptionOrNull()!!
            lastError = error

            // Don't retry user-initiated cancellations
            if (error is DownloadException.Cancelled) {
                onStateChange(DownloadState.Cancelled("User cancelled"))
                return@withContext result
            }

            // Don't retry auth/forbidden errors: these require user action
            if (error is DownloadException.Unauthorized || error is DownloadException.Forbidden) {
                onStateChange(stateMapper(DownloadState.Error(error.message ?: "Error")))
                return@withContext result
            }

            // 416 Range Not Satisfiable: temp file is stale. Delete it and retry
            // without Range header so the next attempt gets a clean 200 response.
            // Unconditional (TASK-969): this repairs LOCAL state, not the
            // network, so a strict isRetryable filter must not skip it.
            if (error is DownloadException.RangeNotSatisfiable) {
                if (config.tempFile.exists()) {
                    Log.i(tag, "Deleting stale temp file for retry: ${config.tempFile.path}")
                    config.tempFile.delete()
                }
                if (attempt < attempts) {
                    val delayMs = 500L
                    Log.i(tag, "Range not satisfiable (attempt $attempt/$attempts), retrying in ${delayMs}ms without resume")
                    onStateChange(stateMapper(DownloadState.Retrying(attempt, attempts, error.message ?: "Range error")))
                    delay(delayMs)
                } else {
                    onStateChange(stateMapper(DownloadState.Error(error.message ?: "Range error")))
                    return@withContext result
                }
                continue
            }

            // The caller's taxonomy decides what may re-attempt; a refused
            // failure returns the ORIGINAL typed result (never the wrapped
            // after-N-attempts form), so type-inspecting callers keep
            // seeing the real DownloadException.
            if (!isRetryable(error)) {
                onStateChange(stateMapper(DownloadState.Error(error.message ?: "Error")))
                return@withContext result
            }

            if (attempt < attempts) {
                val delayMs = backoffFor(attempt)
                Log.i(tag, "Download failed (attempt $attempt/$attempts), retrying in ${delayMs}ms: ${error.message}")
                onStateChange(stateMapper(DownloadState.Retrying(attempt, attempts, error.message ?: "Unknown error")))
                delay(delayMs)
            }

        } catch (e: CancellationException) {
            throw e // structured concurrency: never swallow
        } catch (e: Exception) {
            lastError = e
            if (!isRetryable(e)) {
                onStateChange(stateMapper(DownloadState.Error(e.message ?: "Error")))
                return@withContext Result.failure(e)
            }
            if (attempt < attempts) {
                val delayMs = backoffFor(attempt)
                Log.i(tag, "Download crashed (attempt $attempt/$attempts), retrying in ${delayMs}ms: ${e.message}")
                onStateChange(stateMapper(DownloadState.Retrying(attempt, attempts, e.message ?: "Unknown error")))
                delay(delayMs)
            }
        }
    }

    val errorMsg = "Download failed after $attempts attempts: ${lastError?.message}"
    onStateChange(stateMapper(DownloadState.Error(errorMsg)))
    Result.failure(Exception(errorMsg, lastError))
}
