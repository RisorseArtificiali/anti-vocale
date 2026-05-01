package com.antivocale.app.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.antivocale.app.transcription.LlmTranscriptionBackend
import com.antivocale.app.transcription.Qwen3AsrBackend
import com.antivocale.app.transcription.SherpaOnnxBackend
import com.antivocale.app.transcription.WhisperBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ShareTargetManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager
) {
    companion object {
        private const val TAG = "ShareTargetManager"

        private data class ShareTarget(
            val className: String,
            val backendId: String
        )

        private val TARGETS = listOf(
            ShareTarget("com.antivocale.app.ShareParakeet", SherpaOnnxBackend.BACKEND_ID),
            ShareTarget("com.antivocale.app.ShareWhisper", WhisperBackend.BACKEND_ID),
            ShareTarget("com.antivocale.app.ShareQwen3", Qwen3AsrBackend.BACKEND_ID),
            ShareTarget("com.antivocale.app.ShareGemma", LlmTranscriptionBackend.BACKEND_ID)
        )
    }

    private fun hasModel(backendId: String): Boolean = runBlocking {
        when (backendId) {
            SherpaOnnxBackend.BACKEND_ID -> preferencesManager.parakeetModelPath.first() != null
            WhisperBackend.BACKEND_ID -> preferencesManager.whisperModelPath.first() != null
            Qwen3AsrBackend.BACKEND_ID -> preferencesManager.qwen3AsrModelPath.first() != null
            LlmTranscriptionBackend.BACKEND_ID -> preferencesManager.modelPath.first() != null
            else -> false
        }
    }

    private fun setComponentEnabled(target: ShareTarget, enabled: Boolean) {
        val state = if (enabled)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, target.className),
                state,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync ${target.className}", e)
        }
    }

    fun syncAll() {
        val advancedEnabled = runBlocking {
            preferencesManager.advancedSharingEnabled.first()
        }

        TARGETS.forEach { target ->
            setComponentEnabled(target, advancedEnabled && hasModel(target.backendId))
        }
    }

    fun onModelDeleted(backendId: String) {
        val target = TARGETS.find { it.backendId == backendId } ?: return
        setComponentEnabled(target, false)
    }

    fun onModelDownloaded() {
        syncAll()
    }

    fun setAdvancedSharingEnabled(enabled: Boolean) {
        if (enabled) {
            syncAll()
        } else {
            TARGETS.forEach { setComponentEnabled(it, false) }
        }
    }
}
