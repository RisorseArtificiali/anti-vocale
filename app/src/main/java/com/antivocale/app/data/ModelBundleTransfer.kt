package com.antivocale.app.data

import android.content.Context
import android.util.Log
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.download.CatalogModelValidator
import com.antivocale.app.transcription.SherpaModelManager
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * TASK-931: the shared core of the offline-bundle transfer, so the
 * ViewModel (SAF orchestration and UI state) and the debug SPI (file
 * paths, no UI) run the SAME logic and cannot drift. ONE owner of the
 * export metadata and of the restore/external routing, next to the codec
 * that owns the manifest vocabulary.
 *
 * Export: [export] builds the manifest metadata exactly as the TASK-761
 * flow did (family null for the builtin-only modelTypes since TASK-924)
 * and defers to [ModelBundleCodec.export].
 *
 * Import: [importBundle] verifies the bundle into a cache staging dir,
 * then ONE identity pass decides the arm: a bundle whose pinned bytes are
 * this device's catalog variant's bytes restores onto the built-in dir
 * (no external record; the card flips through the disk-truth presence),
 * every other bundle rides the external importer exactly as before
 * TASK-924. Callers own the stream and the presentation.
 */
object ModelBundleTransfer {

    private const val TAG = "ModelBundleTransfer"

    /** The two success shapes of a bundle import: a builtin restore that
     *  wrote no record, or an external record. */
    sealed interface Outcome {
        data class Restored(val displayName: String) : Outcome
        data class Imported(val record: ExternalModelRecord) : Outcome
    }

    /** The restore refused because a download of the same variant is in
     *  flight: thrown by [claimRestoreVariant]'s implementation (the
     *  ViewModel state owns the check) and localized at the UI failure
     *  tail, since the data layer carries no resources. */
    class DownloadActiveException(val entryId: String, val variantName: String) :
        IllegalStateException("a download of $entryId/$variantName is in progress")

    /** Writes [modelDir]'s bundle to [out]; the caller owns closing. */
    fun export(
        modelDir: File,
        entry: CatalogEntry,
        variantName: String,
        displayName: String,
        variantDirName: String?,
        appVersion: String,
        out: OutputStream,
    ) {
        val metadata = ModelBundleCodec.BundleMetadata(
            displayName = displayName,
            // The IMPORT family (the sherpa import taxonomy), NOT the
            // entry's architecture string, when the modelType has an
            // external slot. TASK-924: null (omitted from the manifest)
            // for the builtin-only types (qwen3, the streaming Nemotron,
            // the LLM): the receiving restore arm needs no family, and
            // the external fallback re-derives one from the modelType
            // when it can.
            family = ModelBundleCodec.importFamilyForModelType(entry.modelType)?.name,
            modelType = entry.modelType,
            languages = BundledCatalog.byId(entry.id)?.let { catalogEntry ->
                // The variantName is the catalog variant NAME ("v3"),
                // never the dirName ("gigaam-v3"): comparing dirNames here
                // silently exported empty language lists (TASK-801 review).
                // Entries may also declare their languages at entry level
                // only (gigaam: ['ru'], no per-variant list).
                catalogEntry.variants
                    .firstOrNull { it.name == variantName }
                    ?.languages
                    ?.takeIf { it.isNotEmpty() }
                    ?: catalogEntry.languages
            } ?: emptyList(),
            streaming = entry.isStreaming,
            options = emptyMap(),
            appVersion = appVersion,
            // TASK-797: the built-in entry the bundle came from; the
            // receiver links or restores against its card.
            catalogEntryId = entry.id,
            catalogVariantDir = variantDirName,
        )
        ModelBundleCodec.export(modelDir, out, metadata)
    }

