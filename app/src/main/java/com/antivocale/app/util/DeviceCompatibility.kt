package com.antivocale.app.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Checks whether the device meets the minimum hardware requirements
 * for running on-device AI models (ONNX Runtime, LiteRT-LM, MediaPipe).
 *
 * Prevents confusing native crashes (SIGILL, UnsatisfiedLinkError) on
 * unsupported hardware by catching incompatibilities before any native
 * library is loaded.
 *
 * See: https://github.com/google-ai-edge/gallery/issues/543
 */
object DeviceCompatibility {

    private const val TAG = "DeviceCompatibility"

    // TASK-395 (GH strk/Moto G10): two-tier RAM gate. The old flat 4GB floor
    // blocked real users: a 4GB-nominal Moto G(10) reports totalMem ~3.6GB and
    // saw the "not enough memory" wall, while a Redmi Note 12 with 3.3GB free
    // ran the app habitually. The global floor is now a sanity check (1.5GB:
    // below this even Parakeet int8 at ~640MB plus OS pressure is unsafe);
    // heavier models are gated per-model at selection time via [hasRamForModel].
    private const val MIN_RAM_BYTES = 1_500L * 1024 * 1024

    /** Model RAM budget: download size is a proxy for the resident set; the
     *  factor covers ONNX arena + inference buffers + system headroom. */
    private const val RAM_HEADROOM_FACTOR = 2.5

    /** Floor for the smallest realistic model (Parakeet int8 640MB * 2.5 = 1.6GB). */
    private const val MIN_MODEL_BUDGET_MB = 1_200L

    sealed class CheckResult {
        data object Compatible : CheckResult()
        data class Incompatible(val reason: Reason) : CheckResult()

        sealed class Reason {
            data object UnsupportedArchitecture : Reason()
            data class InsufficientRam(val totalGb: Double) : Reason()
        }
    }

    /** TASK-427 tiered verdict for the pre-download hint; Fits keeps the
     *  boolean contract of [hasRamForModel]. */
    sealed class ModelFit {
        data object Fits : ModelFit()
        /** Within 25% above the required budget: workable today, fragile
         *  under memory pressure. Surfaced as a caution, never a block. */
        data object Tight : ModelFit()
        data object DoesNotFit : ModelFit()
    }

    /**
     * TASK-427: the pre-download hint verdict. Same budget as the selection
     * gate (one definition): Fits/Tight/DoesNotFit against the model's
     * estimated size; null when the device RAM is unreadable (fail-open,
     * rendering nothing). The decode-time TranscriptionMemoryPolicy stays
     * the runtime authority; this is advice at the download button.
     */
    fun modelFit(context: Context, modelSizeMB: Long): ModelFit? {
        val total = totalRamBytes(context)
        if (total == Long.MAX_VALUE) return null
        return modelFitForRam(total, modelSizeMB)
    }

    /**
     * The pure tier core (JVM-testable): null on EITHER unreadable form, the
     * wrapper's negative marker and the platform sentinel Long.MAX_VALUE
     * that a direct caller could pass.
     */
    internal fun modelFitForRam(totalRamBytes: Long, modelSizeMB: Long): ModelFit? {
        if (totalRamBytes < 0 || totalRamBytes == Long.MAX_VALUE) return null
        val totalRamMb = totalRamBytes / (1024 * 1024)
        val required = requiredBudgetMb(modelSizeMB)
        return when {
            // Difference form (totalRamMb - required) >= required / 4 is the
            // Tight-band test without an addition that could overflow at a
            // saturating budget; saturation then simply reads as DoesNotFit.
            totalRamMb >= required && (totalRamMb - required) >= required / 4 -> ModelFit.Fits
            totalRamMb >= required -> ModelFit.Tight
            else -> ModelFit.DoesNotFit
        }
    }

    /** One budget definition, shared by the gate and the hint tiers. */
    internal fun requiredBudgetMb(modelSizeMB: Long): Long = maxOf(
        (modelSizeMB * RAM_HEADROOM_FACTOR).toLong(),
        MIN_MODEL_BUDGET_MB,
    )

    /**
     * TASK-395: per-model RAM check. Called at model-selection time (Model tab,
     * Use/Download) with the model's estimated size in MB. Returns true when the
     * device likely has enough headroom for that specific model; a false result
     * should surface as a warning (not a block) so the user can still try
     * lighter models. The global [check] remains the hard gate for the app itself.
     */
    fun hasRamForModel(context: Context, modelSizeMB: Long): Boolean =
        modelFit(context, modelSizeMB) != ModelFit.DoesNotFit

    private fun totalRamBytes(context: Context): Long =
        // MemoryReadings is the one owner of the platform memory reads; this
        // gate keeps its own fail-open semantics on top.
        com.antivocale.app.audio.MemoryReadings.totalRamBytes(context)
            ?: Long.MAX_VALUE // Can't determine, allow to proceed

    /**
     * Checks if the current device is compatible.
     *
     * Should be called early (e.g., in MainActivity.onCreate) before any
     * model loading is attempted.
     */
    fun check(context: Context): CheckResult {
        val archCheck = checkArchitecture()
        if (archCheck is CheckResult.Incompatible) return archCheck

        val ramCheck = checkRam(context)
        if (ramCheck is CheckResult.Incompatible) return ramCheck

        return CheckResult.Compatible
    }

    private fun checkArchitecture(): CheckResult {
        val supportedAbis = Build.SUPPORTED_ABIS
        val hasArm64 = supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }

        if (!hasArm64) {
            val abis = supportedAbis.joinToString(", ")
            Log.w(TAG, "Device does not support arm64-v8a. Supported ABIs: $abis")
            return CheckResult.Incompatible(CheckResult.Reason.UnsupportedArchitecture)
        }

        return CheckResult.Compatible
    }

    private fun checkRam(context: Context): CheckResult {
        // MemoryReadings is the one owner of the platform memory reads (same
        // delegation as totalRamBytes above; code review 2026-09-03 caught this
        // hand-rolled copy surviving 40 lines below its migrated sibling).
        val totalRam = com.antivocale.app.audio.MemoryReadings.totalRamBytes(context)
            ?: run {
                Log.w(TAG, "ActivityManager not available")
                return CheckResult.Compatible // Can't determine, allow to proceed
            }

        val totalGb = totalRam / (1024.0 * 1024.0 * 1024.0)

        if (totalRam < MIN_RAM_BYTES) {
            Log.w(TAG, "Device has insufficient RAM: ${"%.1f".format(totalGb)} GB (minimum 1.5 GB sanity floor)")
            return CheckResult.Incompatible(CheckResult.Reason.InsufficientRam(totalGb))
        }

        return CheckResult.Compatible
    }
}
