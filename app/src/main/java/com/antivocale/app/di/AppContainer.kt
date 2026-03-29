package com.antivocale.app.di

import android.content.Context
import com.antivocale.app.data.HuggingFaceApiClient
import com.antivocale.app.data.HuggingFaceAuthManager
import com.antivocale.app.data.HuggingFaceTokenManager
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.TranscriptionCalibrator
import com.antivocale.app.transcription.LlmTranscriptionBackend
import com.antivocale.app.transcription.SherpaOnnxBackend
import com.antivocale.app.transcription.TranscriptionBackendManager
import com.antivocale.app.transcription.WhisperBackend
import com.antivocale.app.data.local.AppDatabase
import com.antivocale.app.ui.viewmodel.LogsViewModel

/**
 * Simple dependency container for the app.
 * Uses singleton pattern for simplicity (no Hilt/Dagger needed for this app size).
 */
object AppContainer {

    private var _preferencesManager: PreferencesManager? = null
    private var _logsViewModel: LogsViewModel? = null
    private var _database: AppDatabase? = null
    private var _applicationContext: Context? = null
    private var _huggingFaceTokenManager: HuggingFaceTokenManager? = null
    private var _huggingFaceApiClient: HuggingFaceApiClient? = null
    private var _huggingFaceAuthManager: HuggingFaceAuthManager? = null
    private var _perAppPreferencesManager: PerAppPreferencesManager? = null
    private var _transcriptionCalibrator: TranscriptionCalibrator? = null

    val preferencesManager: PreferencesManager
        get() = _preferencesManager!!

    val logsViewModel: LogsViewModel
        get() = _logsViewModel!!

    val database: AppDatabase
        get() = _database!!

    val applicationContext: Context
        get() = _applicationContext!!

    val huggingFaceTokenManager: HuggingFaceTokenManager
        get() = _huggingFaceTokenManager!!

    val huggingFaceApiClient: HuggingFaceApiClient
        get() = _huggingFaceApiClient!!

    val huggingFaceAuthManager: HuggingFaceAuthManager
        get() = _huggingFaceAuthManager!!

    val perAppPreferencesManager: PerAppPreferencesManager
        get() = _perAppPreferencesManager!!

    val transcriptionCalibrator: TranscriptionCalibrator
        get() = _transcriptionCalibrator!!

    /**
     * Initialize the container. Must be called in Application.onCreate()
     */
    fun initialize(context: Context) {
        _applicationContext = context.applicationContext
        _preferencesManager = PreferencesManager(context.applicationContext)
        _perAppPreferencesManager = PerAppPreferencesManager(context.applicationContext)
        _transcriptionCalibrator = TranscriptionCalibrator(context.applicationContext)
        _database = AppDatabase.getDatabase(context.applicationContext)
        _logsViewModel = LogsViewModel(database.logDao())
        _huggingFaceTokenManager = HuggingFaceTokenManager(context.applicationContext).apply {
            initialize()
        }
        _huggingFaceApiClient = HuggingFaceApiClient()
        _huggingFaceAuthManager = HuggingFaceAuthManager(
            context.applicationContext,
            huggingFaceTokenManager
        )

        // Register transcription backends
        TranscriptionBackendManager.registerBackend(LlmTranscriptionBackend())
        TranscriptionBackendManager.registerBackend(SherpaOnnxBackend())
        TranscriptionBackendManager.registerBackend(WhisperBackend())
    }
}
