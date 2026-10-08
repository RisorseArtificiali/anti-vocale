package com.antivocale.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint

/**
 * TASK-785 (GH #144): the Open Transcribe contract provider. The component
 * ships disabled; [OpenTranscribeComponentSync] aligns it with the
 * preference. The AIDL surface lands with the transcription bridge.
 */
@AndroidEntryPoint
class OpenTranscribeProviderService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null
}
