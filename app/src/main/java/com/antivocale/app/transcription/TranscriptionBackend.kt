package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord

import android.content.Context

/**
 * Interface for transcription backends.
 *
 * Each backend handles audio transcription
 * and text generation
 * using different underlying technologies (LiteRT-LM, sherpa-onnx, etc).
 */
interface TranscriptionBackend {
    /**
     * Unique identifier for this backend.
     */
    val id: String

    /**
     * User-friendly display name.
     */
    val displayName: String

    /**
     * Whether this backend supports audio transcription.
     */
    val supportsAudio: Boolean

    /**
     * Whether this backend supports text generation
     */
    val supportsText: Boolean

    /**
     * Maximum audio chunk duration this backend can process efficiently.
     * Audio longer than this will be split into chunks.
     * null means no chunking limit (process entire audio as single chunk)
     */
    val maxChunkDurationSeconds: Int?
        get() = 30  // Default: 30 seconds (safe for most backends)

    /**
     * True when this backend's decoder degrades on chunks cut at arbitrary
     * positions and needs VAD-aligned (silence-boundary) segmentation instead of
     * fixed-length pipeline cuts, REGARDLESS of the user's VAD toggle. TASK-370
     * (Gemma audio encoder) and the canary external family (TASK-408, measured:
     * mid-speech cuts make half the chunks decode empty) set this.
     */
    val requiresVadAlignedChunking: Boolean
        get() = false

    /**
     * Initializes the backend with the given configuration.
     */
    suspend fun initialize(context: Context, config: BackendConfig): Result<Unit>

    /**
     * Transcribes audio data to text.
     *
     * @param samples PCM float samples normalized to [-1.0, 1.0], mono channel
     * @param sampleRate Sample rate of the audio data
     * @return Result containing [TranscriptionResult] with text, optional confidence, and detected language
     */
    suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String): Result<TranscriptionResult>

    /**
     * Streaming variant of [transcribeAudio] that emits partial hypotheses via [onPartial]
     * as the audio is decoded, enabling progressive/real-time display.
     *
     * The default implementation ignores [onPartial] and delegates to [transcribeAudio];
     * backends backed by a streaming recognizer (e.g. Nemotron's OnlineRecognizer) override
     * this to surface progressive text. The final returned result must be equivalent to
     * [transcribeAudio]'s output for the same input.
     */
    suspend fun transcribeAudioStreaming(
        samples: FloatArray,
        sampleRate: Int,
        prompt: String,
        onPartial: suspend (String) -> Unit
    ): Result<TranscriptionResult> = transcribeAudio(samples, sampleRate, prompt)

    /**
     * Generates text from a prompt.
     */
    suspend fun generateText(prompt: String): Result<String>

    /**
     * Returns whether the backend is ready for inference.
     */
    fun isReady(): Boolean

    /**
     * Returns whether this backend supports audio transcription.
     */
    fun isAudioSupported(): Boolean

    /**
     * Unloads the backend and releases resources.
     */
    fun unload()

    /**
     * Sets the keep-alive timeout for the backend.
     */
    fun setKeepAliveTimeout(minutes: Int)

    /**
     * Callback invoked after the backend unloads itself (idle timeout or
     * explicit unload), so the manager can clear its bookkeeping. Default
     * no-op for backends without self-managed lifecycle.
     */
    fun setOnAutoUnloadCallback(callback: (() -> Unit)?) {}

    /**
     * TASK-644: true while a native call is executing on this backend.
     * Callers that would tear the engine down (the benchmark's warm-engine
     * displacement, its post-run unload) must refuse or defer while busy:
     * releasing a native recognizer mid-decode is a use-after-free crash.
     * Default false for backends without a native in-flight bracket.
     */
    fun isBusy(): Boolean = false

    /**
     * Returns the path to the model file.
     */
    fun getModelPath(): String?

    /**
     * TASK-546 AC3: the language this backend was last CONFIGURED with, in the
     * backend's normalized vocabulary (blank preference resolved to "auto").
     * Third component of the load-path residency identity (backend id + model
     * path + language): a warm engine decodes under this value, so callers
     * comparing a desired language against a resident backend compare against
     * it. Null (the default) means no language identity claim: treat a null
     * as warm-eligible, the [getModelPath] convention.
     */
    fun getConfiguredLanguage(): String? = null
}

