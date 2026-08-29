# Background Share-Flow Freeze Investigation (OriginOS / Vivo)

**Date**: 2026-08-25
**Device under test**: Vivo (USB serial 10AE4M2062003N1), **OriginOS, Android 15** (API 35)
NOTE: this is NOT the Realme RMX3853 / ColorOS device listed in CLAUDE.md — the
session ran against a separate Vivo phone. An early recents clue
(`com.bbk.launcher2` = BBK/Vivo launcher) went unnoticed at the time.
**App under test**: fdroid debug build (`com.antivocale.app.diag` during the
experiment), backend GigaAM v3, audio = Telegram voice notes (opus/48kHz).

## Problem

Every sherpa-onnx model hung when audio arrived via the share menu:

- no result notification / PiP ever delivered;
- the Logs row stayed PROCESSING indefinitely;
- opening the app caused the result to arrive (after a wait);
- switching inference provider CPU <-> NNAPI changed nothing;
- the battery-exemption card in Settings had never been shown to the reporter.

## Root Cause (proven live)

**The OriginOS freezer suspends the whole process seconds into background
inference**, regardless of everything the app is entitled to:

```
curProcState=4            (foreground-service-level state)
isFrozen=true             (cgroup freezer engaged anyway)
cpu:/background, cpuset:/background, blkio:/bg   (restricted cgroups)
deviceidle whitelist      membership does NOT prevent the freeze
```

Diagnostic signature from the DiagTrace heartbeat (5s ticks, wall vs CPU):

- heartbeats stop mid-`sherpa-decode-begin` and stay silent for minutes;
- after thaw, wall-vs-cpu deltas show suspension (e.g. a MediaCodec decode
  worth ~3.5s logged as `PERF: preprocessing 181087ms`; another gap measured
  `tickGap=99159ms cpuDuringGap=3734ms`);
- work resumes exactly where it stopped upon any interaction that thaws the
  process (opening the app, tapping its notification);
- while frozen, the process cannot post anything — notifications/alarm
  receivers simply do not run, so self-rescue from inside the freeze is
  impossible.

This closes the loop on the earlier research note
(`2026-08-19_notification-teardown-race-verification.md`), which predicted
exactly this residual case ("if the freezer symptom persists on device
despite the fix ... check the reporter's battery settings") — though it turns
out even battery settings do not help here.

## Tested and Ruled Out

| Mechanism | Result |
|---|---|
| Partial wake lock (30-min cap) | No effect against the freezer. Kept anyway: correct baseline for stock-Android deep idle. |
| FGS type `specialUse` -> `mediaProcessing` (API 35+) | No effect on freezing. |
| Doze whitelist (system dialog `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, verified via `dumpsys deviceidle whitelist`) | **No effect.** This freezer is not Doze. The user's earlier report "adding the exemption fixed it" did not reproduce under controlled conditions — likely a coincidental interaction (e.g. touching the app thawed the run). |
| AlarmManager self-thaw (RTC_WAKEUP every 20s via `setAndAllowWhileIdle`) | **Alarms are not delivered to frozen apps on this ROM** — zero deliveries logged across multiple attempts. Removed. |
| Dual declared FGS types `specialUse\|mediaProcessing` + `ServiceCompat.startForeground(type)` | **Crash**: `InvalidForegroundServiceTypeException: Starting FGS with type none ... has been prohibited` on API 35/36. The dual declaration resolves to none; single declared type is mandatory. |

## The Fix That Works

**`SilentAudioKeepalive`** (`service/SilentAudioKeepalive.kt`): an inaudible
looping `AudioTrack` (`USAGE_MEDIA`, CONTENT_TYPE_MUSIC, static-mode zero PCM,
~zero CPU) held for the duration of the transcription batch. The platform then
classifies the process as actively playing media via AudioPlaybackConfiguration,
and the OEM freezer skips playing-media apps (freezing one would be audible).
Confirmed working end-to-end on-device: share -> screen off -> result
notification arrives without any user interaction.

Kept alongside the fix:

- **Freeze detector**: a heartbeat tick arriving >15s late (interval 5s) is
  conclusive proof of an OS suspension between ticks (`freeze-detected` mark),
  since no app-side code runs while frozen.
- **Rescue path** for devices where the keepalive does not classify us as
  media: one-shot battery dialog at share time (the only guaranteed-foreground
  moment), post-freeze notification deep-linking the same dialog (the tap
  itself thaws the process, so the interrupted transcription auto-resumes),
  Settings battery card shown whenever the app is not exempt (a freeze closes
  its row as SUCCESS, so the TASK-336 kill-sweep counter never saw them).
- **DiagTrace** stage timeline retained for future diagnosis.

## Commits

Branch `fix/transcription-wake-lock`:

- `eee028f` — partial wake lock held for transcription batches
- `1b3392c` — silent-audio keepalive defeats OEM mid-inference freeze
  (+ diagnostics + rescue path; experiment-only `.diag` package branding
  reverted before commit)

## Open Items for Release

1. **FGS type compat**: `mediaProcessing` exists only on API 35+; this ROM is
   exactly API 35. Since dual declaration crashes (see table), release keeps a
   SINGLE declared type `mediaProcessing` and passes it via the two-argument
   startForeground() verbatim. VERIFIED SAFE on Android 14 / API 34 (Xiaomi 13,
   near-AOSP ROM, 2026-08-25): the platform silently accepts the unknown bit;
   two consecutive BenchmarkActivity-driven service starts ran the full batch
   lifecycle with no InvalidForegroundServiceTypeException. APIs 26-33 remain
   untested but use the same lenient flag-passthrough behavior.
2. **DiagTrace volume**: marked TEMP; prune or downgrade to `Log.v` behind a
   debug flag before release.
3. **Cleanup**: uninstall the `com.antivocale.app.diag` experiment package
   from the test phone.
4. **CLAUDE.md**: the "Device" section documents only the Realme RMX3853;
   this Vivo/OriginOS Android 15 phone is now a known test target too.
5. The wake-lock commit message predates the true root cause and reads as if
   deep idle were the suspect; the follow-up commit supersedes it.
