package com.antivocale.app.data.download

import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Extracts a tar.bz2 archive, selecting only files matching [isRequiredFile].
 *
 * @param tarFile The .tar.bz2 archive to extract
 * @param modelDir Target directory for extracted files
 * @param isRequiredFile Predicate that returns true for files that should be extracted
 * @param isCancelled Checked between entries to allow cancellation
 * @param tag Log tag for this extraction
 * @param onProgress Called with (fileIndex, totalRequired, fileName, bytesExtracted, fileSize)
 * @return [Result] containing the model directory or an error
 */
object TarBz2Extractor {

    private const val BUFFER_SIZE = 8192

    fun extract(
        tarFile: File,
        modelDir: File,
        isRequiredFile: (String) -> Boolean,
        isCancelled: () -> Boolean = { false },
        tag: String = "TarBz2Extractor",
        totalFiles: Int = -1,
        onProgress: (fileIndex: Int, totalFiles: Int, fileName: String, bytesExtracted: Long, fileSize: Long) -> Unit = { _, _, _, _, _ -> }
    ): Result<File> {
        return try {
            var fileIndex = 0

            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }

            FileInputStream(tarFile).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    BZip2CompressorInputStream(bis).use { bzIn ->
                        TarArchiveInputStream(bzIn).use { tarIn ->
                            var entry = tarIn.nextTarEntry

                            while (entry != null) {
                                if (isCancelled()) {
                                    return Result.failure(Exception("Extraction cancelled"))
                                }

                                if (!entry.isDirectory) {
                                    val fileName = File(entry.name).name

                                    if (isRequiredFile(fileName)) {
                                        fileIndex++
                                        val currentFileSize = entry.realSize
                                        var bytesExtracted = 0L

                                        val outputFile = File(modelDir, fileName)

                                        FileOutputStream(outputFile).use { output ->
                                            val buffer = ByteArray(BUFFER_SIZE)
                                            var bytesRead: Int
                                            var lastReportedPercent = 0

                                            while (tarIn.read(buffer).also { bytesRead = it } != -1) {
                                                output.write(buffer, 0, bytesRead)
                                                bytesExtracted += bytesRead

                                                val currentPercent = if (currentFileSize > 0) {
                                                    ((bytesExtracted * 100) / currentFileSize).toInt()
                                                } else 0

                                                if (currentPercent - lastReportedPercent >= 5 || bytesRead == -1) {
                                                    onProgress(fileIndex, totalFiles, fileName, bytesExtracted, currentFileSize)
                                                    lastReportedPercent = currentPercent
                                                }
                                            }
                                        }

                                        Log.d(tag, "Extracted: $fileName ($fileIndex, ${bytesExtracted / 1024}KB)")
                                        onProgress(fileIndex, totalFiles, fileName, currentFileSize, currentFileSize)
                                    } else {
                                        Log.d(tag, "Skipping: ${entry.name}")
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
            Log.e(tag, "Extraction failed", e)
            Result.failure(e)
        }
    }
}
