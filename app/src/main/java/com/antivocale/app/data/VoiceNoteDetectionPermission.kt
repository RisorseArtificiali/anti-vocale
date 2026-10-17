package com.antivocale.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permission voice-note detection needs: READ_MEDIA_AUDIO on
 * API 33+, READ_EXTERNAL_STORAGE below (where the manifest caps it with
 * maxSdkVersion="32"). One owner of the mapping; the coordinator's
 * injected checker and the Settings card's permission request both consume
 * it.
 */
object VoiceNoteDetectionPermission {

    /**
     * API 29 is deliberately excluded (review F2, premise NARROWED
     * 2026-10-17): the original reason blamed the resolve-through-grant
     * hop, but that hop is SAF-mediated (DocumentsContract + the persisted
     * tree grant), not a path read, and works on 29. The LIVE question is
     * the detection side: whether FileObserver + listing on another app's
     * Android/media tree works on API 29 under scoped storage without the
     * legacy-storage flag. That needs an actual Android 10 device probe
     * (TASK-777 owns it); until then the gate stays conservative.
     */
    fun detectionAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission()) ==
            PackageManager.PERMISSION_GRANTED
}
