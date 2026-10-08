package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.catalog.CatalogEntry
import org.opentranscribe.api.TranscriberCapabilities
import java.io.File

/**
 * TASK-785: derives the Open Transcribe [TranscriberCapabilities] from the
 * active-model state. Pure Kotlin over resolved inputs (the
 * AudioDurationPolicy pattern): the service resolves the catalog entry and
 * the external record, so this derivation is JVM-testable with no catalog
 * assets in sight.
 *
 * supportedLanguages is the model's DECLARED language list (entry-level,
 * variant-overridden; the external record's own list), null when nothing
 * is declared. It deliberately differs from
 * [TranscriptionLanguagePolicy.offeredLanguages], which gates on the
 * catalog flags because THAT derivation answers "what can the user pin";
 * a client asks "what can this transcriber handle", and Parakeet handles
 * twelve languages it never conditions on. modelReady follows the
 * loadability the pickers use: a loadable external record, or a saved
 * built-in path that still exists on disk.
 */
object OpenTranscribeCapabilities {

    /** The CONTRACT.md v3 shape this provider implements (files only). */
    const val CONTRACT_VERSION = 3

    const val ENGINE_ID = "anti-vocale"

    fun derive(
        versionName: String,
        modelPath: String?,
        externalRecord: ExternalModelRecord?,
        catalogEntry: CatalogEntry?,
        pathExists: (String) -> Boolean = { File(it).exists() },
    ): TranscriberCapabilities {
        val ready = when {
            // The caller resolves external records through the store's
            // loadability predicate (dir present, not quarantined), so a
            // non-null record IS the ready state.
            externalRecord != null -> true
            else -> modelPath != null && pathExists(modelPath)
        }
        val languages: Array<String>? = when {
            externalRecord != null -> externalRecord.languages.takeIf { it.isNotEmpty() }?.toTypedArray()
            catalogEntry != null -> {
                val variant = catalogEntry.variantForSavedPath(modelPath)
                catalogEntry.languagesFor(variant).takeIf { it.isNotEmpty() }?.toTypedArray()
            }
            else -> null
        }
        return TranscriberCapabilities().apply {
            contractVersion = CONTRACT_VERSION
            engineId = ENGINE_ID
            engineVersion = versionName
            supportedLanguages = languages
            // Honest like streaming: a single-language variant (Distil-IT)
            // never runs detection, so a client trusting the flag must not
            // send it hint-less foreign audio expecting a sane result.
            autoDetectLanguage = languages == null || languages.size > 1
            cancellable = true
            modelReady = ready
            streaming = false
        }
    }
}
