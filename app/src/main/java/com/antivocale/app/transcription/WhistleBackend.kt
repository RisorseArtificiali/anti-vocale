package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.di.ApplicationScope
import com.antivocale.app.util.NativeKeepAlive
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TASK-853 slice 2: the Cactus Whistle backend (16.9 MB, 7 languages,
 * own Needle engine through [WhistleNative]). The engine windows audio
 * past 30 s internally (each window cut at its last sentence end), so
 * this backend takes WHOLE audio: [maxChunkDurationSeconds] is null and
 * the orchestrator's chunking never splits what the engine splits better.
 *
 * The engine holds ~17 MB of native memory while loaded; idle unload
 * rides [NativeKeepAlive] (the sherpa/Gemma pattern) and runs the FULL
 * [unload] so every piece of state flips together.
 *
 * Language: Whistle emits the detected language as a token and accepts
 * ISO codes for forcing; [configuredLanguage] is the config arm (null =
 * detect; only the engine's own seven pass through [toEngineLanguage],
 * everything else falls back to detect rather than forcing an
 * unsupported code). [languageIdentity] keeps the RAW config vocabulary
 * for the residency comparison. A request-side override maps the same
 * way once slice 3 wires the orchestrator's load arm.
 */
@Singleton
class WhistleBackend @Inject constructor(
    @ApplicationScope private val appScope: CoroutineScope,
) : TranscriptionBackend {

    private var configuredLanguage: String? = null

    /** The loaded .cact path: the backend's residency identity (getModelPath). */
    private var cactPath: String? = null

    /** Non-empty identity for the residency comparison: "auto" for detect. */
    private var languageIdentity: String = LANGUAGE_AUTO

    /** The manager's bookkeeping hook, fired from unload (the sherpa shape). */
    @Volatile
    private var autoUnloadCallback: (() -> Unit)? = null

    /**
     * Bumped by every successful load; a drain-release captured at an older
     * epoch is retired instead of resetting the engine under a fresh load
     * (unload -> immediate re-initialize is the orchestrator's standard
     * reload sequence, and the engine is process-global and SINGLE).
     */
    private val loadEpoch = AtomicLong(0)

    /**
     * ONE monitor for the instance lifetime (never nulled): workInFlight
     * stays truthful through the drain window, so isBusy cannot answer
     * false while a native decode is still in flight.
     */
    private val keepAlive = NativeKeepAlive(
        scope = appScope,
        tag = TAG,
        defaultTimeoutMinutes = PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT,
        // The sherpa shape: the idle fire runs the FULL unload (state
        // flip + callback + re-armable), not a bare reset.
        onIdleUnload = { runCatching { unload() } },
    )

    override val id: String = BuiltInBackendIds.WHISTLE
    override val displayName: String = "Whistle (Cactus, 17 MB)"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = false

    /** Whole-clip: the engine windows past 30 s itself. */
    override val maxChunkDurationSeconds: Int? = null
    override val requiresVadAlignedChunking: Boolean = false
    override val transcribesWholeContainer: Boolean = false
    override val trimsDegenerateRepetition: Boolean = false

    override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> {
        val path = (config as? BackendConfig.WhistleConfig)?.cactPath
            ?: return Result.failure(IllegalArgumentException(
                "WhistleBackend requires BackendConfig.WhistleConfig (got ${config.javaClass.simpleName})"))
        if (!WhistleNative.available) {
            return Result.failure(TranscriptionException.ModelLoadError(
                "Whistle engine unavailable on this ABI: " + WhistleNative.lastError()))
        }
        // The already-loaded guard (the sherpa shape): a redundant
        // initialize must not re-copy 17MB through the engine.
        if (isReady() && cactPath == path) {
            return Result.success(Unit)
        }
        return runCatching {
            // beginWork brackets the load AND cancels any pending idle fire
            // (a fire mid-load would reset the engine right after success;
            // unreachable today, live with slice 3's same-id reloads).
            keepAlive.beginWork()
            try {
                // IO hop (the sherpa discipline): the native 17MB load never
                // runs on whatever dispatcher the caller arrived on.
                withContext(Dispatchers.IO) {
                    if (!WhistleNative.loadFromFile(path)) {
                        throw TranscriptionException.ModelLoadError(
                            "Whistle load failed: " + WhistleNative.lastError())
                    }
                }
            } finally {
                keepAlive.endWork()
            }
            loadEpoch.incrementAndGet()
            cactPath = path
            configuredLanguage = config.language.takeIf { it.isNotBlank() }?.let(::toEngineLanguage)
            // The residency identity speaks the POLICY's raw vocabulary (the
            // orchestrator's expected side is unmapped): storing the
            // engine-mapped value would mismatch forever on non-seven pins.
            languageIdentity = config.language.ifBlank { LANGUAGE_AUTO }
            // Re-arm on EVERY initialize: the monitor disarms itself on an
            // idle fire, and its contract says the next initialize re-arms
            // (the sherpa/LlmManager precedent; start() is idempotent).
            keepAlive.start()
            Log.i(TAG, "loaded from $path")
        }
    }

    override suspend fun transcribeAudio(
        samples: FloatArray,
        sampleRate: Int,
        prompt: String,
    ): Result<TranscriptionResult> {
        if (cactPath == null) {
            return Result.failure(TranscriptionException.NotInitialized())
        }
        keepAlive.beginWork()
        return try {
            val result = withContext(Dispatchers.IO) {
                WhistleNative.transcribe(samples, configuredLanguage)
            } ?: run {
                Log.w(TAG, "transcribe failed: " + WhistleNative.lastError())
                return Result.failure(TranscriptionException.NativeError(
                    "Whistle decode failed: " + WhistleNative.lastError()))
            }
            Log.i(TAG, "decoded ${samples.size / sampleRate}s lang=${result.language} " +
                "ttft=${result.ttftMs}ms tps=${result.decodeTps}")
            Result.success(
                TranscriptionResult(
                    text = result.text,
                    detectedLanguage = result.language,
                ))
        } finally {
            keepAlive.endWork()
        }
    }

    override suspend fun generateText(prompt: String): Result<String> =
        Result.failure(UnsupportedOperationException("Whistle is audio-only"))

    /** Residency by Kotlin state: a JNI isReady would BLOCK behind any
     *  in-flight decode (the engine mutex serializes everything). */
    override fun isReady(): Boolean = cactPath != null

    /** The ABI gate the engine's own KDoc prescribes: available only means
     *  the bridge linked (true on the x86_64 stub); ready = a real load
     *  answered. A backend instance only exists where a load succeeded. */
    override fun isAudioSupported(): Boolean = WhistleNative.available && isReady()
    override fun unload() {
        keepAlive.stop()
        cactPath = null
        configuredLanguage = null
        languageIdentity = LANGUAGE_AUTO
        // The engine is process-global and SINGLE: a deferred reset from a
        // previous life must never destroy a FRESH load (unload -> immediate
        // re-initialize is the orchestrator's standard reload sequence). The
        // epoch guard retires the release the moment a new load begins.
        val epochAtUnload = loadEpoch.get()
        keepAlive.requestDrainRelease {
            if (loadEpoch.get() == epochAtUnload) WhistleNative.reset()
        }
        autoUnloadCallback?.invoke()
        Log.i(TAG, "unloaded")
    }

    override fun setOnAutoUnloadCallback(callback: (() -> Unit)?) {
        autoUnloadCallback = callback
    }

    override fun setKeepAliveTimeout(minutes: Int) {
        // SYSTEM sync: the orchestrator's post-load push is not a user
        // override (the sherpa rationale; arms no userOverride flag).
        keepAlive.setTimeoutSystemSync(minutes)
    }

    override fun isBusy(): Boolean = keepAlive.workInFlightCount() > 0

    override fun getModelPath(): String? = cactPath

    /** Non-empty when loaded ("auto" = detect): a language pin change must
     *  force a reload, which a null identity would hide. */
    override fun getConfiguredLanguage(): String = languageIdentity

    private fun toEngineLanguage(code: String): String? =
        if (code in ENGINE_LANGUAGES) code else null

    private companion object {
        const val TAG = "WhistleBackend"
        /** Slice-2 placeholder: slice 3's catalog entry carries the seven
         *  codes as its languages array; the backend then reads the entry
         *  (the forcedLanguage pattern) instead of this set. */
        val ENGINE_LANGUAGES =
            setOf("en", "de", "fr", "es", "it", "nl", "pl")
        const val LANGUAGE_AUTO = "auto"
    }
}