/**
 * Sealed class for backend-specific configuration.
 */
sealed class BackendConfig {
    /**
     * Configuration for LiteRT-LM backend.
     */
    data class LiteRTConfig(val modelPath: String) : BackendConfig()

    /**
     * Configuration for sherpa-onnx backend.
     *
     * @param modelDir Directory containing encoder/decoder/joiner/tokens
     * @param modelType Model architecture type (default: nemo_transducer for Parakeet)
     */
    data class SherpaOnnxConfig(
        val modelDir: String,
        val modelType: String = "nemo_transducer",
        val numThreads: Int,
        val language: String = "",
        val provider: String = "cpu"
    ) : BackendConfig()

    data class ExternalConfig(
        val record: ExternalModelRecord,
        val numThreads: Int,
        val provider: String,
    ) : BackendConfig()
}

/**
 * Typed exceptions backends throw inside Result.failure, so the orchestrator
 * and UI can distinguish failure causes and show specific, user-facing messages
 * instead of a generic "transcription failed".
 *
 * Backends should prefer these over raw exceptions where the cause is identifiable.
 * The [cause] chain is always preserved for logcat diagnostics.
 */
sealed class TranscriptionException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    /** The model file is missing, corrupt, truncated, or the wrong format for this backend. */
    class ModelLoadError(detail: String, cause: Throwable? = null) :
        TranscriptionException("Model load failed: $detail", cause)

    /** The model loaded but a native/decoding error occurred during transcription. */
    class NativeError(detail: String, cause: Throwable? = null) :
        TranscriptionException("Native inference error: $detail", cause)

    /** The backend was not initialized (no model loaded) when transcription was requested. */
    class NotInitialized :
        TranscriptionException("Backend not initialized (no model loaded)")

    /**
     * Audio could be decoded but produced no transcription text.
     * TASK-622: [blankChunks] counts chunks that decoded successfully but
     * blank (1 on the whole-file path); it rides the exception to the ERROR
     * row's FailureContext without changing the user-facing message.
     */
    class NoTranscriptionProduced(val blankChunks: Int? = null) :
        TranscriptionException("No transcription produced")

    /** The device had too little free memory to load the model (pre-flight block). */
    class InsufficientMemory(detail: String) :
        TranscriptionException("Insufficient memory: $detail")

    /**
     * TASK-607 F5: a generation timed out under its ceiling WITHOUT the
     * engine being wedged (the healthy-but-slow case). Lives in the project's
     * sealed seam so the pairing with the summarize/chunk ladders is
     * compiler-checked; nothing matches the raw JDK TimeoutException anymore
     * (grep-verified at conversion time).
     */
    class GenerationTimeout(detail: String) : TranscriptionException(detail)

    /** The persisted external model record is gone or its files vanished (TASK-342). */
    class ExternalModelUnavailable(backendId: String) :
        TranscriptionException("External model no longer available: $backendId")
}

/**
 * TASK-625: whether this failure belongs to the memory class (the pre-flight
 * refusals plus the OOM catch). The error notification offers the
 * jump-to-Memory-protection action for exactly these; detection is typed, never the
 * localized message text.
 */
fun isMemoryClassFailure(error: Throwable): Boolean =
    error is TranscriptionException.InsufficientMemory || error is OutOfMemoryError

/**
 * TASK-631: the load pre-flight refusal. Protection is OPT-IN (off by default:
 * the app never refuses a model on its own), and an unreadable memory value
 * (0) fails open rather than blocking on an unknown figure.
 */
fun shouldRefuseForMemory(protectionOn: Boolean, availBytes: Long, requiredBytes: Long): Boolean =
    protectionOn && availBytes > 0L && availBytes < requiredBytes

/**
 * Result from audio transcription containing the text and optional metadata.
 */
