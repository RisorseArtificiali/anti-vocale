package com.antivocale.app.manager

import android.content.Context
import android.util.Log
import com.antivocale.app.data.PreferencesManager
import com.google.ai.edge.litertlm.*
import com.antivocale.app.util.CrashReporter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Singleton manager for on-device LLM inference.
 *
 * Supports two backends:
 * 1. **LiteRT-LM** (preferred): Multimodal inference with audio support
 * 2. **MediaPipe Tasks GenAI** (fallback): Text-only inference
 *
 * The manager automatically selects the best available backend.
 * For audio transcription, LiteRT-LM uses Gemma 3n's native audio encoder.
 *
 * Handles:
 * - Model initialization and lifecycle
 * - Text generation
 * - Audio transcription (multimodal)
 * - Keep-alive timeout for automatic unloading
 */
object LlmManager {

    private const val TAG = "LlmManager"
    private const val MAX_TOKENS = 2048

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

    // MediaPipe fallback (text only)
    private var mediapipeInference: com.google.mediapipe.tasks.genai.llminference.LlmInference? = null

    // Common state
    private var modelPath: String? = null
    private var isInitialized = false
    private var appContext: Context? = null

    // Keep-alive timeout management
    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CrashReporter.handler)
    private var keepAliveJob: Job? = null
    private var keepAliveTimeoutMinutes: Int = PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT

    // Callback for when model is auto-unloaded
    private val onAutoUnloadCallback = AtomicReference<(() -> Unit)?>(null)

    // Callback for when model is externally loaded (e.g., via ModelPreloadReceiver)
    private val onExternalLoadCallback = AtomicReference<((String) -> Unit)?>(null)

    /**
     * Sets the keep-alive timeout in minutes.
     * After this period of inactivity, the model will be automatically unloaded.
     */
    fun setKeepAliveTimeout(minutes: Int) {
        keepAliveTimeoutMinutes = if (minutes > 0) minutes else PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT
        Log.d(TAG, "Keep-alive timeout set to $keepAliveTimeoutMinutes minutes")
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
            return Result.failure(IllegalArgumentException("Model file not found: $path"))
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
            val conversationConfig = ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = 40,
                    topP = 0.95,
                    temperature = 0.8
                )
            )
            litertConversation = litertEngine!!.createConversation(conversationConfig)

            currentBackend = Backend.LITERT_LM
            modelPath = path
            isInitialized = true
            _isReady.value = true
            startKeepAliveTimer()

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
            startKeepAliveTimer()

            Log.i(TAG, "MediaPipe backend initialized (text only)")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe initialization also failed", e)
            Result.failure(e)
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

        // Reset keep-alive timer on activity
        resetKeepAliveTimer()

        Log.d(TAG, "Generating text for prompt: ${prompt.take(50)}...")

        return@withContext when (currentBackend) {
            Backend.LITERT_LM -> generateTextLiteRT(prompt)
            Backend.MEDIAPIPE_GENAI -> generateTextMediaPipe(prompt)
            null -> Result.failure(IllegalStateException("No backend initialized"))
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
                .catch { e ->
                    Log.e(TAG, "LiteRT streaming error", e)
                }
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
     * with Gemma 3n's native audio encoder.
     *
     * For MediaPipe backend (text-only), returns an error indicating
     * audio is not supported.
     *
     * @param prompt The prompt (e.g., "Transcribe this speech:")
     * @param audioData WAV ByteArray (16kHz mono, 16-bit PCM)
     * @return Result containing the transcription/understanding
     */
    suspend fun generateFromAudio(prompt: String, audioData: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            return@withContext Result.failure(IllegalStateException("Model not initialized"))
        }

        // Reset keep-alive timer on activity
        resetKeepAliveTimer()

        Log.d(TAG, "Processing audio: ${audioData.size} bytes with backend: $currentBackend")

        return@withContext when (currentBackend) {
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

    /**
     * Generates text from audio using LiteRT-LM backend.
     * Temporarily replaces the main conversation to avoid context accumulation,
     * then restores it after processing. This is critical for multi-chunk processing.
     */
    private suspend fun generateFromAudioLiteRT(prompt: String, audioData: ByteArray): Result<String> {
        return try {
            val engine = litertEngine
                ?: return Result.failure(IllegalStateException("LiteRT engine not available"))

            // Save the existing conversation reference
            val originalConversation = litertConversation

            Log.d(TAG, "Creating fresh conversation for audio (temporarily replacing main session)...")

            // Delete the existing session to create a fresh one
            // LiteRT only supports ONE session at a time
            originalConversation?.close()

            // Create a FRESH conversation for this single request
            val conversationConfig = ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = 40,
                    topP = 0.95,
                    temperature = 0.8
                )
            )
            val freshConversation = engine.createConversation(conversationConfig)

            Log.d(TAG, "Processing audio in fresh conversation...")
            Log.d(TAG, "Audio data size: ${audioData.size} bytes")

            val response = StringBuilder()

            freshConversation.sendMessageAsync(
                Contents.of(
                    Content.AudioBytes(audioData),
                    Content.Text(prompt)
                )
            )
                .catch { e ->
                    Log.e(TAG, "LiteRT audio streaming error", e)
                }
                .collect { message ->
                    response.append(message.toString())
                }

            val result = response.toString()
            Log.d(TAG, "Fresh conversation audio processing complete: ${result.length} chars")

            // Close the fresh conversation and recreate the main one for text chat
            freshConversation.close()
            litertConversation = engine.createConversation(conversationConfig)
            Log.d(TAG, "Restored main conversation for text chat")

            Result.success(result)

        } catch (e: Exception) {
            Log.e(TAG, "LiteRT audio processing failed", e)
            // Try to restore conversation on error
            try {
                litertConversation?.close()
                litertConversation = litertEngine?.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8)
                    )
                )
            } catch (restoreError: Exception) {
                Log.e(TAG, "Failed to restore conversation after error", restoreError)
            }
            Result.failure(e)
        } catch (e: Error) {
            // Catch native errors (SIGSEGV, etc.)
            Log.e(TAG, "LiteRT native error", e)
            Result.failure(IllegalStateException("Native error during audio processing: ${e.message}"))
        }
    }

    /**
     * Checks if the model is ready for inference.
     */
    fun isReady(): Boolean = isInitialized && (litertEngine != null || mediapipeInference != null)

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
        if (!isInitialized || keepAliveJob == null) return null
        return (keepAliveTimeoutMinutes * 60).toLong()
    }

    /**
     * Unloads the model from memory.
     */
    fun unload() {
        Log.i(TAG, "Unloading model")

        cancelKeepAliveTimer()

        // Close LiteRT resources
        litertConversation?.close()
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
        cancelKeepAliveTimer()
        startKeepAliveTimer()
    }

    private fun startKeepAliveTimer() {
        if (!isInitialized) return

        keepAliveJob = managerScope.launch {
            val timeoutMs = keepAliveTimeoutMinutes * 60 * 1000L
            Log.d(TAG, "Starting keep-alive timer: ${keepAliveTimeoutMinutes} minutes")

            delay(timeoutMs)

            if (isInitialized) {
                Log.i(TAG, "Keep-alive timeout reached, auto-unloading model")
                performAutoUnload()
            }
        }
    }

    private fun cancelKeepAliveTimer() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    private fun performAutoUnload() {
        litertConversation?.close()
        litertConversation = null
        litertEngine?.close()
        litertEngine = null

        mediapipeInference?.close()
        mediapipeInference = null

        modelPath = null
        isInitialized = false
        _isReady.value = false
        currentBackend = null
        keepAliveJob = null

        onAutoUnloadCallback.get()?.let { callback ->
            managerScope.launch(Dispatchers.Main) {
                callback.invoke()
            }
        }
    }

    /**
     * Cancels all coroutines and cleans up.
     * Call this when the app is being destroyed.
     */
    fun shutdown() {
        cancelKeepAliveTimer()
        managerScope.cancel()
        unload()
    }
}
