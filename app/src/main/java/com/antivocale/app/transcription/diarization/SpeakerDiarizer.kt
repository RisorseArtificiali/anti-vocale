package com.antivocale.app.transcription.diarization

import android.content.Context
import com.antivocale.app.data.download.DownloadConfig
import com.antivocale.app.data.download.HashVerifier
import com.antivocale.app.data.download.ResumeDownloadHelper
import com.antivocale.app.data.download.downloadWithRetry
import com.antivocale.app.data.download.TarBz2Extractor
import com.antivocale.app.transcription.TranscriptionException
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * GH #83: the JNI wrapper around the OfflineSpeakerDiarization engine that
 * already ships inside the pinned sherpa-onnx AAR (verified by javap and
 * nm on the artifact; no native work was needed). Loads the pyannote
 * segmentation and titanet embedding models from files, runs the sliding
 * window + clustering pipeline and returns app-side [DiarizedSegment]s
 * with dense speaker ids.
 *
 * Speaker counting is AUTO (maintainer decision over the 2-person-call
 * preset): the model identifies as many speakers as the audio carries.
 * The threshold is the one the 2026-09-20 prototype measured correct on
 * every file (2-speaker and 3-speaker ground truths both landed on the
 * exact count): 0.8, never sherpa's 0.5 default, which over-split every
 * file measured.
 */
class SpeakerDiarizer private constructor(
    private val engine: OfflineSpeakerDiarization,
) {
    companion object {
        /** The auto-clustering threshold measured correct on every
         *  prototype file (0.5 over-split all of them). */
        const val AUTO_THRESHOLD = 0.8f

        /**
         * @param segmentationModel int8 pyannote segmentation ONNX file
         * @param embeddingModel titanet (or compatible) embedding ONNX file
         * @param numThreads inference threads, from the user's setting
         */
        fun create(
            segmentationModel: File,
            embeddingModel: File,
            numThreads: Int,
        ): Result<SpeakerDiarizer> = runCatching {
            val clustering =
                FastClusteringConfig(-1, AUTO_THRESHOLD, computeConfidence = true)
            val config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                        model = segmentationModel.absolutePath,
                    ),
                    numThreads = numThreads,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = embeddingModel.absolutePath,
                    numThreads = numThreads,
                ),
                clustering = clustering,
            )
            val engine = OfflineSpeakerDiarization(assetManager = null, config = config)
            SpeakerDiarizer(engine)
        }.recoverCatching { cause ->
            throw TranscriptionException.ModelLoadError(
                "speaker diarization: ${cause.message}", cause)
        }

        /** The engine's required sample rate, for callers to assert against. */
        fun sampleRate(): Int = 16000
    }

    /**
     * Runs the pipeline over the full waveform. Times of the returned
     * segments share the caller's timeline: the caller passes the chunks of
     * a CONTIGUOUS decode (the orchestrator excludes VAD-stripped runs,
     * whose cues carry gapped original-audio times) concatenated in order.
     *
     * @throws TranscriptionException.NativeError when the native run fails
     */
    fun diarize(
        samples: FloatArray,
        sampleRate: Int,
    ): List<DiarizedSegment> {
        if (sampleRate != sampleRate()) {
            throw TranscriptionException.NativeError(
                "speaker diarization needs ${sampleRate()} Hz, got $sampleRate")
        }
        val raw = engine.process(samples)
        return SpeakerLabeler.denseSegments(raw.map { segment ->
            DiarizedSegment(
                startSec = segment.start,
                endSec = segment.end,
                speaker = segment.speaker,
                confidence = segment.confidence,
            )
        })
    }

    fun release() {
        engine.release()
    }
}

/**
 * GH #83: where the two diarization models live and how they get there.
     * Two pinned files under filesDir/models/diarization/ (~42 MB kept on
 * disk; the first-use download is ~47 MB),
 * comparable to one small ASR model), downloaded on first enable with the
 * app's resumable downloader; the VAD model is bundled as an asset because
 * it is tiny, these are not. Hashes pin the EXTRACTED model files (both
 * verified against the 2026-09-20 prototype's downloads, which matched the
 * publishers' manifests); the segmentation side arrives as the official
 * tar.bz2 because that is the only shipped artifact, and the int8 model
 * plus its MIT LICENSE file are what gets kept.
 */
object DiarizationModels {

    /** The official tarball (fp32 + int8 + LICENSE + scripts), hash-pinned. */
    private const val SEGMENTATION_TARBALL_SHA256 =
        "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488"
    private const val SEGMENTATION_TARBALL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
    private const val SEGMENTATION_TARBALL_BYTES = 6_958_444L
    private const val SEGMENTATION_INT8_SHA256 =
        "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d"

    private const val EMBEDDING_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/nemo_en_titanet_small.onnx"
    private const val EMBEDDING_SHA256 =
        "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e"
    private const val EMBEDDING_BYTES = 40_257_283L

