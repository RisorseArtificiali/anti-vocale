package com.antivocale.app.service

import android.content.Context
import android.content.pm.PackageManager
import com.antivocale.app.data.PreferencesManager
import kotlinx.coroutines.flow.first

/**
 * TASK-785: aligns the Open Transcribe provider's manifest component with
 * the [PreferencesManager.openTranscribeEnabled] gate. The service ships
 * android:enabled="false", so a disabled provider is invisible to client
 * queryIntentServices sweeps (Forkgram never lists an app the user never
 * opted in); enabling the toggle flips the component without a reinstall,
 * the ShareTargetManager component contract. Read-first like
 * VoiceNoteIdentityComponent: the preference replays its cached value on
 * every cold start and an unconditional write would churn package state on
 * the startup path for the default-off case.
 */
object OpenTranscribeComponentSync {

    private const val TAG = "OpenTranscribe"

    fun setEnabled(context: Context, enabled: Boolean) {
        val component = android.content.ComponentName(context, OpenTranscribeProviderService::class.java)
        val target = if (enabled)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (context.packageManager.getComponentEnabledSetting(component) == target) return
        com.antivocale.app.util.ComponentAliasSync.setEnabled(
            context, OpenTranscribeProviderService::class.java.name, enabled, TAG)
    }

    /** Startup alignment: reads the gate and mirrors it onto the component. */
    suspend fun syncFromPreference(context: Context, preferences: PreferencesManager) {
        setEnabled(context, preferences.openTranscribeEnabled.first())
    }
}
