package com.antivocale.app.transcription

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.antivocale.app.R
import com.antivocale.app.manager.EngineWedgeTimeoutException
import com.antivocale.app.util.DecodedOfTotalFormat
import com.antivocale.app.audio.AudioDurationPolicy
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.service.ResultNotificationFactory
import com.antivocale.app.audio.AudioPreprocessor.PreprocessingError
import com.antivocale.app.audio.AudioPreprocessor.StreamEvent
import com.antivocale.app.transcription.diarization.DiarizationModels
import com.antivocale.app.transcription.diarization.SpeakerDiarizer
import com.antivocale.app.transcription.diarization.SpeakerLabeler
import com.antivocale.app.audio.MemoryReadings
import com.antivocale.app.audio.PreprocessingErrorMessages
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.TranscriptionCalibrator
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.data.catalog.CatalogDisplay
import com.antivocale.app.data.catalog.CatalogStringKeys
import com.antivocale.app.data.local.FailureContext
import com.antivocale.app.data.local.FailureContextJson
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.ProcessingContextConverter
import com.antivocale.app.data.local.TimedSegmentsConverter
import com.antivocale.app.data.local.toEntity
import com.antivocale.app.data.local.toLogEntry
import com.antivocale.app.service.ExtractionService
import com.antivocale.app.service.InferenceService
import com.antivocale.app.service.TranscriptionListener
import com.antivocale.app.ui.viewmodel.LogEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure business logic for transcription orchestration.
 *
 * Owns queue processing, backend loading, audio preprocessing,
 * transcription, calibration, and DB logging. Communicates results
 * and progress back to the Android service layer via [TranscriptionListener].
 */
