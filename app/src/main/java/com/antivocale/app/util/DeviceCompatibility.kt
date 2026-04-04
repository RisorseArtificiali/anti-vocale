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

    // Minimum device RAM to run models reliably (4 GB)
    private const val MIN_RAM_BYTES = 4L * 1024 * 1024 * 1024

    sealed class CheckResult {
        data object Compatible : CheckResult()
        data class Incompatible(val reason: Reason) : CheckResult()

        sealed class Reason {
            data object UnsupportedArchitecture : Reason()
            data class InsufficientRam(val totalGb: Double) : Reason()
        }
    }

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
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: run {
                Log.w(TAG, "ActivityManager not available")
                return CheckResult.Compatible // Can't determine, allow to proceed
            }

        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalRam = memInfo.totalMem
        val totalGb = totalRam / (1024.0 * 1024.0 * 1024.0)

        if (totalRam < MIN_RAM_BYTES) {
            Log.w(TAG, "Device has insufficient RAM: ${"%.1f".format(totalGb)} GB (minimum 4 GB)")
            return CheckResult.Incompatible(CheckResult.Reason.InsufficientRam(totalGb))
        }

        return CheckResult.Compatible
    }
}
