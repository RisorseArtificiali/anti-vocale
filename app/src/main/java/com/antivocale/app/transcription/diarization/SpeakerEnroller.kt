package com.antivocale.app.transcription.diarization

import android.content.Context
import android.net.Uri
import android.util.Log
import com.antivocale.app.audio.AudioPreprocessor
import java.io.File

/**
 * TASK-670 (GH #83): one validated enrollment sample waiting for its name
 * in the enrollment dialog. Moved out of SettingsViewModel together with
 * the pipeline that produces it (TASK-670 simplify F2).
 */
// Review F6: NOT a data class: the FloatArray fields would generate
// identity-based equals/hashCode (two enrollments of the same clip never
// compare equal); no current code relies on structural equality.
class PendingSpeakerEnrollment(
    val sampleSeconds: Float,
    val embedding: FloatArray,
    val samples: FloatArray,
    val sampleRate: Int,
)

/**
 * TASK-670 (GH #83): the enrollment failure the Settings card maps to its
 * localized message. Moved with the pipeline (TASK-670 simplify F2).
 */
enum class SpeakerEnrollError { TOO_SHORT, TOO_LONG, DECODE, EXTRACT, SAVE, MODEL_DOWNLOAD }

/**
 * TASK-670 (GH #83): the enrollment pipeline behind the file pick, as a
 * minimal injectable seam (TASK-670 simplify F2, the
 * ExternalModelImportOperations precedent): the pipeline lived inline in
 * SettingsViewModel, so nothing could pin its branch map without SAF,
 * MediaMetadataRetriever, or the native extractor in the loop. The
 * ViewModel keeps the state flows and forwards; the environment-bound
 * steps are constructor seams whose real implementations [create] binds:
 *
 * - [readClip] copies the picked clip into the cache (the decode path is
 *   path-based);
 * - [metadataSeconds] reads the container's declared duration;
 * - [ensureModels] is [DiarizationModels.ensureDownloaded];
 * - [embed] runs the titanet session over the merged samples.
 *
 * Everything between the seams is the pipeline body that moved VERBATIM
 * from the ViewModel: the R4 metadata pre-check before the full decode,
 * the decode through the standard preprocessor (16 kHz mono, no VAD: the
 * sample IS the voice), the length bounds, the embed with the diarization
 * titanet model so the stored voiceprint and the transcription-time
 * clusters share one embedding space, and (through [confirm]) the store
 * save. A validated sample parks in the returned [EnrollOutcome.Pending]
 * until the user names it; every failure maps to a [SpeakerEnrollError]
 * and stores nothing.
 */
