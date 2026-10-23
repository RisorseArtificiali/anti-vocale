package com.antivocale.app.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * GH #67's core mechanism (Dum4G's root-cause work, proven on-device):
 * ColorOS-class OEM freezers suspend the process mid-inference even with
 * a foreground service (any type), a wake lock, and Doze-whitelist
 * membership, but they universally spare apps that are actively PLAYING
 * AUDIO (freezing one would be audible). A silent media stream makes the
 * process look like a media app to the platform's
 * AudioPlaybackConfiguration, which the freezer consults.
 *
 * Static-mode track with a 1s zero-filled PCM16 buffer looping forever:
 * negligible CPU, zero audible output; the price is the audio HAL path
 * staying clocked for the batch (the mechanism IS the active track).
 * The OEM classifier keys on ACTIVE AUDIO, not on the declared FGS type
 * (the #67 probe table: FGS type mediaProcessing had no effect on
 * freezing), so this rides WITHOUT any manifest or FGS-type change.
 *
 * NOT the other keepalive: [com.antivocale.app.util.NativeKeepAlive]
 * keeps the native engine resident while IDLE; this keeps the PROCESS
 * unfrozen while a batch runs.
 *
 * Scoped to a transcription batch: started when the batch-owning drain
 * coroutine begins work, stopped when the queue drains (the same
 * lifecycle points that own the foreground notification). Threading
 * contract: start() runs on the serviceScope (Dispatchers.IO) drain
 * coroutine, stop() runs on that scope (drain finally, cancel path) or
 * the main thread (onDestroy); both methods are synchronized, so any
 * interleaving is safe and a stop can never orphan a playing track.
 * Keep start() SYNCHRONOUS relative to the drain's stop(): an async
 * wrapper could invert the order and leak the looping track. Failure to
 * start is logged and swallowed: on devices without the freezer the
 * keepalive is unnecessary, and a broken audio path (including a
 * throwing Builder.build) must never take transcription down with it.
 */
class SilentAudioKeepalive(private val tag: String) {

    private var track: AudioTrack? = null

    /** Starts the silent loop; idempotent. */
    @Synchronized
    fun start() {
        if (track != null) return
        val sampleRateHz = 8000
        val silentPcm = ShortArray(sampleRateHz) // 1 second of zeros
        var built: AudioTrack? = null
        runCatching {
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
            built = audioTrack
            // A short or failed write leaves the static buffer undefined:
            // the loop would emit garbage, not silence (review). Fail the
            // start instead; the NEXT BATCH's start() retries (idempotent).
            val written = audioTrack.write(silentPcm, 0, silentPcm.size)
            check(written == silentPcm.size) { "short write: $written" }
            audioTrack.setLoopPoints(0, silentPcm.size, -1)
            audioTrack.play()
            track = audioTrack
            Log.i(tag, "Silent keepalive started")
        }.onFailure { e ->
            Log.w(tag, "Silent keepalive failed to start", e)
            // The built track holds native resources and an audio-session
            // entry: a start that did not complete must release it (review:
            // the failure path used to leak one per batch on a broken
            // audio device).
            built?.let { runCatching { it.release() } }
        }
    }

    /** Stops and releases the loop; idempotent, safe from any teardown path. */
    @Synchronized
    fun stop() {
        track?.let { t ->
            runCatching { t.stop() }
            runCatching { t.release() }
            Log.i(tag, "Silent keepalive stopped")
        }
        track = null
    }
}
