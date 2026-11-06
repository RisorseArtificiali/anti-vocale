package com.antivocale.app.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * GH #185: derives a text-export file name from the source audio's own
 * name, so a transcript saved from the share chooser sits next to its
 * audio ("Hello.ogg" -> "Hello.ogg.txt"). The audio keeps its extension
 * in the name on purpose: the pair sorts together and the origin stays
 * readable in any file listing.
 */
object ExportFileNamer {

    /**
     * ISO_LOCAL_DATE, not a locale pattern: locale dates can carry "/" or
     * "," and a file provider rejects the resulting name.
     */
    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE

    /** The two schemes from the request; the share dialog offers both. */
    enum class Scheme { AUDIO_NAME, AUDIO_NAME_DATE }

    /**
     * The .txt export name for [scheme], or null when no audio file is
     * known (text entries, rows without a path): the caller keeps its
     * current naming rather than inventing a stem. [today] is injectable
     * so tests pin the date.
     */
    fun txtFileName(
        audioFilePath: String?,
        scheme: Scheme,
        today: LocalDate = LocalDate.now(),
    ): String? {
        val name = audioFilePath
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
        if (name.isEmpty() || name == "." || name == "..") return null
        return when (scheme) {
            Scheme.AUDIO_NAME -> "$name.txt"
            Scheme.AUDIO_NAME_DATE -> {
                // The date lands inside the audio's own extension so both
                // schemes keep the same shape ("Hello_2026-11-06.ogg.txt").
                val dot = name.lastIndexOf('.')
                if (dot > 0) {
                    name.substring(0, dot) + "_" + today.format(DATE) + name.substring(dot) + ".txt"
                } else {
                    "${name}_${today.format(DATE)}.txt"
                }
            }
        }
    }
}
