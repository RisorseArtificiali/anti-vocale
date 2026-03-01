package com.localai.bridge

import android.app.Application
import com.localai.bridge.di.AppContainer

class BridgeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContainer.initialize(this)
        // Clean up old shared audio files
        com.localai.bridge.util.SharedAudioHandler.cleanupOldFiles(this)
    }
}
