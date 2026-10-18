package com.antivocale.app.transcription

import android.util.Log
import org.json.JSONObject

/**
 * TASK-853: the Kotlin seam over the Cactus Needle speech engine
 * (whistle_jni.cpp over libneedle.a). The engine is process-global and
 * not thread-safe; the bridge serializes every call, so this object is
 * safe to use from any dispatcher but never concurrently.
 *
 * ABI REALITY (scripts/fetch-whistle-engine.sh): upstream ships no
 * android-x86_64 engine, so the native library exists only in the arm
 * ABIs. [available] is the runtime gate every caller must honor: on
 * x86_64 the loader fails and Whistle is simply not offered.
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
            // The expected failure class on x86_64: no libwhistle_jni in
            // the APK. Anything else still gates the same way (no engine,
            // no backend) but is logged at warn for diagnosis.
            Log.w(TAG, "engine unavailable on this ABI: ${e.message}")
            false
        }
    }

    /** Reads the .cact bytes into the process-global engine. */
    fun load(cactBytes: ByteArray): Boolean =
        available && loadNative(cactBytes)

    fun isSpeechLoaded(): Boolean = available && isSpeechLoadedNative()

    /**
     * Whole-clip transcription (the engine windows past 30 s itself, cut
     * at sentence ends). [language] null = detect; ISO codes are the
     * engine's own seven (en/de/fr/es/it/nl/pl).
     */
    fun transcribe(
        pcm: FloatArray,
        language: String?,
        keywords: List<String> = emptyList(),
    ): Transcription? {
        if (!available) return null
        val json = transcribeNative(
            pcm, language, keywords.takeIf { it.isNotEmpty() }?.joinToString("\n"), false)
            ?: run {
                Log.w(TAG, "transcribe failed: " + lastError())
                return null
            }
        val obj = JSONObject(json)
        return Transcription(
            text = obj.optString("text"),
            language = obj.optString("language").takeIf { it.isNotEmpty() },
            ttftMs = obj.optDouble("ttft_ms", 0.0),
            decodeTps = obj.optDouble("decode_tps", 0.0),
        )
    }

    /** The engine's last error string (valid until the next call). */
    fun lastError(): String = if (available) lastErrorNative() else "engine unavailable"

    private external fun loadNative(cactBytes: ByteArray): Boolean
    private external fun isSpeechLoadedNative(): Boolean
    private external fun transcribeNative(
        pcm: FloatArray,
        language: String?,
        keywords: String?,
        wordTimestamps: Boolean,
    ): String?
    private external fun lastErrorNative(): String
}
