package com.antivocale.app.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * TASK-840 (GH #157 part 1): ONE tab-owned audio player for the result
 * rows. Framework MediaPlayer (no Media3 in the dependency set; minSdk 26
 * makes PlaybackParams speed native, pitch preserved). The player template
 * is the speaker-enrollment replay (SettingsTab, TASK-670): one live
 * player, a new play stops the previous, released on error and completion.
 *
 * DESIGN LINES (the task's explicit asks):
 * - AUDIO FOCUS: USAGE_MEDIA + CONTENT_TYPE_SPEECH (voice notes are
 *   speech; this renders on the SPEAKER, the sane default for a
 *   transcript-reading surface; an earpiece route would need
 *   USAGE_VOICE_COMMUNICATION and its muting quirks, deliberately not v1).
 *   Any focus loss pauses; the focus is abandoned on EVERY teardown
 *   (completion, error, failed start, switch, dispose), so another app's
 *   player is never left paused by an idle controller.
 * - SPEED: 1x / 1.25x / 1.5x cycle through PlaybackParams (pitch preserved
 *   by default), kept for the controller's lifetime across rows and
 *   replays. Setting params on a PAUSED player starts it on real devices,
 *   so the cycle re-pauses when it was paused (the params persist and the
 *   resume plays at the new rate).
 * - STATE is hoisted HERE, never row-local: the History LazyColumn
 *   recycles rows, and a row-local player would die mid-playback when its
 *   row scrolls out (the task's own design line).
 * - PROGRESS polls on the Main dispatcher (MediaPlayer is not thread-safe;
 *   200ms is imperceptible for a thin bar), and every native read is
 *   contained: an error-state player throws from currentPosition, which
 *   must heal (teardown), never crash the Main dispatcher.
 */
class ResultPlaybackController(private val audioManager: AudioManager) {

    /** Null = nothing playing (idle, completed, or failed). */
    data class NowPlaying(
        val taskId: String,
        val playing: Boolean,
        val positionMs: Int,
        val durationMs: Int,
        val speed: Float,
    )

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    /** Survives row switches and replays (the state flow does not). */
    private var speed = 1f

    /**
     * The user's intent while a prepare is in flight: a pause or focus
     * loss landing during prepareAsync must not be swallowed by the
     * prepared listener's unconditional start (audio would play with no
     * focus or against a Play icon).
     */
    private var wantsPlayback = true

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ONE usage/content-type pairing (the design line above): the focus
    // request and the player must never drift apart.
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(speechAttributes)
        .setOnAudioFocusChangeListener { change ->
            // Any loss pauses (v1: no ducking; voice-note playback is short
            // and foreground).
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ) {
                pause()
            }
        }
        .build()

    /** Toggle for a row: play / pause / resume, switching away from any
     *  other row's playback. A vanished file neither kills another row's
     *  live playback nor surfaces anything (the button disables on the next
     *  recomposition, the honest ~24h-sweep limit). */
    fun toggle(taskId: String, filePath: String?) {
        if (filePath == null || !playbackFileExists(filePath)) return
        val current = _state.value
        if (current?.taskId == taskId) {
            if (current.playing) pause() else resume()
            return
        }
        start(taskId, filePath)
    }

    fun cycleSpeed() {
        val current = _state.value ?: return
        val next = when (current.speed) {
            1f -> 1.25f
            1.25f -> 1.5f
            else -> 1f
        }
        val mp = player ?: return
        val applied = runCatching {
            mp.playbackParams = mp.playbackParams.setSpeed(next)
            // OEM guard: params on a PAUSED player start it; re-pause keeps
            // the paused-ness the user chose (the params persist).
            if (!current.playing) mp.pause()
        }.isSuccess
        if (!applied) return  // the UI must not report a rate never applied
        speed = next
        _state.value = current.copy(speed = next)
    }

    private fun start(taskId: String, filePath: String) {
        teardown()
        if (audioManager.requestAudioFocus(focusRequest) !=
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        ) {
            return
        }
        wantsPlayback = true
        _state.value = NowPlaying(
            taskId = taskId, playing = false, positionMs = 0, durationMs = 0, speed = speed)
        val created = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(speechAttributes)
                setDataSource(filePath)
                setOnPreparedListener { mp ->
                    // The identity guard: a fast row switch released this
                    // player while its prepare was in flight; a posted
                    // listener must not start the corpse.
                    if (mp !== player) return@setOnPreparedListener
                    // A pause or focus loss landed while prepareAsync was in
                    // flight: honor it (the resume path starts the prepared
                    // player later; it never auto-starts).
                    if (!wantsPlayback) return@setOnPreparedListener
                    // The speed must ride the params BEFORE start: a mid-play
                    // setSpeed on a paused player restarts it on some OEMs.
                    runCatching {
                        mp.playbackParams = mp.playbackParams.setSpeed(speed)
                    }
                    mp.start()
                    _state.value = _state.value?.copy(playing = true, durationMs = mp.duration)
                    startTicker()
                }
                // The replay template's release discipline (TASK-670 review):
                // a failed async prepare must release, not linger holding its
                // fd; a COMPLETED player releases and clears the row state.
                // Both teardown fully (focus abandoned too).
                setOnErrorListener { _, _, _ -> teardown(); true }
                setOnCompletionListener { teardown() }
                prepareAsync()
            }
        }.getOrNull()
        if (created == null) {
            teardown()
            return
        }
        player = created
    }

    private fun pause() {
        wantsPlayback = false
        val mp = player ?: return
        runCatching { mp.pause() }
        ticker?.cancel()
        // Contained like every native read: an error-state player throws
        // here (the class's own containment rule), and losing the position
        // snapshot is the right degradation, not a Main crash.
        val position = runCatching { mp.currentPosition }.getOrNull() ?: return
        _state.value = _state.value?.copy(playing = false, positionMs = position)
    }

    private fun resume() {
        wantsPlayback = true
        val mp = player ?: return
        runCatching { mp.start() }
        _state.value = _state.value?.copy(playing = true)
        startTicker()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && _state.value?.playing == true) {
                val mp = player
                if (mp == null) break
                // An error-state player throws here in the wild: heal by
                // teardown, never crash the Main dispatcher.
                val position = runCatching { mp.currentPosition }.getOrNull() ?: run {
                    teardown()
                    break
                }
                val duration = runCatching { mp.duration }.getOrNull() ?: 0
                _state.value = _state.value?.copy(positionMs = position, durationMs = duration)
                delay(200)
            }
        }
    }

    /**
     * The ONE teardown: cancels the ticker, releases the player (and nulls
     * the reference: a released instance must never be touched again),
     * abandons the focus, and clears the state. Called from every exit:
     * completion, error, failed start, row switch, and [release].
     */
    private fun teardown() {
        ticker?.cancel()
        player?.let { runCatching { it.release() } }
        player = null
        audioManager.abandonAudioFocusRequest(focusRequest)
        _state.value = null
    }

    /** The composition-exit hook; also the tab-switch cleaner. */
    fun release() {
        teardown()
        scope.cancel()
    }
}

/** The tab-owned controller: one per History composition, released with it. */
@Composable
fun rememberResultPlayback(): ResultPlaybackController {
    val context = LocalContext.current
    val controller = remember {
        ResultPlaybackController(
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
    }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}

/** The row's play eligibility: a row is playable only while its sandbox
 *  copy still exists (the ~24h SharedAudioHandler sweep; Tasker rows with
 *  durable user paths are the exception, per the task's honest limit). */
fun playbackFileExists(filePath: String?): Boolean =
    filePath != null && File(filePath).exists()
