package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.util.WavUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * TASK-681: the OPT-IN LAN-offload backend. Delegates whole-file
 * transcription to the user's own OmniVoice instance on their network
 * (OpenAI-compatible POST /v1/audio/transcriptions, bearer
 * OMNIVOICE_API_KEY auth). It exists only after the user enables it in
 * Settings and enters endpoint plus key; every other backend stays fully
 * on-device, and this one sends audio ONLY to the entered address (the
 * disclosure string the Settings card renders says exactly that).
 *
 * Whole-file by design ([maxChunkDurationSeconds] null, no VAD): the server
 * chunks internally, so the orchestrator's [TranscriptionBackend.transcribeFile]
 * arm uploads the ORIGINAL container unchunked and no silence is stripped
 * before upload. The wall-clock budget ([WALL_CLOCK_BUDGET_MS]) keeps a hung
 * box from parking the notification forever; the connect timeout fails fast
 * when the box is off.
 */
@Singleton
class RemoteOmnivoiceBackend @Inject constructor(
    sharedClient: OkHttpClient,
) : TranscriptionBackend {

    companion object {
        const val BACKEND_ID = "remote-omnivoice"

        /** OpenAI-compatible endpoint path appended to the user's base URL. */
        private const val TRANSCRIPTIONS_PATH = "/v1/audio/transcriptions"

        /**
         * The whole-run wall-clock budget: the request covers files up to 2h
         * and the server queues serially, so minutes are legitimate; a hung
         * box must not own the notification indefinitely. TASK-674's budget
         * discipline: hard ceiling, honest timeout error.
         */
        const val WALL_CLOCK_BUDGET_MS: Long = 15L * 60 * 1000

        /**
         * LAN fail-fast: when the box is off, the connect attempt must die in
         * seconds, not the OS-default silence (the shared app client's 30s is
         * tuned for WAN model downloads).
         */
        const val CONNECT_TIMEOUT_SECONDS = 10L

        /**
         * The Settings "Test connection" probe sends half a second of real
         * silence through the real endpoint (the verified OmniVoice surface
         * exposes no /health or /v1/models route), so one round trip proves
         * address, key and decode. The shorter budget keeps the button
         * snappy; a busy serial queue answering late reads as Timeout.
         */
        const val TEST_BUDGET_MS: Long = 20L * 1000
        private const val TEST_CLIP_SECONDS = 0.5f
        private const val TEST_CLIP_SAMPLE_RATE = 16000

        /**
         * Default of the pass-through `model` field. The OpenAI-compatible
         * contract requires one; OmniVoice accepts OpenAI-style names and
         * resolves them to its engines, and the Settings field lets the user
         * name their own engine instead.
         */
        const val DEFAULT_MODEL = "whisper-1"

        private const val TAG = "RemoteOmnivoiceBackend"
    }

    /**
     * Budget override for tests only (Dagger cannot supply a Long, so it is
     * a var; the orchestrator's maxConcurrentChunks precedent). Production
     * never writes it.
     */
    @androidx.annotation.VisibleForTesting
    internal var wallClockBudgetMs: Long = WALL_CLOCK_BUDGET_MS

    /**
     * Derived from the shared client (same pool and interceptors, own
     * timeouts): no read/write/call timeout, the coroutine wall clock
     * governs the long waits instead.
     */
    private val client: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(0L, TimeUnit.MILLISECONDS)
        .writeTimeout(0L, TimeUnit.MILLISECONDS)
        .callTimeout(0L, TimeUnit.MILLISECONDS)
        .build()

    /** Active configuration; null while unloaded (the backend is stateless beyond it). */
    @Volatile
    private var config: BackendConfig.RemoteConfig? = null

    override val id: String = BACKEND_ID
    override val displayName: String = "OmniVoice (LAN)"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = false

    /** TASK-681: whole-file offload; the server chunks internally. */
    override val maxChunkDurationSeconds: Int? = null
    override val requiresVadAlignedChunking: Boolean = false
    override val transcribesWholeContainer: Boolean = true

    override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> {
        val remoteConfig = config as? BackendConfig.RemoteConfig
            ?: return Result.failure(IllegalArgumentException(
                "Invalid config type for RemoteOmnivoiceBackend"))
        if (remoteConfig.baseUrl.isBlank()) {
            return Result.failure(TranscriptionException.NotInitialized())
        }
        this.config = remoteConfig
        return Result.success(Unit)
    }

    override suspend fun transcribeFile(path: String, language: String): Result<TranscriptionResult> {
        val cfg = config ?: return Result.failure(TranscriptionException.NotInitialized())
        val url = normalizeBaseUrl(cfg.baseUrl)
            ?: return Result.failure(TranscriptionException.RemoteUnreachableException(
                "The OmniVoice server address is not usable: '${cfg.baseUrl}'"))
        val file = File(path)
        if (!file.isFile) {
            return Result.failure(TranscriptionException.ModelLoadError("audio file not found: $path"))
        }
        return upload(
            url = url,
            bearer = cfg.apiKey,
            body = multipartBody(cfg.model, language) {
                addFormDataPart("file", file.name, file.asRequestBody(contentTypeFor(file.name)))
            },
            budgetMs = wallClockBudgetMs,
        ).toResult()
    }

    /**
     * Samples entry point kept honest for any caller that hands PCM over
     * (the interface contract): the samples are wrapped as WAV and uploaded
     * as one container. The orchestrator's remote arm uses [transcribeFile]
     * (the original container), never this.
     */
    override suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String): Result<TranscriptionResult> {
        val cfg = config ?: return Result.failure(TranscriptionException.NotInitialized())
        val url = normalizeBaseUrl(cfg.baseUrl)
            ?: return Result.failure(TranscriptionException.RemoteUnreachableException(
                "The OmniVoice server address is not usable: '${cfg.baseUrl}'"))
        val wav = WavUtils.floatSamplesToWav(samples, sampleRate)
        return upload(
            url = url,
            bearer = cfg.apiKey,
            body = multipartBody(cfg.model, language = "") {
                addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            },
            budgetMs = wallClockBudgetMs,
        ).toResult()
    }

    override suspend fun generateText(prompt: String): Result<String> =
        Result.failure(UnsupportedOperationException("The LAN-offload backend does not generate text"))

    override fun isReady(): Boolean = config != null
    override fun isAudioSupported(): Boolean = true
    override fun unload() { config = null }

    /** The endpoint IS this backend's path identity (endpoint edits must reload it). */
    override fun getModelPath(): String? = config?.baseUrl?.takeIf { it.isNotBlank() }

    override fun setKeepAliveTimeout(minutes: Int) { /* no resident engine */ }

    /**
     * The Settings "Test connection" probe: half a second of real silence
     * through the real endpoint, with the typed verdict the card renders.
     * Takes explicit values so a configuration can be verified before it is
     * saved.
     */
    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): ConnectionTestResult {
        val url = normalizeBaseUrl(baseUrl) ?: return ConnectionTestResult.Unreachable
        return when (val outcome = upload(
            url = url,
            bearer = apiKey,
            body = multipartBody(model, language = "") {
                addFormDataPart(
                    "file",
                    "silence.wav",
                    WavUtils.generateSilence(
                        sampleRate = TEST_CLIP_SAMPLE_RATE,
                        durationSeconds = TEST_CLIP_SECONDS,
                    ).toRequestBody("audio/wav".toMediaType()))
            },
            budgetMs = TEST_BUDGET_MS,
        )) {
            is Outcome.Text -> ConnectionTestResult.Success
            is Outcome.HttpError -> when (outcome.statusCode) {
                401, 403 -> ConnectionTestResult.AuthRejected
                else -> ConnectionTestResult.ServerError(outcome.statusCode)
            }
            is Outcome.Unreachable -> ConnectionTestResult.Unreachable
            Outcome.TimedOut -> ConnectionTestResult.Timeout
        }
    }

    /** Verdicts the Settings card renders; see [testConnection]. */
    sealed interface ConnectionTestResult {
        data object Success : ConnectionTestResult
        data object Unreachable : ConnectionTestResult
        data object AuthRejected : ConnectionTestResult
        data object Timeout : ConnectionTestResult
        data class ServerError(val statusCode: Int) : ConnectionTestResult
    }

    /** What one POST actually produced; the callers map it to their contracts. */
    private sealed interface Outcome {
        class Text(val text: String) : Outcome
        class HttpError(val statusCode: Int, val detail: String) : Outcome
        class Unreachable(val cause: IOException?) : Outcome
        data object TimedOut : Outcome
    }

    private fun Outcome.toResult(): Result<TranscriptionResult> = when (this) {
        is Outcome.Text ->
            if (text.isBlank()) {
                Result.failure(TranscriptionException.NoTranscriptionProduced(blankChunks = 1))
            } else {
                Result.success(TranscriptionResult(text = text))
            }
        is Outcome.HttpError ->
            Result.failure(TranscriptionException.RemoteServerError(statusCode, detail))
        is Outcome.Unreachable ->
            Result.failure(TranscriptionException.RemoteUnreachableException(
                "Cannot reach the OmniVoice server (check the address in Settings and that it is running)",
                cause))
        Outcome.TimedOut ->
            Result.failure(TranscriptionException.RemoteTimeoutException(
                "OmniVoice transcription exceeded the ${wallClockBudgetMs / 1000}s wall-clock budget"))
    }

    /**
     * One multipart POST with the wall clock enforced by coroutine
     * cancellation (the OkHttp call is cancelled with it, so no connection
     * lingers past the budget). Network and body handling on Dispatchers.IO.
     */
    private suspend fun upload(
        url: String,
        bearer: String,
        body: RequestBody,
        budgetMs: Long,
    ): Outcome = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url + TRANSCRIPTIONS_PATH)
            .addHeader("Authorization", "Bearer $bearer")
            .post(body)
            .build()
        val started = System.currentTimeMillis()
        try {
            val response = withTimeout(budgetMs) { client.newCall(request).await() }
            response.use { resp ->
                val responseBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val detail = fastApiDetail(responseBody)
                    Log.w(TAG, "OmniVoice HTTP ${resp.code} after ${System.currentTimeMillis() - started}ms: $detail")
                    Outcome.HttpError(resp.code, detail)
                } else {
                    val text = runCatching { JSONObject(responseBody).optString("text") }.getOrNull().orEmpty()
                    Log.i(TAG, "OmniVoice returned ${text.length} chars in ${System.currentTimeMillis() - started}ms")
                    Outcome.Text(text)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "OmniVoice call exceeded the ${budgetMs}ms budget; cancelled")
            Outcome.TimedOut
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "OmniVoice unreachable: ${e.message}")
            Outcome.Unreachable(e)
        } catch (e: IllegalArgumentException) {
            // Malformed base URL (Request.Builder parses eagerly): same advice
            // as an unreachable box, the address is wrong.
            Log.w(TAG, "OmniVoice base URL malformed: ${e.message}")
            Outcome.Unreachable(null)
        }
    }

    /** The one multipart contract: the file part, then model and format fields. */
    private fun multipartBody(
        model: String,
        language: String,
        filePart: MultipartBody.Builder.() -> Unit,
    ): RequestBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .apply(filePart)
        .addFormDataPart("model", model.ifBlank { DEFAULT_MODEL })
        .addFormDataPart("response_format", "json")
        .apply { if (language.isNotBlank()) addFormDataPart("language", language) }
        .build()

    /** FastAPI errors carry {"detail": ...}; the value may be a string or a typed object (queue/deadline info). */
    private fun fastApiDetail(body: String): String = runCatching {
        when (val detail = JSONObject(body).opt("detail")) {
            null, JSONObject.NULL -> "no detail"
            is String -> detail
            else -> detail.toString()
        }
    }.getOrDefault("unparseable error body")

    /**
     * Accepts "192.168.1.10:3900", "http://host/" and bare hosts: prepends
     * http:// when no scheme is present and strips trailing slashes so the
     * endpoint path joins cleanly. Null when nothing usable remains.
     */
    private fun normalizeBaseUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        return withScheme.trimEnd('/')
    }

    /** Common containers the app accepts; a hint for the server, octet-stream covers the rest. */
    private fun contentTypeFor(name: String): MediaType = when {
        name.endsWith(".wav", ignoreCase = true) -> "audio/wav"
        name.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
        name.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
        name.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
        name.endsWith(".aac", ignoreCase = true) -> "audio/aac"
        name.endsWith(".ogg", ignoreCase = true) || name.endsWith(".opus", ignoreCase = true) -> "audio/ogg"
        name.endsWith(".webm", ignoreCase = true) -> "audio/webm"
        name.endsWith(".flac", ignoreCase = true) -> "audio/flac"
        else -> "application/octet-stream"
    }.toMediaType()

    /** Cancellable enqueue: withTimeout's cancellation cancels the HTTP call too. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }
}
