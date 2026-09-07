package com.antivocale.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antivocale.app.benchmark.BenchmarkManager
import com.antivocale.app.benchmark.BenchmarkState
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.transcription.BackendConfig
import com.antivocale.app.transcription.InferenceProvider
import com.antivocale.app.transcription.TranscriptionBackendManager
import com.antivocale.app.transcription.TranscriptionLanguagePolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Model tab's benchmark dialog (TASK-260).
 *
 * Owns the benchmark run state and the backend-id to [BackendConfig] mapping;
 * the measurement itself (timing, memory, result persistence) lives in
 * [BenchmarkManager].
 */
@HiltViewModel
class BenchmarkViewModel @Inject constructor(
    private val benchmarkManager: BenchmarkManager,
    private val backendManager: TranscriptionBackendManager,
    private val preferencesManager: PreferencesManager,
) : ViewModel() {

    private data class BenchmarkTarget(
        val backendId: String,
        val modelPath: String,
        val displayName: String
    )

    private val _benchmarkState = MutableStateFlow<BenchmarkState>(BenchmarkState.Idle)
    val benchmarkState: StateFlow<BenchmarkState> = _benchmarkState.asStateFlow()

    private val _benchmarkTargetName = MutableStateFlow("")
    val benchmarkTargetName: StateFlow<String> = _benchmarkTargetName.asStateFlow()

    private var benchmarkJob: Job? = null
    private var lastBenchmarkTarget: BenchmarkTarget? = null

    fun startBenchmark(backendId: String, modelPath: String, displayName: String) {
        benchmarkJob?.cancel()
        lastBenchmarkTarget = BenchmarkTarget(backendId, modelPath, displayName)
        _benchmarkTargetName.value = displayName
        _benchmarkState.value = BenchmarkState.Idle

        val backend = backendManager.getBackend(backendId) ?: run {
            _benchmarkState.value = BenchmarkState.Error("Unknown backend: $backendId")
            return
        }

        benchmarkJob = viewModelScope.launch(Dispatchers.IO) {
            val threadCount = preferencesManager.threadCount.first()
            val providerPref = preferencesManager.inferenceProvider.first()
            val resolvedProvider = InferenceProvider.resolve(providerPref)

            val config = when {
                backendId == GGUF_BACKEND_ID -> BackendConfig.GgufConfig(
                    modelPath = modelPath,
                    threadCount = threadCount
                )
                else -> {
                    val entry = BundledCatalog.byId(backendId) ?: run {
                        _benchmarkState.value = BenchmarkState.Error("Unsupported backend for benchmark")
                        return@launch
                    }
                    val lang = preferencesManager.transcriptionLanguage.first()
                    // Same language resolution as the orchestrator's load path: the
                    // benchmark must measure what transcription would actually run
                    // with, and the "system" default must never reach the recognizer
                    // as a literal language code.
                    BackendConfig.SherpaOnnxConfig(
                        modelDir = modelPath,
                        numThreads = threadCount,
                        language = TranscriptionLanguagePolicy.resolveForEntry(
                            entry = entry,
                            preference = lang,
                        ),
                        provider = resolvedProvider
                    )
                }
            }

            val result = benchmarkManager.runBenchmark(backend, config) { progress ->
                _benchmarkState.value = BenchmarkState.Running(progress)
            }
            _benchmarkState.value = result.fold(
                onSuccess = { BenchmarkState.Complete(it) },
                onFailure = { BenchmarkState.Error(it.message ?: "Benchmark failed") }
            )
        }
    }

    fun rerunBenchmark() {
        val target = lastBenchmarkTarget ?: return
        startBenchmark(target.backendId, target.modelPath, target.displayName)
    }

    fun cancelBenchmark() {
        benchmarkJob?.cancel()
        benchmarkJob = null
        _benchmarkState.value = BenchmarkState.Idle
    }

    fun dismissBenchmark() {
        _benchmarkState.value = BenchmarkState.Idle
        _benchmarkTargetName.value = ""
    }

    override fun onCleared() {
        super.onCleared()
        benchmarkJob?.cancel()
    }

    companion object {
        /** Backend id of the disabled GGUF backend; deliberately unregistered in BackendRegistry. */
        private const val GGUF_BACKEND_ID = "gemma4_gguf"
    }
}
