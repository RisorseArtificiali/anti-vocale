package com.antivocale.app.data.download

import android.content.Context
import android.util.Log
import com.antivocale.app.data.ExternalModelEntryJson
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.BIASING_VOCAB_FILE
import com.antivocale.app.data.ExternalModelImporter
import com.antivocale.app.data.ExternalModelRecordsProvider
import com.antivocale.app.data.HuggingFaceRepoListing
import com.antivocale.app.data.ModelFamily
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.data.catalog.CatalogFile
import com.antivocale.app.data.catalog.CatalogSource
import com.antivocale.app.data.catalog.CatalogVariant
import com.antivocale.app.transcription.PersonalDictionary
import com.antivocale.app.transcription.SherpaModelManager
import com.antivocale.app.transcription.TranscriptionBackendManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TASK-994.1: the existing-install migration for variant supplements (the
 * transducer biasing vocab, bpe.vocab). The biasing wiring (TASK-994
 * phases 2-3) arms when the vocab is in the model dir; no built-in install
 * has it, and pinning it as a REQUIRED file fails the completeness layer
 * for every existing install (the release blocker, held twice). This pass
 * instead fetches each INSTALLED variant's missing supplements directly
 * into the model dir (~100-320KB, pinned sha), so the next model
 * construction arms biasing with no full-model re-download and no heal.
 *
 * TASK-1007: the same lane now serves EXTERNAL records whose community
 * catalog entry declares supplements: the record's sourceUrl IS the entry
 * JSON (the import path records it), so the pass re-reads the entry, and
 * a missing supplement lands into the record's dir the same way. This is
 * what arms the Farsi streaming model's dictionary (its vocab lives on
 * our mirror; mentisialog ships none).
 *
 * GATED on the personal dictionary being non-empty: the supplement serves
 * biasing only, so a user without terms gets no network traffic from this
 * pass at all. Silent by design (an internal engine file, not a user
 * download); a failure defers to the next app start, and a dir without
 * its supplement stays a perfectly valid install forever.
 */
