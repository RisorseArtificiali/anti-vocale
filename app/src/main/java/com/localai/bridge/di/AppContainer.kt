package com.localai.bridge.di

import android.content.Context
import com.localai.bridge.data.HuggingFaceApiClient
import com.localai.bridge.data.HuggingFaceAuthManager
import com.localai.bridge.data.HuggingFaceTokenManager
import com.localai.bridge.data.PreferencesManager
import com.localai.bridge.ui.viewmodel.LogsViewModel

/**
 * Simple dependency container for the app.
 * Uses singleton pattern for simplicity (no Hilt/Dagger needed for this app size).
 */
object AppContainer {

    private var _preferencesManager: PreferencesManager? = null
    private var _logsViewModel: LogsViewModel? = null
    private var _applicationContext: Context? = null
    private var _huggingFaceTokenManager: HuggingFaceTokenManager? = null
    private var _huggingFaceApiClient: HuggingFaceApiClient? = null
    private var _huggingFaceAuthManager: HuggingFaceAuthManager? = null

    val preferencesManager: PreferencesManager
        get() = _preferencesManager!!

    val logsViewModel: LogsViewModel
        get() = _logsViewModel!!

    val applicationContext: Context
        get() = _applicationContext!!

    val huggingFaceTokenManager: HuggingFaceTokenManager
        get() = _huggingFaceTokenManager!!

    val huggingFaceApiClient: HuggingFaceApiClient
        get() = _huggingFaceApiClient!!

    val huggingFaceAuthManager: HuggingFaceAuthManager
        get() = _huggingFaceAuthManager!!

    /**
     * Initialize the container. Must be called in Application.onCreate()
     */
    fun initialize(context: Context) {
        _applicationContext = context.applicationContext
        _preferencesManager = PreferencesManager(context.applicationContext)
        _logsViewModel = LogsViewModel()
        _huggingFaceTokenManager = HuggingFaceTokenManager(context.applicationContext).apply {
            initialize()
        }
        _huggingFaceApiClient = HuggingFaceApiClient()
        _huggingFaceAuthManager = HuggingFaceAuthManager(
            context.applicationContext,
            huggingFaceTokenManager
        )
    }
}
