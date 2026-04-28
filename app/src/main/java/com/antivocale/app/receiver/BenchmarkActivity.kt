package com.antivocale.app.receiver

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.service.InferenceService
import com.antivocale.app.service.InferenceService.Companion.EXTRA_SOURCE
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug-only transparent activity for automated benchmarking.
 *
 * Accepts intent extras to set preferences, then triggers transcription.
 * Being a visible activity satisfies Android 16 foreground service restrictions.
 */
@AndroidEntryPoint
class BenchmarkActivity : ComponentActivity() {

    companion object {
        const val TAG = "BenchmarkActivity"
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_VAD = "vad"
        const val EXTRA_PROGRESSIVE = "progressive"
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_RUN_ID = "run_id"
    }

    @Inject
    lateinit var preferencesManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val backend = intent.getStringExtra(EXTRA_BACKEND) ?: run { finish(); return }
        val vad = intent.getBooleanExtra(EXTRA_VAD, false)
        val progressive = intent.getBooleanExtra(EXTRA_PROGRESSIVE, false)
        val provider = intent.getStringExtra(EXTRA_PROVIDER) ?: "cpu"
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: run { finish(); return }
        val runId = intent.getStringExtra(EXTRA_RUN_ID) ?: "bench_${System.currentTimeMillis()}"

        Log.i(TAG, "BENCH_CONFIG: run=$runId backend=$backend vad=$vad progressive=$progressive provider=$provider file=$filePath")

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Set preferences
                preferencesManager.saveTranscriptionBackend(backend)
                preferencesManager.saveVadEnabled(vad)
                preferencesManager.saveProgressiveTranscription(progressive)
                preferencesManager.saveInferenceProvider(provider)

                // Wait for DataStore persistence
                delay(500)

                // Force-stop and reload backend by starting transcription
                val taskId = "bench_${runId}"
                val serviceIntent = Intent(this@BenchmarkActivity, InferenceService::class.java).apply {
                    putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "audio")
                    putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, filePath)
                    putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
                    putExtra(TaskerRequestReceiver.EXTRA_PROMPT, "")
                    putExtra(EXTRA_SOURCE, "benchmark")
                }
                startForegroundService(serviceIntent)
                Log.i(TAG, "BENCH_START: run=$runId taskId=$taskId")
            } catch (e: Exception) {
                Log.e(TAG, "BENCH_ERROR: run=$runId", e)
            }

            // Stay alive for 5 minutes to keep the foreground service running.
            // Android 16 kills FGS when the launching activity finishes,
            // and LLM backends (Gemma 4B) can take several minutes to complete.
            delay(300_000)
            finish()
        }
    }
}