@Singleton
class BiasingVocabSupplement @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val backendManager: TranscriptionBackendManager,
    private val recordsProvider: ExternalModelRecordsProvider,
) {

    /**
     * ONE pass at a time: the startup lane, the dictionary collector and
     * the post-download hook can overlap, and two concurrent passes would
     * interleave writes on the same resume temp (collectLatest cannot
     * interrupt the blocking download body). withLock is cancellation-safe.
     */
    private val passMutex = Mutex()

    /** Test seam: JVM tests replace the network fetch (production hits HF). */
    internal var fetcher: suspend (url: String, temp: File, target: File) -> Result<File> =
        { url, temp, target ->
            downloadWithRetry(
                DownloadConfig(
                    url = url,
                    tempFile = temp,
                    targetFile = target,
                    estimatedSizeBytes = ESTIMATED_SUPPLEMENT_BYTES,
                ),
                delaysMs = longArrayOf(RETRY_DELAY_MS, RETRY_DELAY_MS),
                tag = TAG,
            )
        }

    /**
     * The dictionary-CHANGE hook (the collector's entry): terms apply at
     * model construction, so a resident armed recognizer is stale after ANY
     * edit or clear; without this it would keep beam-biasing toward the OLD
     * (possibly deleted) names until a keep-alive unload. Unloading makes
     * the next transcription rebuild with the current dictionary. Cheap and
     * idempotent; no-op when the active backend is not a supplement-bearing
     * built-in entry.
     */
    fun onDictionaryChanged() {
        val active = backendManager.activeBackendId.value ?: return
        val isSupplementEntry = if (active.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX)) {
            // TASK-1007: an external TRANSDUCER whose dir carries the vocab
            // is armed (or armable at construction); an edit must re-arm it.
            recordsProvider.records.value.any { r ->
                r.backendId == active && r.family == ModelFamily.TRANSDUCER &&
                    File(r.dir, BIASING_VOCAB_FILE).isFile
            }
        } else {
            BundledCatalog.byId(active)?.variants?.any { it.supplements.isNotEmpty() } == true
        }
        if (isSupplementEntry) {
            rearmOrDefer("dictionary changed: active backend $active")
        }
    }

    /**
     * TASK-1010: unload now only when no service instance is alive (no
     * request can be mid-preprocessing); with a live service the re-arm
     * defers to its batch drain, so a dictionary edit during a run costs
     * nothing to the run (the next request arms the new vocab).
     */
    private fun rearmOrDefer(reason: String) {
        if (com.antivocale.app.service.InferenceService.alive ||
            com.antivocale.app.benchmark.BenchmarkManager.running) {
            com.antivocale.app.service.InferenceService.requestRearm()
            Log.i(TAG, "$reason; re-arm deferred to drain (service or benchmark busy)")
        } else {
            backendManager.unloadActiveBackend()
            Log.i(TAG, "$reason unloaded; next request re-arms")
        }
    }

    /**
     * One pass over the INSTALLED built-in variants; idempotent, cheap to
     * re-run. IO contract: callers launch on [Dispatchers.IO] (the
     * application-scope startup lane and the dictionary-change collector).
     */
    suspend fun run(context: Context) = passMutex.withLock {
        if (PersonalDictionary.promptTerms(preferencesManager.keywordBiasing.first()) == null) {
            Log.i(TAG, "dictionary empty: supplement pass skipped (no network)")
            return@withLock
        }
        for (entry in BundledCatalog.entries()) {
            for (variant in entry.variants) {
                if (variant.supplements.isEmpty()) continue
                val manager = SherpaModelManager.of(entry.id)
                val dir = File(manager.getModelStorageDir(context), variant.dirName)
                // Installed check = the same completeness the load gate uses
                // (over variant.files only): a dir without its supplements
                // is a valid install by contract, exactly the property that
                // keeps this migration safe for every pre-supplement install.
                if (!manager.isValidModelDir(dir)) continue
                for (supplement in variant.supplements) {
                    val target = File(dir, supplement.name)
                    // The repo override serves supplements hosted on a
                    // different repo than the variant's source (the streaming
                    // Nemotron's vocab on our mirror while the model stays
                    // upstream).
                    val source = if (supplement.repo != null) {
                        CatalogSource(kind = "huggingface", repo = supplement.repo)
                    } else {
                        variant.source
                    }
                    // TASK-994 review: a mid-session landing must reach
                    // the RESIDENT recognizer. Transducer/streaming
                    // biasing arms at construction, so an initialized
                    // backend would stay greedy (the copy names these
                    // engines as honored); unloading makes the next
                    // transcription re-construct with the vocab in
                    // place. Built-in backend ids are the catalog entry
                    // ids, so the comparison is direct.
                    landIfMissing(
                        context, target, source.resolveUrl(supplement.name),
                        supplement.sha256, "builtin-${entry.id}-${variant.dirName}", entry.id)
                }
            }
        }
        runExternalLane(context)
    }

    /**
     * Presence-then-pin check, fetch, and the resident-recognizer re-arm:
     * the discipline both lanes share. Presence is not validity: a rotted
     * or sideloaded vocab would ride straight into the native recognizer
     * (the load gate content-checks only .onnx/.txt), so a pin mismatch
     * deletes and refetches JUST the supplement, never the whole model (a
     * heal here would delete 326-862MB for a 316KB file). A landing that
     * matches the active backend unloads it so the next request re-arms.
     */
    private suspend fun landIfMissing(
        context: Context,
        target: File,
        url: String,
        pin: String?,
        tempKey: String,
        activeBackendId: String,
    ) {
        if (target.isFile) {
            if (pin == null || HashVerifier.verify(target, pin)) return
            Log.w(TAG, "supplement ${target.name}: pin mismatch on disk, refetching")
            target.delete()
        }
        if (land(context, url, target.name, pin, tempKey, target) &&
            backendManager.activeBackendId.value == activeBackendId) {
            rearmOrDefer("active backend $activeBackendId")
        }
    }

    /**
     * TASK-1007: community-catalog records whose entry JSON declares
     * supplements. The record's [ExternalModelRecord.sourceUrl] IS the entry
     * URL (the catalog-entry import path records it), so the entry is
     * re-read live and its supplements land into the record's dir with the
     * same presence-then-pin discipline as the built-in lane. A record whose
     * entry has no supplements costs one small JSON fetch per pass; a
     * non-catalog record (folder import, repo URL) has a sourceUrl the entry
     * parse rejects or that is null, and the lane skips it on the parse
     * failure alone (silently, like every other deferral here).
     */
    private suspend fun runExternalLane(context: Context) {
        for (record in recordsProvider.records.value) {
            if (record.family != ModelFamily.TRANSDUCER) continue
            // Only catalog-entry sourceUrls carry supplements; repo imports
            // persist the repo web URL, and fetching it would download the
            // HTML landing page to fail the parse (the classification is the
            // importer's own one choke point, reused verbatim).
            val sourceUrl = record.sourceUrl ?: continue
            if (!ExternalModelImporter.isCatalogEntryUrl(sourceUrl)) continue
            // Quarantined and half-wiped dirs cannot load; a supplement
            // landing there is a wasted fetch into a dead dir.
            val dir = File(record.dir)
            if (!dir.isDirectory || record.quarantined) continue
            val entry = fetchEntry(sourceUrl)
            if (entry == null) {
                Log.i(TAG, "entry $sourceUrl unreadable: supplements deferred to next pass")
                continue
            }
            for (supplement in entry.supplements) {
                landIfMissing(
                    context, File(dir, supplement.name), supplement.url,
                    supplement.sha256, "external-${record.id}", "external:${record.id}")
            }
        }
    }

    /** Fetches and parses one entry JSON; null on any failure (the lane logs the cause and skips). */
    private suspend fun fetchEntry(url: String): ExternalModelEntryJson.Entry? =
        runCatching { ExternalModelEntryJson.parse(entryTextFetcher(url)) }
            .onFailure { Log.i(TAG, "entry fetch/parse failed: ${it.message}") }
            .getOrNull()

    /** Test seam: JVM tests replace the text fetch (production is a plain GET). */
    internal var entryTextFetcher: suspend (String) -> String =
        { HuggingFaceRepoListing().fetchText(it) }

    /**
     * Downloads one supplement to [target] through the [fetcher] seam and
     * pin-verifies it; true when it landed. Shared by the built-in lane
     * (URL derived from the variant source or its repo override) and the
     * TASK-1007 external lane (URL authored in the entry JSON).
     */
    private suspend fun land(
        context: Context,
        url: String,
        name: String,
        pin: String?,
        tempKey: String,
        target: File,
    ): Boolean {
        // The resume temp lives in cacheDir, NEVER in the model dir: a
        // leftover partial must not ride exported bundles (the codec
        // excludes only .size sidecars) nor meet any model-dir validator.
        val temp = File(context.cacheDir, "supplement-$tempKey-$name$PART_SUFFIX")
        target.parentFile?.mkdirs()
        val result = fetcher(url, temp, target)
        var landed = false
        result.fold(
            onSuccess = { file ->
                if (pin != null && !HashVerifier.verify(file, pin)) {
                    Log.w(TAG, "supplement $name: pin mismatch, deleted (upstream moved?)")
                    file.delete()
                } else {
                    Log.i(TAG, "supplement $name landed (${file.length()} bytes)")
                    landed = true
                }
                // The helper's resume sidecar outlives the rename; a model
                // dir must not accumulate it (the importers' cleanup rule).
                ResumeDownloadHelper.sizeSidecar(temp).delete()
            },
            onFailure = { e ->
                Log.i(TAG, "supplement $name deferred to next start: ${e.message}")
                // The temp partial STAYS: the next attempt resumes from it.
            },
        )
        return landed
    }

    private companion object {
        const val TAG = "BiasingVocabSupplement"
        const val PART_SUFFIX = ".supp.part"
        const val RETRY_DELAY_MS = 5_000L

        /** Progress estimate only (the largest supplement today is ~316KB). */
        const val ESTIMATED_SUPPLEMENT_BYTES = 320L * 1024
    }
}
