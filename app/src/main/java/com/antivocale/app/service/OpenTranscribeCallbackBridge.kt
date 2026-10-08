package com.antivocale.app.service

import com.antivocale.app.transcription.TimedSegment
import com.antivocale.app.util.SubtitleFormatter
import org.opentranscribe.api.ErrorType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TASK-785: the delivery seam between [TranscriptionOrchestrator] and an
 * Open Transcribe client. Implementations of [OpenTranscribeEmitter] own
 * the transport (the AIDL callback in the provider service, a recorder in
 * tests); this class owns the CONTRACT mapping, so it stays JVM-testable
 * with no Binder in sight.
 *
 * Progress cumulativeness (resolved at the orchestrator emit sites,
 * 2026-10-08): interim bigText is NOT cumulative. Three of the four
 * onInterimResult arms (the VAD segment loop, the parallel-chunk fold and
 * the streaming pipeline's deliverDecodedChunk) pass the just-completed
 * chunk's own text as bigText, keeping only their LOCAL accumulator
 * cumulative for the Room row; the fourth arm (single-chunk streaming)
 * passes the recognizer's growing partial, which revises the same chunk.
 * The bridge therefore accumulates by chunk identity: an unseen chunkIndex
 * appends its text (a completed-chunk delta), a repeat chunkIndex REPLACES
 * it (a revised partial of the same utterance). The contract's cumulative
 * guarantee holds on both shapes, and the held-empty ladder recovery is
 * invisible here by design (that arm suppresses the listener emission;
 * its text still reaches the terminal result).
 *
 * Error mapping: the orchestrator's errorCode vocabulary
 * (BACKEND_LOAD_FAILED, INFERENCE_ERROR, OUT_OF_MEMORY, PROCESSING_ERROR)
 * cannot distinguish decode failures, so the bridge consumes the typed
 * flags instead: isNoModelError -> MODEL_NOT_AVAILABLE (NotInitialized and
 * the dangling-external class), isDecodeError -> DECODE_FAILED (a
 * PreprocessingError: invalid format, no audio track, no decoder,
 * conversion, chunk, duration failures; also as a PipelineFailure cause),
 * everything else -> UNEXPECTED (OOM, native and load failures). A pinned
 * language the active model cannot serve still decodes via detection, so
 * UNSUPPORTED_LANGUAGE is never emitted.
 */
class OpenTranscribeCallbackBridge(private val emitter: OpenTranscribeEmitter) : TranscriptionListener {

    /**
     * Exactly one terminal delivery per request (the contract's core
     * guarantee): whichever of result, error or cancellation lands first
     * wins; later arms are dropped, so a cancel racing a finishing job
     * cannot produce a result AND an error.
     */
    private val terminalDelivered = AtomicBoolean(false)

    /** Insertion-ordered chunk texts; see the class KDoc for the shapes. */
    private val chunkTexts = LinkedHashMap<Int, String>()

    override fun onStatusUpdate(message: String) = Unit

    override fun onIndeterminateProgress(message: String) = Unit

    override fun onProgress(
        contentText: String,
        progressPercent: Int,
        etaText: String,
        durationSeconds: Int,
        startTimeMillis: Long,
        queuedCount: Int
    ) = Unit

    override fun onInterimResult(
        contentText: String,
        bigText: String,
        subText: String,
        chunkIndex: Int,
        chunkText: String?,
        totalChunks: Int
    ) {
        val text = bigText.takeUnless { it.isBlank() } ?: contentText
        if (text.isBlank()) return
        if (chunkIndex >= 0) {
            chunkTexts[chunkIndex] = text
            emitter.onProgress(chunkTexts.values.joinToString(" "))
        } else {
            // No chunk identity (no current emit site sends this): the text
            // cannot be attributed to a chunk, so it passes through as-is.
            emitter.onProgress(text)
        }
    }

    override fun onSuccess(
        taskId: String,
        resultText: String,
        isShareRequest: Boolean,
        sourcePackage: String?,
        durationMs: Long,
        confidence: Float?,
        detectedLanguage: String?,
        isPartial: Boolean,
        failedChunkCount: Int,
        streamedWithoutVad: Boolean,
        segments: List<TimedSegment>,
        refinementOutcome: String?,
        repetitionSuspected: Boolean,
    ) {
        if (!terminalDelivered.compareAndSet(false, true)) return
        // Segments just before the terminal result, per the contract. Cue
        // text stays plain: the turn-start SPEAKER prefix needs rendering
        // context (SubtitleFormatter prefixes only at turns), and it rides
        // the annotated result text below instead.
        segments.forEach { emitter.onSegment(it.startMs, it.endMs, it.text) }
        emitter.onResult(SubtitleFormatter.annotatedOrStored(resultText, segments))
    }

    override fun onError(
        taskId: String,
        errorCode: String,
        errorMessage: String,
        isShareRequest: Boolean,
        isNoModelError: Boolean,
        durationMs: Long,
        isMemoryFailure: Boolean,
        isDecodeError: Boolean,
    ) {
        if (!terminalDelivered.compareAndSet(false, true)) return
        val type: Byte = when {
            isNoModelError -> ErrorType.MODEL_NOT_AVAILABLE
            isDecodeError -> ErrorType.DECODE_FAILED
            else -> ErrorType.UNEXPECTED
        }
        emitter.onError(type, null, errorMessage)
    }

    /** The cancellation terminal, delivered by the job owner on cancel. */
    fun deliverCancelled() {
        if (!terminalDelivered.compareAndSet(false, true)) return
        emitter.onError(ErrorType.CANCELLED, null, null)
    }
}

/** The transport seam the provider service implements over the AIDL callback. */
interface OpenTranscribeEmitter {
    fun onProgress(text: String)
    fun onSegment(startMs: Long, endMs: Long, text: String)
    fun onResult(text: String)
    fun onError(type: Byte, language: String?, message: String?)
}
