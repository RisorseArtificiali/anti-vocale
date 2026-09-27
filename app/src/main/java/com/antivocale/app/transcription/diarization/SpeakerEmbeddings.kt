package com.antivocale.app.transcription.diarization

import com.antivocale.app.transcription.TranscriptionException
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * TASK-670 (GH #83): the standalone speaker-embedding extractor session,
 * built on the SAME titanet model file the diarizer embeds with
 * ([DiarizationModels.embeddingFile]). One embedding space for enrollment
 * and identification is what makes the cosine comparison meaningful; the
 * classes ship in the pinned AAR (verified by javap, no native work).
 *
 * The diarization engine itself does NOT expose per-cluster embeddings
 * (OfflineSpeakerDiarization.process returns index-labeled segments only),
 * so identification re-extracts from each cluster's audio through this
 * wrapper: a separate pass, not a reuse of the diarizer's internals.
 */
class SpeakerEmbeddings private constructor(
    private val extractor: SpeakerEmbeddingExtractor,
) {
    companion object {
        fun create(
            embeddingModel: File,
            numThreads: Int,
        ): Result<SpeakerEmbeddings> = runCatching {
            val config = SpeakerEmbeddingExtractorConfig(
                model = embeddingModel.absolutePath,
                numThreads = numThreads,
            )
            SpeakerEmbeddings(SpeakerEmbeddingExtractor(assetManager = null, config = config))
        }.recoverCatching { cause ->
            throw TranscriptionException.ModelLoadError(
                "speaker embedding extractor: ${cause.message}", cause)
        }
    }

    fun dim(): Int = extractor.dim()

    /**
     * Embeds one complete speech buffer. The stream contract mirrors the
     * app's other sherpa feed sites: waveform, then inputFinished, then the
     * compute once the engine reports ready.
     *
     * @throws TranscriptionException.NativeError when the buffer is too
     *         short for the model to produce an embedding
     */
    fun compute(samples: FloatArray, sampleRate: Int): FloatArray {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            stream.inputFinished()
            if (!extractor.isReady(stream)) {
                throw TranscriptionException.NativeError(
                    "speaker embedding extractor not ready after ${samples.size} samples")
            }
            return extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    fun release() {
        extractor.release()
    }
}
