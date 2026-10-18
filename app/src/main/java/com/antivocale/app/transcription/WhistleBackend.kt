package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.di.ApplicationScope
import com.antivocale.app.util.NativeKeepAlive
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TASK-853 slice 2: the Cactus Whistle backend (16.9 MB, 7 languages,
 * own Needle engine through [WhistleNative]). The engine windows audio
 * past 30 s internally (each window cut at its last sentence end), so
 * this backend takes WHOLE audio: [maxChunkDurationSeconds] is null and
 * the orchestrator's chunking never splits what the engine splits better.
 *
 * The engine holds ~17 MB of native memory while loaded; idle unload
 * rides [NativeKeepAlive] (the sherpa/Gemma pattern) via
 * [WhistleNative.resetNative], exposed to the bridge as reset.
 *
 * Language: Whistle emits the detected language as a token and accepts
 * ISO codes for forcing; [configuredLanguage] is one config arm, the
 * request-side override maps through the same [toEngineLanguage] (null =
 * detect; only the engine's own seven pass through, everything else
 * falls back to detect rather than forcing an unsupported code).
 */
@Singleton
class WhistleBackend @Inject constructor(
    @ApplicationScope private val appScope: CoroutineScope,
) : TranscriptionBackend {

    private var keepAlive: NativeKeepAlive? = null
    private var configuredLanguage: String? = null

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
            return Result.failure(IllegalStateException(
                "Whistle engine unavailable on this ABI: " + WhistleNative.lastError()))
        }
        return runCatching {
            check(WhistleNative.loadFromFile(path)) {
                "Whistle load failed: " + WhistleNative.lastError()
            }
            configuredLanguage = config.language.takeIf { it.isNotBlank() }?.let(::toEngineLanguage)
            if (keepAlive == null) {
                keepAlive = NativeKeepAlive(
                    scope = appScope,
                    tag = TAG,
                    defaultTimeoutMinutes = DEFAULT_KEEP_ALIVE_MINUTES,
                    onIdleUnload = { WhistleNative.reset() },
                ).also { it.start() }
            }
            Log.i(TAG, "loaded from $path")
        }
    }

    override suspend fun transcribeAudio(
        samples: FloatArray,
        sampleRate: Int,
        prompt: String,
    ): Result<TranscriptionResult> {
        val engine = keepAlive ?: return Result.failure(IllegalStateException("not initialized"))
        engine.beginWork()
        return try {
            val language = configuredLanguage ?: null
            val result = WhistleNative.transcribe(samples, language)
                ?: return Result.failure(IllegalStateException(
                    "Whistle decode failed: " + WhistleNative.lastError()))
            Log.i(TAG, "decoded ${samples.size / sampleRate}s lang=${result.language} " +
                "ttft=${result.ttftMs}ms tps=${result.decodeTps}")
            Result.success(
                TranscriptionResult(
                    text = result.text,
                    detectedLanguage = result.language,
                ))
        } finally {
            engine.endWork()
        }
    }

    override suspend fun generateText(prompt: String): Result<String> =
        Result.failure(UnsupportedOperationException("Whistle is audio-only"))

    override fun isReady(): Boolean = WhistleNative.isSpeechLoaded()
    override fun isAudioSupported(): Boolean = WhistleNative.available
    override fun unload() {
        keepAlive?.stop()
        keepAlive = null
        WhistleNative.reset()
        Log.i(TAG, "unloaded")
    }

    override fun setKeepAliveTimeout(minutes: Int) {
        keepAlive?.setTimeout(minutes)
    }

    override fun isBusy(): Boolean = (keepAlive?.workInFlightCount() ?: 0) > 0

    override fun getModelPath(): String? = null

    override fun getConfiguredLanguage(): String? = configuredLanguage

    private fun toEngineLanguage(code: String): String? =
        if (code in ENGINE_LANGUAGES) code else null

    private companion object {
        const val TAG = "WhistleBackend"
        const val DEFAULT_KEEP_ALIVE_MINUTES = 10
        val ENGINE_LANGUAGES =
            setOf("en", "de", "fr", "es", "it", "nl", "pl")
    }
}
