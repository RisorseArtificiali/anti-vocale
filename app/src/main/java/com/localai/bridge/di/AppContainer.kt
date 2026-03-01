package com.localai.bridge.di

import android.content.Context
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

    val preferencesManager: PreferencesManager
        get() = _preferencesManager!!

    val logsViewModel: LogsViewModel
        get() = _logsViewModel!!

    val applicationContext: Context
        get() = _applicationContext!!

    /**
     * Initialize the container. Must be called in Application.onCreate()
     */
    fun initialize(context: Context) {
        _applicationContext = context.applicationContext
        _preferencesManager = PreferencesManager(context.applicationContext)
        _logsViewModel = LogsViewModel()
    }
}
