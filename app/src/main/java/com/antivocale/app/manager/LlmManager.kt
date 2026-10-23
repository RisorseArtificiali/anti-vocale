package com.antivocale.app.manager

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.di.ApplicationScope
import com.antivocale.app.transcription.TranscriptionException
import com.antivocale.app.util.NativeKeepAlive
import com.google.ai.edge.litertlm.*
import com.antivocale.app.util.WavUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton manager for on-device LLM inference.
 *
 * Supports two backends:
 * 1. **LiteRT-LM** (preferred): Multimodal inference with audio support
 * 2. **MediaPipe Tasks GenAI** (fallback): Text-only inference
 *
 * The manager automatically selects the best available backend.
 * For audio transcription, LiteRT-LM uses Gemma's native audio encoder.
 *
 * Handles:
 * - Model initialization and lifecycle
 * - Text generation
 * - Audio transcription (multimodal)
 * - Keep-alive timeout for automatic unloading
 */
/**
 * TASK-606: the wedge marker. Extends TimeoutException so existing
 * timeout-shaped matching keeps working, but the chunk loops and the
 * summarize ladder dispatch on THIS subtype: a plain TimeoutException
 * (a healthy-but-slow generation whose native cancel returned promptly)
 * is an ordinary failure worth a GC retry, while this marker means the
 * engine itself stopped responding and the run must abort. This class
 * must stay ABOVE LlmManager's annotations: a class inserted between
 * @Singleton and the decorated class silently steals the annotation
 * (fifteenth review, bytecode-verified on the Dagger component).
 */
class EngineWedgeTimeoutException(message: String) :
    java.util.concurrent.TimeoutException(message)

