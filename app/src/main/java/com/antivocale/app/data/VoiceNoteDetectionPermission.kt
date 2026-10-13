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
     * API 29 is deliberately excluded (review F2): scoped storage on Android
     * 10 lets the listing see WhatsApp's tree but forbids reading another
     * app's media by absolute file path, so the resolve-through-grant hop
     * would fail silently. Direct path reads work again from API 30+.
     * Supporting 29 needs the request to carry a MediaStore content URI
     * end to end (filed as TASK-777).
     */
    fun detectionAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission()) ==
            PackageManager.PERMISSION_GRANTED
}