class SpeakerEnroller(
    private val preprocessor: AudioPreprocessor,
    private val threadCount: suspend () -> Int,
    private val cacheDir: File,
    private val readClip: (Uri) -> File,
    private val metadataSeconds: (File) -> Double?,
    private val ensureModels: suspend () -> Result<Unit>,
    private val embed: (samples: FloatArray, sampleRate: Int, numThreads: Int) -> FloatArray,
    private val store: SpeakerIdentityStore,
) {

    /** The enroll outcome: a validated sample parked for naming, or the mapped failure. */
    sealed interface EnrollOutcome {
        data class Pending(val sample: PendingSpeakerEnrollment) : EnrollOutcome
        data class Failed(val error: SpeakerEnrollError) : EnrollOutcome
    }

    /** The confirm outcome: the persisted identity, or the mapped failure (SAVE). */
    sealed interface ConfirmOutcome {
        data class Saved(val identity: SpeakerIdentity) : ConfirmOutcome
        data class Failed(val error: SpeakerEnrollError) : ConfirmOutcome
    }

    /**
     * TASK-670 (GH #83): the enrollment pipeline behind the file pick.
     * Copies the picked clip to the cache, decodes it through the standard
     * preprocessor (16 kHz mono, no VAD: the sample IS the voice), bounds
     * its length, then embeds it with the diarization titanet model so the
     * stored voiceprint and the transcription-time clusters share one
     * embedding space. A validated sample parks in [EnrollOutcome.Pending];
     * every failure lands in [EnrollOutcome.Failed] and stores nothing.
     */
    suspend fun enroll(uri: Uri): EnrollOutcome {
        return try {
            // SAF copy to a real path (the decode path is path-based).
            val cached = readClip(uri)
            try {
                // Review R4: reject out-of-bounds clips from the
                // CONTAINER metadata BEFORE the full decode (a 90-minute
                // pick decoded ~345MB of PCM only to fail the bound).
                val clipSeconds = metadataSeconds(cached)
                if (clipSeconds != null && clipSeconds > SpeakerIdentityStore.MAX_ENROLL_SECONDS + 1.0) {
                    throw IllegalArgumentException("clip too long")
                }
                val decoded = preprocessor.prepareAudioForMediaPipe(
                    inputPath = cached.absolutePath,
                    cacheDir = cacheDir,
                    maxChunkDurationSeconds = null,
                )
                val (samples, sampleRate) = preprocessor.mergeAndResample(
                    decoded.chunks.toMutableList(), decoded.sampleRate)
                val seconds = samples.size.toFloat() / sampleRate
                when {
                    seconds < SpeakerIdentityStore.MIN_ENROLL_SECONDS ->
                        EnrollOutcome.Failed(SpeakerEnrollError.TOO_SHORT)
                    seconds > SpeakerIdentityStore.MAX_ENROLL_SECONDS ->
                        EnrollOutcome.Failed(SpeakerEnrollError.TOO_LONG)
                    else -> {
                        if (ensureModels().isFailure) {
                            EnrollOutcome.Failed(SpeakerEnrollError.MODEL_DOWNLOAD)
                        } else {
                            // Review F4: the thread-count read sits OUTSIDE the
                            // embed try (a preference-read failure maps DECODE,
                            // as the pre-extraction code did), and the embed
                            // catch is Throwable (review F1: the native titanet
                            // compute throws OutOfMemoryError, an Error, and the
                            // old Result.fold caught every Throwable; narrowing
                            // to Exception here killed the process where the old
                            // code showed the extract error).
                            val threads = threadCount()
                            try {
                                val embedding = embed(samples, sampleRate, threads)
                                EnrollOutcome.Pending(
                                    PendingSpeakerEnrollment(
                                        sampleSeconds = seconds,
                                        embedding = embedding,
                                        samples = samples,
                                        sampleRate = sampleRate,
                                    ))
                            } catch (failure: Throwable) {
                                if (failure is kotlinx.coroutines.CancellationException) {
                                    // Review F3: a public suspend API must let
                                    // cancellation propagate, not surface it as
                                    // a decode error.
                                    throw failure
                                }
                                Log.e(TAG, "Speaker enrollment embedding failed", failure)
                                EnrollOutcome.Failed(SpeakerEnrollError.EXTRACT)
                            }
                        }
                    }
                }
            } finally {
                cached.delete()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Speaker enrollment failed", e)
            EnrollOutcome.Failed(SpeakerEnrollError.DECODE)
        }
    }

    /** TASK-670 (GH #83): names and persists the parked sample (voiceprint + WAV). */
    suspend fun confirm(pending: PendingSpeakerEnrollment, name: String): ConfirmOutcome {
        val saved = runCatching {
            store.save(
                name = name,
                embedding = pending.embedding,
                sampleSeconds = pending.sampleSeconds,
                samples = pending.samples,
                sampleRate = pending.sampleRate,
            )
        }
        return saved.fold(
            onSuccess = { ConfirmOutcome.Saved(it) },
            onFailure = { failure ->
                Log.e(TAG, "Speaker identity save failed", failure)
                // Review R9: extraction SUCCEEDED here; a disk-full save
                // must not send the user to re-pick a clip.
                ConfirmOutcome.Failed(SpeakerEnrollError.SAVE)
            },
        )
    }

    companion object {
        private const val TAG = "SpeakerEnroller"

        /**
         * The production wiring (TASK-670 simplify F2): the environment
         * seams close over the app context; AppModule binds this so the
         * class itself stays JVM-testable.
         */
        fun create(
            context: Context,
            preprocessor: AudioPreprocessor,
            threadCount: suspend () -> Int,
            store: SpeakerIdentityStore,
        ): SpeakerEnroller = SpeakerEnroller(
            preprocessor = preprocessor,
            threadCount = threadCount,
            cacheDir = context.cacheDir,
            readClip = { uri ->
                val suffix = context.contentResolver.getType(uri)
                    ?.substringAfterLast('/')
                    ?.take(8)?.let { ".$it" } ?: ""
                val cached = File(context.cacheDir, "speaker-enroll$suffix")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        cached.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("unreadable enrollment clip")
                } catch (e: Throwable) {
                    // Review F5: this lambda runs BEFORE enroll's try/finally
                    // owns the file; a mid-copy failure (grant revoked, cloud
                    // provider drops the stream) must not leave the partial
                    // file behind.
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    cached.delete()
                    throw e
                }
                cached
            },
            metadataSeconds = { cached ->
                // Review F2: MediaMetadataRetriever implements AutoCloseable
                // only since API 29 (minSdk is 26): use{} would crash with
                // IncompatibleClassChangeError on 8.x/9. release() is the
                // API-10 form.
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(cached.absolutePath)
                    r.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull()?.div(1000.0)
                } finally {
                    r.release()
                }
            },
            ensureModels = { DiarizationModels.ensureDownloaded(context) },
            embed = { samples, sampleRate, numThreads ->
                SpeakerEmbeddings.create(
                    embeddingModel = DiarizationModels.embeddingFile(context),
                    numThreads = numThreads,
                ).mapCatching { session ->
                    try {
                        session.compute(samples, sampleRate)
                    } finally {
                        session.release()
                    }
                }.getOrThrow()
            },
            store = store,
        )
    }
}
