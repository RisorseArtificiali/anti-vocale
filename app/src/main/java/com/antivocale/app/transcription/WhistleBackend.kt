package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.di.ApplicationScope
import com.antivocale.app.util.NativeKeepAlive
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    private val preferences: PreferencesManager,
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
     * Bumped at every load ATTEMPT (see initialize); a drain-release
     * captured at an older epoch is retired instead of resetting the engine
     * under a fresh load (unload -> immediate re-initialize is the
     * orchestrator's standard reload sequence, and the engine is
     * process-global and SINGLE).
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
            // withWork brackets the load AND cancels any pending idle fire
            // (an idle fire cannot run mid-load: the bracket holds the
            // count above zero; a DRAIN release can, and the epoch below
            // retires it). The epoch retires pending drain releases the
            // moment a load ATTEMPT begins, not at its success: under the
            // TASK-794 watermark drain the old decode's drain can fire
            // while this load is still running, and a reset landing after
            // a successful load would wipe the fresh engine (a failed load
            // retires its pending releases too: the backend claims no
            // residency then, and skipping the reset is the safe side).
            keepAlive.withWork {
                val epochAtLoad = loadEpoch.incrementAndGet()
                try {
                    // IO hop (the sherpa discipline): the native 17MB load
                    // never runs on whatever dispatcher the caller arrived on.
                    withContext(Dispatchers.IO) {
                        if (!WhistleNative.loadFromFile(path)) {
                            throw TranscriptionException.ModelLoadError(
                                "Whistle load failed: " + WhistleNative.lastError())
                        }
                    }
                } catch (t: Throwable) {
                    // The bump above already retired any pending release
                    // from the previous life; a FAILED or CANCELLED load
                    // must not also leak the engine: re-arm the reset at
                    // THIS epoch (guarded against a later successful load),
                    // firing once this load bracket drains.
                    requestGuardedResetAtDrain(epochAtLoad)
                    throw t
                }
            }
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
        return keepAlive.withWork {
            // The owner discipline (review F1): the readiness gate and the
            // language read belong INSIDE the bracket, like every handle
            // field on the other owners. A gate outside it lets a straggler
            // pass, get bracketed AFTER an unload's watermark, and decode
            // on the old engine with already-nulled state; inside, an
            // unload landing after this read waits for THIS bracket.
            val language = configuredLanguage
            if (cactPath == null) {
                return@withWork Result.failure(TranscriptionException.NotInitialized())
            }
            // TASK-858: keyword biasing is REQUEST-time, not residency
            // identity (an edit applies to the next decode with no reload):
            // the orchestrator's .first() pattern on the cache-seeded flow.
            // Newline OR comma separators (lenient parse: a pasted
            // comma-separated list must not reach the engine as one giant
            // hotword; commas INSIDE a term are meaningless anyway, the
            // native boundary joins with \n).
            val keywords = preferences.keywordBiasing.first()
                .split('\n', ',')
                .map(String::trim)
                .filter(String::isNotEmpty)
            val result = withContext(Dispatchers.IO) {
                WhistleNative.transcribe(samples, language, keywords)
            } ?: run {
                Log.w(TAG, "transcribe failed: " + WhistleNative.lastError())
                return@withWork Result.failure(TranscriptionException.NativeError(
                    "Whistle decode failed: " + WhistleNative.lastError()))
            }
            Log.i(TAG, "decoded ${samples.size / sampleRate}s lang=${result.language} " +
                "ttft=${result.ttftMs}ms tps=${result.decodeTps}")
            Result.success(
                TranscriptionResult(
                    text = result.text,
                    detectedLanguage = result.language,
                ))
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
        requestGuardedResetAtDrain(loadEpoch.get())
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

    /** The variant DIR (the saved-path convention every surface assumes);
     *  the .cact file itself is the config-time detail. */
    override fun getModelPath(): String? = cactPath?.let { File(it).parent }

    /** Non-empty when loaded ("auto" = detect): a language pin change must
     *  force a reload, which a null identity would hide. */
    override fun getConfiguredLanguage(): String = languageIdentity

    /**
     * The engine's supported codes are CATALOG DATA (the entry's languages
     * array, the forcedLanguage pattern): no second hardcoded set can drift
     * from what the Settings picker offers.
     */
    private fun toEngineLanguage(code: String): String? =
        if (code in engineLanguages()) code else null

    private fun engineLanguages(): Set<String> =
        com.antivocale.app.data.catalog.BundledCatalog
            .byId(BuiltInBackendIds.WHISTLE)?.languages?.toSet()
            ?: emptySet()

    /**
     * The ONE guarded deferred reset: a release captured at epoch X queues a
     * reset that runs only while X is still the current load generation, so
     * a fresh engine can never be destroyed by a straggler from a previous
     * life. Both the unload arm and the failed/cancelled-load re-arm ride
     * this single definition.
     */
    private fun requestGuardedResetAtDrain(capturedEpoch: Long) {
        keepAlive.requestDrainRelease {
            if (loadEpoch.get() == capturedEpoch) WhistleNative.reset()
        }
    }

    private companion object {
        const val TAG = "WhistleBackend"
        const val LANGUAGE_AUTO = "auto"
    }
}