@Singleton
class TranscriptionOrchestrator @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val logDao: LogDao,
    private val transcriptionCalibrator: TranscriptionCalibrator,
    private val backendManager: TranscriptionBackendManager,
    private val audioPreprocessor: AudioPreprocessor,
    private val backendRegistry: BackendRegistry,
    private val externalModelStore: ExternalModelStore,
) {
    companion object {
        private const val TAG = "TranscriptionOrchestrator"
        /**
         * TASK-631 part 2: fixed id (reserved-range table, below the result
         * allocator's 3000 base) for the default path's dismissable
         * tight-margin warning.
         */
        internal const val MEMORY_MARGIN_WARNING_ID = 1006
        // TASK-406: each in-flight chunk carries its own attention activations, so peak
        // memory multiplies by the permit count. Measured (desktop, 6-min file, 60s
        // chunks): 2 permits cut wall-clock ~14% (28.6s vs 33.1s) for +23% peak
        // (2337 vs 1894 MiB); serial is the safe ceiling on low-RAM phones, which is
        // the trade we take (d6a49e0 had measured the 2-permit wall-clock win).
        private const val MAX_CONCURRENT_CHUNKS = 1
        private const val PARTIAL_SAVE_INTERVAL_MS = 5000L
        /** TASK-520: map -> reduce recursion budget; partials shrink hard per level. */
        private const val MAX_SUMMARY_LEVELS = 3
        private const val MB = 1024L * 1024L
        // Headroom over the on-disk model size: absorbs sherpa inference buffers and reclaimable-cache
        // noise in availMem. Tunable; see TASK-314 spec. ~300MB derived from the SmoothQuant incident.
        private const val MEMORY_HEADROOM_BYTES = 300L * MB


        internal fun isNoModelConfiguredError(error: Throwable): Boolean {
            return error is TranscriptionException.NotInitialized ||
                // A dangling external id is the same UX class: no usable model configured.
                error is TranscriptionException.ExternalModelUnavailable
        }

        /**
         * Maps a [TranscriptionException] to a user-facing localized message via the given [context].
         * Non-TranscriptionException errors fall back to the generic [R.string.transcription_failed].
         */
        internal fun userFacingErrorMessage(context: Context, error: Throwable): String {
            return when (error) {
                is WedgeAbortException ->
                    // TASK-606 F8: the abort's own message is English diagnostics;
                    // the notification and the Tasker reply carry the localized
                    // generic failure (the technical detail stays in logcat).
                    context.getString(R.string.transcription_failed)
                is TranscriptionException.ModelLoadError ->
                    // The heal path (TASK-479) carries the actionable
                    // "corrupt files removed, re-download" instruction;
                    // every other load error keeps the generic string.
                    if (error.message?.startsWith("corrupt model files") == true) {
                        context.getString(R.string.error_model_corrupt_healed)
                    } else {
                        context.getString(R.string.error_model_load)
                    }
                is TranscriptionException.InsufficientMemory ->
                    // The exception already carries the localized low-memory message with the
                    // measured numbers; surface it directly instead of the generic model-load string.
                    error.message ?: context.getString(R.string.error_model_load)
                is TranscriptionException.NativeError ->
                    context.getString(R.string.error_native)
                is TranscriptionException.NotInitialized ->
                    context.getString(R.string.error_not_initialized)
                is TranscriptionException.ExternalModelUnavailable ->
                    // A dangling external: id must not read as the generic corrupt-model
                    // message; the user just needs to pick another model (TASK-342).
                    context.getString(R.string.error_model_unavailable)
                is TranscriptionException.NoTranscriptionProduced ->
                    context.getString(R.string.transcription_failed)
                // TASK-432: the pre-read duration refusal and every other
                // preprocessing failure reach users through the notification and
                // the Tasker reply; this branch routes them to localized advice.
                is PreprocessingError -> PreprocessingErrorMessages.localize(context, error)
                // TASK-568: the streaming failure wrapper keeps the original
                // exception as its cause; route through it so typed advice
                // still reaches the notification (the wrapper itself only
                // adds the decoded-of-total suffix at the caller).
                is PipelineFailure -> userFacingErrorMessage(context, error.cause ?: error)
                else -> context.getString(R.string.transcription_failed)
            }
        }
    }

    @Volatile
    private var lastPartialSaveMs: Long = 0L

    /** Per-task timestamp of the last interim Room write (throttle, TASK-340 Fix 2b). */
    private val lastInterimRoomWriteMs = mutableMapOf<String, Long>()

    /**
     * Clock read ONLY by the interim-write throttle. Injectable so the throttle is
     * unit-testable; mocking System.currentTimeMillis statically is not viable (mockk
     * intercepts JVM-internal calls and recurses forever).
     */
    internal var throttleClock: () -> Long = System::currentTimeMillis

    /**
     * In-flight chunk limit. Production keeps the serial TASK-406 default; the
     * lazy semaphore reads this so the out-of-order join test can exercise
     * permits > 1 without a Dagger-provided constructor parameter.
     */
    internal var maxConcurrentChunks: Int = MAX_CONCURRENT_CHUNKS
    private val chunkSemaphore by lazy { Semaphore(maxConcurrentChunks) }

    /**
     * Processes a single transcription request.
     * All Android-specific side effects are delegated to [listener].
     *
     * @param context Android context needed for backend initialization (not stored)
     * @param cacheDir Cache directory for audio preprocessing (not stored)
     * @param coroutineScope Scope for launching background work (progress timer, calibration)
     */
    suspend fun processRequest(
        taskId: String,
        requestType: String,
        prompt: String = "",
        filePath: String?,
        source: String?,
        sourcePackage: String?,
        backendOverride: String? = null,
        trackIndex: Int = -1,
        queuePosition: Int,
        queueTotal: Int,
        context: Context,
        cacheDir: File,
        listener: TranscriptionListener,
        coroutineScope: CoroutineScope
    ): Result<String> {
        val isShareRequest = source == InferenceService.SOURCE_SHARE

        // Log request start
        markProcessing(taskId)

        val startTime = System.currentTimeMillis()

        try {
            // Subtitle mode: extract embedded text subtitles WITHOUT loading any model. On
            // extraction failure (null/blank), fall back to the normal audio/ASR path below
            // rather than reporting an error. This branch must run BEFORE ensureBackendLoaded
            // so a missing model never blocks a subtitle hit.
            if (requestType == "subtitles") {
                val subtitleResult = processSubtitleRequest(
                    taskId = taskId,
                    filePath = filePath,
                    trackIndex = trackIndex,
                    source = source,
                    sourcePackage = sourcePackage,
                    isShareRequest = isShareRequest,
                    startTime = startTime,
                    context = context,
                    cacheDir = cacheDir,
                    listener = listener,
                    coroutineScope = coroutineScope,
                    queuePosition = queuePosition,
                    queueTotal = queueTotal,
                    prompt = prompt,
                    backendOverride = backendOverride
                )
                // Non-null result = the subtitle path resolved the request (success OR a
                // fallback-driven error already reported to the listener). Null = extraction
                // yielded nothing and the caller should run ASR — handled below.
                if (subtitleResult != null) return subtitleResult
            }

            // GH #43 (design D6): the two-pass first pass. Runs the fast
            // streaming backend as phase 1 for instant visible text; phase 2
            // below (the unmodified single-model path) refines it. Phase 1
            // never touches the result funnel: no logSuccess, no onSuccess,
            // no Tasker reply. Its text rides the row as interim, exactly
            // like today's streaming partials.
            // F0 guard: any unexpected exception in the phase-1 machinery
            // (eligibility, load, streaming pass) degrades to the normal
            // single-model run; phase 1 may never break a request.
            // F1/F3 tokens land here when phase 1 attempted but degraded.
            var dualSkipToken: String? = null
            var dualSkipLoopMetrics: String? = null
            val fastFirstPass = runCatching {
                runRefinementFirstPass(
                    taskId = taskId, requestType = requestType, backendOverride = backendOverride,
                    filePath = filePath, prompt = prompt, queuePosition = queuePosition,
                    queueTotal = queueTotal, context = context, cacheDir = cacheDir,
                    listener = listener, coroutineScope = coroutineScope,
                    onSkipped = { token, loopMetrics ->
                        dualSkipToken = token
                        dualSkipLoopMetrics = loopMetrics
                    },
                )
            }.onFailure {
                // Cancellation must propagate (the house contract): a
                // cancelled job may not keep loading models.
                if (it is kotlinx.coroutines.CancellationException) throw it
                Log.w(TAG, "First pass machinery failed; single-model run", it)
            }.getOrNull()
            if (fastFirstPass != null) {
                // Phase transition: the row keeps the first-pass text; the
                // notification says what is happening now (the design's
                // "Refining with <model>..." line).
                runCatching {
                    val accurateId = backendOverride ?: preferencesManager.transcriptionBackend.first()
                    val name = displayNameForBackend(context, accurateId)
                    listener.onStatusUpdate(context.getString(R.string.refining_status, name))
                }
            }

            // Ensure the correct backend is loaded
            val loadResult = ensureBackendLoaded(context, backendOverride)
            // Design F4: the accurate model failed to load but a complete
            // first pass exists. recoverFirstPass below routes it through the
            // SAME funnel as F5 (punctuation and summary included), so both
            // degradation arms deliver identically.
            val f4LoadFailure = if (loadResult.isFailure && fastFirstPass != null)
                loadResult.exceptionOrNull() else null
            if (loadResult.isFailure && f4LoadFailure == null) {
                val error = loadResult.exceptionOrNull()!!
                val userMsg = userFacingErrorMessage(context, error)
                val logMsg = "Failed to load backend: ${error.message}"
                val duration = System.currentTimeMillis() - startTime
                val isNoModel = isNoModelConfiguredError(error)
                persistFailureContext(taskId, error, context)
                logError(taskId, logMsg, duration)
                listener.onError(taskId, "BACKEND_LOAD_FAILED", userMsg, isShareRequest, isNoModel, duration,
                    isMemoryFailure = isMemoryClassFailure(error))
                return Result.failure(error)
            }

            // GH #45: record which model handled the request, as soon as the
            // backend is resolved (before the result lands). Resolved through the
            // registry display-name contract so raw backend ids never reach the
            // Logs UI; TASK-436 makes the shared derivation variant-aware
            // ("Whisper Small", not the bare family label). Metadata only:
            // never let it break the transcription itself.
            runCatching {
                val backend = backendManager.getActiveBackend() ?: return@runCatching
                val descriptor = backendRegistry.byBackendId(backend.id)
                val name = when {
                    descriptor == null -> backend.displayName
                    else -> variantAwareDisplayName(context, descriptor, modelPathForBackend(backend.id))
                }
                logDao.setModelName(taskId, name)
            }

            // GH #83: collect the preprocessed chunks once for the optional
            // speaker-labeling pass (references only; the concatenated copy
            // is built lazily inside the pass, after ASR releases its own
            // working set). The fast first pass passes no collector.
            var diarizationChunks: List<FloatArray>? = null
            var diarizationSampleRate = 16000
            val collectSpeakers = runCatching {
                preferencesManager.speakerLabelsEnabled.first()
            }.getOrDefault(false)

            val result = when (requestType) {
                "audio" -> if (f4LoadFailure != null) {
                    recoverFirstPass(fastFirstPass!!, f4LoadFailure,
                        DualRefinementPolicy.SKIP_REFINE_LOAD_FAILED)
                } else {
                    val phase2 = processAudioRequest(
                        taskId = taskId,
                        filePath = filePath,
                        prompt = prompt,
                        queuePosition = queuePosition,
                        queueTotal = queueTotal,
                        context = context,
                        cacheDir = cacheDir,
                        listener = listener,
                        coroutineScope = coroutineScope,
                        // The first-pass text stays on the row; phase 2's
                        // growing partials must not overwrite it (design D6).
                        emitInterim = fastFirstPass == null,
                        collectSamples = if (collectSpeakers) {
                            { chunks, rate ->
                                diarizationChunks = chunks
                                diarizationSampleRate = rate
                            }
                        } else {
                            null
                        },
                    )
                    if (fastFirstPass == null) phase2
                    else phase2.fold(
                        onSuccess = { refined -> refinementFoldSuccess(fastFirstPass, refined) },
                        onFailure = { failure ->
                            // Design F5: deliver through the same funnel.
                            recoverFirstPass(fastFirstPass, failure,
                                DualRefinementPolicy.SKIP_REFINE_INFERENCE_FAILED)
                        }
                    )
                }
                else -> processTextRequest(prompt)
            }

            val duration = System.currentTimeMillis() - startTime

            // TASK-276: the punctuation pass maps the RESULT before the fold, so
            // the returned value, the log row and the notification all carry the
            // same final text. It runs exactly once at this single funnel for
            // every decode path (pipeline, parallel, VAD-progressive); text
            // requests are the LLM's own output and take no pass.
            // TASK-121.4: the summary pass chains AFTER it and only attaches
            // metadata; the delivered text is whatever the punctuation pass
            // left (or the raw transcript when it skipped).
            val delivered: Result<TranscriptionResult> =
                if (requestType == "audio" && result.isSuccess) {
                    // Captured once here (not re-read inside the pass): the
                    // backend that produced this result. The queue is serial,
                    // so nothing else has swapped it since the ASR finished.
                    // Guard-review finding (pre-existing on the F5 arm): when
                    // the delivered text came from the FAST first pass, the
                    // producer is the fast model, not the still-active
                    // accurate one; with the LLM selected the polish would
                    // otherwise be skipped on non-LLM text.
                    val deliveredFromFirstPass =
                        result.getOrNull()?.firstPass?.refinementFailedToken != null
                    val asrBackendId = when {
                        deliveredFromFirstPass ->
                            result.getOrNull()?.firstPass?.processing?.backendId
                                ?: backendManager.getActiveBackend()?.id
                        else -> backendManager.getActiveBackend()?.id
                    }
                    if (asrBackendId == null) result
                    else result
                        .map { applyPunctuationPass(context, asrBackendId, it, listener) }
                        .map { applySummaryPass(context, it, listener) }
                } else result

            // GH #83: the optional speaker-labeling pass. Runs on the same
            // timeline the cues were assembled on (the concatenated
            // preprocessed chunks), labels each cue by speech-time voting,
            // and can only ADD metadata: any failure logs and delivers the
            // unlabeled result, never breaking the run.
            // TASK-600 F13: an honest skip reason. The log names the decode
            // path ONLY when a decode actually delivered and the pass had
            // something to skip; a failed run (memory refusal, decode error,
            // pipeline exception) never decoded, so nothing was skipped and
            // the old blanket line misattributed those failures to the path.
            if (collectSpeakers && diarizationChunks == null && requestType == "audio" &&
                delivered.isSuccess && delivered.getOrNull()?.text?.isNotBlank() == true
            ) {
                Log.i(TAG, "Speaker labels skipped: the decode path delivered no sample timeline (streaming or VAD-segmented)")
            }
            val speakerLabeled: Result<TranscriptionResult> =
                if (delivered.isSuccess && diarizationChunks != null) {
                    applySpeakerLabels(
                        context, delivered, diarizationChunks!!, diarizationSampleRate, listener)
                    // The concatenated copy must not outlive the pass.
                        .also { diarizationChunks = null }
                } else {
                    delivered
                }

            speakerLabeled.fold(
                onSuccess = { transcriptionResult ->
                    // F8 (review): the delivered text is the FAST model's when
                    // refinement failed; credit it, not the accurate one.
                    if (transcriptionResult.firstPass?.refinementFailedToken != null) {
                        // F8 credit by DISPLAY name: the Logs model column
                        // renders verbatim, so a raw backend id must never
                        // reach it (loop arm newly routes completed phase-2
                        // runs through here too).
                        fastFirstPass?.processing?.backendId?.let { fastId ->
                            // The suspend derivation stays OUTSIDE the catch so
                            // a cancellation unwinds here, not at the next
                            // suspension point (review F8).
                            val name = displayNameForBackend(context, fastId)
                            runCatching { logDao.setModelName(taskId, name) }
                        }
                    }
                    logSuccess(
                        taskId,
                        transcriptionResult.text,
                        duration,
                        transcriptionResult.isPartial,
                        transcriptionResult.failedChunkCount,
                        rawTranscript = transcriptionResult.rawTranscript,
                        summary = transcriptionResult.summary,
                        summarySkipReason = transcriptionResult.summarySkipReason,
                        segments = transcriptionResult.segments,
                        processing = transcriptionResult.processing?.withRefinement(
                            refinedFrom = fastFirstPass?.processing?.takeIf {
                                transcriptionResult.firstPass?.refinementFailedToken == null
                            },
                            skipReason = transcriptionResult.firstPass?.refinementFailedToken
                                ?: dualSkipToken,
                            loopMetrics = transcriptionResult.firstPass?.refinementLoopMetrics
                                ?: dualSkipLoopMetrics,
                        ),
                        detectedLanguage = transcriptionResult.detectedLanguage,
                        languagePin = resolvedLanguagePin(context),
                        firstPassTranscript = when {
                            // F4/F5 delivered the first pass AS the final text:
                            // no duplicate block; the caption carries the story.
                            transcriptionResult.firstPass?.refinementFailedToken != null -> null
                            else -> transcriptionResult.firstPass?.text
                        }
                    )
                    listener.onSuccess(taskId, transcriptionResult.text, isShareRequest, sourcePackage, duration,
                        confidence = transcriptionResult.confidence,
                        detectedLanguage = transcriptionResult.detectedLanguage,
                        isPartial = transcriptionResult.isPartial,
                        failedChunkCount = transcriptionResult.failedChunkCount,
                        streamedWithoutVad = transcriptionResult.streamedWithoutVad,
                        segments = transcriptionResult.segments,
                        // GH #43: which fast model the text refined from, or
                        // the not-refined sentinel (F4/F5 delivery).
                        refinementOutcome = when {
                            transcriptionResult.firstPass?.refinementFailedToken != null ->
                                DualRefinementPolicy.NOT_REFINED
                            // TASK-601: the contract is the fast model's DISPLAY
                            // name (formatted into the localized "Refined from
                            // %1$s"); the raw catalog id was reaching every
                            // locale's notification.
                            else -> fastFirstPass?.processing?.backendId
                                ?.let { displayNameForBackend(context, it) }
                        }
                    )
                },
                onFailure = { error ->
                    val logMsg = error.message ?: "Unknown error"
                    // TASK-568: the decoded-at-failure context (written by the
                    // streaming catches) must survive this writeback, so no
                    // elapsed duration here; the notification instead GAINS
                    // the decoded-of-total sentence when the failure carries it.
                    // The streaming path already persisted the rich context
                    // (chunks, durations, backend) inside pipelineFailed; a
                    // second persist here would clobber it with the all-null
                    // shape, so only non-streaming failures write their own.
                    if (error !is PipelineFailure) {
                        persistFailureContext(taskId, error, context)
                    }
                    logError(taskId, logMsg)
                    var userMsg = userFacingErrorMessage(context, error)
                    if (error is PipelineFailure) {
                        DecodedOfTotalFormat.format(context, error.decodedSeconds, error.totalSeconds)
                            ?.let { userMsg += " $it" }
                    }
                    val isNoModel = isNoModelConfiguredError(error)
                    listener.onError(taskId, "INFERENCE_ERROR", userMsg, isShareRequest, isNoModel, duration,
                        isMemoryFailure = isMemoryClassFailure(error))
                }
            )

            return delivered.map { it.text }

        } catch (e: CancellationException) {
            val duration = System.currentTimeMillis() - startTime
            cancelIfPending(taskId, "Transcription cancelled", durationMs = 0)
            throw e
        } catch (e: OutOfMemoryError) {
            // TASK-396: OOM is an Error, not an Exception; without this catch it
            // escapes processRequest unhandled and the user sees a crash instead
            // of the memory advice. Keep this handler lean (the heap is exhausted):
            // reuse the existing logError/listener paths, map to the dedicated
            // string, and bail.
            Log.e(TAG, "Out of memory during transcription", e)
            val duration = System.currentTimeMillis() - startTime
            persistFailureContext(taskId, e, context)
            logError(taskId, "OutOfMemoryError")
            // TASK-631: the advice differs by protection state. With protection
            // OFF (default) the notification's action offers to enable it; with
            // protection ON it already tried the guard and the advice must be
            // close apps / smaller model, not "enable what is enabled". The
            // localized text travels as errorMessage (the service no longer
            // swaps the string for this code).
            val protectionOn = preferencesManager.memoryProtection.first()
            val oomMessage = context.getString(
                if (protectionOn) R.string.error_oom_transcription_protected
                else R.string.error_oom_transcription)
            listener.onError(taskId, "OUT_OF_MEMORY", oomMessage, isShareRequest, false, duration,
                isMemoryFailure = !protectionOn)
            return Result.failure(TranscriptionException.InsufficientMemory(oomMessage))
        } catch (e: Exception) {
            Log.e(TAG, "Error processing request", e)
            val duration = System.currentTimeMillis() - startTime
            val errorMsg = e.message ?: "Unknown error"
            persistFailureContext(taskId, e, context)
            logError(taskId, errorMsg)
            // TASK-606 R5 (round 15): route through the shared funnel (its
            // WedgeAbortException arm carries the localized string; every
            // other error keeps the raw message this catch always sent).
            val userMsg = if (e is WedgeAbortException) {
                userFacingErrorMessage(context, e)
            } else {
                errorMsg
            }
            listener.onError(taskId, "PROCESSING_ERROR", userMsg, isShareRequest, false, duration)
            return Result.failure(e)
        } finally {
            if (backendOverride != null) {
                try {
                    backendManager.unloadActiveBackend()
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---- Subtitle Extraction ----

    /**
     * Handles `requestType == "subtitles"`: extract embedded subtitle text without loading
     * any ASR model, and fall back to the normal ASR path when extraction yields nothing.
     *
     * @return `Result.success(text)` when subtitle text was produced and reported to
     *         [listener]; `Result.failure(...)` when the fallback ASR path itself failed
     *         (already reported to [listener]); `null` to signal the caller to run the
     *         normal ASR path (extraction returned null/blank). The `null` sentinel keeps
     *         the fallback's listener.onSuccess/onError calls in ONE place (the caller's
     *         result.fold) instead of duplicating them here.
     */
    private suspend fun processSubtitleRequest(
        taskId: String,
        filePath: String?,
        trackIndex: Int,
        source: String?,
        sourcePackage: String?,
        isShareRequest: Boolean,
        startTime: Long,
        context: Context,
        cacheDir: File,
        listener: TranscriptionListener,
        coroutineScope: CoroutineScope,
        queuePosition: Int,
        queueTotal: Int,
        prompt: String = "",
        backendOverride: String?
    ): Result<String>? {
        if (filePath.isNullOrEmpty() || trackIndex < 0) {
            Log.w(TAG, "subtitle request missing filePath or trackIndex (filePath=$filePath, trackIndex=$trackIndex) — falling back to ASR")
            listener.onStatusUpdate(context.getString(R.string.subtitle_fallback_status))
            return null
        }

        val text = try {
            SubtitleExtractor.extractToText(filePath, trackIndex)
        } catch (e: Exception) {
            Log.w(TAG, "Subtitle extraction threw — falling back to ASR", e)
            null
        }

        if (text.isNullOrBlank()) {
            Log.w(TAG, "subtitle extraction null/blank for trackIndex=$trackIndex — falling back to ASR")
            listener.onStatusUpdate(context.getString(R.string.subtitle_fallback_status))
            return null
        }

        // Extraction succeeded: report exactly as the audio success path does, then return.
        val duration = System.currentTimeMillis() - startTime
        logSuccess(taskId, text, duration, isPartial = false, failedChunkCount = 0)
        listener.onSuccess(
            taskId,
            text,
            isShareRequest,
            sourcePackage,
            duration,
            confidence = null,
            detectedLanguage = null,
            isPartial = false,
            failedChunkCount = 0
        )
        return Result.success(text)
    }

    // ---- Backend Loading ----

    /**
     * The shared degrade path of the optional LLM passes (punctuation,
     * summary): a cancellation is rethrown so processRequest's dedicated
     * CancellationException handling keeps its contract; any other failure
     * logs and delivers the untouched transcript. An optional extra may
     * never fail a completed transcription.
     */
    private fun degradeTo(result: TranscriptionResult, passName: String, e: Throwable): TranscriptionResult {
        if (e is CancellationException) throw e
        Log.w(TAG, "$passName pass failed; delivering the transcript unchanged", e)
        return result
    }

    /**
     * TASK-276: the punctuation pass. Chains Gemma after a non-punctuating ASR
     * model (GigaAM today): the transcript is complete in hand, so loading the
     * LLM through the normal backend swap unloads the ASR model first and the
     * two are never resident together. Every skip path (mode, per-model flag,
     * the text's own punctuation, context limit, no Gemma configured) avoids
     * the swap entirely, and any failure degrades to the raw transcript: an
     * optional polish may never fail a completed transcription. The backend
     * that produced the text is the manager's active one at fold time (the
     * queue is serial, so nothing else has loaded since).
     */
    private suspend fun applyPunctuationPass(
        context: Context,
        asrBackendId: String,
        result: TranscriptionResult,
        listener: TranscriptionListener,
    ): TranscriptionResult {
        // The LLM's own ASR output is covered by the custom-prompt final pass
        // (ChunkPromptPolicy.plan); polishing it here would double-pass.
        if (asrBackendId == LlmTranscriptionBackend.BACKEND_ID) return result
        // Everything from here runs under runCatching: a preference read, a
        // backend swap, or a generation failure in an OPTIONAL polish must
        // never break the delivery of a finished transcript.
        return runCatching {
            val mode = PunctuationPolicy.modeFromPref(preferencesManager.punctuationMode.first())
            val modelPunctuates = backendRegistry.byBackendId(asrBackendId)?.punctuatesOutput ?: true
            if (!PunctuationPolicy.shouldRun(mode, modelPunctuates, result.text)) return@runCatching result
            if (!PunctuationPolicy.withinContextLimit(result.text)) {
                Log.i(TAG, "Punctuation pass skipped: ${result.text.length} chars exceeds the Gemma context guard")
                return@runCatching result
            }
            if (preferencesManager.modelPath.first().isNullOrBlank()) {
                Log.i(TAG, "Punctuation pass skipped: no Gemma model configured (delivering raw transcript)")
                return@runCatching result
            }
            listener.onStatusUpdate(context.getString(R.string.punctuation_status))
            ensureBackendLoaded(context, LlmTranscriptionBackend.BACKEND_ID).getOrThrow()
            val llm = backendManager.getActiveBackend() ?: error("LLM backend not active after load")
            val prompt = ChunkPromptPolicy.finalPrompt(
                PunctuationPolicy.effectivePrompt(
                    preferencesManager.punctuationPrompt.first(),
                    context.getString(R.string.punctuation_default_prompt)),
                result.text)
            val polished = llm.generateText(prompt).getOrThrow().trim()
            if (!PunctuationPolicy.acceptablePolish(polished, result.text)) {
                error("punctuation pass collapsed the transcript " +
                    "(${polished.length} vs ${result.text.length} chars); keeping the original")
            }
            val effectiveText = polished.ifBlank { result.text }
            result.copy(
                text = effectiveText,
                rawTranscript = if (effectiveText != result.text) result.text else null
            )
        }.fold(
            onSuccess = { polished ->
                if (polished !== result) {
                    Log.i(TAG, "Punctuation pass applied (${result.text.length} -> ${polished.text.length} chars)")
                }
                polished
            },
            onFailure = { e ->
                degradeTo(result, "Punctuation", e)
            },
        )
    }

    /**
     * TASK-121.4: the summary pass. Chains Gemma after a long transcription
     * (the punctuation pass, when it ran, has already left the LLM active)
     * and attaches a short summary as METADATA: the delivered transcript is
     * never replaced (content preservation is the app's prime directive).
     * Opt-in and skipped for short transcripts; every skip path (toggle,
     * length, context limit, no Gemma configured) avoids the swap entirely,
     * and any failure degrades to no summary: an optional extra may never
     * fail a completed transcription.
     */
    /**
     * TASK-520: map-reduce summary for transcripts past the context guard
     * (a 78-minute call is 60-80k chars; the single-shot pass would skip).
     * Runs AFTER the common gates and swap in [applySummaryPass], on the
     * already-loaded LLM. The TASK-498 retry ladder applies here too: each
     * instruction gets a full map-reduce attempt before the next runs.
     * Per-chunk failures degrade by dropping that partial; only a total
     * map failure (or the last attempt's reduce failure) fails the pass,
     * because an optional extra may never break the delivery.
     */
    private suspend fun summarizeLongTranscript(
        llm: TranscriptionBackend,
        instructions: List<String>,
        result: TranscriptionResult,
    ): TranscriptionResult {
        var lastFailure: Throwable? = null
        for ((attempt, instruction) in instructions.withIndex()) {
            if (attempt > 0) {
                Log.i(TAG, "Custom summary prompt did not produce an acceptable map-reduce summary; retrying with the built-in prompt")
            }
            val generation = summarizeWithMapReduce(llm, instruction, result.text, MAX_SUMMARY_LEVELS)
            lastFailure = generation.failure ?: lastFailure
            val summary = generation.summary
            if (summary != null && SummaryPolicy.acceptableSummary(summary, result.text)) {
                Log.i(TAG, "Summary map-reduce applied (${result.text.length} chars -> ${summary.length}-char summary)")
                return result.copy(summary = summary)
            }
        }
        // Match the single-shot tail: a THROWN generation (map or reduce)
        // fails the pass; completed-but-rejected output is the guards
        // verdict. The reason rides the result (TASK-494).
        lastFailure?.let { throw it }
        Log.w(TAG, "Summary map-reduce produced no acceptable summary after ${instructions.size} attempt(s); delivering without, reason recorded")
        return result.copy(summarySkipReason = SummaryPolicy.SKIP_REASON_GUARDS)
    }

    /**
     * Recursive map-reduce core, bounded by the level budget (not by
     * shrinkage: a near-copier model passes the 1.2x guard per chunk, so
     * growth is possible until the budget stops it). Null summary with a
     * null failure = completed but rejected (guards); null summary with a
     * failure = a generation crashed (failed). Cancellation always
     * propagates (the house contract): the map loop rethrows it instead
     * of recording it as a chunk failure.
     */
    private suspend fun summarizeWithMapReduce(
        llm: TranscriptionBackend,
        instruction: String,
        text: String,
        levelsLeft: Int,
    ): SummaryGeneration {
        // Single generation whenever the text fits the guard (the common
        // reduce case: joined partials are a fraction of the original).
        if (SummaryPolicy.withinContextLimit(text)) {
            val generated = llm.generateText(ChunkPromptPolicy.finalPrompt(instruction, text))
                .map { it.trim() }
            val candidate = generated.getOrNull()
            if (candidate != null && SummaryPolicy.acceptableSummary(candidate, text)) {
                return SummaryGeneration(candidate)
            }
            return SummaryGeneration(null, failure = generated.exceptionOrNull())
        }
        if (levelsLeft <= 0) return SummaryGeneration(null)
        val chunks = ContextChunker.split(text)
        Log.i(TAG, "Summary map stage: ${chunks.size} chunks (${text.length} chars)")
        val partials = mutableListOf<String>()
        var lastFailure: Throwable? = null
        for ((index, chunk) in chunks.withIndex()) {
            val generated = runCatching {
                llm.generateText(ChunkPromptPolicy.finalPrompt(instruction, chunk)).map { it.trim() }
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
            val candidate = generated.getOrNull()
            if (candidate != null && SummaryPolicy.acceptableSummary(candidate, chunk)) {
                partials += candidate
            } else {
                val failure = generated.exceptionOrNull()
                if (failure is EngineWedgeTimeoutException) {
                    // TASK-594 circuit breaker: a timeout means the engine is
                    // wedged, not that this chunk is hard; grinding the
                    // remaining chunks at one ceiling each turns a hang into
                    // a 40-70 minute crawl. Abort with what we have.
                    Log.w(TAG, "Summary map stage: generation timeout on chunk " +
                        "${index + 1}/${chunks.size}; aborting the attempt")
                    // No partial salvage: a first-chunk summary would pass the
                    // whole-transcript length guard and ship as if it covered
                    // the call (review F1). Null routes the timeout through the
                    // ladder's failure path to SKIP_REASON_FAILED instead.
                    return SummaryGeneration(null, failure = failure)
                }
                failure?.let { lastFailure = it }
                Log.w(TAG, "Summary map stage: chunk ${index + 1}/${chunks.size} produced no usable partial; continuing")
            }
        }
        if (partials.isEmpty()) return SummaryGeneration(null, failure = lastFailure)
        // A lone surviving partial cannot gain coverage from a reduce over
        // itself: return it and let the whole-transcript guard judge it.
        if (partials.size == 1) return SummaryGeneration(partials.first(), failure = lastFailure)
        return summarizeWithMapReduce(llm, instruction, partials.joinToString("\n\n"), levelsLeft - 1)
    }

    /** TASK-520: the map-reduce core's verdict (a summary, or why not). */
    private data class SummaryGeneration(
        val summary: String?,
        val failure: Throwable? = null,
    )

    /**
     * TASK-121.4: the summary pass at the transcribeAudio funnel, chained
     * after the punctuation pass. Every skip path (toggle off, short
     * length, no Gemma configured) avoids the backend swap entirely, and
     * any failure degrades to no summary: an optional extra may never
     * fail a completed transcription. Transcripts past the context guard
     * take the TASK-520 map-reduce branch below instead of skipping.
     */
    private suspend fun applySummaryPass(
        context: Context,
        result: TranscriptionResult,
        listener: TranscriptionListener,
    ): TranscriptionResult {
        // Everything from here runs under runCatching: a preference read, a
        // backend swap, or a generation failure in an OPTIONAL extra must
        // never break the delivery of a finished transcript.
        return runCatching {
            if (!preferencesManager.summarizeEnabled.first()) return@runCatching result
            if (!SummaryPolicy.needsSummary(result.text)) return@runCatching result
            // The no-Gemma skip runs BEFORE any branch that can swap the
            // backend (TASK-520 review: a >12k transcript with no model
            // configured used to enter the map-reduce load and unload the
            // working ASR backend on its way to a wrong skip reason).
            if (preferencesManager.modelPath.first().isNullOrBlank()) {
                Log.i(TAG, "Summary pass skipped: no Gemma model configured (delivering transcript without summary)")
                return@runCatching result.copy(summarySkipReason = SummaryPolicy.SKIP_REASON_NO_MODEL)
            }
            listener.onStatusUpdate(context.getString(R.string.summarize_status))
            // Same swap bracket as the punctuation pass: when it ran, the LLM
            // is already active and this is a no-op; when it did not, the ASR
            // model unloads first and the two are never resident together.
            ensureBackendLoaded(context, LlmTranscriptionBackend.BACKEND_ID).getOrThrow()
            val llm = backendManager.getActiveBackend() ?: error("LLM backend not active after load")
            // Language-aware by instruction: the curated default tells the
            // model to answer in the transcript's language (the app locale
            // is deliberately not resolved into the prompt). TASK-483: a
            // saved prompt overrides the built-in, same contract as the
            // punctuation pass (blank = built-in).
            val builtInInstruction = context.getString(R.string.summary_default_prompt)
            val customInstruction = preferencesManager.summaryPrompt.first().trim()
            // TASK-498: a custom-prompt attempt that fails (guard rejection
            // or a thrown generation error) retries ONCE with the built-in
            // instruction on the same loaded backend: the user still gets a
            // real recap instead of a caption. With no custom prompt the
            // built-in IS the first attempt and a failure stays one-shot.
            // TASK-520: the ladder applies to the map-reduce path too
            // (summarizeLongTranscript): long transcripts keep the retry.
            val instructions = buildList {
                add(SummaryPolicy.effectivePrompt(customInstruction, builtInInstruction))
                // A saved prompt identical to the built-in text must not run
                // the same generation twice.
                if (customInstruction.isNotEmpty() && customInstruction != builtInInstruction) {
                    add(builtInInstruction)
                }
            }
            if (!SummaryPolicy.withinContextLimit(result.text)) {
                // TASK-520: too long for ONE generation, not too long to
                // summarize: map over context-sized chunks, reduce the
                // partials. Falls back to the recorded skip only when the
                // map stage dies entirely (see summarizeLongTranscript).
                Log.i(TAG, "Summary pass: ${result.text.length} chars exceeds the context guard; map-reduce over chunks")
                return@runCatching summarizeLongTranscript(
                    llm = llm, instructions = instructions, result = result)
            }
            var summary: String? = null
            var generationFailure: Throwable? = null
            var lastCandidateChars = -1
            for ((attempt, instruction) in instructions.withIndex()) {
                if (attempt > 0) {
                    Log.i(TAG, "Custom summary prompt did not produce an acceptable summary; retrying with the built-in prompt")
                }
                val generated = llm.generateText(
                    ChunkPromptPolicy.finalPrompt(instruction, result.text)
                ).map { it.trim() }
                // Log every thrown attempt when it happens: the loop below
                // overwrites generationFailure, and a first crash the retry
                // rescued must not vanish from logcat.
                generated.exceptionOrNull()?.let {
                    Log.w(TAG, "Summary generation attempt ${attempt + 1} of ${instructions.size} failed", it)
                }
                val candidate = generated.getOrNull()
                if (candidate != null) lastCandidateChars = candidate.length
                if (candidate != null && SummaryPolicy.acceptableSummary(candidate, result.text)) {
                    summary = candidate
                    break
                }
                generationFailure = generated.exceptionOrNull()
            }
            if (summary != null) return@runCatching result.copy(summary = summary)
            // A thrown generation error on the LAST attempt is a failed
            // pass (the outer fold degrades with SKIP_REASON_FAILED); a
            // completed-but-rejected output is the guards verdict.
            generationFailure?.let { throw it }
            // TASK-494: not an error; the reason rides the result instead
            // of dying in logcat. The length detail separates a stutter-short
            // rejection from a rewrite-long one during triage.
            Log.w(TAG, "Summary rejected by the guards after ${instructions.size} attempt(s) " +
                "(last candidate $lastCandidateChars chars vs transcript ${result.text.length}); delivering without, reason recorded")
            return@runCatching result.copy(summarySkipReason = SummaryPolicy.SKIP_REASON_GUARDS)
        }.fold(
            onSuccess = { withSummary ->
                if (withSummary.summary != null) {
                    Log.i(TAG, "Summary pass applied (${result.text.length} chars -> ${withSummary.summary.length}-char summary)")
                }
                withSummary
            },
            onFailure = { e ->
                // TASK-494: an attended attempt that died mid-generation is
                // as invisible as a guard rejection; record it too, then
                // degrade as before.
                degradeTo(result.copy(summarySkipReason = SummaryPolicy.SKIP_REASON_FAILED), "Summary", e)
            },
        )
    }

    private suspend fun ensureBackendLoaded(
        context: Context,
        backendOverride: String? = null
    ): Result<Unit> {
        val hasBackend = backendManager.hasActiveBackend()
        val activeBackend = backendManager.getActiveBackend()
        val backendReady = activeBackend?.isReady() ?: false
        val preferredBackendId = backendOverride ?: preferencesManager.transcriptionBackend.first()
        val activeBackendId = activeBackend?.id
        val backendMismatch = hasBackend && activeBackendId != preferredBackendId
        // TASK-626: the backend id alone is not a residency identity. Switching
        // variant within one entry (useModel) or writing the saved path directly
        // (TEST_SPI) keeps the id and swaps the model: a warm backend would keep
        // decoding with the OLD recognizer while the UI names the new variant.
        val variantMismatch = !backendMismatch && variantChanged(activeBackend, preferredBackendId)

        if (!hasBackend || !backendReady || backendMismatch || variantMismatch) {
            Log.i(TAG, "Backend needs (re)load (hasBackend=$hasBackend, ready=$backendReady, active=$activeBackendId, preferred=$preferredBackendId, variantMismatch=$variantMismatch)")

            if (hasBackend) {
                Log.i(TAG, "Unloading previous backend: $activeBackendId")
                backendManager.unloadActiveBackend()
            }

            // Sherpa-onnx consolidation: the load dispatch keys on the registry
            // descriptor (the catalog entry id); every built-in model goes through
            // the one generic [loadCatalogBackend]. Unknown ids yield a null
            // descriptor and fall through to the LLM loader, exactly as the
            // former ModelType-keyed when did.
            // External ids are intercepted BEFORE the registry lookup for a behavioral
            // reason, not a registry gap: a prefix-matched id whose record is gone must
            // fail fast with ExternalModelUnavailable instead of falling through to the
            // LLM loader (pinned by the unknown-external-id override test).
            val loadResult = if (preferredBackendId.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX)) {
                loadExternalBackend(context, preferredBackendId)
            } else when (preferredBackendId) {
                // The LLM backend ("llm") stores its model in the generic preference.
                LlmTranscriptionBackend.BACKEND_ID -> loadLlmBackend(context)
                else -> backendRegistry.byBackendId(preferredBackendId)?.let { descriptor ->
                    loadCatalogBackend(context, descriptor)
                } ?: loadLlmBackend(context)
            }

            loadResult.fold(
                onSuccess = {
                    Log.i(TAG, "Backend auto-loaded successfully: $preferredBackendId")
                    val timeout = preferencesManager.keepAliveTimeout.first()
                    backendManager.setKeepAliveTimeout(timeout)
                },
                onFailure = { return Result.failure(it) }
            )
        }

        return Result.success(Unit)
    }

    /**
     * TASK-626: true when the warm [activeBackend] holds a different model
     * than the saved path names for [preferredBackendId] (same id, different
     * variant). The saved path is the single source every writer converges on
     * (useModel persists the variant choice; [loadCatalogBackend] persists
     * its resolution), so this one comparison covers them all. A blank side
     * is no identity claim, not a mismatch: every shipped backend reports a
     * concrete path while ready (unload nulls path and readiness together),
     * and a blank saved path means auto-resolution, which the loader
     * persists so the mismatch cannot flap. One corner keeps serving warm
     * by design: a variant DELETED while its backend stays warm leaves the
     * saved path blank (deleteModel clears it) and the resident engine
     * answers until idle-unload, exactly as before this fix.
     *
     * The expected side deliberately does NOT reuse [modelPathForBackend]:
     * its unknown-id fallback answers the GENERIC Gemma path, which would
     * compare an unrelated preference against e.g. an external engine whose
     * record is mid-deletion (registry descriptor already gone) and
     * manufacture a reload whose only outcome is failing a request the old
     * code served warm. Unknown-to-the-registry ids carry no path identity.
     */
    private suspend fun variantChanged(
        activeBackend: TranscriptionBackend?,
        preferredBackendId: String
    ): Boolean {
        val loaded = activeBackend?.getModelPath()?.takeIf { it.isNotBlank() } ?: return false
        val expected = when {
            preferredBackendId == LlmTranscriptionBackend.BACKEND_ID ->
                preferencesManager.modelPath.first()
            else -> backendRegistry.byBackendId(preferredBackendId)
                ?.modelPathFlow?.invoke(preferencesManager)?.first()
        } ?: return false
        return expected.isNotBlank() && expected != loaded
    }

    private suspend fun loadLlmBackend(context: Context): Result<Unit> {
        val modelPath = preferencesManager.modelPath.first()
        if (modelPath.isNullOrBlank()) {
            return Result.failure(TranscriptionException.NotInitialized())
        }
        return backendManager.setActiveBackend(
            backendId = LlmTranscriptionBackend.BACKEND_ID,
            context = context,
            config = BackendConfig.LiteRTConfig(modelPath = modelPath)
        )
    }

    private suspend fun loadCatalogBackend(context: Context, descriptor: BackendDescriptor): Result<Unit> {
        val entry = BundledCatalog.byId(descriptor.backendId)
            ?: return Result.failure(TranscriptionException.NotInitialized())
        // The saved path is the user's explicit variant choice (useModel(variant)): honor
        // it when it still exists. Only fall back to auto-resolution when the saved path
        // is blank or its directory is gone (deleted, cleaner).
        val savedPath = descriptor.modelPathFlow(preferencesManager).first()
        val resolvedPath = when {
            !savedPath.isNullOrBlank() && File(savedPath).isDirectory -> savedPath
            else -> SherpaModelManager.of(entry.id).resolveActiveModelPath(context, fallbackPath = savedPath)
        } ?: return Result.failure(TranscriptionException.NotInitialized())
        // Persist the resolved path so the rest of the app (UI, benchmark) sees a valid path.
        if (resolvedPath != savedPath) {
            descriptor.saveModelPath(preferencesManager, resolvedPath)
        }
        // Language wiring is catalog data: languageOption (online Nemotron) passes "auto"
        // or a code per stream; passLanguage (offline Whisper) maps "auto" to "" so the
        // model auto-detects and passes a concrete code through; everything else gets "".
        // Single-language variants (Distil-IT) are forced later in SherpaBackend, which
        // keeps winning over this resolution.
        val languagePref = preferencesManager.transcriptionLanguage.first()
        val language = TranscriptionLanguagePolicy.resolveForEntry(
            // TASK-547: the phone-locale pin needs the DEVICE locale (the
            // system one, not the app locale); LocaleManager owns that read.
            phoneLanguage = com.antivocale.app.util.LocaleManager.phoneLanguage(context),
            entry = entry,
            preference = languagePref,
        )
        val label = when (val d = entry.display) {
            is CatalogDisplay.Resource -> context.getString(CatalogStringKeys.resolve(d.key))
            is CatalogDisplay.Literal -> d.text
        }
        return configureSherpaBackend(
            backendId = descriptor.backendId,
            modelPath = resolvedPath,
            label = label,
            language = language,
            context = context,
            modelType = entry.modelType,
        )
    }

    /**
     * GH #43 (design D3/D6): phase 1 of a two-pass run. Resolves the fast
     * streaming backend through [DualRefinementPolicy], loads it, and runs
     * the normal audio path with a listener that only forwards progress.
     * The first-pass text is force-written to the row (unthrottled, the
     * persistPipelineFailureContext salvage precedent) so it stays visible
     * while phase 2 refines. Returns null whenever the pass does not apply
     * or failed: the request then proceeds single-model exactly as today.
     */
    private suspend fun runRefinementFirstPass(
        taskId: String,
        requestType: String,
        backendOverride: String?,
        filePath: String?,
        prompt: String,
        queuePosition: Int,
        queueTotal: Int,
        context: Context,
        cacheDir: File,
        listener: TranscriptionListener,
        coroutineScope: CoroutineScope,
        onSkipped: (token: String, loopMetrics: String?) -> Unit = { _, _ -> },
    ): FirstPassOutcome? {
        val fastId = DualRefinementPolicy.fastBackendFor(
            requestType = requestType,
            backendOverride = backendOverride,
            refinementEnabled = preferencesManager.refinementEnabled.first(),
            selectedBackendId = preferencesManager.transcriptionBackend.first(),
            streamingBackendId = installedStreamingBackendId(context),
        ) ?: return null

        // F1: a fast-model load failure skips phase 1 silently; the run
        // degrades to single-model (the skip token rides the context).
        val load = ensureBackendLoaded(context, fastId)
        if (load.isFailure) {
            Log.i(TAG, "Fast first pass skipped (load failed): ${load.exceptionOrNull()?.message}")
            onSkipped(DualRefinementPolicy.SKIP_FAST_LOAD_FAILED, null)
            return null
        }
        // The base listener is passed as-is: processAudioRequest reports
        // failures as return values and never calls the funnel callbacks, so
        // plain delegation (which an empty wrapper reduced to) is identity.
        val result = processAudioRequest(
            taskId = taskId, filePath = filePath, prompt = prompt,
            queuePosition = queuePosition, queueTotal = queueTotal,
            context = context, cacheDir = cacheDir,
            listener = listener, coroutineScope = coroutineScope,
        )
        val outcome = result.getOrNull()?.takeIf { it.text.isNotBlank() }
        if (outcome == null) {
            // F2/F3: mid-stream death or a blank pass. The TASK-568 machinery
            // salvaged any partial onto the row; phase 2 supersedes it.
            Log.i(TAG, "Fast first pass produced no usable text; single-model run")
            onSkipped(DualRefinementPolicy.SKIP_FAST_BLANK, null)
            return null
        }
        // TASK-579 (AC2, the other direction): a budget-filling loop from the
        // fast pass must not ride the row as the first pass nor survive as
        // the F4/F5 fallback text; the run degrades to single-model instead.
        val fastLoop = RepetitionLoopDetector.detect(outcome.text)
        if (fastLoop != null) {
            val loopMetrics = fastLoop.metrics()
            Log.i(TAG, "Fast first pass repetition loop (${fastLoop.reason} $loopMetrics); single-model run")
            onSkipped(DualRefinementPolicy.SKIP_FAST_LOOP, loopMetrics)
            return null
        }
        // Force-write the complete first-pass text so the row shows it while
        // PROCESSING (the interim throttle would otherwise sit on it), and
        // seed the crash-recovery state with it (review F3): phase 2 runs
        // with interim writes suppressed, so without a fresh seed the state
        // goes stale and the interruption dialog misfires mid-run.
        runCatching {
            logDao.updateInterimResult(taskId, outcome.text, isPartial = true)
            preferencesManager.savePartialTranscriptionState(outcome.text)
        }
        return FirstPassOutcome(
            text = outcome.text,
            // Belt-and-braces stamp: today all four audio assembly paths
            // stamp backendId themselves and no backend ever supplies its
            // own ProcessingContext, so this is normally a same-value copy;
            // a future arm that forgets the stamp still credits the fast
            // model here. No test pins the four stamps yet (TASK-601
            // residue, with TASK-595's harness).
            processing = (outcome.processing ?: ProcessingContext(decodePath = "whole_file"))
                .copy(backendId = fastId),
            confidence = outcome.confidence,
            detectedLanguage = outcome.detectedLanguage,
            segments = outcome.segments,
            isPartial = outcome.isPartial,
            failedChunkCount = outcome.failedChunkCount,
        )
    }

    /**
     * GH #83: the optional speaker-labeling pass over a completed audio
     * result. Downloads the two models on first use, runs the diarization
     * engine on the concatenated preprocessed chunks (the cues' own
     * timeline), and labels each cue by speech-time voting with the
     * 2-speaker "phone call" preset. Additive only: any failure logs and
     * returns the input unchanged.
     */
    private suspend fun applySpeakerLabels(
        context: Context,
        result: Result<TranscriptionResult>,
        chunks: List<FloatArray>,
        sampleRate: Int,
        listener: TranscriptionListener,
    ): Result<TranscriptionResult> {
        val transcription = result.getOrNull() ?: return result
        if (transcription.segments.isEmpty()) return result
        // The failure arm recovers OUT HERE (fold, the punctuation pass's
        // shape): a .map recovery inside runCatching would never run, and
        // the failure would surface as INFERENCE_ERROR on a transcript
        // that already succeeded. Cancellation rethrows through degradeTo;
        // everything else degrades to the unlabeled result. The status
        // precedes the first-use download so the row never sits silent
        // behind ~47 MB.
        return runCatching {
            listener.onStatusUpdate(context.getString(R.string.speaker_labels_status))
            DiarizationModels.ensureDownloaded(context).getOrThrow()
            val threads = preferencesManager.threadCount.first()
            // The preprocessor's own merge keeps this copy's memory-peak
            // contract pinned with its tests (code review F8); the list is
            // dead after the merge, so its destructive clear is fine.
            val samples = audioPreprocessor.mergeAndResample(
                chunks.toMutableList(), sampleRate).first
            val diarizer = SpeakerDiarizer.create(
                segmentationModel = DiarizationModels.segmentationFile(context),
                embeddingModel = DiarizationModels.embeddingFile(context),
                numThreads = threads,
            ).getOrThrow()
            try {
                val segments = diarizer.diarize(samples, sampleRate)
                val labels = SpeakerLabeler.label(transcription.segments, segments)
                result.map { unlabeled ->
                    unlabeled.copy(
                        segments = unlabeled.segments.mapIndexed { index, cue ->
                            cue.copy(speaker = labels[index])
                        })
                }
            } finally {
                diarizer.release()
            }
        }.fold(
            onSuccess = { it },
            onFailure = { failure ->
                Result.success(degradeTo(transcription, "Speaker labels", failure))
            },
        )
    }

    /**
     * TASK-579: the phase-2 success arm of the dual fold. A refinement
     * that completed in a repetition loop (RepetitionLoopDetector) must
     * not replace the good first pass; it delivers through the same
     * F4/F5 funnel with the loop skip token. A clean refinement carries
     * the first pass alongside, as before. Extracted for the fold-arm
     * tests: the full success path needs a real phase-2 load.
     */
    internal fun refinementFoldSuccess(
        fastFirstPass: FirstPassOutcome,
        refined: TranscriptionResult,
    ): Result<TranscriptionResult> {
        val loop = RepetitionLoopDetector.detect(refined.text)
        val refineLoopMetrics = loop?.metrics()
        return if (loop == null) {
            Result.success(refined.copy(firstPass = fastFirstPass))
        } else {
            recoverFirstPass(
                fastFirstPass,
                IllegalStateException("refinement repetition loop: ${loop.reason} $refineLoopMetrics"),
                DualRefinementPolicy.SKIP_REFINE_LOOP,
                loopMetrics = refineLoopMetrics,
            )
        }
    }

    /**
     * F4/F5 and the TASK-579 loop arm: phase 2 is dead, or completed with
     * unusable (looping) output, while the first pass completed. Build the
     * first-pass result that flows the normal funnel (punctuation, summary,
     * one notification); Cancellation keeps its contract.
     */
    private fun recoverFirstPass(
        firstPass: FirstPassOutcome,
        failure: Throwable,
        skipToken: String,
        loopMetrics: String? = null,
    ): Result<TranscriptionResult> {
        if (failure is kotlinx.coroutines.CancellationException) {
            throw failure
        }
        Log.w(TAG, "Refinement failed; delivering first pass (${failure.message})")
        return Result.success(
            TranscriptionResult(
                text = firstPass.text,
                processing = firstPass.processing,
                confidence = firstPass.confidence,
                detectedLanguage = firstPass.detectedLanguage,
                segments = firstPass.segments,
                // A partially-decoded first pass is delivered as partial:
                // the notification title and logSuccess must not claim a
                // complete transcript (guard-review finding).
                isPartial = firstPass.isPartial,
                failedChunkCount = firstPass.failedChunkCount,
                firstPass = firstPass.copy(
                    refinementFailedToken = skipToken,
                    refinementLoopMetrics = loopMetrics,
                ),
            )
        )
    }

    /** The installed streaming catalog entry id, when one resolves locally.
     *  TASK-603 F3: delegates to the shared owner (Settings derives the
     *  toggle's availability from the same probe). */
    private suspend fun installedStreamingBackendId(context: Context): String? =
        SherpaModelManager.installedStreamingEntryId(context)

    /** GH #43: nests the first pass's context and the skip token on the
     *  delivered row's (phase 2) context; a no-op for single-model runs. */
    private fun ProcessingContext?.withRefinement(
        refinedFrom: ProcessingContext?,
        skipReason: String?,
        loopMetrics: String? = null,
    ): ProcessingContext? {
        if (this == null && refinedFrom == null && skipReason == null) return this
        return (this ?: ProcessingContext(decodePath = "unknown")).copy(
            refinementPhase = refinedFrom,
            refinementSkipReason = skipReason,
            // Future-proofing only: today both metrics sources are already
            // loop-token-exclusive by construction; if a future skip token
            // ever carries metrics, this guard must grow with it.
            refinementLoopMetrics = loopMetrics?.takeIf {
                skipReason == DualRefinementPolicy.SKIP_FAST_LOOP ||
                    skipReason == DualRefinementPolicy.SKIP_REFINE_LOOP
            },
        )
    }

    private fun availableMemoryBytes(context: Context): Long =
        MemoryReadings.availableRamBytes(context) ?: 0L

    private fun formatMb(bytes: Long): String = "${bytes / MB}MB"

    /** On-disk footprint of a model path: a single file (Gemma .litertlm) or a directory tree. */
    private fun modelSizeBytes(path: File): Long =
        if (path.isFile) path.length()
        else path.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Shared pre-flight + preference resolution + backend activation body.
     *
     * Validates the model directory, runs the OOM memory pre-flight gated by
     * the opt-in [memoryProtection] preference, resolves thread count and
     * inference provider, then calls
     * [backendManager.setActiveBackend] with the [configBlock] lambda.
     *
     * Shared by [configureSherpaBackend] (static sherpa-onnx backends) and
     * [loadExternalBackend] (imported external models).
     */
    private suspend fun configureBackend(
        backendId: String,
        label: String,
        modelDir: File,
        context: Context,
        configBlock: (threadCount: Int, provider: String) -> BackendConfig,
    ): Result<Unit> {
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return Result.failure(IllegalStateException("$label model directory not found: ${modelDir.absolutePath}"))
        }

        // Pre-flight memory check: refuse to load if free memory is below the model size + headroom.
        // TASK-631: runs ONLY when the opt-in memoryProtection preference is on; off (the default)
        // the app always attempts the load and never refuses on its own (two healthy-device
        // misfires). availMem is a coarse predictor (lmkd uses PSI + oom_score_adj, not a literal
        // MemAvailable comparison); the headroom absorbs inference overhead and reclaimable-cache
        // noise.
        // TASK-575: A0 sampled unconditionally (the measurement is wanted even
        // when protection is off); provider/threads are
        // part of the measurement key (same model under NNAPI vs CPU is a
        // different footprint).
        val resolvedProviderPref = InferenceProvider.resolve(preferencesManager.inferenceProvider.first())
        val threadCountPref = preferencesManager.threadCount.first()
        val availBeforeLoad = availableMemoryBytes(context)
        val memoryProtectionOn = preferencesManager.memoryProtection.first()
        if (memoryProtectionOn) {
            val availBytes = availBeforeLoad
            // Fail open if we could not read available memory (e.g. no ActivityManager service in
            // a test/local context): blocking on an unknown value would regress those contexts and
            // offer no real protection. Only compute the model size and compare when we have a
            // concrete measurement. This also avoids touching the filesystem (walkTopDown) when the
            // measurement is unavailable.
            if (availBytes > 0) {
                // TASK-575 / GH #106: a measured record (from a previous
                // successful load on this device) replaces the disk-size
                // estimate: the #63 over-refusal was exactly this estimate
                // overshooting by ~1GB. The size walk runs only when the
                // estimate branch needs it.
                val measuredKey = memoryKey(backendId, modelDir, resolvedProviderPref, threadCountPref)
                val measured = preferencesManager.measuredModelMemory.first()[measuredKey]
                val requiredBytes = if (measured != null) {
                    val r = MeasuredModelMemory.requiredBytes(measured, MEMORY_HEADROOM_BYTES)
                    Log.i(TAG, "Pre-flight uses the measured footprint for $label: required=${r / MB}MB (loadDelta=${measured.maxLoadDeltaBytes / MB}MB over ${measured.runs} run(s))")
                    r
                } else {
                    modelSizeBytes(modelDir) + MEMORY_HEADROOM_BYTES
                }
                if (shouldRefuseForMemory(memoryProtectionOn, availBytes, requiredBytes)) {
                    Log.w(TAG, "Blocking $label load: avail=${availBytes / MB}MB < required=${requiredBytes / MB}MB (basis=${if (measured != null) "measured" else "size+headroom"}, headroom=${MEMORY_HEADROOM_BYTES / MB}MB)")
                    return Result.failure(TranscriptionException.InsufficientMemory(
                        context.getString(R.string.model_load_low_memory, formatMb(availBytes), formatMb(requiredBytes))
                    ))
                }
            }
        } else {
            // TASK-631 part 2: the default path never blocks, but a tight
            // margin warns once per model identity.
            maybeWarnTightMargin(context, label, backendId, modelDir, resolvedProviderPref, threadCountPref, availBeforeLoad)
        }
        Log.i(TAG, "Auto-loading $label model from: ${modelDir.absolutePath}")
        Log.i(TAG, "Inference provider: resolved=$resolvedProviderPref")
        // TASK-640: arm the crash marker around backendManager.setActiveBackend,
        // whose window includes both the PREVIOUS backend's native unload and the
        // new model's native init (an unload crash would over-quarantine the new
        // id: accepted, splitting unload from init is a manager-level change).
        // External ids only: that is the class CrashQuarantineCheck acts on.
        // The clear is NonCancellable: a task-swipe cancellation must still clear
        // the marker, or the next cold start would falsely quarantine a healthy
        // model. Scope: sherpa loads only; the LLM and benchmark loads are
        // unarmed by design (their quarantine is not specified).
        val armed = backendId.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX)
        if (armed) preferencesManager.savePendingBackendLoad(backendId)
        val loadResult = try {
            backendManager.setActiveBackend(
                backendId = backendId,
                context = context,
                config = configBlock(threadCountPref, resolvedProviderPref),
            )
        } finally {
            if (armed) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                preferencesManager.savePendingBackendLoad(null)
            }
        }
        // TASK-575: record the measured footprint of a successful load so the
        // next pre-flight uses it instead of the disk-size estimate. Failures
        // here never fail the load itself. The merge runs inside the storage
        // transaction (concurrent loads must not lose the max) and warm
        // no-op loads (delta ~0, e.g. after a benchmark warmed the backend
        // singleton) never create or strengthen a record (review F1/F5).
        if (loadResult.isSuccess && availBeforeLoad > 0) {
            runCatching {
                val availAfter = availableMemoryBytes(context)
                if (availAfter > 0) {
                    preferencesManager.mergeMeasuredModelMemorySample(
                        key = memoryKey(backendId, modelDir, resolvedProviderPref, threadCountPref),
                        loadDeltaBytes = availBeforeLoad - availAfter,
                        modelSizeBytes = modelSizeBytes(modelDir),
                    )
                    pruneMeasuredMemoryRecords()
                    Log.i(TAG, "Measured $label footprint: loadDelta=${(availBeforeLoad - availAfter) / MB}MB")
                }
            }
        }
        return loadResult
    }

    /**
     * TASK-575 (review F3): drops records whose model dir is gone (versioned
     * catalog dirs are deleted on update; the record must not outlive them).
     */
    private suspend fun pruneMeasuredMemoryRecords() {
        val records = preferencesManager.measuredModelMemory.first()
        val valid = records.keys.filterTo(mutableSetOf()) { key ->
            MeasuredModelMemory.pathOfKey(key)?.let { File(it).exists() } == true
        }
        if (valid.size != records.size) {
            preferencesManager.pruneMeasuredModelMemory(valid)
        }
    }

    /**
     * TASK-575: the per-model key for the measured-footprint records. The
     * provider and thread count are part of the identity: the same model
     * under a different provider (NNAPI driver buffers vs CPU arena, issue
     * #26) has a different footprint (review F2).
     */

    /**
     * TASK-575: the per-model key for the measured-footprint records. The
     * provider and thread count are part of the identity: the same model
     * under a different provider (NNAPI driver buffers vs CPU arena, issue
     * #26) has a different footprint (review F2).
     */
    private fun memoryKey(backendId: String, modelDir: File, provider: String, threadCount: Int): String =
        backendId + '@' + provider + '@' + threadCount + '@' + modelDir.absolutePath

    /**
     * TASK-631 part 2: the default path's dismissable tight-margin warning.
     * Never blocks; posts ONCE per model identity per process (swiping the
     * notification away does not re-arm: the dedup key is the identity, not
     * the notification's lifetime). The measured footprint is preferred,
     * mirroring the opt-in branch; the size walk is a few stat() calls.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun maybeWarnTightMargin(
        context: Context,
        label: String,
        backendId: String,
        modelDir: File,
        provider: String,
        threads: Int,
        availBytes: Long,
    ) {
        if (availBytes <= 0) return
        val key = memoryKey(backendId, modelDir, provider, threads)
        // Dedup first: a repeat load of the same identity skips both the
        // preference read and the size work entirely.
        if (key in warnedMarginKeys) return
        // Measured-basis ONLY (review finding): the disk-size estimate is the
        // figure this file documents as overshooting ~1GB (GH #63/#106); the
        // first load of an identity has no record yet and must not cry wolf
        // on healthy devices. After one successful load the record exists and
        // the warning speaks from measured data.
        val measured = preferencesManager.measuredModelMemory.first()[key] ?: return
        val requiredBytes = MeasuredModelMemory.requiredBytes(measured, MEMORY_HEADROOM_BYTES)
        if (availBytes < requiredBytes && warnedMarginKeys.add(key)) {
            Log.w(TAG, "Tight memory margin for $label (avail=${availBytes / MB}MB < required=${requiredBytes / MB}MB); posting the dismissable warning")
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.notify(
                    MEMORY_MARGIN_WARNING_ID,
                    ResultNotificationFactory(context).alertNotification(
                        title = context.getString(R.string.memory_margin_warning_title),
                        text = context.getString(R.string.memory_margin_warning_body, label),
                    ))
            }.onFailure { Log.w(TAG, "Could not post the memory-margin warning", it) }
        }
    }

    /** TASK-631 part 2: one warning per model identity per process. */
    private val warnedMarginKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Sherpa-onnx backend loader: validates the model dir, delegates to the shared
     * [configureBackend] for memory pre-flight and preference resolution, and builds
     * a [BackendConfig.SherpaOnnxConfig].
     */
    private suspend fun configureSherpaBackend(
        backendId: String,
        modelPath: String,
        label: String,
        language: String = "",
        context: Context,
        modelType: String = "nemo_transducer"
    ): Result<Unit> {
        return configureBackend(
            backendId = backendId,
            label = label,
            modelDir = File(modelPath),
            context = context,
        ) { threadCount, provider ->
            BackendConfig.SherpaOnnxConfig(
                modelDir = modelPath,
                modelType = modelType,
                numThreads = threadCount,
                language = language,
                provider = provider,
            )
        }
    }

    /**
     * Loads an external (user-imported) model by resolving the record from the store.
     * The [backendId] must carry [ExternalModelRecord.BACKEND_ID_PREFIX] with the record UUID after it.
     */
    private suspend fun loadExternalBackend(context: Context, backendId: String): Result<Unit> {
        val record = externalModelStore.byId(backendId.removePrefix(ExternalModelRecord.BACKEND_ID_PREFIX))
            ?: run {
                Log.w(TAG, "no external model record for $backendId")
                return Result.failure(TranscriptionException.ExternalModelUnavailable(backendId))
            }
        return configureBackend(
            backendId = record.backendId,
            label = record.displayName,
            modelDir = File(record.dir),
            context = context,
        ) { threadCount, provider ->
            BackendConfig.ExternalConfig(
                record = record,
                numThreads = threadCount,
                provider = provider,
            )
        }
    }

    // ---- Text Processing ----

    private suspend fun processTextRequest(prompt: String): Result<TranscriptionResult> {
        if (prompt.isEmpty()) {
            return Result.failure(IllegalArgumentException("Empty prompt provided"))
        }
        val backend = backendManager.getActiveBackend()
            ?: return Result.failure(IllegalStateException("No active backend"))
        if (!backend.supportsText) {
            return Result.failure(IllegalStateException(
                "Current backend (${backend.displayName}) does not support text generation. Switch to LLM backend in Settings."
            ))
        }
        return backend.generateText(prompt).map { text -> TranscriptionResult(text = text) }
    }

    // ---- Audio Processing ----

    private suspend fun resolvePrompt(prompt: String): String {
        val savedDefaultPrompt = preferencesManager.defaultPrompt.first()
        return prompt.ifEmpty {
            savedDefaultPrompt.ifEmpty { ChunkPromptPolicy.DEFAULT_AUDIO_PROMPT }
        }
    }

    private suspend fun processAudioRequest(
        taskId: String,
        filePath: String?,
        prompt: String = "",
        queuePosition: Int,
        queueTotal: Int,
        context: Context,
        cacheDir: File,
        listener: TranscriptionListener,
        coroutineScope: CoroutineScope,
        /** GH #43: phase 2 of a two-pass run passes false so the accurate
         *  pass's growing partials never overwrite the complete first-pass
         *  text already on the row (design D6: the text never regresses). */
        emitInterim: Boolean = true,
        /** GH #83: when non-null, invoked once with the preprocessed chunks
         *  and their sample rate (contiguous-timeline runs only) for the
         *  speaker-labeling pass. Streaming and VAD-segmented runs never
         *  invoke it and the pass is skipped for them. */
        collectSamples: ((List<FloatArray>, Int) -> Unit)? = null,
    ): Result<TranscriptionResult> {
        if (filePath.isNullOrEmpty()) {
            return Result.failure(IllegalArgumentException("No file path provided"))
        }

        val backend = backendManager.getActiveBackend()
            ?: return Result.failure(IllegalStateException("No active backend"))

        if (!backend.isAudioSupported()) {
            return Result.failure(IllegalStateException(
                "${backend.displayName} does not support audio transcription"
            ))
        }

        // Read settings. TASK-370 forced VAD-aligned segmentation for the llm
        // backend (mid-word cuts garble Gemma chunks); TASK-408 moved the flag
        // onto the backend interface and canary sets it too (mid-speech cuts
        // make half its chunks decode empty, measured on desktop).
        // TASK-545: the raw toggle for the run's processing context (the
        // effective decision is decodePath; see ProcessingContext).
        val vadRequested = preferencesManager.vadEnabled.first()
        val vadEnabled = vadRequested || backend.requiresVadAlignedChunking
        val threadCount = preferencesManager.threadCount.first()
        val providerPref = preferencesManager.inferenceProvider.first()
        val resolvedProvider = InferenceProvider.resolve(providerPref)
        val progressiveEnabled = preferencesManager.progressiveTranscription.first()

        // Resolve prompt: request → settings → fallback. TASK-370: multi-chunk
        // routing lives in ChunkPromptPolicy (plain instruction per chunk,
        // custom prompt once at the end).
        val resolvedPrompt = resolvePrompt(prompt)
        val promptPlan = ChunkPromptPolicy.plan(backend.id, resolvedPrompt)

        // Use streaming pipeline for multi-chunk non-VAD scenarios
        // TASK-406: the catalog cap is tightened to what free RAM can hold (attention
        // peak grows with the square of chunk length; both chunk paths below resolve
        // their sizes from this value).
        val maxChunkDuration = backend.maxChunkDurationSeconds?.let { cap ->
            // avail first: the model-size walk is skipped when memory is unreadable,
            // preserving the TASK-314 rule that the filesystem is not touched when
            // the measurement is unavailable.
            val availBytes = availableMemoryBytes(context)
            val modelSize = if (availBytes <= 0) 0L
                else modelPathForBackend(backend.id).takeIf { it.isNotBlank() }
                    ?.let { modelSizeBytes(File(it)) } ?: 0L
            // TASK-475: per-family overhead. The modelType signals the
            // architecture: whisper's cross-attention needs ~2320 MiB vs a
            // transducer's ~900 (both calibrated on device). Unknown types
            // keep the conservative transducer value.
            // Resolve from the BACKEND (not BundledCatalog: external imports
            // carry external: ids absent from the built-in catalog). Built-in
            // whisper backends are the known id; externals expose their family
            // through the ExternalSherpaBackend's configured family.
            val memoryFamily = when {
                backend.id == "whisper" -> TranscriptionMemoryPolicy.Family.WHISPER
                // MOONSHINE rides the whisper curve too (GH #89): an
                // encoder/decoder attention model with whisper-shaped memory
                // behavior, and the flat transducer baseline under-refused
                // exactly the light models the family exists for (a 953MB
                // moonshine passed a bar the whisper curve refuses). The
                // curve still PRICES the 30s whisper window though moonshine
                // chunks at 8s (TASK-619): deliberate conservatism, no
                // moonshine RAM measurement exists, and the 600MB overhead
                // floor dominates the term anyway.
                backend is ExternalSherpaBackend && (
                    backend.memoryFamily == com.antivocale.app.data.ModelFamily.WHISPER ||
                        backend.memoryFamily == com.antivocale.app.data.ModelFamily.MOONSHINE) ->
                    TranscriptionMemoryPolicy.Family.WHISPER
                else -> TranscriptionMemoryPolicy.Family.TRANSDUCER
            }

            // TASK-472a: refuse instead of floor-clamping. When free RAM cannot
            // hold even the minimum-chunk baseline, the old path proceeded at
            // the floor and the process walked into an LMK/OEM kill with no
            // trace anywhere (the 4GB crash report, TASK-468). A clear error
            // beats a silent death; TASK-631 makes the refusal opt-in
            // (memoryProtection, mirroring the load pre-flight) so by default
            // the app attempts the transcription anyway. Returned (not thrown)
            // so the refusal rides the same failure path as every other
            // transcription refusal.
            // avail is read POST-load (the model is resident), so the
            // required figure is the decode-side bar only; the displayed
            // number is exactly the compared number.
            if (preferencesManager.memoryProtection.first() &&
                TranscriptionMemoryPolicy.canServeMinimumChunk(availBytes, modelSize, memoryFamily) == false
            ) {
                // minimumDecodeBaselineBytes already carries the headroom:
                // this IS the compared bar, byte for byte.
                val requiredBytes = TranscriptionMemoryPolicy.minimumDecodeBaselineBytes(memoryFamily, modelSize)
                Log.w(
                    TAG,
                    "Refusing ${backend.id}: post-load avail=${availBytes / MB}MB cannot hold " +
                        "the minimum-chunk decode baseline (required=${requiredBytes / MB}MB)",
                )
                return Result.failure(TranscriptionException.InsufficientMemory(
                    context.getString(
                        R.string.transcribe_low_memory, formatMb(availBytes), formatMb(requiredBytes))
                ))
            }
            val effective = TranscriptionMemoryPolicy.effectiveChunkSeconds(availBytes, modelSize, cap, memoryFamily)
            if (effective != cap) {
                Log.i(TAG, "Chunk cap tightened ${cap}s -> ${effective}s for ${backend.id} (RAM-derived)")
            }
            effective
        }
        // streamingChunkSeconds is the single source of the usePipeline rule:
        // the chunk cap when this request streams, null when it decodes whole-file.
        // TASK-450: when the VAD preference routes a file the whole-file path
        // would refuse for this device's memory ceiling, and the backend can
        // stream, run this request on the streaming path instead of failing:
        // the user keeps the transcription and loses only silence stripping
        // on this file (the result notification says so). The two capability
        // guards sit BEFORE the duration probe so VAD-off and forced-VAD
        // requests (Gemma, Canary) never pay the metadata open.
        val canStreamWithoutVad = !backend.requiresVadAlignedChunking && maxChunkDuration != null
        val fellBackFromVad = vadEnabled && canStreamWithoutVad &&
            AudioDurationPolicy.shouldFallBackToStreaming(
                audioPreprocessor.getAudioDuration(filePath),
                AudioDurationPolicy.ceilingSeconds(
                    AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM,
                    MemoryReadings.availableRamBytes(context),
                    MemoryReadings.maxHeapBytes()),
            )
        if (fellBackFromVad) {
            Log.i(TAG, "TASK-450: file exceeds this device's VAD-path ceiling; streaming without silence stripping (backend=${backend.id})")
        }
        val effectiveVad = vadEnabled && !fellBackFromVad
        val pipelineChunkSeconds = AudioDurationPolicy.streamingChunkSeconds(effectiveVad, maxChunkDuration)

        val totalStartMs = System.currentTimeMillis()

        if (pipelineChunkSeconds != null) {
            return applyFinalGenerativePass(
                backend, promptPlan.finalPass,
                processPipelinedAudio(
                    taskId = taskId,
                    filePath = filePath,
                    backend = backend,
                    maxChunkDurationSeconds = pipelineChunkSeconds,
                    streamedWithoutVad = fellBackFromVad,
                    context = context,
                    coroutineScope = coroutineScope,
                    listener = listener,
                    prompt = promptPlan.perChunk,
                    progressiveEnabled = progressiveEnabled,
                    emitInterim = emitInterim,
                    // GH #83: the pipeline is the DEFAULT contiguous path
                    // (Parakeet); without this the collector only reached
                    // the whole-file branch and every default run skipped
                    // labels (caught on the first real device trial).
                    collectSamples = collectSamples
                ))
        }

        val preprocessStartMs = System.currentTimeMillis()
        // TASK-512: request-time RAM, shared by the prepare call and the
        // processing contexts written below.
        val availableRamBytes = runCatching {
            MemoryReadings.availableRamBytes(context)
        }.getOrNull()
        val preprocessingResult = try {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = filePath,
                cacheDir = cacheDir,
                // Tightened cap here too: the VAD merge limit derives from it
                // (GH #50 "derived from the same limit"), and external models
                // with large catalog caps need the RAM protection in VAD mode
                // as much as the pipeline path does.
                maxChunkDurationSeconds = maxChunkDuration,
                context = context,
                enableVad = effectiveVad,
                vadNumThreads = threadCount,
                vadProvider = resolvedProvider,
                availableRamBytes = availableRamBytes,
                maxHeapBytes = MemoryReadings.maxHeapBytes()
            )
        } catch (e: PreprocessingError) {
            // TASK-522: the failure writeback rule (DurationTooLong's
            // measured length, else the decoded seconds so far) lives in one
            // helper shared with the pipeline path's catches.
            failureWritebackSeconds(e)?.let { updateAudioDuration(taskId, it) }
            return Result.failure(e)
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("Audio preprocessing failed: ${e.message}"))
        }

        // GH #83: hand the caller the preprocessed chunks while they are all
        // in hand, only when NO VAD preprocessing ran: VAD-segmented runs
        // carry gap-stripped cues on the original timeline, and even the
        // single-speech-span arm offsets cues by originMs while the
        // concatenation starts at the speech onset (code review F3); the
        // offset mapping is a tracked follow-up, not a v1 guess.
        if (collectSamples != null && !vadEnabled) {
            collectSamples(preprocessingResult.chunks, preprocessingResult.sampleRate)
        }

        val chunkCount = preprocessingResult.chunkCount
        val audioDurationSeconds = preprocessingResult.totalDurationSeconds.toInt()
        val preprocessMs = System.currentTimeMillis() - preprocessStartMs
        Log.i(TAG, "PERF: preprocessing ${preprocessMs}ms for ${audioDurationSeconds}s audio, $chunkCount chunks, backend=${backend.id}, pipeline=false")

        updateAudioDuration(taskId, preprocessingResult.totalDurationSeconds)

        val transcriptionStartTime = System.currentTimeMillis()
        val chunkProcessingStartTime = System.currentTimeMillis()

        // Fast path: single chunk. Uses the streaming variant so streaming backends
        // (e.g. Nemotron) can emit progressive partials; non-streaming backends ignore
        // the callback via the default implementation in TranscriptionBackend.
        if (chunkCount == 1) {
            val t0 = System.currentTimeMillis()
            // TASK-602 F1: a single non-streaming decode (the common
            // voice-message shape) has no callbacks; without a heartbeat the
            // phase-2 boundary seed ages past the 15s staleness gate while
            // the accurate model loads and decodes, and reopening mid-run
            // offers recovery for a LIVE run. Re-saving the same text only
            // refreshes the timestamp.
            val seedHeartbeat = if (!emitInterim) coroutineScope.launch {
                while (isActive) {
                    delay(PARTIAL_SAVE_INTERVAL_MS)
                    val seed = preferencesManager.partialTranscriptionText.first()
                    if (!seed.isNullOrBlank()) {
                        preferencesManager.savePartialTranscriptionState(seed)
                    }
                }
            } else null
            val result = try {
                backend.transcribeAudioStreaming(
                    prompt = resolvedPrompt,
                    samples = preprocessingResult.chunks.first(),
                    sampleRate = preprocessingResult.sampleRate
                ) { partial ->
                if (emitInterim) {
                    updateInterimResult(taskId, partial)
                    listener.onInterimResult(
                    contentText = partial,
                    bigText = partial,
                    subText = "",
                    chunkIndex = 0,
                    chunkText = partial,
                    totalChunks = 1
                )
                    }
                }
            } finally {
                seedHeartbeat?.cancel()
            }
            val inferMs = System.currentTimeMillis() - t0
            Log.i(TAG, "Inference timing: ${inferMs}ms for ${audioDurationSeconds}s audio (backend=${backend.id}, provider=$resolvedProvider, threads=${threadCount}, chunks=$chunkCount)")
            return when {
                result.isSuccess -> {
                    val tr = result.getOrNull()!!
                    if (tr.text.isNotBlank()) {
                        recordCalibration(backend, audioDurationSeconds, chunkProcessingStartTime)
                        val trimmed = tr.text.trim()
                        // GH #92: sentence cues when the backend supplied token
                        // timestamps, else the one positional cue for the one
                        // chunk; the preprocessor's ranges are aligned with chunks by
                        // construction, so no per-path offset arithmetic exists here.
                        val range = preprocessingResult.chunkRangesMs.firstOrNull()
                        val segments = if (range != null) {
                            cuesForChunk(tr.tokens, trimmed, range.first, range.second)
                        } else emptyList()
                        // Tokens are chunk-relative intermediates; the assembled result
                        // carries cues only, same convention as the other three paths.
                        Result.success(tr.copy(
                            text = trimmed, segments = segments, tokens = emptyList(),
                            // TASK-512: the single-decode fast path (the most
                            // common run: the short voice message).
                            processing = ProcessingContext(
                                backendId = backend.id,
                                decodePath = "whole_file",
                                vadRequested = vadRequested,
                                transcribedSeconds = audioDurationSeconds.toDouble().takeIf { it > 0.0 },
                                chunkCapSeconds = maxChunkDuration,
                                availableRamBytes = availableRamBytes,
                            )))
                    } else {
                        // TASK-622: whole-file blank (1 chunk); on the most
                        // common path silence and broken decode were
                        // indistinguishable on the ERROR row until this.
                        Result.failure(TranscriptionException.NoTranscriptionProduced(blankChunks = 1))
                    }
                }
                else -> Result.failure(result.exceptionOrNull()!!)
            }
        }

        // Progressive path: VAD-segmented audio + progressive toggle enabled
        if (preprocessingResult.isVadSegmented && progressiveEnabled) {
            return applyFinalGenerativePass(
                backend, promptPlan.finalPass,
                processProgressiveSegments(
                    taskId = taskId,
                    chunkCapSeconds = maxChunkDuration,
                    availableRamBytes = availableRamBytes,
                    vadRequested = vadRequested,
                    chunks = preprocessingResult.chunks,
                    sampleRate = preprocessingResult.sampleRate,
                    segmentRangesMs = preprocessingResult.chunkRangesMs,
                    prompt = promptPlan.perChunk,
                    backend = backend,
                    audioDurationSeconds = audioDurationSeconds,
                    chunkProcessingStartTime = chunkProcessingStartTime,
                    listener = listener,
                    transcriptionStartTime = transcriptionStartTime,
                    emitInterim = emitInterim
                ))
        }

        // Multi-chunk path: parallel processing with progress tracking
        return applyFinalGenerativePass(
            backend, promptPlan.finalPass,
            processParallelChunks(
                taskId = taskId,
                chunkCapSeconds = maxChunkDuration,
                availableRamBytes = availableRamBytes,
                vadRequested = vadRequested,
                vadSegmented = preprocessingResult.isVadSegmented,
                chunks = preprocessingResult.chunks,
                sampleRate = preprocessingResult.sampleRate,
                segmentRangesMs = preprocessingResult.chunkRangesMs,
                prompt = promptPlan.perChunk,
                backend = backend,
                audioDurationSeconds = audioDurationSeconds,
                chunkProcessingStartTime = chunkProcessingStartTime,
                queuePosition = queuePosition,
                queueTotal = queueTotal,
                listener = listener,
                coroutineScope = coroutineScope,
                transcriptionStartTime = transcriptionStartTime,
                progressiveEnabled = progressiveEnabled,
                emitInterim = emitInterim
            ))
    }

    /**
     * TASK-370: the single, final application of the user's generative prompt
     * over the concatenated multi-chunk transcript. Fail-open: if the pass
     * fails or returns blank, the raw transcript is delivered unchanged (the
     * transcription itself succeeded; post-processing must not lose it).
     */
    private suspend fun applyFinalGenerativePass(
        backend: TranscriptionBackend,
        generativePrompt: String?,
        result: Result<TranscriptionResult>
    ): Result<TranscriptionResult> {
        if (generativePrompt == null || result.isFailure) return result
        val transcript = result.getOrNull()?.text?.takeIf { it.isNotBlank() } ?: return result
        return backend
            .generateText(ChunkPromptPolicy.finalPrompt(generativePrompt, transcript))
            .fold(
                onSuccess = { processed ->
                    if (processed.isNotBlank()) result.map { it.copy(text = processed.trim()) }
                    else result
                },
                onFailure = { error ->
                    Log.w(TAG, "Final generative pass failed; delivering raw transcript", error)
                    result
                })
    }

    /**
     * GH #92: the cue set for one decoded chunk: sentence cues from the
     * backend's token timestamps when it supplies them, else the chunk-level
     * cue built positionally from the chunk's range. A token-bearing chunk
     * whose cues all normalize blank yields no cue (the honest gap for a noise
     * decode). One owner keeps all four assembly paths in step.
     */
    /**
     * TASK-622: the one cause phrase for every blank-chunk diagnostic (log
     * lines and the all-blank error text share it so the wording cannot
     * drift). [silenceExpected] keys it on the path's relationship with
     * silence: the pipeline stream never strips silence (blanks are normal
     * there), VAD paths selected their chunks AS speech (a blank there is
     * the suspect signal).
     */
    private fun blankCause(silenceExpected: Boolean): String =
        "silence ${if (silenceExpected) "(expected on this path) " else ""}or swallowed decode failure"

    private fun cuesForChunk(
        tokens: List<TimedToken>,
        trimmedChunkText: String,
        startMs: Long,
        endMs: Long,
    ): List<TimedSegment> =
        if (tokens.isNotEmpty()) SentenceCueBuilder.build(tokens, startMs, endMs, trimmedChunkText)
        else listOf(TimedSegment(startMs, endMs, trimmedChunkText))

    private suspend fun processProgressiveSegments(
        taskId: String,
        chunkCapSeconds: Int?,
        availableRamBytes: Long?,
        vadRequested: Boolean,
        chunks: List<FloatArray>,
        sampleRate: Int,
        segmentRangesMs: List<Pair<Long, Long>>,
        prompt: String = "",
        backend: TranscriptionBackend,
        audioDurationSeconds: Int,
        chunkProcessingStartTime: Long,
        listener: TranscriptionListener,
        transcriptionStartTime: Long,
        emitInterim: Boolean = true
    ): Result<TranscriptionResult> {
        val chunkCount = chunks.size
        Log.i(TAG, "VAD-segmented progressive path: $chunkCount segments")
        val accumulatedText = StringBuilder()
        var failedSegments = 0
        var blankSegments = 0
        var minConfidence: Float? = null
        var detectedLang: String? = null
        val segments = mutableListOf<TimedSegment>()

        for (i in chunks.indices) {
            val segNumber = i + 1
            if (accumulatedText.isEmpty()) {
                if (emitInterim) listener.onStatusUpdate("Transcribing segment 1…")
            }

            val segResult = backend.transcribeAudio(samples = chunks[i], sampleRate = sampleRate, prompt = prompt)
            segResult.fold(
                onSuccess = { tr ->
                    if (tr.text.isNotBlank()) {
                        val trimmed = tr.text.trim()
                        if (accumulatedText.isNotEmpty()) accumulatedText.append(' ')
                        accumulatedText.append(trimmed)
                        // GH #92: a failed segment leaves no cue (honest gap).
                        segmentRangesMs.getOrNull(i)?.let { (startMs, endMs) ->
                            segments.addAll(cuesForChunk(tr.tokens, trimmed, startMs, endMs))
                        }
                        // TASK-602: the crash-recovery partial must refresh even
                        // when interim writes are suppressed (dual phase 2),
                        // or the seed ages past RECOVERY_STALE_THRESHOLD_MS and
                        // reopening mid-run offers recovery for a LIVE run;
                        // same writeRow idiom as the pipeline/windowed paths.
                        updateInterimResult(taskId, accumulatedText.toString(), writeRow = emitInterim)
                        if (emitInterim) {
                            Log.i(TAG, "Progressive preview: segment ${trimmed.length} chars, total ${accumulatedText.length} chars")
                            listener.onInterimResult(
                                contentText = trimmed,
                                bigText = trimmed,
                                subText = "Segment $segNumber/$chunkCount",
                                chunkIndex = i,
                                chunkText = trimmed,
                                totalChunks = chunkCount
                            )
                        }
                    } else {
                        blankSegments++
                    }
                    minConfidence = aggregateConfidence(minConfidence, tr.confidence)
                    if (detectedLang == null) detectedLang = tr.detectedLanguage
                },
                onFailure = { error ->
                    if (error is EngineWedgeTimeoutException) {
                        // TASK-606 F2: wedged engine; each remaining segment
                        // would burn a full generation ceiling for nothing.
                        Log.e(TAG, "Segment $segNumber/$chunkCount timed out; engine wedged, aborting the run")
                        return Result.failure(WedgeAbortException(error))
                    }
                    failedSegments++
                    Log.e(TAG, "Segment $segNumber/$chunkCount failed", error)
                }
            )
        }

        val totalMs = System.currentTimeMillis() - chunkProcessingStartTime
        Log.i(TAG, "PERF: progressive total ${totalMs}ms for ${audioDurationSeconds}s audio, $chunkCount segments, backend=${backend.id}")
        recordCalibration(backend, audioDurationSeconds, chunkProcessingStartTime)

        if (blankSegments > 0) {
            // TASK-622: a blank is silence by design (GH #96) but also the
            // signature of a swallowed decode failure; all-blank here is the
            // over-ceiling tell.
            Log.i(TAG, "Progressive: $blankSegments/$chunkCount segments decoded blank (${blankCause(false)})")
        }
        return if (accumulatedText.isEmpty()) {
            // TASK-622: the old message said "failed" even when every segment
            // SUCCEEDED empty, misdirecting the first debugging pass.
            Result.failure(when {
                // All genuinely failed: the historical message (tests pin it).
                failedSegments == chunkCount -> IllegalStateException("All $chunkCount segments failed to transcribe")
                // All succeeded empty: a swallowed decode failure looks exactly
                // like this (GH #96 semantics made it success); name it.
                failedSegments == 0 -> BlankSegmentsException(
                    blankSegments,
                    "All $chunkCount segments decoded blank (${blankCause(false)})")
                else -> BlankSegmentsException(
                    blankSegments,
                    "All $chunkCount segments produced no text ($failedSegments failed, $blankSegments blank)")
            })
        } else {
            if (failedSegments > 0) {
                Log.w(TAG, "Completed with $failedSegments/$chunkCount failed segments")
            }
            Result.success(TranscriptionResult(
                text = accumulatedText.toString(),
                confidence = minConfidence,
                detectedLanguage = detectedLang,
                isPartial = failedSegments > 0,
                failedChunkCount = failedSegments,
                segments = segments,
                processing = ProcessingContext(
                    backendId = backend.id,
                    decodePath = "vad_chunked",
                    vadRequested = vadRequested,
                    totalChunks = chunkCount,
                    failedChunks = failedSegments,
                    blankChunks = blankSegments.takeIf { it > 0 },
                    transcribedSeconds = audioDurationSeconds.toDouble().takeIf { it > 0.0 },
                    chunkCapSeconds = chunkCapSeconds,
                    availableRamBytes = availableRamBytes,
                )
            ))
        }
    }

    private suspend fun processParallelChunks(
        taskId: String,
        chunkCapSeconds: Int?,
        availableRamBytes: Long?,
        vadRequested: Boolean,
        vadSegmented: Boolean,
        chunks: List<FloatArray>,
        sampleRate: Int,
        /** GH #92: per-chunk offsets aligned with the chunks; empty when no timing exists. */
        segmentRangesMs: List<Pair<Long, Long>>,
        prompt: String = "",
        backend: TranscriptionBackend,
        audioDurationSeconds: Int,
        chunkProcessingStartTime: Long,
        queuePosition: Int,
        queueTotal: Int,
        listener: TranscriptionListener,
        coroutineScope: CoroutineScope,
        transcriptionStartTime: Long,
        progressiveEnabled: Boolean = false,
        emitInterim: Boolean = true
    ): Result<TranscriptionResult> {
        val chunkCount = chunks.size
        val completedChunks = AtomicInteger(0)
        val results = arrayOfNulls<String>(chunkCount)
        val chunkConfidences = arrayOfNulls<Float>(chunkCount)
        val chunkLanguages = arrayOfNulls<String>(chunkCount)
        // GH #92: per-chunk token timestamps for the sentence cues; null when the
        // chunk failed or the backend supplies no token timing.
        val chunkTokens = arrayOfNulls<List<TimedToken>>(chunkCount)

        Log.i(TAG, "Processing $chunkCount chunks with up to $maxConcurrentChunks concurrent transcriptions")

        val backendId = backend.id
        val modelPath = modelPathForBackend(backendId)
        val modelDisplayName = deriveDisplayName(backendId, modelPath, backend.displayName)
        val calibrationProfile = transcriptionCalibrator.getEstimate(backendId, modelPath)
        val chunkDurationSeconds = (audioDurationSeconds.toDouble() / chunkCount).toLong()
        val estimatedChunkDurationMs = calibrationProfile?.let {
            if (it.hasEstimate) (it.msPerSecondOfAudio * chunkDurationSeconds).toLong() else null
        }
        val estimatedTotalMs = estimatedChunkDurationMs?.let { est ->
            val batches = ceilDiv(chunkCount, maxConcurrentChunks).toLong()
            batches * est
        }

        val progressTimerJob = coroutineScope.launch {
            startGlobalProgressTimer(
                totalChunks = chunkCount,
                completedChunks = completedChunks,
                estimatedTotalMs = estimatedTotalMs,
                audioDurationSeconds = audioDurationSeconds,
                calibrationProfile = calibrationProfile,
                queuePosition = queuePosition,
                queueTotal = queueTotal,
                backendId = backendId,
                modelPath = modelPath,
                modelDisplayName = modelDisplayName,
                chunkDurationSeconds = chunkDurationSeconds,
                transcriptionStartTime = transcriptionStartTime,
                listener = listener
            )
        }

        var failedChunks = 0
        var blankChunks = 0

        // TASK-606 F1: the timer is cancelled in a FINALLY: a WedgeAbort (or
        // any throw) out of the scope below used to orphan the infinite
        // progress timer, and a live child kept the per-task job from
        // completing, deadlocking the service queue on taskJob.join().
        // TASK-606 (round 16): hoisted so the post-scope return can read them.
        var wedge: Throwable? = null
        var wedgeResult: Result<TranscriptionResult>? = null

        try {
        coroutineScope {
            val deferredResults = chunks.mapIndexed { index, chunk ->
                async {
                    chunkSemaphore.acquire()
                    try {
                        val chunkResult = backend.transcribeAudio(samples = chunk, sampleRate = sampleRate, prompt = prompt)
                        completedChunks.incrementAndGet()
                        chunkResult
                    } finally {
                        chunkSemaphore.release()
                    }
                }
            }

            // TASK-602 F2: the seed must refresh on EVERY phase-2 chunk
            // completion, not only when the progressive builder exists:
            // with the toggle off the old site never ran and the boundary
            // seed went stale mid-run. Single-model runs with the toggle
            // off keep the old no-interim-writes behavior.
            val progressiveText = if (progressiveEnabled) StringBuilder() else null
            var seedText = ""

            // TASK-606 (round 16): a wedge is recorded and the run RETURNs a
            // failure like the VAD twin (the old throw escaped
            // processAudioRequest, bypassed the dual-model recoverFirstPass
            // fold, and discarded every completed chunk). Remaining
            // iterations skip; the pending sibling chunks are cancelled
            // after the loop so none burns another ceiling.
            deferredResults.forEachIndexed { index, deferred ->
                if (wedge != null) return@forEachIndexed
                val chunkResult = deferred.await()
                chunkResult.fold(
                    onSuccess = { tr ->
                        if (tr.text.isNotBlank()) {
                            val trimmed = tr.text.trim()
                            results[index] = trimmed
                            chunkTokens[index] = tr.tokens
                            chunkConfidences[index] = tr.confidence
                            chunkLanguages[index] = tr.detectedLanguage
                            if (progressiveText != null) {
                                if (progressiveText.isNotEmpty()) progressiveText.append(' ')
                                progressiveText.append(trimmed)
                            }
                            seedText = if (progressiveText != null) progressiveText.toString() else trimmed
                            if (progressiveText != null || !emitInterim) {
                                updateInterimResult(taskId, seedText, writeRow = emitInterim)
                            }
                            if (progressiveText != null && emitInterim) {
                                listener.onInterimResult(
                                    contentText = trimmed,
                                    bigText = trimmed,
                                    subText = "Chunk ${index + 1}/$chunkCount",
                                    chunkIndex = index,
                                    chunkText = trimmed,
                                    totalChunks = chunkCount
                                )
                            }
                        } else {
                            blankChunks++
                        }
                    },
                    onFailure = { error ->
                        if (error is EngineWedgeTimeoutException) {
                            // TASK-606 F2: wedged engine; retrying burns a
                            // second ceiling, continuing burns one per chunk.
                            Log.e(TAG, "Parallel chunk ${index + 1} timed out; engine wedged, aborting the run")
                            wedge = error
                            return@fold
                        }
                        Log.w(TAG, "Parallel chunk ${index + 1} failed, retrying with memory cleanup", error)
                        val retried = retryChunkWithGc(backend, chunks[index], sampleRate, prompt)
                        retried.fold(
                            onSuccess = { tr ->
                                Log.i(TAG, "Parallel chunk ${index + 1} retry succeeded")
                                if (tr.text.isNotBlank()) {
                                    val trimmed = tr.text.trim()
                                    results[index] = trimmed
                                    chunkTokens[index] = tr.tokens
                                    chunkConfidences[index] = tr.confidence
                                    chunkLanguages[index] = tr.detectedLanguage
                                } else {
                                    blankChunks++
                                }
                            },
                            onFailure = { retryError ->
                                failedChunks++
                                Log.e(TAG, "Parallel chunk ${index + 1} retry also failed", retryError)
                            }
                        )
                    }
                )
            }

            wedge?.let { w ->
                // Cancel the still-pending siblings so the scope can
                // complete (coroutineScope waits for every child) and none
                // burns another ceiling; the failure is RETURNED after the
                // scope (a non-local return is not allowed in this lambda).
                deferredResults.forEach { it.cancel() }
                wedgeResult = Result.failure(WedgeAbortException(w))
            }
            }
        } finally {
            progressTimerJob.cancel()
        }

        val abort: Result<TranscriptionResult>? = wedgeResult
        if (abort != null) {
            return abort
        }

        val combinedResult = results.filterNotNull().joinToString(" ")
        Log.i(TAG, "Audio transcription complete: ${combinedResult.length} chars from ${results.filterNotNull().size}/$chunkCount chunks")
        if (blankChunks > 0) {
            // TASK-622: silence by design (GH #96), but also the swallowed
            // decode-failure signature; the count is the tell.
            Log.i(TAG, "Parallel: $blankChunks/$chunkCount chunks decoded blank (${blankCause(false)})")
        }

        // GH #92: one positional rule for every origin of these chunks (VAD merged
        // segments, fixed windows, the stripped single span): the preprocessor's
        // ranges are aligned with the chunks BY CONSTRUCTION, so cue i is range i.
        // The size guard is defensive only; a failed chunk leaves no cue. Token
        // timestamps, when the backend supplied them, refine each chunk's cue
        // into sentence cues inside that range.
        val segmentRanges = segmentRangesMs.takeIf { it.size == chunkCount }
        val segments = if (segmentRanges != null) {
            results.mapIndexedNotNull { index, text ->
                text?.let {
                    cuesForChunk(chunkTokens[index].orEmpty(), it,
                        segmentRanges[index].first, segmentRanges[index].second)
                }
            }.flatten()
        } else emptyList()

        val totalMs = System.currentTimeMillis() - chunkProcessingStartTime
        Log.i(TAG, "PERF: parallel total ${totalMs}ms for ${audioDurationSeconds}s audio, $chunkCount chunks, backend=${backend.id}")

        recordCalibration(backend, audioDurationSeconds, chunkProcessingStartTime)

        return if (combinedResult.isBlank()) {
            // TASK-622: all-blank IS the swallowed-decode signature; the
            // count rides to the ERROR row's FailureContext.
            Result.failure(TranscriptionException.NoTranscriptionProduced(blankChunks = blankChunks))
        } else {
            if (failedChunks > 0) {
                Log.w(TAG, "Parallel completed with $failedChunks/$chunkCount failed chunks")
            }
            val minConfidence = chunkConfidences.filterNotNull().minOrNull()
            val detectedLang = chunkLanguages.firstOrNull { it != null }
            Result.success(TranscriptionResult(
                text = combinedResult,
                confidence = minConfidence,
                detectedLanguage = detectedLang,
                isPartial = failedChunks > 0,
                failedChunkCount = failedChunks,
                segments = segments,
                processing = ProcessingContext(
                    backendId = backend.id,
                    // The label separates the two ways this shape arises:
                    // VAD-merged segments vs fixed-window splits of one long
                    // speech span (and the VAD-threw fallback): the chunk
                    // boundaries mean different things.
                    decodePath = if (vadSegmented) "vad_chunked" else "windowed",
                    vadRequested = vadRequested,
                    totalChunks = chunkCount,
                    failedChunks = failedChunks,
                    blankChunks = blankChunks.takeIf { it > 0 },
                    transcribedSeconds = audioDurationSeconds.toDouble().takeIf { it > 0.0 },
                    chunkCapSeconds = chunkCapSeconds,
                    availableRamBytes = availableRamBytes,
                )
            ))
        }
    }

    /**
     * Pipelined processing: transcribes chunks as they're decoded, overlapping
     * MediaCodec decoding with backend inference. Time-to-first-text improves
     * from ~3-5s to ~700ms for long audio.
     */
    private suspend fun processPipelinedAudio(
        taskId: String,
        filePath: String,
        backend: TranscriptionBackend,
        maxChunkDurationSeconds: Int,
        /** TASK-450: set when the request fell back from the refused VAD path. */
        streamedWithoutVad: Boolean,
        context: Context,
        coroutineScope: CoroutineScope,
        listener: TranscriptionListener,
        prompt: String = "",
        progressiveEnabled: Boolean = false,
        emitInterim: Boolean = true,
        /** GH #83: when non-null, invoked after the stream completes with the
         *  decoded chunk list. The pipeline's stream never strips silence
         *  (enableVad is false by construction here) and cue times derive
         *  from the same accumulated decoded seconds, so the timelines
         *  match by construction. */
        collectSamples: ((List<FloatArray>, Int) -> Unit)? = null,
    ): Result<TranscriptionResult> {
        val resolvedPrompt = resolvePrompt(prompt)
        val speakerChunks = if (collectSamples != null) mutableListOf<FloatArray>() else null
        var speakerSampleRate = 16000

        val pipelineStartMs = System.currentTimeMillis()
        val chunkProcessingStartTime = System.currentTimeMillis()
        val accumulatedText = StringBuilder()
        var totalDurationSeconds = 0.0
        // Actual decoded duration: replaces the header's metadata-derived value
        // at the summary sites, repairing the metadata-less-container case (0s
        // Logs row, silently dropped calibration sample; code review 2026-09-04 F6).
        var decodedSeconds = 0.0
        var expectedChunkCount = 0
        var processedChunks = 0
        var firstChunkDecodeMs = 0L
        var firstChunkInferStartMs = 0L
        var failedChunks = 0
        var blankChunks = 0
        var minConfidence: Float? = null
        var detectedLang: String? = null
        // GH #92: cue boundaries from the running decoded total (container
        // durations lie; the accumulated sample counts are the ground truth).
        val segments = mutableListOf<TimedSegment>()

        // TASK-512: RAM at REQUEST time (a completion-time read would report
        // the post-run state, not the constraint the path ran under).
        val availableRamBytes = runCatching {
            MemoryReadings.availableRamBytes(context)
        }.getOrNull()
        try {
            audioPreprocessor.prepareAudioStream(
                inputPath = filePath,
                maxChunkDurationSeconds = maxChunkDurationSeconds,
                context = context,
                enableVad = false,
                availableRamBytes = availableRamBytes,
                maxHeapBytes = MemoryReadings.maxHeapBytes()
            ).collect { event ->
                when (event) {
                    is AudioPreprocessor.StreamEvent.Header -> {
                        totalDurationSeconds = event.header.totalDurationSeconds
                        expectedChunkCount = event.header.expectedChunkCount
                        updateAudioDuration(taskId, event.header.totalDurationSeconds)
                        Log.i(TAG, if (expectedChunkCount > 0)
                            "Pipeline: expecting $expectedChunkCount chunks, ${event.header.totalDurationSeconds}s"
                        else
                            "Pipeline: unknown chunk count (no duration metadata), ${event.header.totalDurationSeconds}s")
                    }
                    is AudioPreprocessor.StreamEvent.Chunk -> {
                        val chunk = event.chunk
                        if (speakerChunks != null) {
                            speakerChunks.add(chunk.samples)
                            speakerSampleRate = chunk.sampleRate
                        }
                        processedChunks++
                        val chunkStartMs = (decodedSeconds * 1000).toLong()
                        decodedSeconds += chunk.samples.size.toDouble() / chunk.sampleRate
                        val chunkEndMs = (decodedSeconds * 1000).toLong()
                        val chunkReceiveMs = System.currentTimeMillis() - pipelineStartMs
                        if (chunk.chunkIndex == 0) {
                            firstChunkDecodeMs = chunkReceiveMs
                            firstChunkInferStartMs = System.currentTimeMillis()
                            Log.i(TAG, "PERF: pipeline first chunk decoded in ${firstChunkDecodeMs}ms")
                        }
                        Log.d(TAG, "Pipeline: transcribing chunk ${chunk.chunkIndex} (${chunk.samples.size} samples)")
                        // One label for both the success and retry emissions; the
                        // suffix helper hides the total once the decoded stream
                        // passes the metadata-derived estimate (TASK-449).
                        val chunkLabel = "Chunk ${chunk.chunkIndex + 1}${AudioPreprocessor.chunkTotalSuffix(expectedChunkCount, chunk.chunkIndex)}"

                        val chunkResult = backend.transcribeAudio(
                            samples = chunk.samples,
                            sampleRate = chunk.sampleRate,
                            prompt = resolvedPrompt
                        )
                        chunkResult.fold(
                            onSuccess = { tr ->
                                if (tr.text.isNotBlank()) {
                                    val trimmed = tr.text.trim()
                                    if (accumulatedText.isNotEmpty()) accumulatedText.append(' ')
                                    accumulatedText.append(trimmed)
                                    segments.addAll(cuesForChunk(tr.tokens, trimmed, chunkStartMs, chunkEndMs))
                                    updateInterimResult(taskId, accumulatedText.toString(), writeRow = emitInterim)
                                    if (progressiveEnabled && emitInterim) {
                                        listener.onInterimResult(
                                            contentText = trimmed,
                                            bigText = trimmed,
                                            subText = chunkLabel,
                                            chunkIndex = chunk.chunkIndex,
                                            chunkText = trimmed,
                                            totalChunks = expectedChunkCount
                                        )
                                    }
                                } else {
                                    blankChunks++
                                }
                                minConfidence = aggregateConfidence(minConfidence, tr.confidence)
                                if (detectedLang == null) detectedLang = tr.detectedLanguage
                            },
                            onFailure = { error ->
                                if (error is EngineWedgeTimeoutException) {
                                    // TASK-606 F2: wedged engine; abort the
                                    // stream instead of a second ceiling.
                                    Log.e(TAG, "Pipeline chunk ${chunk.chunkIndex} timed out; engine wedged, aborting the run")
                                    throw WedgeAbortException(error)
                                }
                                Log.w(TAG, "Pipeline chunk ${chunk.chunkIndex} failed, retrying with memory cleanup", error)
                                val retried = retryChunkWithGc(backend, chunk.samples, chunk.sampleRate, resolvedPrompt)
                                retried.fold(
                                    onSuccess = { tr ->
                                        Log.i(TAG, "Pipeline chunk ${chunk.chunkIndex} retry succeeded")
                                        if (tr.text.isNotBlank()) {
                                            val trimmed = tr.text.trim()
                                            if (accumulatedText.isNotEmpty()) accumulatedText.append(' ')
                                            accumulatedText.append(trimmed)
                                            segments.addAll(cuesForChunk(tr.tokens, trimmed, chunkStartMs, chunkEndMs))
                                            updateInterimResult(taskId, accumulatedText.toString(), writeRow = emitInterim)
                                            if (progressiveEnabled && emitInterim) {
                                                listener.onInterimResult(
                                                    contentText = trimmed,
                                                    bigText = trimmed,
                                                    subText = "$chunkLabel (retry)",
                                                    chunkIndex = chunk.chunkIndex,
                                                    chunkText = trimmed,
                                                    totalChunks = expectedChunkCount
                                                )
                                            }
                                        } else {
                                            blankChunks++
                                        }
                                        minConfidence = aggregateConfidence(minConfidence, tr.confidence)
                                        if (detectedLang == null) detectedLang = tr.detectedLanguage
                                    },
                                    onFailure = { retryError ->
                                        failedChunks++
                                        Log.e(TAG, "Pipeline chunk ${chunk.chunkIndex} retry also failed", retryError)
                                    }
                                )
                            }
                        )

                        if (chunk.chunkIndex == 0 && accumulatedText.isNotEmpty()) {
                            val ttft = System.currentTimeMillis() - firstChunkInferStartMs
                            Log.i(TAG, "PERF: pipeline time-to-first-text = ${System.currentTimeMillis() - pipelineStartMs}ms (decode=${firstChunkDecodeMs}ms + infer=${ttft}ms)")
                            if (!progressiveEnabled) {
                                if (emitInterim) listener.onStatusUpdate("Transcribing…")
                            }
                        }
                    }
                }
            }
        } catch (e: PreprocessingError) {
            return pipelineFailed(taskId, e, accumulatedText.toString(), decodedSeconds, totalDurationSeconds, processedChunks, failedChunks, blankChunks, context, backend.id)
        } catch (e: Exception) {
            return pipelineFailed(taskId, e, accumulatedText.toString(), decodedSeconds, totalDurationSeconds, processedChunks, failedChunks, blankChunks, context, backend.id)
        }

        val combinedResult = accumulatedText.toString()
        // The decoded length beats the metadata value at the summary sites: it
        // is the ground truth (also when the container's duration tag lies or
        // is absent, where totalDurationSeconds is 0.0).
        if (decodedSeconds > 0.0) {
            totalDurationSeconds = decodedSeconds
            updateAudioDuration(taskId, decodedSeconds)
        }
        recordCalibration(backend, totalDurationSeconds.toInt(), chunkProcessingStartTime)

        val totalMs = System.currentTimeMillis() - pipelineStartMs
        Log.i(TAG, "PERF: pipeline total ${totalMs}ms for ${totalDurationSeconds}s audio, $processedChunks chunks (expected $expectedChunkCount), backend=${backend.id}, ttft_decode=${firstChunkDecodeMs}ms")

        // GH #83: hand the accumulated contiguous chunks to the caller's
        // speaker-labeling pass. By construction the pipeline never strips
        // silence and cue times derive from the same accumulated decoded
        // seconds, so the timelines match; chunks are references, the
        // concatenated copy is built lazily inside the pass.
        speakerChunks?.let { collectSamples?.invoke(it, speakerSampleRate) }

        // TASK-622: logged BEFORE the blank fail-fast so an all-blank run
        // (the swallowed-decode signature) still leaves the tell in logcat;
        // the pipeline stream never strips silence, so blanks here are
        // expected-quiet first, suspect second (contrast the VAD paths).
        if (blankChunks > 0) {
            Log.i(TAG, "Pipeline: $blankChunks/$processedChunks chunks decoded blank (${blankCause(true)})")
        }
        return if (combinedResult.isBlank()) {
            // TASK-622: all-blank IS the swallowed-decode signature; the
            // count rides to the ERROR row's FailureContext.
            Result.failure(TranscriptionException.NoTranscriptionProduced(blankChunks = blankChunks))
        } else {
            if (failedChunks > 0) {
                Log.w(TAG, "Pipeline completed with $failedChunks/$processedChunks failed chunks")
            }
            Result.success(TranscriptionResult(
                text = combinedResult,
                confidence = minConfidence,
                detectedLanguage = detectedLang,
                isPartial = failedChunks > 0,
                failedChunkCount = failedChunks,
                streamedWithoutVad = streamedWithoutVad,
                segments = segments,
                processing = ProcessingContext(
                    backendId = backend.id,
                    decodePath = if (streamedWithoutVad) "streamed_no_vad" else "pipeline",
                    // Counted, not the metadata estimate: the estimate
                    // under-reports on lying duration tags (TASK-449), which
                    // would inflate the rendered failure rate against the
                    // counted failedChunks numerator.
                    totalChunks = processedChunks,
                    failedChunks = failedChunks,
                    blankChunks = blankChunks.takeIf { it > 0 },
                    transcribedSeconds = totalDurationSeconds.takeIf { it > 0.0 },
                    chunkCapSeconds = maxChunkDurationSeconds,
                    availableRamBytes = availableRamBytes,
                    // The pipeline never runs VAD (decode overlaps inference);
                    // the toggle still records what the user asked for.
                    vadRequested = preferencesManager.vadEnabled.first(),
                )
            ))
        }
    }

    // ---- Progress Timer ----

    private fun CoroutineScope.startGlobalProgressTimer(
        totalChunks: Int,
        completedChunks: AtomicInteger,
        estimatedTotalMs: Long?,
        audioDurationSeconds: Int,
        calibrationProfile: TranscriptionCalibrator.CalibrationProfile?,
        queuePosition: Int,
        queueTotal: Int,
        backendId: String,
        modelPath: String,
        modelDisplayName: String,
        chunkDurationSeconds: Long,
        transcriptionStartTime: Long,
        listener: TranscriptionListener
    ): Job {
        val startTime = System.currentTimeMillis()
        var lastProgressPercent = -1


        var lastBatchCompletedCount = 0
        var lastBatchElapsedMs: Long? = null
        var measuredAvgBatchMs: Long? = null
        var firstBatchRecorded = false

        return launch {
            while (isActive) {
                delay(200)

                val now = System.currentTimeMillis()
                val elapsedMs = now - startTime
                val completed = completedChunks.get()

                // Feedback loop: detect batch completions and measure actual throughput
                val completedBatches = completed / maxConcurrentChunks
                if (completedBatches > lastBatchCompletedCount) {
                    val prevBatchElapsed = lastBatchElapsedMs
                    measuredAvgBatchMs = if (prevBatchElapsed != null) {
                        elapsedMs - prevBatchElapsed
                    } else {
                        elapsedMs
                    }
                    lastBatchElapsedMs = elapsedMs
                    lastBatchCompletedCount = completedBatches

                    if (!firstBatchRecorded && backendId.isNotEmpty()) {
                        firstBatchRecorded = true
                        val batchAudioSeconds = chunkDurationSeconds * maxConcurrentChunks
                        val batchMs = measuredAvgBatchMs!!
                        launch {
                            try {
                                transcriptionCalibrator.record(
                                    backendId = backendId,
                                    modelPath = modelPath,
                                    displayName = modelDisplayName,
                                    audioDurationSeconds = batchAudioSeconds,
                                    processingTimeMs = batchMs
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed mid-transcription calibration", e)
                            }
                        }
                    }
                }

                val hardPercent = completed * 100 / totalChunks

                val adaptiveEtaMs: Long? = if (measuredAvgBatchMs != null) {
                    val remainingBatches = ceilDiv(totalChunks - completed, maxConcurrentChunks).toLong()
                    remainingBatches * measuredAvgBatchMs
                } else if (estimatedTotalMs != null) {
                    maxOf(0L, estimatedTotalMs - elapsedMs)
                } else null

                val timePercent = if (adaptiveEtaMs != null && adaptiveEtaMs > 0) {
                    (elapsedMs.toFloat() / (elapsedMs + adaptiveEtaMs) * 100f).toInt().coerceIn(0, 95)
                } else {
                    val crawlTarget = audioDurationSeconds * 1000f * 2f
                    (elapsedMs / crawlTarget * 80f).toInt().coerceIn(0, 80)
                }

                val displayProgress = maxOf(1, hardPercent, timePercent).coerceIn(0, 99)
                if (displayProgress == lastProgressPercent) continue
                lastProgressPercent = displayProgress

                val etaText = adaptiveEtaMs?.let { eta ->
                    val confidence = calibrationProfile?.confidence
                        ?: TranscriptionCalibrator.CalibrationProfile.Confidence.LOW
                    formatEta(eta / 1000, confidence)
                } ?: if (calibrationProfile != null && !calibrationProfile.hasEstimate) {
                    "Calibrating…"
                } else {
                    ""
                }

                val contentText = if (completed == 0 && totalChunks > 1) {
                    queueAwareAudioLabel(queuePosition, queueTotal)
                } else {
                    queueAwareChunkLabel(completed, totalChunks, queuePosition, queueTotal)
                }

                listener.onProgress(
                    contentText = contentText,
                    progressPercent = displayProgress,
                    etaText = etaText,
                    durationSeconds = audioDurationSeconds,
                    startTimeMillis = transcriptionStartTime,
                    queuedCount = 0 // queue count managed by service
                )
            }
        }
    }

    // ---- Calibration ----

    private suspend fun recordCalibration(
        backend: TranscriptionBackend,
        audioDurationSeconds: Int,
        startTimeMs: Long
    ) {
        val totalProcessingTimeMs = System.currentTimeMillis() - startTimeMs
        try {
            val backendId = backend.id
            val modelPath = modelPathForBackend(backendId)
            val modelDisplayName = deriveDisplayName(backendId, modelPath, backend.displayName)
            transcriptionCalibrator.record(
                backendId = backendId,
                modelPath = modelPath,
                displayName = modelDisplayName,
                audioDurationSeconds = audioDurationSeconds.toLong(),
                processingTimeMs = totalProcessingTimeMs
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record calibration", e)
        }
    }

    // ---- DB Logging ----

    /**
     * Creates the log entry at enqueue time (GH #51): the request is visible in
     * the Logs tab as QUEUED from the moment it enters the queue, before work
     * starts. Called by InferenceService when a request is accepted. This is the
     * single insert point for a request's row.
     */
    suspend fun logQueued(
        taskId: String,
        requestType: String,
        prompt: String = "",
        filePath: String? = null,
        sourcePackageName: String? = null
    ) {
        logDao.insert(
            LogEntry(
                taskId = taskId,
                type = if (requestType == "audio" || requestType == "subtitles") LogEntry.Type.AUDIO else LogEntry.Type.TEXT,
                status = LogEntry.Status.QUEUED,
                prompt = prompt,
                filePath = filePath,
                sourcePackageName = sourcePackageName
            ).toEntity()
        )
    }

    /**
     * Promotes the QUEUED entry (created by [logQueued]) to PROCESSING when work
     * starts. DAO-level and non-inserting by design: this racing the enqueue
     * write can never produce a duplicate row, and an entry the user deleted
     * mid-flight stays deleted (no resurrect).
     */
    suspend fun markProcessing(taskId: String) {
        logDao.promoteToProcessing(taskId)
    }

    /**
     * TASK-546: the language pin a run executed under, resolved the way the
     * picker displays it: untouched preference = "auto"; the phone pin = the
     * device's language; a code pin = itself. Row-level fact: report-time
     * reads would misattribute settings changed since the run (the TASK-545
     * review lesson).
     */
    private suspend fun resolvedLanguagePin(context: Context): String {
        val pref = preferencesManager.transcriptionLanguage.first()
        return when {
            pref.isBlank() || pref == TranscriptionLanguagePolicy.PREF_SYSTEM -> TranscriptionLanguagePolicy.PREF_AUTO
            pref == TranscriptionLanguagePolicy.PREF_PHONE ->
                com.antivocale.app.util.LocaleManager.phoneLanguage(context)
                    ?: TranscriptionLanguagePolicy.PREF_AUTO
            else -> pref
        }
    }

    private suspend fun logSuccess(
        taskId: String,
        result: String,
        durationMs: Long,
        isPartial: Boolean = false,
        failedChunkCount: Int = 0,
        /** TASK-276 AC3: the pre-punctuation original, kept when the pass changed the text. */
        rawTranscript: String? = null,
        /** TASK-121.4: the AI summary of a long transcript, when generation succeeded. */
        summary: String? = null,
        /** TASK-494: why an attended summary attempt produced none. */
        summarySkipReason: String? = null,
        /** GH #92: the subtitle cues (sentence-level when token timing exists,
         *  else chunk-level), stored as JSON on the row. */
        segments: List<TimedSegment> = emptyList(),
        /** TASK-512: how the run was produced, persisted as the row's
         *  processing context (null on the text-LLM path). */
        processing: ProcessingContext? = null,
        /** TASK-546: what the backend reported it heard (null = not reported). */
        detectedLanguage: String? = null,
        /** TASK-546: the policy-resolved pin in force ("auto" when untouched). */
        languagePin: String? = null,
        /** GH #43: the superseded fast first-pass transcript, persisted when
         *  a two-pass run refined it (null on single-model runs). */
        firstPassTranscript: String? = null,
    ) {
        val entity = logDao.getByTaskId(taskId) ?: return
        logDao.update(entity.toLogEntry().copy(
            status = LogEntry.Status.SUCCESS, result = result, durationMs = durationMs,
            isPartial = isPartial, failedChunkCount = failedChunkCount,
            rawTranscript = rawTranscript,
            summary = summary,
            summarySkipReason = summarySkipReason,
            segments = TimedSegmentsConverter.toJson(segments),
            processingContext = ProcessingContextConverter.toJson(processing),
            firstPassTranscript = firstPassTranscript,
            detectedLanguage = detectedLanguage,
            languagePin = languagePin
        ).toEntity())
        preferencesManager.clearPartialTranscriptionState()
        lastPartialSaveMs = 0L
        lastInterimRoomWriteMs.remove(taskId)
    }

    private suspend fun logError(taskId: String, errorMessage: String, durationMs: Long = 0) {
        val entity = logDao.getByTaskId(taskId) ?: return
        // TASK-568: durationMs on an ERROR row is the decoded-at-failure
        // seconds written by the streaming catches (updateFailureDecodedMs),
        // not the wall-clock elapsed the callers used to pass (the very
        // confusion of the v1.5.x reports: four failures, four different
        // "lengths" that were processing time). A positive param still wins
        // (the non-streaming entry sites have no decoded figure).
        logDao.update(entity.toLogEntry().copy(
            status = LogEntry.Status.ERROR, errorMessage = errorMessage,
            durationMs = if (durationMs > 0) durationMs else entity.durationMs
        ).toEntity())
        preferencesManager.clearPartialTranscriptionState()
        lastPartialSaveMs = 0L
        lastInterimRoomWriteMs.remove(taskId)
    }

    private suspend fun cancelIfPending(taskId: String, errorMessage: String, durationMs: Long) {
        lastInterimRoomWriteMs.remove(taskId)
        logDao.failNonTerminal(taskId, errorMessage, durationMs)
    }

    private suspend fun updateInterimResult(
        taskId: String,
        accumulatedText: String,
        /** GH #43 review F3: phase 2 of a two-pass run passes false: the
         *  row keeps the complete first-pass text, but the crash-recovery
         *  state must keep refreshing or the 15s staleness gate misfires a
         *  false interruption dialog on a live refinement. */
        writeRow: Boolean = true,
    ) {
        // Throttle interim Room writes to the same 5s cadence as the partial-state save
        // (TASK-340 Fix 2b): every interim partial used to write Room, and each write
        // re-emitted the whole (bounded) log list through LogsViewModel. The final
        // result is written unconditionally by logSuccess, so skipping writes inside
        // the interval cannot lose text; notifications still fire per chunk via the
        // listener, which is NOT throttled here.
        val now = throttleClock()
        if (now - (lastInterimRoomWriteMs[taskId] ?: 0L) < PARTIAL_SAVE_INTERVAL_MS) return
        // NOTE (TASK-602 review F7): the key advances on EVERY pass, including
        // writeRow=false ones, so it means "last updateInterimResult pass", not
        // "last Room write"; a writeRow=true caller within 5s of a suppressed
        // pass still skips its row write (acceptable: the final logSuccess is
        // unconditional).
        lastInterimRoomWriteMs[taskId] = now

        // TASK-390: column-scoped update (no read): a whole-row write-back could
        // resurrect a row that a concurrent close (cancel/sweep) had just terminalized.
        if (writeRow) {
            logDao.updateInterimResult(taskId, accumulatedText, isPartial = true)
        }

        if (now - lastPartialSaveMs >= PARTIAL_SAVE_INTERVAL_MS) {
            lastPartialSaveMs = now
            try {
                preferencesManager.savePartialTranscriptionState(accumulatedText)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save partial transcription state", e)
            }
        }
    }

    private suspend fun updateAudioDuration(taskId: String, audioDurationSeconds: Double) {
        // TASK-390: column-scoped, see updateInterimResult.
        logDao.updateAudioDuration(taskId, audioDurationSeconds)
    }

    /**
     * The duration a preprocessing failure leaves on the row (TASK-522),
     * shared by every failure catch: DurationTooLong carries the real length
     * it measured before rejecting (the only true value on the streaming
     * valve, fired before any chunk decodes); otherwise the decoded seconds
     * so far repair the metadata value (0.0 for metadata-less containers).
     * A null error (the generic pipeline catch) reduces to that rule alone.
     */
    private fun failureWritebackSeconds(e: PreprocessingError?, decodedSeconds: Double = 0.0): Double? = when {
        e is PreprocessingError.DurationTooLong && e.durationSeconds > 0.0 -> e.durationSeconds
        decodedSeconds > 0.0 -> decodedSeconds
        else -> null
    }

    /**
     * TASK-568: a run that dies mid-stream must not lose what it already
     * transcribed. With progressive display ON the throttled interim writes
     * mostly cover it; this FINAL write is unthrottled and also covers the
     * progressive-OFF case (otherwise nothing at all would survive). The
     * later logError keeps the result column, so the text stays visible on
     * the ERROR row. durationMs on the ERROR row becomes the decoded-at-
     * failure seconds (see logError).
     */
    private suspend fun persistPipelineFailureContext(
        taskId: String, accumulatedText: String, decodedSeconds: Double,
    ) {
        if (accumulatedText.isNotEmpty()) {
            logDao.updateInterimResult(taskId, accumulatedText, isPartial = true)
        }
        if (decodedSeconds > 0.0) {
            logDao.updateFailureDecodedMs(taskId, (decodedSeconds * 1000).toLong())
        }
    }

    /**
     * TASK-570: one builder for the structured failure context persisted on
     * the ERROR row (backend, provider, version, chunk coverage, durations).
     * Every read is runCatching-wrapped: diagnostics must never turn a
     * failure into a crash.
     */
    private suspend fun persistFailureContext(
        taskId: String,
        error: Throwable,
        context: Context,
        processedChunks: Int? = null,
        failedChunks: Int? = null,
        blankChunks: Int? = null,
        metadataSeconds: Double? = null,
        decodedSeconds: Double? = null,
        backendId: String? = null,
    ) {
        // Whole body guarded, not just the reads: a JSONException on a
        // non-finite double or a SQLiteException on a locked DB must never
        // escape this helper (the OOM catch site calls it; an exception
        // raised inside a catch block is not caught by the sibling handler
        // and would abort the service's queue loop).
        runCatching {
            val version = com.antivocale.app.util.FeedbackHelper.currentVersionName(context)
            val provider = runCatching {
                InferenceProvider.resolve(preferencesManager.inferenceProvider.first())
            }.getOrNull()
            val resolvedBackend = backendId
                ?: runCatching { backendManager.getActiveBackend()?.id }.getOrNull()
            logDao.updateFailureContext(
                taskId,
                FailureContextJson.toJson(
                    FailureContext(
                        errorClass = (error as? PipelineFailure)?.cause
                            ?.let { "PipelineFailure(${it::class.simpleName})" }
                            ?: "${error::class.simpleName}",
                        backendId = resolvedBackend,
                        provider = provider,
                        appVersion = version,
                        processedChunks = processedChunks,
                        failedChunks = failedChunks,
                        blankChunks = blankChunks
                            ?: (error as? TranscriptionException.NoTranscriptionProduced)?.blankChunks
                            ?: (error as? BlankSegmentsException)?.blankChunks,
                        metadataSeconds = metadataSeconds,
                        decodedSeconds = decodedSeconds,
                    )))
        }
    }

    /**
     * TASK-568: carries the failure point out of the streaming loop so the
     * caller can word the error notification as decoded-of-total ("failed
     * after 23 of 77 minutes") instead of a bare error string. The cause is
     * the original exception (a [PreprocessingError] on the typed path);
     * [userFacingErrorMessage] unwraps it so typed preprocessing advice
     * still reaches the notification.
     */
    class PipelineFailure(
        cause: Throwable,
        val decodedSeconds: Double,
        val totalSeconds: Double,
    ) : IllegalStateException("Pipeline failed: ${cause.message}", cause)

    /**
     * TASK-606 F2: a chunk generation timeout means the LLM engine is
     * wedged, not that the chunk is hard. Feeding it to retryChunkWithGc
     * burns a second full ceiling per chunk and letting the loop continue
     * burns one per remaining chunk (a 30-chunk recording at 2 x 5min each
     * was a ~5-hour foreground crawl). The loops abort the whole run
     * instead; LlmManager's engineWedged flag makes the surviving
     * generations fail fast.
     */
    class WedgeAbortException(cause: Throwable) :
        IllegalStateException(
            "LLM engine wedged (chunk generation timeout); run aborted to spare the remaining chunks",
            cause)

    /**
     * TASK-622: the progressive path's no-text terminal state (all segments
     * blank, or a fail+blank mix). Carries the blank count to the ERROR row
     * the same way [TranscriptionException.NoTranscriptionProduced] does.
     */
    class BlankSegmentsException(
        blankChunks: Int,
        message: String,
    ) : IllegalStateException(message) {
        val blankChunks: Int = blankChunks
    }

    /**
     * The one streaming-failure tail (TASK-568, shared by both catches):
     * persist the salvaged text and the decoded-at-failure seconds, keep the
     * row length at the larger of header-total and writeback (TASK-522's
     * DurationTooLong-measured length or the decoded seconds, which repairs
     * the metadata-less container), and wrap the failure so the notification
     * site can append the decoded-of-total sentence.
     */
    private suspend fun pipelineFailed(
        taskId: String,
        cause: Throwable,
        accumulatedText: String,
        decodedSeconds: Double,
        totalDurationSeconds: Double,
        processedChunks: Int,
        failedChunks: Int,
        blankChunks: Int = 0,
        context: Context,
        backendId: String?,
    ): Result<Nothing> {
        persistPipelineFailureContext(taskId, accumulatedText, decodedSeconds)
        persistFailureContext(
            taskId, cause, context,
            processedChunks = processedChunks, failedChunks = failedChunks,
            blankChunks = blankChunks.takeIf { it > 0 },
            metadataSeconds = totalDurationSeconds, decodedSeconds = decodedSeconds,
            backendId = backendId)
        failureWritebackSeconds(cause as? PreprocessingError, decodedSeconds)
            ?.takeIf { it > totalDurationSeconds }
            ?.let { updateAudioDuration(taskId, it) }
        return Result.failure(PipelineFailure(cause, decodedSeconds, totalDurationSeconds))
    }

    // ---- Chunk Retry ----

    /** Retries after GC to reclaim ONNX tensor memory on low-RAM devices. */
    private suspend fun retryChunkWithGc(
        backend: TranscriptionBackend,
        samples: FloatArray,
        sampleRate: Int,
        prompt: String
    ): Result<TranscriptionResult> {
        System.gc()
        delay(100)
        return backend.transcribeAudio(samples = samples, sampleRate = sampleRate, prompt = prompt)
    }

    // ---- Utilities ----

    /**
     * Saved model path for [backendId], read via the registry descriptor's model-path
     * flow (TASK-322; the descriptor for the LLM backend already points at the generic
     * [PreferencesManager.modelPath]). Any unknown id degrades to the generic one,
     * matching the former string-keyed when.
     */
    private suspend fun modelPathForBackend(backendId: String): String {
        val descriptor = backendRegistry.byBackendId(backendId)
        return when {
            descriptor != null -> descriptor.modelPathFlow(preferencesManager).first()
            else -> preferencesManager.modelPath.first()
        } ?: ""
    }

    /**
     * TASK-601: the display name for a backend id, variant-aware when a
     * saved model path resolves, the id itself as the fallback. Shared by
     * the refining status, the Logs model credit, and the notification's
     * "Refined from" line (the GH #45 credit site keeps its own chain:
     * its unknown-id fallback is the backend's display name, not the raw
     * id, and it is not this helper's to change).
     */
    private suspend fun displayNameForBackend(context: Context, backendId: String): String =
        backendRegistry.byBackendId(backendId)
            ?.let { variantAwareDisplayName(context, it, modelPathForBackend(backendId)) }
            ?: backendId

    internal fun deriveDisplayName(backendId: String, modelPath: String, fallbackName: String?): String {
        val dirName = File(modelPath).name
        return when (backendId) {
            BuiltInBackendIds.WHISPER -> {
                val variant = dirName.removePrefix("sherpa-onnx-whisper-")
                    .replace("-", " ")
                    .replaceFirstChar { it.uppercase() }
                if (variant.isNotEmpty()) "Whisper $variant" else fallbackName ?: "Whisper"
            }
            BuiltInBackendIds.QWEN3_ASR -> {
                dirName.removePrefix("sherpa-onnx-qwen3-asr-")
                    .replace("-int8", "")
                    .replace("-", " ")
                    .replaceFirstChar { it.uppercase() }
            }
            else -> fallbackName ?: backendId
        }
    }

    internal fun formatEta(
        remainingSeconds: Long,
        confidence: TranscriptionCalibrator.CalibrationProfile.Confidence
    ): String {
        if (remainingSeconds <= 0) return ""
        return when (confidence) {
            TranscriptionCalibrator.CalibrationProfile.Confidence.HIGH -> {
                when {
                    remainingSeconds < 60 -> "${remainingSeconds}s remaining"
                    remainingSeconds < 3600 -> {
                        val min = remainingSeconds / 60
                        val sec = remainingSeconds % 60
                        "~${min}m ${sec}s remaining"
                    }
                    else -> {
                        val hr = remainingSeconds / 3600
                        val min = (remainingSeconds % 3600) / 60
                        "~${hr}h ${min}m remaining"
                    }
                }
            }
            TranscriptionCalibrator.CalibrationProfile.Confidence.LOW -> {
                val min = remainingSeconds / 60
                val sec = remainingSeconds % 60
                "Est. ~${min}m ${sec}s remaining"
            }
            else -> ""
        }
    }

    internal fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    internal fun aggregateConfidence(current: Float?, next: Float?): Float? {
        if (current == null) return next
        if (next == null) return current
        return minOf(current, next)
    }

    internal fun queueAwareAudioLabel(queuePosition: Int, queueTotal: Int): String =
        if (queueTotal > 1) "Processing audio ($queuePosition of $queueTotal)…"
        else "Processing audio…"

    internal fun queueAwareChunkLabel(completed: Int, totalChunks: Int, queuePosition: Int, queueTotal: Int): String =
        if (queueTotal > 1) "Processing chunk $completed/$totalChunks ($queuePosition of $queueTotal)…"
        else "Processing chunk $completed/$totalChunks…"
}
