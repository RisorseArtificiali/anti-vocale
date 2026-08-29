package com.antivocale.app.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * TEMP diag experiment: an inaudible looping AudioTrack held while a transcription
 * batch runs. Purpose: this ColorOS-class ROM freezes the process mid-inference even
 * with a foreground service (any type), a wake lock, and Doze-whitelist membership —
 * but OEM freezers universally spare apps that are actively PLAYING AUDIO (freezing
 * one would be audible). A silent media stream makes the process look like a media
 * app to the platform's AudioPlaybackConfiguration, which the freezer consults.
 *
 * Static-mode track with a 1s zero-filled PCM16 buffer looping forever: zero CPU,
 * zero audible output, negligible battery. Not a MediaSession — that can be added
 * later if the plain player state alone proves insufficient.
 */
class SilentAudioKeepalive(private val tag: String) {

    private var track: AudioTrack? = null

    val isPlaying: Boolean get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    /** Starts the silent loop; idempotent. */
    fun start() {
        if (track != null) return
        val sampleRateHz = 8000
        val silentPcm = ShortArray(sampleRateHz) // 1 second of zeros
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(silentPcm.size * 2)
            .build()
        runCatching {
            audioTrack.write(silentPcm, 0, silentPcm.size)
            audioTrack.setLoopPoints(0, silentPcm.size, -1)
            audioTrack.play()
            track = audioTrack
            Log.i(tag, "Silent keepalive started")
        }.onFailure { e ->
            Log.w(tag, "Silent keepalive failed to start", e)
            runCatching { audioTrack.release() }
        }
    }

    /** Stops and releases the loop; idempotent, safe from any teardown path. */
    fun stop() {
        track?.let { t ->
            runCatching { t.stop() }
            runCatching { t.release() }
            Log.i(tag, "Silent keepalive stopped")
        }
        track = null
    }
}