    /**
     * The bundle-import core the ViewModel and the debug SPI share.
     * [input] is the bundle zip; it is fully consumed.
     * [claimRestoreVariant] guards the restore arm against a concurrent
     * download of the same variant (the import mutex cannot see the
     * download path): it must throw [DownloadActiveException] when a
     * download of the variant is in flight, otherwise atomically mark
     * the variant restore-in-progress in the same state the download
     * start checks, and return the handle that clears the mark (the
     * contract lives on the ViewModel's claim; see TASK-933 there). The
     * debug SPI passes a no-op because a scripted trial controls both
     * sides. [noFamilyError] is the localized message for the bundle
     * whose type can neither restore nor import externally.
     */
    suspend fun importBundle(
        context: Context,
        input: InputStream,
        importer: ExternalModelImportOperations,
        claimRestoreVariant: (entryId: String, variantName: String) -> AutoCloseable,
        noFamilyError: String,
    ): Outcome {
        val staging = File(context.cacheDir, "bundle-import-${System.currentTimeMillis()}")
        try {
            val manifest = ModelBundleCodec.import(input, staging)
            val params = ModelBundleCodec.recordParams(manifest)
            // TASK-924: ONE identity pass decides the arm, and its result
            // is both the restore key and the entity link the external arm
            // needs (same function, same params; a bundle whose bytes are
            // this device's catalog variant's bytes restores onto the
            // built-in card, everything else rides the external arm
            // exactly as before TASK-924, where a family-less manifest
            // re-derives the family from the modelType and a type with no
            // slot fails there with the honest string).
            val linkedRef = linkedCatalogRefFor(params)
            if (linkedRef != null) {
                return Outcome.Restored(
                    restoreBuiltin(context, params, linkedRef, staging, claimRestoreVariant))
            }
            if (params.catalogEntryId != null) {
                Log.i(TAG, "Bundle from entry '${params.catalogEntryId}' does not match this " +
                    "device's catalog (type=${params.modelType}, " +
                    "variant=${params.catalogVariantDir}); importing as external")
            }
            return Outcome.Imported(importer.importFromDirectory(
                src = staging,
                modelType = params.modelType,
                family = params.family
                    ?: ModelBundleCodec.importFamilyForModelType(params.modelType.orEmpty())
                    ?: error(noFamilyError),
                options = params.options,
                languages = params.languages,
                streaming = params.streaming,
                displayName = params.displayName,
                // TASK-801 heritage: pre-924 a pin-matching bundle linked
                // its record here; post-924 a matching bundle RESTORES
                // instead, and this branch only runs when the identity
                // pass FAILED, so no bundle import links a record anymore.
                // The link machinery stays for records created before 924.
                linkedCatalog = null,
            ))
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * TASK-801: the catalog identity a bundle import links to, or null for
     * a plain import. Linked only when the manifest's entry still exists
     * in THIS device's bundled catalog, the modelTypes match (blank folds
     * into null: the streaming Nemotron, whistle and the LLM carry "" and
     * must still restore; a relabeled or hand-edited manifest must not
     * hijack an unrelated card), the variant resolves (the manifest's
     * dirName when it names one of the entry's variants, else the entry's
     * only variant), AND the bundle's pinned bytes are the catalog
     * variant's pinned bytes: the card must not vouch for weights the
     * catalog never named.
     */
    private fun linkedCatalogRefFor(params: ModelBundleCodec.RecordParams): LinkedCatalogRef? {
        val entryId = params.catalogEntryId ?: return null
        val entry = BundledCatalog.byId(entryId) ?: return null
        if (entry.modelType.ifBlank { null } != params.modelType) return null
        val variantDir = params.catalogVariantDir
            ?.takeIf { dir -> entry.variants.any { it.dirName == dir } }
            ?: entry.variants.singleOrNull()?.dirName
            ?: return null
        val variant = entry.variants.first { it.dirName == variantDir }
        // Key-set equality, not just coverage: an extra canonical-named file
        // (encoder.int8.onnx) riding the bundle could win the importer's
        // role matching over the verified catalog-named file, so the card
        // would vouch for bytes the record never loads.
        val pinsMatch = params.filePins.keys == variant.files.map { it.name }.toSet() &&
            variant.files.all { catalogFile ->
                // Unpinned catalog files (none today, TASK-661 pins all)
                // ride unconstrained.
                catalogFile.sha256 == null || params.filePins[catalogFile.name] == catalogFile.sha256
            }
        if (!pinsMatch) return null
        return LinkedCatalogRef(entryId, variantDir)
    }

    /**
     * TASK-924 (GH #124 reopen): the BUILTIN restore arm. The identity
     * pass has already resolved [ref], so this only lands the
     * codec-verified [staging] files in the built-in dir: no
     * ExternalModelStore record is written, and the card flips to present
     * through the disk-truth computation. Returns the restored variant's
     * display name; a landing failure throws into the caller's channel.
     */
    private suspend fun restoreBuiltin(
        context: Context,
        params: ModelBundleCodec.RecordParams,
        ref: LinkedCatalogRef,
        staging: File,
        claimRestoreVariant: (entryId: String, variantName: String) -> AutoCloseable,
    ): String {
        val entry = BundledCatalog.byId(ref.catalogEntryId)
            ?: error("catalog entry vanished between identity and restore: ${ref.catalogEntryId}")
        val variant = entry.variants.firstOrNull { it.dirName == ref.variantDir }
            ?: error("catalog variant vanished between identity and restore: ${ref.catalogEntryId}/${ref.variantDir}")
        // TASK-933 invariant: a download and a restore never share the
        // variant dir. The claim is BOTH guards in one atomic step: it
        // refuses a download already in flight (download-then-restore)
        // and marks the variant so the download start refuses until the
        // landing ends (restore-then-download). A check-then-act split
        // of the two halves would re-open the window: a download
        // starting between the check and the move writes over the
        // freshly restored tree into a corrupt mixed dir. The release
        // runs whenever the landing ends, failure included.
        val claim = claimRestoreVariant(ref.catalogEntryId, variant.name)
        try {
            val target = File(
                SherpaModelManager.of(ref.catalogEntryId).getModelStorageDir(context), ref.variantDir)
            // The restore replaces whatever partial/corrupt dir is there, so
            // the end state is the same regardless of the target's prior
            // content (the orphan sweep never deletes a current-named dir,
            // which is exactly why a stale one must go here).
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!ExternalModelImporter.moveDirInto(staging, target)) {
                error("cannot move the restored files into $target")
            }
            try {
                // The validator pass is expected (the pins matched and the
                // sidecar absence folds to complete in CatalogModelValidator);
                // a failure is a plumbing bug and fails loudly rather than
                // shipping a junk dir (the failure deletes the landed dir so
                // the sweep has nothing to orphan).
                check(CatalogModelValidator.isValidModelDir(target, variant.files.map { it.name })) {
                    "restored dir failed the catalog validator: $target"
                }
            } catch (e: IllegalStateException) {
                target.deleteRecursively()
                throw e
            }
            Log.i(TAG, "Bundle restored as built-in: ${ref.catalogEntryId}/${ref.variantDir}")
            return params.displayName ?: ref.variantDir
        } finally {
            claim.close()
        }
    }
}
