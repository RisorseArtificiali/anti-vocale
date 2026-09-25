# Replaying the original voice message from History: design exploration (TASK-654, android-hilfe request)

Date: 2026-09-25. Origin: the same first-campaign review as TASK-653 ("toll waere auch, wenn man sich die Originalnachricht aus dem Verlauf heraus nochmal anhoeren koennte"). Exploration deliverable; implementation follows the maintainer's cut.

## Verified facts (code citations)

1. WHERE AUDIO LIVES: every shared note is staged by SharedAudioHandler into filesDir/shared_audio (SHARED_AUDIO_DIR, SharedAudioHandler.kt:34,130) and the row persists that path in filePath (orchestrator writes it on the row). The file is therefore ADDRESSABLE from the row that transcribed it.
2. RETENTION IS ALREADY DECIDED BY THE CODE: SharedAudioHandler.cleanupOldFiles (SharedAudioHandler.kt:302) runs at startup (BridgeApplication.kt:58) and deletes staged files older than 24 hours. So playback today can reach yesterday's notes at best; the retranscribe flow already models the gone state (file-exists check + localized Toast).
3. NO NEW PERMISSION is needed for in-app playback (MediaPlayer over an app-private file).
4. The exported-ACTION_VIEW alternative would need a FileProvider grant, exposing the private audio to other apps: it contradicts the app's local-everything story and is rejected on that ground alone (recorded so it is not re-proposed).

## Recommended v1

- A play/stop icon button on the EXPANDED History row, visible only when File(filePath).exists() (gone = no dead control; no Toast needed for a button that is simply not there).
- Playback via MediaPlayer owned by the LogsViewModel (play toggles stop; a second row's play stops the first: one player instance, the notification-surface precedent of never stacking audio). No scrubber, no MediaSession, no notification playback in v1.
- Video-origin rows play their audio track the same way (MediaPlayer handles the container's audio); a video surface is explicitly out.
- Ownership of the lifecycle: stop the player on row dispose/tab leave/app pause (the keep-alive/bracket conventions used elsewhere).

## The one decision for the maintainer: retention

24 hours (today's constant) covers "listen again to this morning's notes" but not last week's. Options:

- (a) RECOMMENDED for v1: keep 24h, document it in the FAQ entry (the button simply disappears for older notes; honest, zero new state).
- (b) Raise the constant (e.g. 7 days) and keep the startup sweep: one-line change, more disk (voice notes are 50-300KB each; a week of heavy use is tens of MB).
- (c) A retention setting: only if users ask after (a) ships.

## Explicitly out of v1

Waveform display, speed control, per-row playback position memory, background/notification playback, and playing audio for TEXT-type rows (there is none).
