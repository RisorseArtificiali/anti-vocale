package com.antivocale.app.transcription

import android.os.Build

/**
 * Resolves the ONNX Runtime execution provider for inference.
 *
 * sherpa-onnx supports "nnapi" (Android Neural Networks API) and "cpu".
 * NNAPI can route to NPU/GPU/DSP but often adds driver overhead that
 * outweighs the benefit for small models. Default is CPU.
 */
object InferenceProvider {

    /** User-visible provider options stored in preferences. */
    const val AUTO = "auto"
    const val NNAPI = "nnapi"
    const val CPU = "cpu"

    /**
     * Resolves the actual provider string to pass to sherpa-onnx.
     *
     * - "auto" → "cpu" (NNAPI driver overhead often hurts small models)
     * - "nnapi" → "nnapi" (user explicitly wants NNAPI)
     * - "cpu" → "cpu" (user explicitly wants CPU)
     */
    fun resolve(preference: String): String {
        return when (preference) {
            NNAPI -> NNAPI
            CPU -> CPU
            else -> CPU
        }
    }

    /**
     * Checks whether NNAPI is available on this device.
     *
     * NNAPI was introduced in API 27 (Android 8.1). We don't try to
     * probe specific driver capabilities here — if NNAPI is present,
     * we attempt it and let the fallback mechanism handle failures.
     */
    fun isNnapiAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
    }

    /** All valid preference values for the settings dropdown. */
    val options = listOf(AUTO, NNAPI, CPU)
}