data class TranscriptionResult(
    val text: String,
    val confidence: Float? = null,
    val detectedLanguage: String? = null,
    val isPartial: Boolean = false,
    val failedChunkCount: Int = 0,
    /** TASK-450: this request would have been refused on the VAD (whole-file)
     *  path for the device's memory ceiling and was streamed without silence
     *  stripping instead; surfaced in the result notification's subtext. */
    val streamedWithoutVad: Boolean = false,
    /** TASK-276 AC3: the raw ASR text before the punctuation pass, set only
     *  when the pass replaced the text (persisted as the log row's original). */
    val rawTranscript: String? = null,
    /** TASK-121.4: the AI summary of a long transcript, attached as metadata
     *  (persisted as the log row's summary). The delivered [text] is never
     *  replaced by it. */
    val summary: String? = null,
    /** TASK-494: stable token for why an ATTENDED summary attempt produced
     *  no summary (the guards rejected the output). Rendered localized in
     *  the entry details; never user text. */
    val summarySkipReason: String? = null,
    /** GH #92: subtitle cues with offsets from the audio start: sentence-level
     *  when token timing exists, else one positional cue per chunk. Empty on the
     *  whole-clip paths that produce no cue data and whenever honest timing is unavailable; see the
     *  assembly paths in TranscriptionOrchestrator. */
    val segments: List<TimedSegment> = emptyList(),
    /** GH #92: per-token timings, chunk-relative, when the path supplies them
     *  (see [TimedToken]); empty means no token timing and the chunk-level
     *  [segments] apply unchanged. Sentence cues are derived from these in
     *  SentenceCueBuilder at the assembly paths. Populated by the offline sherpa
     *  paths only: the OnlineRecognizer paths (Nemotron, streaming externals)
     *  are deliberately unwired pending an on-device probe of their timestamp
     *  arrays. */
    val tokens: List<TimedToken> = emptyList(),
    /** TASK-512: how this result was produced (decode path, chunk coverage,
     *  chunk cap, RAM at request time); persisted as the row's processing
     *  context and surfaced in the entry details and report email. */
    val processing: ProcessingContext? = null,
    /** GH #43: the fast streaming first pass this result refined (null on
     *  single-model runs). Rides the result to logSuccess, which persists
     *  the text as the row's first-pass block. */
    val firstPass: FirstPassOutcome? = null,
) {
    companion object {
        private val WHITESPACE = Regex("\\s+")

        /**
         * TASK-615: the recognizer's raw `lang` normalized to a bare code.
         * SenseVoice reports the decode token verbatim ("<|en|>"), Whisper a
         * plain "en"; the chip, the pin flow and the persisted row must all
         * see the bare code. Unrecognized shapes (blank, stray pipes) mean
         * no usable detection: null.
         */
        fun normalizedDetectedLanguage(raw: String?): String? =
            raw?.trim()?.removeSurrounding("<|", "|>")?.trim()
                ?.takeIf { it.isNotEmpty() && '|' !in it }

        fun computeConfidence(text: String, sampleCount: Int, sampleRate: Int): Float? {
            val audioDurationSeconds = sampleCount.toFloat() / sampleRate
            if (audioDurationSeconds <= 0f) return null
            val wordCount = text.split(WHITESPACE).count { it.isNotEmpty() }
            if (wordCount == 0) return null
            val wps = wordCount / audioDurationSeconds
            return when {
                wps >= 1.5f -> 0.85f.coerceAtMost(0.7f + 0.15f * minOf(1f, (wps - 1.5f) / 3f))
                wps >= 0.5f -> 0.4f + 0.3f * ((wps - 0.5f) / 1f)
                else -> (wps / 0.5f) * 0.4f
            }
        }
    }
}

/** GH #43: what the fast first pass produced before refinement replaced it. */
data class FirstPassOutcome(
    /** The complete first-pass transcript (never blank when present). */
    val text: String,
    /** The fast backend's own processing context (nested on the row's). */
    val processing: ProcessingContext,
    /** The fast pass's own confidence/language/cues, so an F4/F5 delivery
     *  (review F5) loses nothing a single-model run would keep. */
    val confidence: Float? = null,
    val detectedLanguage: String? = null,
    val segments: List<TimedSegment> = emptyList(),
    /** Chunk-completeness of the first pass, so an F4/F5 delivery reports
     *  partial results as partial (guard-review finding). */
    val isPartial: Boolean = false,
    val failedChunkCount: Int = 0,
    /** Stable token when refinement did NOT complete (F4/F5): the delivered
     *  text IS the first pass and the row carries a not-refined caption. */
    val refinementFailedToken: String? = null,
    /** TASK-582: the detector's measured values when the token is a loop
     *  skip ("compression=2.61 ngram=0.42"), for field threshold tuning. */
    val refinementLoopMetrics: String? = null,
)
