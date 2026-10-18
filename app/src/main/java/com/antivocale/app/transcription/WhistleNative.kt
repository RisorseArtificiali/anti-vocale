package com.antivocale.app.transcription

import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * TASK-853: the Kotlin seam over the Cactus Needle speech engine
 * (whistle_jni.cpp over libneedle.a). The engine is process-global and
 * not thread-safe; the bridge serializes every call, so calls from
 * different threads interleave safely at the memory level, but a load or
 * reset interleaved with a long decode is caller coordination: while a
 * decode holds the engine, any other engine call (including
 * [isSpeechLoaded] and [lastError]) blocks until it finishes.
 *
 * ABI REALITY (scripts/fetch-whistle-engine.sh): the x86_64 APK carries a
 * STUB whistle_jni whose every call fails (upstream ships no
 * android-x86_64 engine), so [available] only means the bridge linked;
 * callers gate on [load] or [isSpeechLoaded], which answer false there.
 */
internal object WhistleNative {

    private const val TAG = "WhistleNative"

    /** One decode's JSON contract (the engine's own output shape). */
    data class Transcription(
        val text: String,
        val language: String?,
        val ttftMs: Double,
        val decodeTps: Double,
    )

    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("whistle_jni")
            true
        }.getOrElse { e ->
            Log.w(TAG, "bridge unavailable: ${e.message}")
            false
        }
    }

    /**
     * The memory-lean load: mmap the .cact file C++-side; the engine
     * copies into its own memory, so the Java heap never sees the 17MB.
     */
    fun loadFromFile(cactPath: String): Boolean =
        available && loadFromFileNative(cactPath)

    /** The stream-source load (SAF/external imports with bytes at hand). */
    fun load(cactBytes: ByteArray): Boolean =
        available && loadNative(cactBytes)

    fun isSpeechLoaded(): Boolean = available && isSpeechLoadedNative()

    /**
     * Whole-clip transcription (the engine windows past 30 s itself, cut
     * at sentence ends). [language] null = detect; ISO codes are the
     * engine's own seven (en/de/fr/es/it/nl/pl). Null on failure or on
     * malformed engine JSON (the engine contract is trusted no further
     * than the parser).
     */
    fun transcribe(
        pcm: FloatArray,
        language: String?,
        keywords: List<String> = emptyList(),
    ): Transcription? {
        if (!available) return null
        val jsonBytes = transcribeNative(
            pcm, language, keywords.takeIf { it.isNotEmpty() }?.joinToString("\n"), false)
            ?: run {
                Log.w(TAG, "transcribe failed: " + lastError())
                return null
            }
        return parse(String(jsonBytes, StandardCharsets.UTF_8))
    }

    /** Unloads the engine (the NativeKeepAlive idle-unload hook). */
    fun reset() {
        if (available) resetNative()
    }

    /** The engine's last error string (valid until the next call). */
    fun lastError(): String =
        if (available) String(lastErrorNative() ?: ByteArray(0), StandardCharsets.UTF_8)
        else "bridge unavailable"

    private fun parse(json: String): Transcription? = try {
        val obj = JSONObject(json)
        Transcription(
            text = obj.optString("text"),
            language = obj.optString("language").takeIf { it.isNotEmpty() },
            ttftMs = obj.optDouble("ttft_ms", 0.0),
            decodeTps = obj.optDouble("decode_tps", 0.0),
        )
    } catch (e: JSONException) {
        Log.w(TAG, "engine JSON malformed: ${e.message}")
        null
    }

    private external fun loadNative(cactBytes: ByteArray): Boolean
    private external fun loadFromFileNative(cactPath: String): Boolean
    private external fun isSpeechLoadedNative(): Boolean
    private external fun transcribeNative(
        pcm: FloatArray,
        language: String?,
        keywords: String?,
        wordTimestamps: Boolean,
    ): ByteArray?
    private external fun lastErrorNative(): ByteArray?
    private external fun resetNative()
}
