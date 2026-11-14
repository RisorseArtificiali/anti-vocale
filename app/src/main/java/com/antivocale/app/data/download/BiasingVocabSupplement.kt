package com.antivocale.app.data.download

import android.content.Context
import android.util.Log
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
        val active = backendManager.activeBackendId.value
        val isSupplementEntry = BundledCatalog.byId(active ?: return)
            ?.variants?.any { it.supplements.isNotEmpty() } == true
        if (isSupplementEntry) {
            backendManager.unloadActiveBackend()
            Log.i(TAG, "dictionary changed: active backend $active unloaded; next request re-arms")
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
                    // Presence is not validity: a rotted or sideloaded vocab
                    // would ride straight into the native recognizer (the
                    // load gate content-checks only .onnx/.txt). Verify and
                    // surgically refetch JUST the supplement, never the
                    // whole model (a heal here would delete 326-862MB for a
                    // 316KB file).
                    if (target.isFile) {
                        val pin = supplement.sha256
                        if (pin == null || HashVerifier.verify(target, pin)) continue
                        Log.w(TAG, "supplement ${supplement.name}: pin mismatch on disk, refetching")
                        target.delete()
                    }
                    if (fetch(context, entry.id, variant, supplement, target) &&
                        backendManager.activeBackendId.value == entry.id) {
                        // TASK-994 review: a mid-session landing must reach
                        // the RESIDENT recognizer. Transducer/streaming
                        // biasing arms at construction, so an initialized
                        // backend would stay greedy (the copy names these
                        // engines as honored); unloading makes the next
                        // transcription re-construct with the vocab in
                        // place. Built-in backend ids are the catalog entry
                        // ids, so the comparison is direct.
                        backendManager.unloadActiveBackend()
                        Log.i(TAG, "active backend ${entry.id} unloaded; next request re-arms biasing")
                    }
                }
            }
        }
    }

    /** Fetches one supplement; true when it landed pin-verified. */
    private suspend fun fetch(
        context: Context,
        entryId: String,
        variant: CatalogVariant,
        supplement: CatalogFile,
        target: File,
    ): Boolean {
        // The repo override serves supplements hosted on a different repo
        // than the variant's source (the streaming Nemotron's vocab on our
        // mirror while the model stays upstream). Supplement pins ride OUR
        // mirrors only, so the PinOverrides drift channel (TASK-986, for
        // third-party repos that re-upload) does not apply: a drifted
        // mirror pin is re-pinned in a release, like any catalog pin.
        val source = if (supplement.repo != null) {
            CatalogSource(kind = "huggingface", repo = supplement.repo)
        } else {
            variant.source
        }
        val url = source.resolveUrl(supplement.name)
        // The resume temp lives in cacheDir, NEVER in the model dir: a
        // leftover partial must not ride exported bundles (the codec
        // excludes only .size sidecars) nor meet any model-dir validator.
        val temp = File(context.cacheDir,
            "supplement-$entryId-${variant.dirName}-${supplement.name}$PART_SUFFIX")
        target.parentFile?.mkdirs()
        val result = fetcher(url, temp, target)
        var landed = false
        result.fold(
            onSuccess = { file ->
                val pin = supplement.sha256
                if (pin != null && !HashVerifier.verify(file, pin)) {
                    Log.w(TAG, "supplement ${supplement.name}: pin mismatch, deleted (upstream moved?)")
                    file.delete()
                } else {
                    Log.i(TAG, "supplement ${supplement.name} landed (${file.length()} bytes)")
                    landed = true
                }
                // The helper's resume sidecar outlives the rename; a model
                // dir must not accumulate it (the importers' cleanup rule).
                ResumeDownloadHelper.sizeSidecar(temp).delete()
            },
            onFailure = { e ->
                Log.i(TAG, "supplement ${supplement.name} deferred to next start: ${e.message}")
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
