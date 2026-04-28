package com.antivocale.app.benchmark

import android.content.Context
import android.os.Debug
import android.util.Log
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.transcription.BackendConfig
import com.antivocale.app.transcription.TranscriptionBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a model benchmark run.
 */
data class BenchmarkResult(
    val modelId: String,
    val inferenceTimeMs: Long,
    val audioDurationSeconds: Float,
    val peakMemoryMb: Float,
    val timestamp: Long = System.currentTimeMillis(),
    val provider: String = ""
) {
    /** Seconds of processing time per minute of audio (extrapolated). */
    val secondsPerMinute: Float
        get() = if (audioDurationSeconds > 0f) (inferenceTimeMs / 1000f) * (60f / audioDurationSeconds) else 0f

    /** Simple speed rating based on seconds-per-minute. */
    val rating: SpeedRating
        get() = when {
            secondsPerMinute <= 15f -> SpeedRating.FAST
            secondsPerMinute <= 30f -> SpeedRating.GOOD
            secondsPerMinute <= 60f -> SpeedRating.MODERATE
            else -> SpeedRating.SLOW
        }

    fun toJson(): String = JSONObject().apply {
        put("modelId", modelId)
        put("inferenceTimeMs", inferenceTimeMs)
        put("audioDurationSeconds", audioDurationSeconds.toDouble())
        put("peakMemoryMb", peakMemoryMb.toDouble())
        put("timestamp", timestamp)
        if (provider.isNotEmpty()) put("provider", provider)
    }.toString()

    companion object {
        fun fromJson(json: String): BenchmarkResult? = runCatching {
            val obj = JSONObject(json)
            BenchmarkResult(
                modelId = obj.getString("modelId"),
                inferenceTimeMs = obj.getLong("inferenceTimeMs"),
                audioDurationSeconds = obj.getDouble("audioDurationSeconds").toFloat(),
                peakMemoryMb = obj.getDouble("peakMemoryMb").toFloat(),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                provider = obj.optString("provider", "")
            )
        }.getOrNull()
    }
}

enum class SpeedRating {
    FAST, GOOD, MODERATE, SLOW
}

/** State for a running benchmark. */
sealed class BenchmarkState {
    data object Idle : BenchmarkState()
    data class Running(val progress: Float = 0f) : BenchmarkState()
    data class Complete(val result: BenchmarkResult) : BenchmarkState()
    data class Error(val message: String) : BenchmarkState()
}

/**
 * Manages model benchmarking: runs a standardized audio sample through
 * a transcription backend and measures inference time + peak memory.
 */
@Singleton
class BenchmarkManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager
) {
    companion object {
        private const val TAG = "BenchmarkManager"
        internal const val SAMPLE_DURATION_SECONDS = 10f
    }

    private val sampleAudio: FloatArray by lazy {
        FloatArray((16000 * SAMPLE_DURATION_SECONDS).toInt())
    }

    private val sampleRate: Int = 16000

    /**
     * Run a benchmark for the given model configuration.
     * Temporarily initializes the backend, runs inference, measures time+memory, then unloads.
     */
    suspend fun runBenchmark(
        backend: TranscriptionBackend,
        config: BackendConfig,
        onProgress: (Float) -> Unit = {}
    ): Result<BenchmarkResult> {
        return try {
            onProgress(0.1f)
            val wasReady = backend.isReady()

            try {
                if (!wasReady) {
                    Log.i(TAG, "Initializing backend ${backend.id} for benchmark")
                    val initResult = backend.initialize(context, config)
                    if (initResult.isFailure) {
                        return Result.failure(initResult.exceptionOrNull()
                            ?: Exception("Failed to initialize ${backend.displayName}"))
                    }
                }

                onProgress(0.3f)

                val memoryBefore = getMemoryInfo()

                onProgress(0.5f)

                val startTime = System.currentTimeMillis()
                val transcriptionResult = backend.transcribeAudio(sampleAudio, sampleRate, "")
                val inferenceTimeMs = System.currentTimeMillis() - startTime

                onProgress(0.8f)

                if (transcriptionResult.isFailure) {
                    Log.w(TAG, "Benchmark transcription error (expected for silent audio): ${transcriptionResult.exceptionOrNull()?.message}")
                }

                val memoryAfter = getMemoryInfo()
                val peakMemoryMb = maxOf(memoryBefore, memoryAfter)

                val result = BenchmarkResult(
                    modelId = backend.id,
                    inferenceTimeMs = inferenceTimeMs,
                    audioDurationSeconds = SAMPLE_DURATION_SECONDS,
                    peakMemoryMb = peakMemoryMb,
                    provider = (config as? BackendConfig.SherpaOnnxConfig)?.provider ?: ""
                )

                saveResult(result)

                onProgress(1.0f)
                Log.i(TAG, "Benchmark complete for ${backend.id}: ${result.secondsPerMinute}s/min, ${peakMemoryMb}MB, rating=${result.rating}")

                Result.success(result)
            } finally {
                if (!wasReady) {
                    backend.unload()
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Benchmark cancelled")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Benchmark failed", e)
            Result.failure(e)
        }
    }

    fun getCachedResult(modelId: String): Flow<BenchmarkResult?> {
        return preferencesManager.getBenchmarkResult(modelId).map { jsonStr ->
            jsonStr?.let { BenchmarkResult.fromJson(it) }
        }
    }

    fun getAllCachedResults(): Flow<Map<String, BenchmarkResult>> {
        return preferencesManager.getAllBenchmarkResults().map { jsonMap ->
            jsonMap.mapNotNull { (id, jsonStr) ->
                BenchmarkResult.fromJson(jsonStr)?.let { id to it }
            }.toMap()
        }
    }

    suspend fun clearCachedResult(modelId: String) {
        preferencesManager.clearBenchmarkResult(modelId)
    }

    suspend fun clearAllCachedResults() {
        preferencesManager.clearAllBenchmarkResults()
    }

    private suspend fun saveResult(result: BenchmarkResult) {
        preferencesManager.saveBenchmarkResult(result.modelId, result.toJson())
    }

    private fun getMemoryInfo(): Float {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        return memoryInfo.totalPss / 1024f
    }
}
