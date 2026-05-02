package com.antivocale.app.data

import kotlinx.coroutines.flow.Flow

interface PreferencesManager {

    val modelPath: Flow<String?>
    val keepAliveTimeout: Flow<Int>
    val themePreference: Flow<String>
    val transcriptionBackend: Flow<String>
    val parakeetModelPath: Flow<String?>
    val whisperModelPath: Flow<String?>
    val qwen3AsrModelPath: Flow<String?>
    val ggufModelPath: Flow<String?>
    val autoCopyEnabled: Flow<Boolean>
    val vadEnabled: Flow<Boolean>
    val vadAdvisoryDismissed: Flow<Boolean>
    val progressiveTranscription: Flow<Boolean>
    val defaultPrompt: Flow<String>
    val threadCount: Flow<Int>
    val inferenceProvider: Flow<String>
    val transcriptionLanguage: Flow<String>
    val swipeActionMode: Flow<String>
    val groupLogsByConversation: Flow<Boolean>
    val advancedSharingEnabled: Flow<Boolean>
    val showRetranscribeButton: Flow<Boolean>

    suspend fun saveModelPath(path: String)
    suspend fun clearModelPath()
    suspend fun saveKeepAliveTimeout(minutes: Int)
    suspend fun saveThemePreference(theme: String)
    suspend fun saveTranscriptionBackend(backendId: String)
    suspend fun saveParakeetModelPath(path: String)
    suspend fun clearParakeetModelPath()
    suspend fun saveWhisperModelPath(path: String)
    suspend fun clearWhisperModelPath()
    suspend fun saveQwen3AsrModelPath(path: String)
    suspend fun clearQwen3AsrModelPath()
    suspend fun saveGgufModelPath(path: String)
    suspend fun clearGgufModelPath()
    suspend fun saveAutoCopyEnabled(enabled: Boolean)
    suspend fun saveVadEnabled(enabled: Boolean)
    suspend fun saveVadAdvisoryDismissed(dismissed: Boolean)
    suspend fun saveProgressiveTranscription(enabled: Boolean)
    suspend fun saveDefaultPrompt(prompt: String)
    suspend fun saveThreadCount(threads: Int)
    suspend fun saveInferenceProvider(provider: String)
    suspend fun saveTranscriptionLanguage(language: String)
    suspend fun saveSwipeActionMode(mode: String)
    suspend fun saveGroupLogsByConversation(enabled: Boolean)
    suspend fun saveAdvancedSharingEnabled(enabled: Boolean)
    suspend fun saveShowRetranscribeButton(enabled: Boolean)

    suspend fun saveBenchmarkResult(modelId: String, jsonResult: String)
    fun getBenchmarkResult(modelId: String): Flow<String?>
    fun getAllBenchmarkResults(): Flow<Map<String, String>>
    suspend fun clearBenchmarkResult(modelId: String)
    suspend fun clearAllBenchmarkResults()

    suspend fun getLegacyLanguagePreference(): String

    val partialTranscriptionText: Flow<String?>
    val partialTranscriptionTimestamp: Flow<Long?>
    suspend fun savePartialTranscriptionState(text: String)
    suspend fun clearPartialTranscriptionState()

    companion object {
        const val DEFAULT_KEEP_ALIVE_TIMEOUT = 5
        val DEFAULT_THREAD_COUNT = maxOf(2, Runtime.getRuntime().availableProcessors() - 2).coerceAtMost(8)
        const val DEFAULT_AUTO_COPY_ENABLED = false
        const val DEFAULT_VAD_ENABLED = false
        const val DEFAULT_PROGRESSIVE_TRANSCRIPTION = true
        const val DEFAULT_PROMPT_VALUE = ""
        const val DEFAULT_THEME = "DEFAULT"
        const val DEFAULT_TRANSCRIPTION_BACKEND = "llm"
        const val DEFAULT_LANGUAGE = "system"
        const val DEFAULT_TRANSCRIPTION_LANGUAGE = "auto"
        const val DEFAULT_SWIPE_ACTION_MODE = "REVEAL"
        const val DEFAULT_INFERENCE_PROVIDER = "auto"
        const val DEFAULT_GROUP_LOGS_BY_CONVERSATION = true
        const val DEFAULT_ADVANCED_SHARING_ENABLED = false
        const val DEFAULT_SHOW_RETRANSCRIBE_BUTTON = true
    }
}
