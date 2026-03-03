package com.localai.bridge.transcription

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads and extracts Whisper models for sherpa-onnx.
 *
 * Model source: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/
 * Model sizes:
 * - Tiny: ~75MB (int8 quantized)
 * - Base: ~150MB (int8 quantized)
 *
 * Languages: Multilingual (99+ languages including excellent Italian support)
 */
object WhisperDownloader {

    private const val TAG = "WhisperDownloader"

    // Official sherpa-onnx pre-converted models (no authentication required)
    private val MODEL_URLS = mapOf(
        WhisperModelManager.Variant.TINY to
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
        WhisperModelManager.Variant.BASE to
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2"
    )

    private val MODEL_NAMES = mapOf(
        WhisperModelManager.Variant.TINY to "sherpa-onnx-whisper-tiny",
        WhisperModelManager.Variant.BASE to "sherpa-onnx-whisper-base"
    )

    private val ESTIMATED_SIZES = mapOf(
        WhisperModelManager.Variant.TINY to 75L,
        WhisperModelManager.Variant.BASE to 150L
    )

    // Required files after extraction (Whisper uses separate encoder/decoder)
    val REQUIRED_FILES = listOf(
        "tokens.txt",
        "encoder.int8.onnx",  // or tiny.en-encoder.int8.onnx, etc.
        "decoder.int8.onnx"   // or tiny.en-decoder.int8.onnx
    )

    /**
     * Download state for progress tracking.
     */
    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Connecting(val url: String) : DownloadState()
        data class Downloading(
            val bytesDownloaded: Long,
            val totalBytes: Long,
            val progressPercent: Float
        ) : DownloadState()
        data class Extracting(
            val fileIndex: Int,
            val totalFiles: Int,
            val fileName: String = "",
            val bytesExtracted: Long = 0,
            val currentFileSize: Long = 0
        ) : DownloadState()
        data class Complete(val modelDir: File) : DownloadState()
        data class Error(val message: String, val throwable: Throwable? = null) : DownloadState()
        data class Cancelled(val reason: String) : DownloadState()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var isCancelled = false

