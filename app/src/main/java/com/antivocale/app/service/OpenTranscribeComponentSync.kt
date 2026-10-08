package com.antivocale.app.service

import android.content.Context
import com.antivocale.app.util.ComponentAliasSync

/**
 * TASK-785: flips the Open Transcribe provider's manifest component. The
 * service ships android:enabled="false", so a disabled provider is invisible
 * to client queryIntentServices sweeps (Forkgram never lists an app the user
 * never opted in). One owner drives this: the BridgeApplication collector on
 * [com.antivocale.app.data.PreferencesManager.openTranscribeEnabled], which
 * covers every writer (the Settings toggle, TEST_SPI, any future one).
 */
object OpenTranscribeComponentSync {

    private const val TAG = "OpenTranscribe"

    fun setEnabled(context: Context, enabled: Boolean) {
        ComponentAliasSync.setEnabled(
            context, OpenTranscribeProviderService::class.java.name, enabled, TAG)
    }
}