@Singleton
open class LlmManager @Inject constructor(
    // Shared process-lifetime scope (TASK-438; see [ApplicationScope]) for the
    // keep-alive timer and callback dispatches. Never cancelled here: its
    // shutdown() cancels only the keep-alive Job.
    @ApplicationScope private val managerScope: CoroutineScope
) {

    /** TASK-594: the per-generation ceiling, injectable for the unit test
     *  (the NativeKeepAlive idleUnloadWindowHook settable-seam precedent;
     *  volatile for the same cross-thread reason). Generous by default:
     *  on-device summaries legitimately take minutes. */
    @VisibleForTesting
    @Volatile
    internal var generationTimeoutMs: Long = DEFAULT_GENERATION_TIMEOUT_MS

    /**
     * TASK-606 F8: cross-attempt wedge memory. Set the first time a
     * generation ceiling fires (LiteRT) or the MediaPipe deadline passes;
     * every later generation fails fast instead of burning another full
     * ceiling on an engine that already proved wedged (the summarize
     * ladder's second instruction, the remaining audio chunks, the retry
     * arms). Cleared only by a successful [initialize]: a re-init is the
     * one recovery action that plausibly unwedges the engine.
     */
    @VisibleForTesting
    internal var engineWedged: Boolean
        get() = engineWedgedFlag.get()
        set(value) { engineWedgedFlag.set(value) }

    /** TASK-791 review F2: atomic backing so the teardown can clear by
     *  compareAndSet(SNAPSHOT) and a latch landing between its read and its
     *  clear survives instead of being clobbered. */
    private val engineWedgedFlag = AtomicBoolean(false)

    /** TASK-606 F9: the deadline for one native cancelProcess attempt.
     *  Injectable for the unit test (the generationTimeoutMs precedent). */
    @VisibleForTesting
    @Volatile
    internal var nativeCancelTimeoutMs: Long = DEFAULT_NATIVE_CANCEL_TIMEOUT_MS

    /** TASK-606 (round 15): wedged engines leaked by the teardown's
     *  skip-close this process; surfaced in the wedge message because the
     *  remedy (restart) is a user action. TASK-791: atomic because the
     *  drain-window skip-close increments off the manager monitor. */
    private val wedgedLeaks = java.util.concurrent.atomic.AtomicInteger(0)

    companion object {
        private const val TAG = "LlmManager"
        /** TASK-520: per-call ceiling for one LiteRT generation. Generous
         * because on-device summaries legitimately take minutes; the value
         * exists so a hung stream becomes a failure, not a frozen service. */
        private const val DEFAULT_GENERATION_TIMEOUT_MS = 5 * 60_000L
        /** TASK-606 F9: generous for a healthy engine's cancel, short
         *  against a wedged one (the abandoned thread is the cost). */
        private const val DEFAULT_NATIVE_CANCEL_TIMEOUT_MS = 5_000L
        private const val MAX_TOKENS = 2048

        // Single source of truth for the LiteRT conversation/sampler config of TEXT
        // pass generations (each generation creates its own fresh conversation with
        // this config). Hoisted so the text path cannot drift. Internal for the
        // pinned unit test.
        internal val DEFAULT_CONVERSATION_CONFIG = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8)
        )

        // TASK-370 E1+E2: dedicated config for the audio-transcription path. The chat-tuned
        // sampler (temperature 0.8) on a fresh-per-chunk session made the model answer as a
        // conversational assistant: refusals ("I can't process that request") and language
        // drift (German/French chunks) on the 2026-08-23 240s device run, vs the Edge Gallery
        // reference which transcribes inside a persistent session with a system instruction.
        // A transcript is deterministic content: sample greedily and instruct verbatim output.
        internal val AUDIO_CONVERSATION_CONFIG = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            // E2b REFUTED ON DEVICE 2026-08-24 (g240-e2b): the "SAME language /
            // do NOT translate" instruction produced catastrophic repetition loops
            // with greedy sampling (19,224 chars vs the 3,750-char Parakeet control;
            // the tail repeats one sentence indefinitely). The original verbatim
            // instruction measured best (2,832 chars, 0 refusals): keep it.
            systemInstruction = Contents.of(
                "You are a speech-transcription engine. Transcribe the audio verbatim in its " +
                    "original language. Output only the transcription, with no commentary, no " +
                    "translation unless the user prompt explicitly asks for it."
            )
        )
    }

    // Reactive state for UI observation
    private val _isReady = MutableStateFlow(false)
    val isReadyFlow: StateFlow<Boolean> = _isReady.asStateFlow()

    // Backend enum
    enum class Backend {
        LITERT_LM,      // LiteRT-LM (multimodal: text + audio)
        MEDIAPIPE_GENAI // MediaPipe Tasks GenAI (text only)
    }

    // Current backend being used
    private var currentBackend: Backend? = null

    // LiteRT-LM engine (preferred for multimodal)
    private var litertEngine: Engine? = null

    // MediaPipe fallback (text only). MediaPipe's LlmInference is deprecated upstream
    // (GenAI is in maintenance mode, superseded by LiteRT-LM), but retained as a text-only
    // fallback for when LiteRT-LM init fails. Removing the fallback is a separate decision.
    @Suppress("DEPRECATION")
    private var mediapipeInference: com.google.mediapipe.tasks.genai.llminference.LlmInference? = null

    // Common state
    private var modelPath: String? = null
    private var isInitialized = false
    private var appContext: Context? = null

    // Keep-alive timeout management

    // TASK-451: the shared idle-unload component (util.NativeKeepAlive, born in
    // TASK-344 for sherpa) replaces this manager's hand-rolled timer. The old
    // timer only RESET before a generation, so a generation longer than the
    // timeout unloaded the engine mid-stream; the component's work-in-flight
    // bracket pauses the countdown for the whole call, re-arming on completion
    // (including failure), and re-checks inactivity after the delay.
    // The scope carries no dispatcher (ApplicationScope contract): the
    // component's launch falls back to Dispatchers.Default, exactly the old
    // timer's explicit default, so the fire path's dispatcher is unchanged.
    private val keepAlive = NativeKeepAlive(
        scope = managerScope,
        tag = TAG,
        defaultTimeoutMinutes = PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT,
        onIdleUnload = { performAutoUnload() },
    )

    // Mutex to serialize audio transcription — LiteRT-LM only supports ONE conversation at a time,
    // so parallel chunk processing must be serialized to avoid "Conversation is closed" errors.
    private val audioMutex = Mutex()

    // Mutex serializing every fresh-conversation lifecycle (both generation
    // paths): LiteRT-LM permits ONE live conversation per engine, and the
    // text paths take no audioMutex, so WITHOUT this a subtitle-timeout
    // request (which bypasses the service queue) could overlap a queued
    // one and create two live conversations. audioMutex stays outer-scoped
    // for its keep-alive bracket; nesting order is always audio -> this.
    private val conversationMutex = Mutex()

    // Callback for when model is auto-unloaded
    private val onAutoUnloadCallback = AtomicReference<(() -> Unit)?>(null)

    // Callback for when model is externally loaded (e.g., via ModelPreloadReceiver)
    private val onExternalLoadCallback = AtomicReference<((String) -> Unit)?>(null)

    /**
     * Sets the keep-alive timeout in minutes.
     * After this period of inactivity, the model will be automatically unloaded.
     */
    fun setKeepAliveTimeout(minutes: Int) {
        keepAlive.setTimeout(if (minutes > 0) minutes else PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT)
    }

    /** TASK-451 test seam: the shared idle-unload component this manager runs on. */
    @androidx.annotation.VisibleForTesting
    internal fun keepAliveForTest(): NativeKeepAlive = keepAlive

    /**
     * TASK-644 test seam: mark the engine warm on [path] WITHOUT any native
     * load, so JVM tests can pin the initialize residency contract (the
     * path-aware re-init) without the LiteRT stack.
     */
    @androidx.annotation.VisibleForTesting
    internal fun warmForTest(path: String) {
        modelPath = path
        isInitialized = true
        _isReady.value = true
    }

    /**
     * Sets a callback to be invoked when the model is automatically unloaded due to timeout.
     */
    fun setOnAutoUnloadCallback(callback: (() -> Unit)?) {
        onAutoUnloadCallback.set(callback)
    }

    /**
     * Sets a callback to be invoked when the model is loaded externally (e.g., via ModelPreloadReceiver).
     * The callback receives the model path as parameter.
     */
    fun setOnExternalLoadCallback(callback: ((String) -> Unit)?) {
        onExternalLoadCallback.set(callback)
    }

    /**
     * Notifies listeners that the model was loaded externally.
     * Called by ModelPreloadReceiver after successful model loading.
     */
    fun notifyExternalLoad(path: String) {
        onExternalLoadCallback.get()?.let { callback ->
            managerScope.launch(Dispatchers.Main) {
                callback.invoke(path)
            }
        }
    }

    /**
     * Gets the current backend being used.
     */
    fun getCurrentBackend(): Backend? = currentBackend

    /**
     * Checks if LiteRT-LM backend is available (always true if dependency is included).
     */
    fun isLiteRTAvailable(): Boolean = true

    /**
     * Initializes the LLM with the specified model file.
     *
     * Automatically selects the best available backend:
     * - LiteRT-LM for .litertlm files (supports multimodal)
     * - MediaPipe Tasks GenAI for .task files (text only)
     *
     * @param context Application context
     * @param path Absolute path to the model file (.litertlm or .task)
     * @return Result.success if initialization succeeded
     */
    @Synchronized
    fun initialize(context: Context, path: String): Result<Unit> {
        if (isInitialized) {
            // TASK-644: residency keys on the model path, not the initialized
            // flag alone. The engine is warmed outside backendManager's
            // bookkeeping (the preload receiver initializes directly; a later
            // saved-path change reaches LlmTranscriptionBackend.initialize
            // with the manager's activeBackend already null, so the
            // orchestrator's variantChanged cannot intercept it), and a path
            // switch while warm would silently keep serving the OLD variant
            // through the early return. Deliberately STRICTER than the
            // orchestrator's two-sided blank-tolerant compare: a blank
            // resident path cannot co-occur with isInitialized here (unload
            // nulls both together), so any divergence is a real switch.
            // Converging both into one shared identity check is the
            // manager-side residency-token follow-up recorded in TASK-644.
            if (path != modelPath) {
                Log.i(TAG, "Model path changed while initialized ($modelPath -> $path); re-initializing")
                // TASK-791 accepted cost (same as the sherpa swap): with a
                // generation in flight the old engine stays open until the
                // DRAIN while the new multi-GB engine constructs here,
                // doubling peak native memory; the drain needs a true idle
                // instant, so sustained back-to-back generations on the new
                // engine can defer it well past one generation.
                unload()
            } else if (engineWedged) {
                // TASK-606 F3: the advertised recovery must be reachable. A
                // wedged engine must not early-return READY: tear it down
                // (unload skips the native close on a wedged engine, see
                // below) and run a full initialize in its place.
                Log.w(TAG, "Engine is wedged; forcing re-initialization")
                unload()
            } else {
                Log.w(TAG, "Model already initialized, resetting keep-alive timer")
                resetKeepAliveTimer()
                return Result.success(Unit)
            }
        }

        Log.i(TAG, "Initializing model from: $path")

        // Validate file exists and is not empty. A 0-byte file passes a bare
        // exists() check and makes the LiteRT-LM engine fail deep inside the
        // native stack with an opaque "model is null" (the email bug report
        // of 2026-09-22): surface the real cause instead.
        val modelFile = File(path)
        if (!modelFile.exists()) {
            return Result.failure(TranscriptionException.ModelLoadError("file not found: $path"))
        }
        // Every real .litertlm/.task model is gigabyte-scale: an empty or
        // sub-1MB file is a truncated download, and LiteRT-LM would fail
        // deep in the native stack with the opaque "model is null" instead.
        if (modelFile.length() < 1L * 1024 * 1024) {
            return Result.failure(TranscriptionException.ModelLoadError(
                "model file is empty or truncated (${modelFile.length()} bytes, " +
                    "expected gigabyte-scale): $path; delete and re-download it"))
        }

        appContext = context.applicationContext

        // Determine backend based on file extension
        val useLiteRT = path.endsWith(".litertlm", ignoreCase = true)

        return if (useLiteRT) {
            initializeLiteRT(context, path)
        } else {
            initializeMediaPipe(context, path)
        }
    }

    /**
     * Initializes LiteRT-LM backend.
     */
    private fun initializeLiteRT(context: Context, path: String): Result<Unit> {
        return try {
            Log.i(TAG, "Initializing LiteRT-LM engine...")
            Log.i(TAG, "Model path: $path")
            Log.i(TAG, "Model file size: ${File(path).length()} bytes")

            // Set minimal logging from native layer
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

            // Configure engine - use CPU backend for reliability
            // IMPORTANT: audioBackend MUST be set for multimodal audio processing
            // See: https://github.com/google-ai-edge/LiteRT-LM/issues/1131
            val engineConfig = EngineConfig(
                modelPath = path,
                backend = com.google.ai.edge.litertlm.Backend.CPU(),
                audioBackend = com.google.ai.edge.litertlm.Backend.CPU(),  // Required for audio!
                cacheDir = context.cacheDir.absolutePath
            )

            Log.i(TAG, "Creating LiteRT engine...")
            litertEngine = Engine(engineConfig)

            Log.i(TAG, "Initializing engine (this may take 10-30 seconds)...")
            litertEngine!!.initialize()

            // Init-time smoke: a model that initializes but cannot host a
            // conversation must fail HERE (falling back to MediaPipe inside
            // this try), not minutes later mid-request at the first
            // generation. The shared conversation this replaces used to
            // provide this fail-fast for free.
            closeConversationSafe(litertEngine!!.createConversation(DEFAULT_CONVERSATION_CONFIG))

            currentBackend = Backend.LITERT_LM
            modelPath = path
            // TASK-606 F8: a fresh successful initialize is the one recovery
            // action that plausibly unwedges the engine.
            engineWedged = false
            isInitialized = true
            _isReady.value = true
            keepAlive.start()

            Log.i(TAG, "LiteRT-LM engine initialized successfully (multimodal)")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "LiteRT-LM initialization failed", e)
            Log.e(TAG, "Exception type: ${e.javaClass.name}")
            Log.e(TAG, "Exception message: ${e.message}")
            e.printStackTrace()
            // Try MediaPipe as fallback
            initializeMediaPipe(context, path)
        } catch (e: Error) {
            // Catch UnsatisfiedLinkError and other Errors
            Log.e(TAG, "LiteRT-LM native error", e)
            Log.e(TAG, "Error type: ${e.javaClass.name}")
            Log.e(TAG, "Error message: ${e.message}")
            initializeMediaPipe(context, path)
        }
    }

    /**
     * Initializes MediaPipe backend (fallback).
     */
    @Suppress("DEPRECATION") // MediaPipe LlmInference deprecated upstream; fallback retained (see mediapipeInference)
    private fun initializeMediaPipe(context: Context, path: String): Result<Unit> {
        return try {
            Log.i(TAG, "Initializing MediaPipe backend...")

            val options = com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(MAX_TOKENS)
                .setMaxTopK(40)
                .build()

            mediapipeInference = com.google.mediapipe.tasks.genai.llminference.LlmInference.createFromOptions(context, options)

            currentBackend = Backend.MEDIAPIPE_GENAI
            modelPath = path
            engineWedged = false
            isInitialized = true
            _isReady.value = true
            keepAlive.start()

            Log.i(TAG, "MediaPipe backend initialized (text only)")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe initialization also failed", e)
            Result.failure(TranscriptionException.ModelLoadError(e.message ?: "unknown", e))
        }
    }

    /**
     * Generates text from a text prompt.
     *
     * @param prompt The input prompt
     * @return Result containing the generated text
     */
    suspend fun generateText(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        if (engineWedged) return@withContext Result.failure(wedgedFailure())
        if (!isInitialized) {
            return@withContext Result.failure(IllegalStateException("Model not initialized"))
        }

        Log.d(TAG, "Generating text for prompt: ${prompt.take(50)}...")

        // TASK-451: the bracket, not a reset. The old reset-before-generate let
        // a generation longer than the idle timeout unload the engine
        // mid-stream; withWork pauses the countdown for the whole call.
        return@withContext keepAlive.withWork {
            when (currentBackend) {
                Backend.LITERT_LM -> generateTextLiteRT(prompt)
                Backend.MEDIAPIPE_GENAI -> generateTextMediaPipe(prompt)
                null -> Result.failure(IllegalStateException("No backend initialized"))
            }
        }
    }

    /**
     * TASK-594: one LiteRT generation under a ceiling. withTimeoutOrNull
     * alone cancels only the awaiting coroutine (litertlm's callbackFlow
     * awaitClose is a no-op, bytecode-verified 0.13.1), so on timeout the
     * native generation must be explicitly cancelled via [cancel]:
     * otherwise the session stays wedged and every later generation on
     * a fresh conversation burns another full ceiling, leaking
     * one native callback per timeout. The same coverage applies to a
     * CALLER cancellation, which escapes withTimeoutOrNull untouched:
     * it cancels the native generation too, then rethrows. Internal for
     * the unit test.
     * Contract: the block must return NON-NULL (production callers pass a
     * Boolean sentinel), because null is the ceiling signal; a nullable
     * result cannot be distinguished from the timeout. Internal for the
     * unit test.
     * @return the block's value, or null when the ceiling fired.
     */
    internal suspend fun <T : Any> withGenerationCeiling(
        cancel: () -> Unit,
        block: suspend () -> T,
    ): T? = try {
        // A non-positive ceiling would shortcut to null without running
        // the block (withTimeoutOrNull semantics), misread as a timeout.
        withTimeoutOrNull(generationTimeoutMs.coerceAtLeast(1L)) { block() }
    } catch (e: CancellationException) {
        // The caller is already cancelling, so the deadline cannot be
        // honored here (join() throws at entry); run the cancel unbounded
        // and surface a native Error as suppressed (TASK-594 F1: it must
        // not vanish under a user or service cancellation).
        // TASK-791 review W1: the SAME TASK-606 F2 discrimination as the
        // timeout arm below applies here. A cancel that did NOT come back
        // is not a routine cancel: an abandoned cancelProcess is still
        // inside the native engine, and without latching the wedge the
        // drain release would close it underneath (use-after-close).
        // Routine caller cancels return promptly and stay non-wedging.
        runCatching { cancelWithDeadline(cancel, "caller cancellation") }
            .getOrNull()?.let { outcome ->
                if (!outcome.returned) engineWedged = true
                outcome.error?.let { e.addSuppressed(it) }
            }
        throw e
    }.also {
        if (it == null) {
            val outcome = cancelWithDeadline(cancel, "timeout")
            // TASK-606 F2: only a cancel that did NOT come back marks the
            // engine wedged; a prompt cancel on a ceiling fire means a slow
            // generation, which a plain GC retry may still complete.
            if (!outcome.returned) engineWedged = true
            // TASK-594 F1 preserved: a native Error from the cancel escapes
            // to the caller instead of dying on the manager scope.
            outcome.error?.let { throw it }
        }
    }

    /** TASK-606 F8: the fail-fast failure every wedged entry returns. */
    private fun wedgedFailure(): EngineWedgeTimeoutException = EngineWedgeTimeoutException(
        "LLM engine is wedged after a generation timeout; re-initialize the model to retry" +
            wedgedLeaks.get().takeIf { it > 0 }?.let { " ($it leaked engine(s) this process; restart the app)" }.orEmpty())

    /**
     * TASK-606 F9 + F2: one native cancel under a deadline. Returns TRUE when
     * the cancel returned promptly (the engine is responsive: a ceiling fire
     * on such an engine is a slow generation, not a wedge), FALSE when it was
     * abandoned or failed (the engine is not responding: the caller marks it
     * wedged). A native [Error] from the cancel is captured for the caller to
     * rethrow, preserving the TASK-594 F1 contract (the audio path's
     * catch(Error) skips the corrupt engine); it cannot propagate from the
     * manager-scope job by itself.
     */
    private class CancelOutcome(val returned: Boolean, val error: Error?)

    private suspend fun cancelWithDeadline(cancel: () -> Unit, cause: String): CancelOutcome {
        // The cancel itself can block on the wedged engine's internal lock
        // (cancelProcess is synchronous native): dispatch it with a deadline
        // and ABANDON it if it does not return. The abandoned job's thread
        // keeps grinding alone, which beats freezing the caller one ceiling
        // later (a leaked thread beats a frozen service).
        // AtomicReference: on the abandon path the caller reads the holder
        // with no happens-before edge to the still-running worker; a plain
        // captured var would be a data race (round 15/16 finding).
        val holder = java.util.concurrent.atomic.AtomicReference<Error?>(null)
        val job = managerScope.launch(Dispatchers.IO) {
            try {
                cancel()
            } catch (e: Error) {
                holder.set(e)
            } catch (e: Exception) {
                Log.w(TAG, "native generation cancel after $cause failed", e)
            }
        }
        // TASK-791 review F1 (probe-proven): on a GENUINE caller
        // cancellation the invoking coroutine's Job is already cancelled, so
        // withTimeoutOrNull { job.join() } threw CancellationException at
        // ENTRY and this outcome never came back (the wedge latch and the
        // TASK-594 F1 suppress were dead code on the primary cancel path).
        // The bound runs NonCancellable: a cancelling caller waits up to the
        // deadline (this is the cancel path, not the generation path), and
        // the returned/dropped discrimination below finally holds for real
        // cancels too.
        val done = withContext(NonCancellable) {
            withTimeoutOrNull(nativeCancelTimeoutMs) { job.join() }
        }
        if (done == null) {
            Log.w(TAG, "native generation cancel after $cause did not return in " +
                "${nativeCancelTimeoutMs}ms; abandoning it (thread leaks, caller proceeds)")
        }
        return CancelOutcome(returned = done != null && holder.get() == null, error = holder.get())
    }

    /**
     * TASK-594: the shared body of both LiteRT generations (text chat and
     * audio transcription): one ceiling, one native-cancel, one timeout
     * translation, so the two sites cannot drift (review low finding).
     */
    private suspend fun generateUnderCeiling(
        conversation: Conversation,
        label: String,
        contents: Contents,
    ): Result<String> = try {
        val response = StringBuilder()
        val completed = withGenerationCeiling(cancel = { conversation.cancelProcess() }) {
            conversation.sendMessageAsync(contents)
                // No .catch: let mid-stream errors propagate to the outer catch
                // (no silent partial success).
                .collect { message ->
                    response.append(message.toString())
                }
            true
        }
        if (completed == null) {
            Log.e(TAG, "LiteRT $label generation timed out after ${generationTimeoutMs / 1000}s")
            // TASK-606 F2 discrimination: withGenerationCeiling marked the
            // engine wedged ONLY when the native cancel did not come back
            // (a stuck engine). A prompt cancel on a ceiling fire means a
            // slow generation: the plain timeout keeps the GC retry path.
            if (engineWedged) {
                Result.failure(EngineWedgeTimeoutException(
                    "LiteRT $label generation timed out after ${generationTimeoutMs / 1000}s"))
            } else {
                // TASK-607 F5: the project's typed seam, not the raw JDK type
                // (any future wrapping stays compiler-checkable).
                Result.failure(com.antivocale.app.transcription.TranscriptionException.GenerationTimeout(
                    "LiteRT $label generation timed out after ${generationTimeoutMs / 1000}s"))
            }
        } else {
            Result.success(response.toString())
        }
    } catch (e: CancellationException) {
        // TASK-594/607 F4: caller cancellation is not a generation failure...
        // but litertlm's onError can close the flow channel with a NATIVE
        // CancellationException (an arbitrary Throwable) for a run the user
        // never cancelled; treating that as caller cancellation aborts a
        // live run and discards the accumulated transcript. Only propagate
        // when the CALLER's job is actually being cancelled; a native cancel
        // surfaces as a generation failure and degrades gracefully.
        if (kotlinx.coroutines.currentCoroutineContext().isActive) {
            Log.e(TAG, "LiteRT $label generation hit a NATIVE cancellation (caller job alive): treating as failure", e)
            Result.failure(e)
        } else {
            throw e
        }
    } catch (e: Exception) {
        Log.e(TAG, "LiteRT $label generation failed", e)
        // DRAFT-11: the ONE place the JNI overflow message exists unwrapped.
        // The typed verdict replaces the consumers' string matching; the
        // anchored phrase is the only signal the JNI gives.
        if (e.message?.contains(
                com.antivocale.app.transcription.SummaryPolicy.PREFILL_OVERFLOW_SIGNAL) == true
        ) {
            Result.failure(
                com.antivocale.app.transcription.TranscriptionException.PrefillOverflow(e))
        } else {
            Result.failure(e)
        }
    }

    /**
     * Generates text using LiteRT-LM backend: a FRESH conversation per call,
     * under the generation ceiling. Pass work (punctuation, summaries) is
     * deterministic one-shot content and must not share state: the former
     * shared conversation accumulated state entries until the summary
     * map-reduce overflowed it on device (LiteRtLmJniException "Prefill
     * input length exceeds available state entries (remaining capacity:
     * 1398)", 2026-09-25: chunk 1 consumed the budget, chunks 2..4 could not
     * enter). The TASK-370 lesson for the audio path, applied to text.
     */
    private suspend fun generateTextLiteRT(prompt: String): Result<String> =
        generateInFreshConversation(
            DEFAULT_CONVERSATION_CONFIG,
            label = "text",
            Contents.of(Content.Text(prompt)),
        )

    /**
     * The ONE fresh-conversation lifecycle, shared by both LiteRT generation
     * paths (the generateUnderCeiling precedent: two hand-rolled copies drift;
     * the drift was found in review the day the second copy was born). Owns
     * create-with-catch, the ceiling, the native-Error wrap, and the
     * close-in-finally: a caller-cancellation rethrow must leak the
     * conversation ONLY under the documented TASK-606 abandonment rule (the
     * abandoned native cancel may still be inside it; the engine unloads it),
     * and a native Error skips the close entirely (same rule, wedged side),
     * because the library holds ONE live session per engine.
     */
    private suspend fun generateInFreshConversation(
        config: ConversationConfig,
        label: String,
        contents: Contents,
    ): Result<String> = conversationMutex.withLock {
        val engine = litertEngine
            ?: return Result.failure(IllegalStateException("LiteRT engine not available"))
        var outcome: Result<String>
        var abandonedByCaller = false
        var conversation: Conversation? = null
        try {
            conversation = engine.createConversation(config)
            outcome = generateUnderCeiling(conversation, label, contents)
        } catch (e: CancellationException) {
            // The caller abandoned this generation; the ceiling already
            // dispatched an unawaited native cancel that may still be inside
            // this conversation. Closing now would race it (the TASK-606
            // crash class), so the conversation is leaked until the engine
            // unloads: the safe ordering on the one-live-conversation engine.
            abandonedByCaller = true
            Log.w(TAG, "LiteRT $label generation abandoned by caller; conversation left to engine unload")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT $label generation failed", e)
            outcome = Result.failure(e)
        } catch (e: Error) {
            // Native errors (SIGSEGV, etc.): preserve the original as the
            // cause, and LATCH the wedge (the MediaPipe-timeout precedent):
            // the engine may be corrupt, and the next generation must fail
            // fast instead of re-entering native createConversation.
            engineWedged = true
            Log.e(TAG, "LiteRT native error ($label); engine marked wedged", e)
            outcome = Result.failure(IllegalStateException("Native error during $label generation: ${e.message}", e))
        } finally {
            if (!engineWedged && !abandonedByCaller) closeConversationSafe(conversation)
        }
        outcome
    }

    /**
     * Generates text using MediaPipe backend.
     */
    private suspend fun generateTextMediaPipe(prompt: String): Result<String> {
        // TASK-606 F3: MediaPipe's generateResponse is a blocking call with no
        // native cancel, so a hang holds the delivery hostage forever. Await
        // it under the same generation ceiling; on timeout ABANDON the worker
        // thread (it keeps grinding alone) and mark the engine wedged so every
        // later generation fails fast instead of burning another ceiling.
        val inference = mediapipeInference
            ?: return Result.failure(IllegalStateException("MediaPipe inference not available"))
        val deferred = managerScope.async(Dispatchers.IO) { inference.generateResponse(prompt) }
        return try {
            val result = withTimeoutOrNull(generationTimeoutMs.coerceAtLeast(1L)) { deferred.await() }
            if (result == null) {
                Log.e(TAG, "MediaPipe generation timed out after ${generationTimeoutMs / 1000}s; " +
                    "abandoning the worker thread")
                // A still-queued job must not start later on the engine we
                // just declared wedged (round 16).
                deferred.cancel()
                engineWedged = true
                return Result.failure(EngineWedgeTimeoutException(
                    "MediaPipe generation timed out after ${generationTimeoutMs / 1000}s"))
            }
            Log.d(TAG, "MediaPipe generation complete: ${result.length} chars")
            Result.success(result)
        } catch (e: CancellationException) {
            // Cancellation always propagates (the withGenerationCeiling
            // contract): swallowing it here would misclassify a user cancel
            // as a generation failure and exit the keep-alive bracket while
            // the abandoned worker still runs the shared session. The worker
            // IS abandoned (the blocking call has no cancel), so the engine
            // is marked wedged: unload() must not close the shared session
            // underneath it, and the next call must not run concurrently.
            deferred.cancel()
            // Deliberately NOT wedging on a caller cancellation (round 16:
            // a routine cancel must not latch a permanent fail-fast on a
            // healthy engine, and the LiteRT path does not either). The
            // blocking call has no cancel, so the worker keeps running on
            // the shared session; the accepted residual risk (a concurrent
            // next call or an unload underneath it) predates this change on
            // this deprecated fallback backend.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe text generation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Generates text from audio input (transcription/understanding).
     *
     * Uses LiteRT-LM's multimodal capabilities to process audio directly
     * with Gemma's native audio encoder.
     *
     * For MediaPipe backend (text-only), returns an error indicating
     * audio is not supported.
     *
     * @param prompt The prompt (e.g., "Transcribe this speech:")
     * @param audioData WAV ByteArray (16kHz mono, 16-bit PCM)
     * @return Result containing the transcription/understanding
     */
    suspend fun generateFromAudio(prompt: String, audioData: ByteArray): Result<String> = audioMutex.withLock {
        withContext(Dispatchers.IO) {
            if (engineWedged) {
                return@withContext Result.failure(wedgedFailure())
            }
            if (!isInitialized) {
                return@withContext Result.failure(IllegalStateException("Model not initialized"))
            }

            Log.d(TAG, "Processing audio: ${audioData.size} bytes with backend: $currentBackend")

            // TASK-451: same bracket as generateText; see there.
            return@withContext keepAlive.withWork {
                when (currentBackend) {
                    Backend.LITERT_LM -> generateFromAudioLiteRT(prompt, audioData)
                    Backend.MEDIAPIPE_GENAI -> {
                        Log.w(TAG, "Audio processing not supported with MediaPipe backend")
                        Result.failure(IllegalStateException(
                            "Audio transcription requires LiteRT-LM backend with a .litertlm model. " +
                            "Current backend (MediaPipe) only supports text inference."
                        ))
                    }
                    null -> Result.failure(IllegalStateException("No backend initialized"))
                }
            }
        }
    }

    /**
     * TASK-872: VibeVoice streaming models take ONE fixed audio window per
     * conversation turn. This method creates ONE persistent Conversation,
     * sends each window as a sequential message, and concatenates the
     * responses. Each turn is individually ceiling-bounded: a slow window
     * fails the WHOLE transcription at that turn, without latching the
     * engine wedge (the engine stays usable for later requests).
     *
     * The model responds with native speaker attribution ("Speaker N: text");
     * the transcript IS the diarization. The orchestrator suppresses its own
     * GH #83 speaker pass on this route via the backend's
     * emitsNativeSpeakerLabels flag, so the model's labels are never
     * double-labeled.
     */
    suspend fun generateFromAudioWindows(prompt: String, windows: List<ByteArray>): Result<String> = audioMutex.withLock {
        withContext(Dispatchers.IO) {
            if (engineWedged) {
                return@withContext Result.failure(wedgedFailure())
            }
            if (!isInitialized) {
                return@withContext Result.failure(IllegalStateException("Model not initialized"))
            }
            if (windows.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("No audio windows"))
            }
            if (currentBackend != Backend.LITERT_LM) {
                return@withContext Result.failure(IllegalStateException("VibeVoice multi-turn requires the LiteRT-LM backend"))
            }
            return@withContext keepAlive.withWork {
                generateWindowsInPersistentConversation(prompt, windows)
            }
        }
    }

    /**
     * The persistent-conversation multi-turn loop. Mirrors
     * [generateInFreshConversation]'s error taxonomy (wedge latching,
     * caller-abandonment leaking the conversation) but holds ONE
     * conversation across all windows instead of one per call.
     */
    private suspend fun generateWindowsInPersistentConversation(
        prompt: String,
        windows: List<ByteArray>,
    ): Result<String> = conversationMutex.withLock {
        val engine = litertEngine
            ?: return Result.failure(IllegalStateException("LiteRT engine not available"))
        var conversation: Conversation? = null
        var abandonedByCaller = false
        val transcript = StringBuilder()
        try {
            conversation = engine.createConversation(AUDIO_CONVERSATION_CONFIG)
            for ((index, wav) in windows.withIndex()) {
                val turn = generateUnderCeiling(
                    conversation,
                    "audio-window-$index",
                    Contents.of(Content.AudioBytes(wav), Content.Text(prompt)),
                )
                // F2 (gate round): propagate the original failure with its
                // cause and TYPED exception preserved (the fresh path's
                // contract; a wedged window must reach the orchestrator as
                // EngineWedgeTimeoutException, not a plain wrapper).
                val text = turn.getOrNull()
                    ?: return Result.failure(turn.exceptionOrNull()
                        ?: IllegalStateException("VibeVoice window $index failed"))
                transcript.append(text)
                if (index < windows.size - 1) transcript.append('\n')
            }
            Result.success(transcript.toString())
        } catch (e: CancellationException) {
            // Same ordering as the fresh path: the ceiling's unawaited
            // native cancel may still be inside this conversation; closing
            // now would race it (the TASK-606 crash class). Leak it.
            abandonedByCaller = true
            Log.w(TAG, "VibeVoice multi-turn abandoned by caller; conversation left to engine unload")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "VibeVoice multi-turn failed", e)
            Result.failure(e)
        } catch (e: Error) {
            engineWedged = true
            Log.e(TAG, "VibeVoice native error; engine marked wedged", e)
            Result.failure(IllegalStateException("Native error during VibeVoice multi-turn: ${e.message}", e))
        } finally {
            if (!engineWedged && !abandonedByCaller) closeConversationSafe(conversation)
        }
    }

    /**
     * Closes a [Conversation] idempotently.
     *
     * LiteRT-LM's `Conversation.close()` is NOT idempotent upstream — calling it on an
     * already-closed instance throws `IllegalStateException: Conversation is closed already`.
     * During error recovery a double-close can happen, so swallow that exception here
     * (logged at WARN since it is expected during recovery, not a real error).
     */
    private fun closeConversationSafe(conversation: Conversation?) {
        if (conversation == null) return
        try {
            conversation.close()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Conversation already closed, ignoring: ${e.message}")
        }
    }

    /**
     * Generates text from audio using LiteRT-LM backend, in a FRESH
     * conversation per call through the shared lifecycle helper
     * ([generateInFreshConversation]); LiteRT-LM permits only ONE live
     * [Conversation] at a time.
     */
    private suspend fun generateFromAudioLiteRT(prompt: String, audioData: ByteArray): Result<String> =
        // E4 REFUTED ON DEVICE 2026-08-24 (g240-e2be4): raw PCM made every
        // chunk return blank ("No transcription produced"); the litertlm
        // 0.13.1 Kotlin AudioBytes path decodes the WAV container via
        // miniaudio and does NOT accept headerless PCM (the Gallery
        // reference's raw-PCM feed goes through a different layer).
        generateInFreshConversation(
            AUDIO_CONVERSATION_CONFIG,
            label = "audio",
            Contents.of(Content.AudioBytes(audioData), Content.Text(prompt)),
        )

    /**
     * Checks if the model is ready for inference.
     */
    open fun isReady(): Boolean =
        // TASK-606 (round 16): a WEDGED engine is not ready. The
        // readiness-gated reload paths (ensureBackendLoaded, the preload
        // receiver) then run initialize(), whose wedged branch tears down
        // and re-initializes: the recovery the wedge message promises.
        !engineWedged && isInitialized && (litertEngine != null || mediapipeInference != null)

    /**
     * Checks if audio processing is available.
     */
    fun isAudioSupported(): Boolean = isInitialized && currentBackend == Backend.LITERT_LM

    /**
     * Gets the current model path.
     */
    fun getModelPath(): String? = modelPath

    /** TASK-644: a native generation is in flight (the withWork bracket). */
    fun isBusy(): Boolean = keepAlive.workInFlightCount() > 0

    /**
     * Gets the remaining idle time before auto-unload in seconds.
     * Returns null if no countdown is live (not loaded, work in flight,
     * or the timer disarmed after a fire). TASK-574: this is the real
     * remaining time, not the configured timeout.
     */
    fun getRemainingTimeSeconds(): Long? {
        if (!isInitialized) return null
        return keepAlive.remainingSeconds()
    }

    /**
     * Unloads the model from memory.
     *
     * TASK-791 (the same class as the 789 FORTIFY crash): a generation may
     * be live inside the keepAlive.withWork bracket, and every generation
     * path reads the engine fields into a local inside it, so the flip
     * happens now and the close waits for the bracket drain. TASK-606 F5:
     * an engine WEDGED at either edge of that window (at unload, or during
     * it: the abandoned cancelProcess lives OUTSIDE the bracket, so the
     * bracket still drains) leaks instead of closing, with its counter and
     * message. Idempotent, except a half-constructed engine (fields
     * non-null, isInitialized false after a failed initialize) still tears
     * down. Accepted cost (the 789 swap tradeoff, both sites that open it:
     * initialize()'s path-change branch and TranscriptionBackendManager
     * .unloadAll during a backend switch): with a generation in flight the
     * old engine stays resident until the DRAIN. Since TASK-794 the drain
     * waits only for the brackets open at unload time, so back-to-back
     * generations on the new engine no longer defer it: the doubled peak
     * native memory lasts at most the in-flight generation's own return.
     */
    @Synchronized
    open fun unload() {
        if (!isInitialized && litertEngine == null && mediapipeInference == null) return
        Log.i(TAG, "Unloading model")

        keepAlive.stop()
        teardownWithDrain()
    }

    /**
     * TASK-791: the one teardown, deliberately unsynchronized. The public
     * [unload] wraps it in the manager monitor; [performAutoUnload] calls
     * it directly because the keep-alive fire already holds the keep-alive
     * monitor, and acquiring the manager monitor there (which routing
     * through [unload] would do) inverts against initialize()'s
     * manager -> keep-alive order and deadlocks the fire against a manual
     * unload. TASK-606 F5: an engine WEDGED here keeps the deliberate
     * skip-close leak with its counter and message.
     */
    private fun teardownWithDrain() {
        val wedgedAtUnload = engineWedged
        val staleEngine = litertEngine
        val staleInference = mediapipeInference
        litertEngine = null
        mediapipeInference = null
        modelPath = null
        isInitialized = false
        // F2: clear by compareAndSet(SNAPSHOT): a latch landing between the
        // read above and this clear survives (the drain re-check below sees
        // it and leaks instead of closing under the abandoned cancel).
        engineWedgedFlag.compareAndSet(wedgedAtUnload, false)
        _isReady.value = false
        currentBackend = null
        if (wedgedAtUnload) {
            leakWedgedEngine("at unload")
        } else {
            keepAlive.requestDrainRelease {
                // Re-check: a wedge can land INSIDE the drain window (the
                // abandoned cancelProcess lives outside the bracket, so the
                // bracket still drains). Visibility across the coroutine's
                // IO-thread hop rests on engineWedged being @Volatile (the
                // write precedes endWork in program order but possibly on
                // another thread); do not drop the annotation. Closing under
                // that abandoned native call is the use-after-close class
                // TASK-606 exists to prevent: leak instead. The flag is
                // manager-global: a wedge latched by a RE-INITIALIZED
                // engine inside this window also leaks the old one (safe
                // direction; the per-engine token is the TASK-644 follow-up).
                if (engineWedged) {
                    leakWedgedEngine("during the drain window")
                } else {
                    // One catch per close: Engine.close() carries no
                    // non-throwing guarantee, and a throwing first close
                    // must not skip the second (run close on the generation
                    // thread under the keep-alive monitor: the accepted
                    // sherpa-789 discipline, it blocks this bracket's result
                    // delivery for the free's duration).
                    staleEngine?.let { runCatching { it.close() }
                        .onFailure { Log.e(TAG, "LiteRT engine close threw", it) } }
                    staleInference?.let { runCatching { it.close() }
                        .onFailure { Log.e(TAG, "MediaPipe inference close threw", it) } }
                }
            }
        }
    }

    /** TASK-606 F5: the deliberate skip-close, with its counter and message. */
    private fun leakWedgedEngine(whenText: String) {
        val leak = wedgedLeaks.incrementAndGet()
        Log.w(TAG, "Engine wedged $whenText: skipping native close (leak #$leak " +
            "this process; restart the app to reclaim it)")
    }

    /**
     * Resets the keep-alive timer, extending the time before auto-unload.
     * Call this when the model is used to prevent premature unloading.
     */
    fun resetKeepAliveTimer() {
        if (!isInitialized) return
        keepAlive.start()
    }


    private fun performAutoUnload() {
        // The KeepAlive fire condition checks inactivity, not initialization;
        // the documented re-arm race (work queued during the unload window)
        // can fire a second time on already-unloaded state. Idempotent no-op.
        if (!isInitialized) return
        // TASK-791: teardownWithDrain directly, NOT through unload(): the
        // fire thread already holds the keep-alive monitor, and taking the
        // manager monitor here would invert against initialize()'s
        // manager -> keep-alive order (deadlock). The fire already disarmed
        // the timer; the drain is the immediate path at an empty bracket,
        // under the keep-alive monitor alone (the sherpa idle discipline).
        teardownWithDrain()

        onAutoUnloadCallback.get()?.let { callback ->
            managerScope.launch(Dispatchers.Main) {
                callback.invoke()
            }
        }
    }

    /**
     * Cleans up the manager's coroutines and model state.
     * Call this when the app is being destroyed.
     *
     * Only the keep-alive Job is cancelled (TASK-438): the scope itself is the
     * shared process-lifetime applicationScope, whose contract forbids
     * cancelling it (see [ApplicationScope]). The only long-lived coroutine
     * this manager launches is the keep-alive timer, already cancelled above;
     * the two Main-dispatcher callback dispatches are momentary fire-and-forget.
     */
    fun shutdown() {
        // TASK-791: unload is idempotent now; the historical double call
        // (a defensive double-close) collapsed to one. No production caller
        // today (TASK-793 may delete the method outright).
        unload()
    }
}