    /**
     * Downloads and extracts a Whisper model.
     *
     * @param context Application context
     * @param variant The model variant to download (Tiny or Base)
     * @param onProgress Callback for overall progress (0.0 to 1.0)
     * @param onStateChange Callback for state changes
     * @return Result containing the model directory or an error
     */
    suspend fun downloadModel(
        context: Context,
        variant: WhisperModelManager.Variant,
        onProgress: (Float) -> Unit = {},
        onStateChange: (DownloadState) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        isCancelled = false

        val targetDir = WhisperModelManager.getModelStorageDir(context)
        val modelDirName = MODEL_NAMES[variant] ?: return@withContext Result.failure(
            IllegalArgumentException("Unknown variant: $variant")
        )
        val modelDir = File(targetDir, modelDirName)

        // Check if already downloaded
        if (isModelDownloaded(context, variant)) {
            Log.i(TAG, "Model already downloaded: ${modelDir.absolutePath}")
            onStateChange(DownloadState.Complete(modelDir))
            return@withContext Result.success(modelDir)
        }

        // Pre-download storage check
        val estimatedSizeMB = ESTIMATED_SIZES[variant] ?: 75L
        val requiredBytes = estimatedSizeMB * 1024 * 1024 * 2 // 2x for tar + extracted
        val availableBytes = context.filesDir.usableSpace
        if (availableBytes < requiredBytes) {
            val errorMsg = "Insufficient storage. Need ~${estimatedSizeMB * 2}MB, have ${availableBytes / (1024*1024)}MB available."
            val error = Exception(errorMsg)
            Log.e(TAG, errorMsg)
            onStateChange(DownloadState.Error(errorMsg, error))
            return@withContext Result.failure(error)
        }

        // Create target directory
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val url = MODEL_URLS[variant] ?: return@withContext Result.failure(
            IllegalArgumentException("No URL for variant: $variant")
        )
        val tarFile = File(targetDir, "$modelDirName.tar.bz2")

        Log.i(TAG, "Starting download: $url")
        onStateChange(DownloadState.Connecting(url))

        // Download phase (60% of progress)
        return@withContext try {
            val request = Request.Builder()
                .url(url)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorMsg = "Download failed: HTTP ${response.code}"
                onStateChange(DownloadState.Error(errorMsg))
                return@withContext Result.failure(Exception(errorMsg))
            }

            val body = response.body ?: run {
                val error = "Empty response body"
                onStateChange(DownloadState.Error(error))
                return@withContext Result.failure(Exception(error))
            }

            val contentLength = body.contentLength()
            Log.i(TAG, "Content length: $contentLength bytes (~${contentLength / (1024*1024)}MB)")

            var totalBytesDownloaded = 0L

            body.byteStream().use { input ->
                FileOutputStream(tarFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled) {
                            tarFile.delete()
                            onStateChange(DownloadState.Cancelled("User cancelled"))
                            return@withContext Result.failure(Exception("Download cancelled"))
                        }

                        output.write(buffer, 0, bytesRead)
                        totalBytesDownloaded += bytesRead

                        // Report download progress (0-60%)
                        val downloadProgress = if (contentLength > 0) {
                            (totalBytesDownloaded.toFloat() / contentLength) * 0.6f
                        } else {
                            0.3f // Unknown size, show 30%
                        }

                        onProgress(downloadProgress.coerceIn(0f, 0.6f))
                        onStateChange(DownloadState.Downloading(
                            bytesDownloaded = totalBytesDownloaded,
                            totalBytes = contentLength,
                            progressPercent = downloadProgress * 100
                        ))
                    }
                }
            }

            Log.i(TAG, "Download complete: ${tarFile.length()} bytes")

            // Extract phase (40% of progress)
            onStateChange(DownloadState.Extracting(0, 3)) // Whisper has 3 main files: tokens, encoder, decoder

            val extractResult = extractTarBz2(tarFile, targetDir, modelDirName) { fileIndex, totalFiles, fileName, bytesExtracted, fileSize ->
                // Report extraction progress (60-100%)
                val fileProgress = if (fileSize > 0) bytesExtracted.toFloat() / fileSize else 0f
                val extractProgress = 0.6f + ((fileIndex - 1 + fileProgress) / totalFiles) * 0.4f
                onProgress(extractProgress.coerceIn(0.6f, 1f))
                onStateChange(DownloadState.Extracting(fileIndex, totalFiles, fileName, bytesExtracted, fileSize))
            }

            // Clean up tar file
            tarFile.delete()

            if (extractResult.isFailure) {
                onStateChange(DownloadState.Error(extractResult.exceptionOrNull()?.message ?: "Extraction failed"))
                return@withContext extractResult
            }

            // Verify extraction - check for tokens.txt and encoder/decoder files
            val hasTokens = File(modelDir, "tokens.txt").exists() ||
                WhisperModelManager.TOKENS_PATTERNS.any { File(modelDir, it).exists() }
            val hasEncoder = WhisperModelManager.ENCODER_PATTERNS.any { File(modelDir, it).exists() }
            val hasDecoder = WhisperModelManager.DECODER_PATTERNS.any { File(modelDir, it).exists() }

            if (!hasTokens || !hasEncoder || !hasDecoder) {
                val errorMsg = "Missing files after extraction in ${modelDir.absolutePath}"
                onStateChange(DownloadState.Error(errorMsg))
                return@withContext Result.failure(Exception(errorMsg))
            }

            Log.i(TAG, "Model ready: ${modelDir.absolutePath}")
            onStateChange(DownloadState.Complete(modelDir))
            Result.success(modelDir)

        } catch (e: Exception) {
            Log.e(TAG, "Download/extract failed", e)
            onStateChange(DownloadState.Error(e.message ?: "Unknown error", e))
            // Clean up partial files
            tarFile.delete()
            modelDir.deleteRecursively()
            Result.failure(e)
        }
    }

    /**
     * Extracts a tar.bz2 file, skipping non-required files.
     * Reports progress during extraction of each file.
     */
    private fun extractTarBz2(
        tarFile: File,
        targetDir: File,
        modelDirName: String,
        onProgress: (fileIndex: Int, totalFiles: Int, fileName: String, bytesExtracted: Long, fileSize: Long) -> Unit
    ): Result<File> {
        return try {
            var fileIndex = 0
            val totalRequired = 3 // tokens.txt + encoder + decoder

            // Create model directory upfront
            val modelDir = File(targetDir, modelDirName)
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }

            FileInputStream(tarFile).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    BZip2CompressorInputStream(bis).use { bzIn ->
                        TarArchiveInputStream(bzIn).use { tarIn ->
                            var entry = tarIn.nextTarEntry

                            while (entry != null) {
                                if (isCancelled) {
                                    return Result.failure(Exception("Extraction cancelled"))
                                }

                                if (!entry.isDirectory) {
                                    // Extract filename from path (handle subdirectories)
                                    val fileName = File(entry.name).name

                                    // Check if this is a file we need
                                    val isTokensFile = WhisperModelManager.TOKENS_PATTERNS.contains(fileName)
                                    val isEncoder = WhisperModelManager.ENCODER_PATTERNS.contains(fileName)
                                    val isDecoder = WhisperModelManager.DECODER_PATTERNS.contains(fileName)
                                    val isEncoderDecoder = WhisperModelManager.ENCODER_DECODER_PATTERNS.contains(fileName)

                                    if (isTokensFile || isEncoder || isDecoder || isEncoderDecoder) {
                                        fileIndex++
                                        val currentFileSize = entry.realSize
                                        var bytesExtracted = 0L

                                        val outputFile = File(modelDir, fileName)

                                        FileOutputStream(outputFile).use { output ->
                                            val buffer = ByteArray(8192)
                                            var bytesRead: Int
                                            var lastReportedPercent = 0

                                            while (tarIn.read(buffer).also { bytesRead = it } != -1) {
                                                output.write(buffer, 0, bytesRead)
                                                bytesExtracted += bytesRead

                                                // Report progress every 5% to avoid UI spam
                                                val currentPercent = if (currentFileSize > 0) {
                                                    ((bytesExtracted * 100) / currentFileSize).toInt()
                                                } else 0

                                                if (currentPercent - lastReportedPercent >= 5 || bytesRead == -1) {
                                                    onProgress(fileIndex, totalRequired, fileName, bytesExtracted, currentFileSize)
                                                    lastReportedPercent = currentPercent
                                                }
                                            }
                                        }

                                        Log.d(TAG, "Extracted: $fileName ($fileIndex/$totalRequired, ${bytesExtracted / 1024}KB)")
                                        // Final progress report for this file
                                        onProgress(fileIndex, totalRequired, fileName, currentFileSize, currentFileSize)
                                    } else {
                                        Log.d(TAG, "Skipping: ${entry.name}")
                                    }
                                }

                                entry = tarIn.nextTarEntry
                            }
                        }
                    }
                }
            }

            Result.success(modelDir)

        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            Result.failure(e)
        }
    }

    /**
     * Cancels any ongoing download.
     */
    fun cancel() {
        isCancelled = true
        Log.i(TAG, "Download cancellation requested")
    }

    /**
     * Checks if a Whisper model is already downloaded.
     */
    fun isModelDownloaded(context: Context, variant: WhisperModelManager.Variant): Boolean {
        val modelDirName = MODEL_NAMES[variant] ?: return false
        val modelDir = File(WhisperModelManager.getModelStorageDir(context), modelDirName)
        return WhisperModelManager.validateModelDirectory(modelDir) != null
    }

    /**
     * Gets the model directory path if downloaded.
     */
    fun getModelPath(context: Context, variant: WhisperModelManager.Variant): String? {
        val modelDirName = MODEL_NAMES[variant] ?: return null
        val modelDir = File(WhisperModelManager.getModelStorageDir(context), modelDirName)
        return if (WhisperModelManager.validateModelDirectory(modelDir) != null) {
            modelDir.absolutePath
        } else {
            null
        }
    }

    /**
     * Gets the estimated download size in MB for a variant.
     */
    fun getEstimatedSizeMB(variant: WhisperModelManager.Variant): Long {
        return ESTIMATED_SIZES[variant] ?: 75L
    }

    /**
     * Deletes a downloaded Whisper model.
     */
    fun deleteModel(context: Context, variant: WhisperModelManager.Variant): Boolean {
        val modelDirName = MODEL_NAMES[variant] ?: return false
        val modelDir = File(WhisperModelManager.getModelStorageDir(context), modelDirName)
        if (!modelDir.exists()) return true

        return try {
            modelDir.deleteRecursively()
            Log.i(TAG, "Model deleted: ${modelDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model", e)
            false
        }
    }
}
