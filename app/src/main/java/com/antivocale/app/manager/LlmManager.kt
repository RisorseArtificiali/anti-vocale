package com.antivocale.app.manager

import android.content.Context
import android.util.Log
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
@Singleton
open class LlmManager @Inject constructor(
    // Shared process-lifetime scope (TASK-438; see [ApplicationScope]) for the
    // keep-alive timer and callback dispatches. Never cancelled here: its
    // shutdown() cancels only the keep-alive Job.
    @ApplicationScope private val managerScope: CoroutineScope
) {

    companion object {
        private const val TAG = "LlmManager"
        private const val MAX_TOKENS = 2048

        // Single source of truth for the LiteRT conversation/sampler config of TEXT chat,
        // shared by the initial conversation (initializeLiteRT) and the post-audio restore.
        // Hoisted so the text paths cannot drift apart. Internal for the pinned unit test.
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
    private var litertConversation: Conversation? = null

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
    fun initialize(context: Context, path: String): Result<Unit> {
        if (isInitialized) {
            Log.w(TAG, "Model already initialized, resetting keep-alive timer")
            resetKeepAliveTimer()
            return Result.success(Unit)
        }

        Log.i(TAG, "Initializing model from: $path")

        // Validate file exists
        val modelFile = File(path)
        if (!modelFile.exists()) {
            return Result.failure(TranscriptionException.ModelLoadError("file not found: $path"))
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

            Log.i(TAG, "Creating conversation...")
            // Create default conversation
            litertConversation = litertEngine!!.createConversation(DEFAULT_CONVERSATION_CONFIG)

            currentBackend = Backend.LITERT_LM
            modelPath = path
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
     * Generates text using LiteRT-LM backend.
     */
    private suspend fun generateTextLiteRT(prompt: String): Result<String> {
        return try {
            val conversation = litertConversation
                ?: return Result.failure(IllegalStateException("LiteRT conversation not available"))

            val response = StringBuilder()

            conversation.sendMessageAsync(Contents.of(Content.Text(prompt)))
                // No .catch: let mid-stream errors propagate to the outer catch (no silent partial success).
                .collect { message ->
                    response.append(message.toString())
                }

            val result = response.toString()
            Log.d(TAG, "LiteRT generation complete: ${result.length} chars")
            Result.success(result)

        } catch (e: Exception) {
            Log.e(TAG, "LiteRT text generation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Generates text using MediaPipe backend.
     */
    private suspend fun generateTextMediaPipe(prompt: String): Result<String> {
        return try {
            val result = mediapipeInference?.generateResponse(prompt)
                ?: return Result.failure(IllegalStateException("MediaPipe inference not available"))
            Log.d(TAG, "MediaPipe generation complete: ${result.length} chars")
            Result.success(result)
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
     * Generates text from audio using LiteRT-LM backend.
     *
     * LiteRT-LM permits only ONE live [Conversation] at a time, so this method closes the
     * main conversation, processes audio in a FRESH conversation, and then ALWAYS restores
     * a valid main conversation in a `finally` block — on success, exception, AND native
     * error paths. This robustness is critical for multi-chunk processing: a failure on one
     * chunk must never leave `litertConversation` pointing at a stale/closed instance,
     * otherwise subsequent chunks (and orchestrator retries) cascade into total failure.
     */
    private suspend fun generateFromAudioLiteRT(prompt: String, audioData: ByteArray): Result<String> {
        val engine = litertEngine
            ?: return Result.failure(IllegalStateException("LiteRT engine not available"))

        // Close the main session via the safe helper and NULL the field immediately so it
        // never holds a stale/closed reference. LiteRT only supports ONE session at a time.
        closeConversationSafe(litertConversation)
        litertConversation = null

        // Audio gets the ASR-tuned config (greedy sampler + transcription system
        // instruction); the main conversation restored in `finally` stays chat-tuned.
        val conversationConfig = AUDIO_CONVERSATION_CONFIG

        var freshConversation: Conversation? = null
        var outcome: Result<String> = Result.failure(IllegalStateException("Audio processing did not complete"))
        var caughtNativeError = false

        try {
            Log.d(TAG, "Creating fresh conversation for audio (temporarily replacing main session)...")
            freshConversation = engine.createConversation(conversationConfig)

            Log.d(TAG, "Processing audio in fresh conversation...")
            Log.d(TAG, "Audio data size: ${audioData.size} bytes")

            val response = StringBuilder()

            freshConversation.sendMessageAsync(
                Contents.of(
                    // E4 REFUTED ON DEVICE 2026-08-24 (g240-e2be4): raw PCM made every
                    // chunk return blank ("No transcription produced"); the litertlm
                    // 0.13.1 Kotlin AudioBytes path decodes the WAV container via
                    // miniaudio and does NOT accept headerless PCM (the Gallery
                    // reference's raw-PCM feed goes through a different layer).
                    Content.AudioBytes(audioData),
                    Content.Text(prompt)
                )
            )
                // No .catch on the flow: let a mid-stream error propagate to the outer catch
                // so a truncated transcript is reported as failure, not silent partial success.
                .collect { message ->
                    response.append(message.toString())
                }

            val result = response.toString()
            Log.d(TAG, "Fresh conversation audio processing complete: ${result.length} chars")
            outcome = Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT audio processing failed", e)
            outcome = Result.failure(e)
        } catch (e: Error) {
            // Catch native errors (SIGSEGV, etc.) — preserve the original as the cause.
            caughtNativeError = true
            Log.e(TAG, "LiteRT native error", e)
            outcome = Result.failure(IllegalStateException("Native error during audio processing: ${e.message}", e))
        } finally {
            // ALWAYS release the fresh conversation (safe helper swallows double-close).
            closeConversationSafe(freshConversation)
            freshConversation = null
            if (caughtNativeError) {
                // After a native Error the engine/runtime may be corrupt — do NOT re-enter
                // native here (createConversation), or a second crash could escape `finally`
                // (its inner catch is Exception-only) and suppress the captured `outcome`.
                // litertConversation is already null (set at entry), so the next caller sees a
                // clean "not available" rather than risking another native crash.
                Log.w(TAG, "Skipping main-conversation restore after native error (engine may be unstable)")
            } else {
                // Recreate a valid main conversation so the next chunk/retry starts from a
                // valid state on BOTH success and ordinary-exception paths.
                try {
                    // DEFAULT (chat-tuned), NOT the local `conversationConfig`: after an
                    // audio chunk the restored chat conversation must not inherit the ASR
                    // system instruction and greedy sampler, or text chat and the final
                    // generative pass (generateText reads this conversation) are conditioned
                    // as a transcription engine (reviewer-caught wiring bug).
                    litertConversation = engine.createConversation(DEFAULT_CONVERSATION_CONFIG)
                    Log.d(TAG, "Restored main conversation for text chat")
                } catch (restoreError: Exception) {
                    // Broad catch is intentional: this runs in `finally` and must never mask the
                    // real `outcome` already captured above, nor throw out of finally. Engine may
                    // be in a bad state — leave the field null so callers see the real failure
                    // rather than a stale closed conversation on the next call.
                    Log.e(TAG, "Failed to restore conversation after audio processing", restoreError)
                    litertConversation = null
                }
            }
        }

        return outcome
    }

    /**
     * Checks if the model is ready for inference.
     */
    open fun isReady(): Boolean = isInitialized && (litertEngine != null || mediapipeInference != null)

    /**
     * Checks if audio processing is available.
     */
    fun isAudioSupported(): Boolean = isInitialized && currentBackend == Backend.LITERT_LM

    /**
     * Gets the current model path.
     */
    fun getModelPath(): String? = modelPath

    /**
     * Gets the remaining time before auto-unload in seconds.
     * Returns null if no timer is running or model is not loaded.
     */
    fun getRemainingTimeSeconds(): Long? {
        if (!isInitialized || !keepAlive.isTimerActiveForTest()) return null
        return (keepAlive.currentTimeoutMinutes() * 60).toLong()
    }

    /**
     * Unloads the model from memory.
     */
    open fun unload() {
        Log.i(TAG, "Unloading model")

        keepAlive.stop()

        // Close LiteRT resources (use safe helper — never throws on double-close)
        closeConversationSafe(litertConversation)
        litertConversation = null
        litertEngine?.close()
        litertEngine = null

        // Close MediaPipe inference
        mediapipeInference?.close()
        mediapipeInference = null

        modelPath = null
        isInitialized = false
        _isReady.value = false
        currentBackend = null
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
        closeConversationSafe(litertConversation)
        litertConversation = null
        litertEngine?.close()
        litertEngine = null

        mediapipeInference?.close()
        mediapipeInference = null

        modelPath = null
        isInitialized = false
        _isReady.value = false
        currentBackend = null

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
        unload()
        unload()
    }
}
