package com.localai.bridge

import android.app.Application
import com.localai.bridge.di.AppContainer

class BridgeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContainer.initialize(this)
    }
}