    const val SEGMENTATION_FILE = "speaker-segmentation-pyannote-3-0.int8.onnx"
    const val EMBEDDING_FILE = "speaker-embedding-titanet-small.onnx"

    fun dir(context: Context): File = File(context.filesDir, "models/diarization")

    fun segmentationFile(context: Context): File = File(dir(context), SEGMENTATION_FILE)

    fun embeddingFile(context: Context): File = File(dir(context), EMBEDDING_FILE)

    fun isDownloaded(context: Context): Boolean =
        ResumeDownloadHelper.isFileComplete(segmentationFile(context)) &&
            ResumeDownloadHelper.isFileComplete(embeddingFile(context))

    /**
     * Downloads both models when missing: resumable, hash-verified, keeping
     * an existing complete file (the app's standard no-redownload). The
     * segmentation tarball is extracted for the int8 model and its LICENSE
     * only; the fp32 graph and scripts are discarded with the tarball.
     *
     * @param onProgress 0..1 across both downloads combined
     */
    suspend fun ensureDownloaded(context: Context): Result<Unit> {
        if (isDownloaded(context)) return Result.success(Unit)
        dir(context).mkdirs()
        val totalBytes = SEGMENTATION_TARBALL_BYTES + EMBEDDING_BYTES
        var doneBytes = 0L

        if (!ResumeDownloadHelper.isFileComplete(segmentationFile(context))) {
            val tarball = File(dir(context), "segmentation-src.tar.bz2")
            val download = downloadWithRetry(
                DownloadConfig(
                    url = SEGMENTATION_TARBALL_URL,
                    tempFile = File(tarball.parentFile, "${tarball.name}.part"),
                    targetFile = tarball,
                    estimatedSizeBytes = SEGMENTATION_TARBALL_BYTES,
                ),
                tag = "DiarizationModels",
            )
            download.fold(
                onSuccess = {},
                onFailure = {
                    ResumeDownloadHelper.clearTarDownload(tarball)
                    return Result.failure(it)
                },
            )
            if (!HashVerifier.verify(tarball, SEGMENTATION_TARBALL_SHA256)) {
                tarball.delete()
                return Result.failure(
                    IllegalStateException("speaker segmentation tarball hash mismatch"))
            }
            // Extract into a STAGING subdir, rename the two keepers out,
            // then delete staging: extraction must never target the live
            // models dir, whose recursive deletion would destroy the
            // embedding model too (code-review blocker).
            val staging = File(dir(context), "staging")
            staging.deleteRecursively()
            val extracted = TarBz2Extractor.extract(
                tarFile = tarball,
                modelDir = staging,
                isRequiredFile = { name ->
                    name.endsWith("model.int8.onnx") || name.endsWith("LICENSE")
                },
            )
            extracted.fold(
                onSuccess = { dir ->
                    val model = File(dir, "model.int8.onnx")
                    if (!model.exists() || !model.renameTo(segmentationFile(context))) {
                        staging.deleteRecursively()
                        tarball.delete()
                        return Result.failure(
                            IllegalStateException("segmentation model extraction failed"))
                    }
                    File(dir, "LICENSE")
                        .takeIf { it.renameTo(File(dir(context), "LICENSE.pyannote")) }
                    staging.deleteRecursively()
                    tarball.delete()
                },
                onFailure = {
                    staging.deleteRecursively()
                    tarball.delete()
                    return Result.failure(it)
                },
            )
            if (!HashVerifier.verify(
                    segmentationFile(context), SEGMENTATION_INT8_SHA256)) {
                // Delete: the file arrived by renameTo with no .size sidecar,
                // so isFileComplete would treat ANY non-empty file as
                // complete forever and the corrupt model could never
                // re-download (code review F4).
                segmentationFile(context).delete()
                return Result.failure(
                    IllegalStateException("speaker segmentation model hash mismatch"))
            }
            doneBytes += SEGMENTATION_TARBALL_BYTES
        }

        if (!ResumeDownloadHelper.isFileComplete(embeddingFile(context))) {
            val download = downloadWithRetry(
                DownloadConfig(
                    url = EMBEDDING_URL,
                    tempFile = File(dir(context), "$EMBEDDING_FILE.part"),
                    targetFile = embeddingFile(context),
                    estimatedSizeBytes = EMBEDDING_BYTES,
                ),
                tag = "DiarizationModels",
            )
            if (download.isFailure) {
                ResumeDownloadHelper.clearTarDownload(embeddingFile(context))
                return download.map { }
            }
            if (!HashVerifier.verify(embeddingFile(context), EMBEDDING_SHA256)) {
                embeddingFile(context).delete()
                return Result.failure(
                    IllegalStateException("speaker embedding model hash mismatch"))
            }
        }
        return Result.success(Unit)
    }
}
