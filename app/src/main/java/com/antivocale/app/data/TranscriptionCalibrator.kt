package com.antivocale.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.io.File

private val Context.calibrationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "transcription_calibration"
)

/**
 * Manages per-model speed calibration profiles for estimating transcription progress.
 *
 * Calibration keys are built as `"${backendId}__${dirName}"` where dirName is the
 * last component of the model path. This distinguishes between model variants
 * (e.g., Whisper Turbo vs Whisper Medium) that share the same backend.
 *
 * After each transcription, record the processing time vs audio duration.
 * Uses a rolling average to refine estimates over time:
 * - 0-1 samples: no estimate available
 * - 2 samples: initial estimate (low confidence)
 * - 3+ samples: stable estimate (high confidence)
 */
class TranscriptionCalibrator(context: Context) {

    private val dataStore = context.calibrationDataStore

    data class CalibrationProfile(
        val modelId: String,
        val displayName: String,
        val msPerSecondOfAudio: Float,
        val sampleCount: Int,
        val totalAudioSeconds: Long = 0,
        val totalProcessingMs: Long = 0,
        val bestMsPerSec: Float = Float.MAX_VALUE,
        val lastTimestamp: Long = 0
    ) {
        enum class Confidence { NONE, LOW, HIGH }

        val confidence: Confidence
            get() = when {
                sampleCount < 2 -> Confidence.NONE
                sampleCount < 3 -> Confidence.LOW
                else -> Confidence.HIGH
            }

        val hasEstimate: Boolean get() = sampleCount >= 2
    }

    /**
     * Builds a calibration key from backend ID and model path.
     * Format: "${backendId}__${dirName}" where dirName is the last path component.
     */
    fun buildKey(backendId: String, modelPath: String): String {
        val dirName = File(modelPath).name
        return "${backendId}__${dirName}"
    }

    private fun msKey(id: String) = floatPreferencesKey("cal_${id}_msPerSec")
    private fun countKey(id: String) = intPreferencesKey("cal_${id}_count")
    private fun totalAudioKey(id: String) = longPreferencesKey("cal_${id}_totalAudio")
    private fun totalProcessingKey(id: String) = longPreferencesKey("cal_${id}_totalProcessing")
    private fun bestMsKey(id: String) = floatPreferencesKey("cal_${id}_bestMs")
    private fun lastTsKey(id: String) = longPreferencesKey("cal_${id}_lastTs")
    private fun nameKey(id: String) = stringPreferencesKey("cal_${id}_name")

    /**
     * Records a completed transcription for calibration.
     *
     * @param backendId Backend ID (e.g., "whisper", "sherpa-onnx", "llm")
     * @param modelPath Full path to the model directory
     * @param displayName Human-readable model name for UI display
     * @param audioDurationSeconds Total audio duration in seconds
     * @param processingTimeMs Total processing time in milliseconds
     */
    suspend fun record(
        backendId: String,
        modelPath: String,
        displayName: String,
        audioDurationSeconds: Long,
        processingTimeMs: Long
    ) {
        if (audioDurationSeconds <= 0) return

        val key = buildKey(backendId, modelPath)
        val newMsPerSecond = processingTimeMs.toFloat() / audioDurationSeconds.toFloat()

        dataStore.edit { prefs ->
            val currentCount = prefs[countKey(key)] ?: 0
            val currentTotalAudio = prefs[totalAudioKey(key)] ?: 0L
            val currentTotalProcessing = prefs[totalProcessingKey(key)] ?: 0L
            val currentBest = prefs[bestMsKey(key)] ?: Float.MAX_VALUE

            val newTotalAudio = currentTotalAudio + audioDurationSeconds
            val newTotalProcessing = currentTotalProcessing + processingTimeMs
            // Compute average from totals to avoid incremental float drift
            val updatedAvg = newTotalProcessing.toFloat() / newTotalAudio.toFloat()

            prefs[msKey(key)] = updatedAvg
            prefs[countKey(key)] = currentCount + 1
            prefs[totalAudioKey(key)] = newTotalAudio
            prefs[totalProcessingKey(key)] = newTotalProcessing
            prefs[bestMsKey(key)] = minOf(currentBest, newMsPerSecond)
            prefs[lastTsKey(key)] = System.currentTimeMillis()
            prefs[nameKey(key)] = displayName
        }
    }

    /**
     * Gets the calibration profile for a model.
     * Returns null if no calibration data exists.
     */
    suspend fun getEstimate(backendId: String, modelPath: String): CalibrationProfile? {
        val key = buildKey(backendId, modelPath)
        return getProfileByKey(key)
    }

    /**
     * Legacy overload that accepts a raw key (for backward compatibility).
     */
    suspend fun getEstimateByKey(key: String): CalibrationProfile? {
        return getProfileByKey(key)
    }

    private suspend fun getProfileByKey(key: String): CalibrationProfile? {
        val prefs = dataStore.data.first()
        val msPerSec = prefs[msKey(key)] ?: return null
        val count = prefs[countKey(key)] ?: 0

        return CalibrationProfile(
            modelId = key,
            displayName = prefs[nameKey(key)] ?: key,
            msPerSecondOfAudio = msPerSec,
            sampleCount = count,
            totalAudioSeconds = prefs[totalAudioKey(key)] ?: 0L,
            totalProcessingMs = prefs[totalProcessingKey(key)] ?: 0L,
            bestMsPerSec = prefs[bestMsKey(key)] ?: Float.MAX_VALUE,
            lastTimestamp = prefs[lastTsKey(key)] ?: 0L
        )
    }

    /**
     * Gets all calibration profiles (for UI display).
     * Uses a single DataStore read to avoid N+1 queries.
     */
    suspend fun getAllProfiles(): List<CalibrationProfile> {
        val prefs = dataStore.data.first()
        val keys = mutableSetOf<String>()

        // Collect all unique calibration keys from msPerSec entries
        for (entry in prefs.asMap()) {
            val entryKey = entry.key.name
            if (entryKey.startsWith("cal_") && entryKey.endsWith("_msPerSec")) {
                val modelKey = entryKey.removePrefix("cal_").removeSuffix("_msPerSec")
                keys.add(modelKey)
            }
        }

        // Build profiles from the same prefs snapshot — no extra reads
        return keys.mapNotNull { key ->
            val msPerSec = prefs[msKey(key)] ?: return@mapNotNull null
            CalibrationProfile(
                modelId = key,
                displayName = prefs[nameKey(key)] ?: key,
                msPerSecondOfAudio = msPerSec,
                sampleCount = prefs[countKey(key)] ?: 0,
                totalAudioSeconds = prefs[totalAudioKey(key)] ?: 0L,
                totalProcessingMs = prefs[totalProcessingKey(key)] ?: 0L,
                bestMsPerSec = prefs[bestMsKey(key)] ?: Float.MAX_VALUE,
                lastTimestamp = prefs[lastTsKey(key)] ?: 0L
            )
        }.sortedBy { it.msPerSecondOfAudio }
    }

    /**
     * Resets all calibration data.
     */
    suspend fun resetAll() {
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith("cal_") }
                .forEach { prefs.remove(it) }
        }
    }
}
