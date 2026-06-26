# Subtitle Extraction (TASK-228 / upstream issue #9)

**Status:** Approved design, pre-implementation (revision 2 — incorporates spec-review findings)
**Date:** 2026-06-26
**Upstream issue:** https://github.com/RisorseArtificiali/anti-vocale/issues/9
**Depends on:** TASK-227 (video file input) — shipped (commit `a05d822`)

## Context

Issue #9 (by @mollyrealized): when Anti-Vocale receives a video, check whether it already contains subtitles before running ASR — creator-prepared captions are often more accurate than ASR (correct names, punctuation, intended wording). If subtitles exist, offer the user: **extract subtitles → text** OR **ignore + transcribe audio**. If none, continue normal ASR. Out of scope: visual OCR / frame analysis.

TASK-227 (video input) shipped first; this task builds on it. Issue #9's *sidecar* subtitle case (a `.srt` next to the video) is **infeasible in the share flow** — Android hands over a single content URI with no sibling-file access — so the scope is **embedded subtitle tracks only**, for the share flow. (Tasker is deferred.)

## Key constraints (verified)

- **No new *media* dependency.** FFmpegKit was retired Jan 2025; the app decodes via Android native `MediaExtractor` + `MediaCodec`. Subtitle detection/extraction also uses `MediaExtractor`.
- **WorkManager is NOT currently a dependency** (verified: no `androidx.work` in `app/build.gradle.kts`, zero usage in `app/src/main/java`). This task **adds `androidx.work:work-runtime-ktx`** (Apache-2.0, F-Droid-safe) to power the choice-timeout. *Correction of an earlier false claim that it was already present.*
- **`MediaExtractor` subtitle reading is format-dependent** and can be flaky; the design is best-effort with ASR fallback. Text-MIME tracks only; image-based (VobSub/PGS) and heavily-styled ASS/SSA are skipped unless they decode as plain text.
- **The share path currently has no UI** — `ShareReceiverActivity` is a transparent activity that copies the file, starts the foreground service, and `finish()`es. The "ask the user" choice is surfaced as a **notification**, not a dialog.
- **No process may stay alive while waiting for the user's choice** (user constraint). This rules out "probe + wait inside the service."
- **Android 12+ (the app targets SDK 35) restricts starting a foreground service from the background.** The timeout fires in a background context, so it must NOT call `startForegroundService(InferenceService)` directly. See the timeout mechanism below.

## Architecture

The feature is framed as **an alternative text source** feeding the existing transcription-result pipeline — not a parallel pipeline. Both ASR and subtitle extraction produce one string of text that flows through the same result notification / copy / share-back / logs path. The branch is a single `mode` at the **input** of the pipeline, which keeps logs, the 🎥 badge, and share-back working unmodified.

### New component: `SubtitleExtractor`

New file under `app/src/main/java/com/antivocale/app/transcription/`. Declared a Kotlin **`object`** (pure utility, no state) — **intentionally not Hilt-injected**, even though the cited precedent `AudioPreprocessor` is `@Singleton @Inject`. No state → no DI need; recorded so reviewers don't flag the deviation.

```kotlin
data class SubtitleTrack(val trackIndex: Int, val language: String?, val mime: String)

object SubtitleExtractor {
    /** Scan [videoPath] for text-MIME subtitle tracks. Empty if none / unreadable. */
    fun probe(videoPath: String): List<SubtitleTrack>

    /** Read [trackIndex] sample data, strip timestamps/markup → plain text.
     *  null on failure or empty result. */
    fun extractToText(videoPath: String, trackIndex: Int): String?
}
```

- `probe`: opens `MediaExtractor`, iterates `trackCount`, returns tracks whose MIME indicates text (e.g. `application/x-subrip`, `text/vtt` / `application/webvtt`, the MP4 mov-text family). Skips image-MIME tracks (VobSub/PGS). Includes the track `language` tag when present (for app-language matching). The exact MIME whitelist is a single named constant, tunable from on-device findings during implementation.
- `extractToText`: `selectTrack`, `readSampleData` loop, strip timestamp/markup (SRT blocks, WebVTT `-->` cues, tx3g styling) → concatenated plain text. `null` if no usable text.

### Share flow change (`ShareReceiverActivity`)

After `SharedAudioHandler.copyToAppStorage` succeeds, **only if the file is a video** (`SharedAudioHandler.isVideoFile(localPath)`):

1. `SubtitleExtractor.probe(localPath)` — a `MediaExtractor` track scan (milliseconds); the activity then finishes.
2. If ≥1 text track: pick the **best track** — the one whose `language` matches the app's configured transcription language; else the first. Post a **"Subtitles found"** notification (reusing existing notification helpers/channels) whose content text includes the picked language when known (e.g. *"Subtitles (it) found — use them?"*) to avoid silent wrong-language picks. Two actions:
   - **Use subtitles** → start `InferenceService` with `requestType = "subtitles"`, `trackIndex`, `taskId`.
   - **Transcribe audio** → start `InferenceService` normally (today's path).
   Each action **cancels the timeout worker** first (see below). Then `finish()` — no ASR started yet.
3. If zero text tracks: start `InferenceService` as today (unchanged).

Audio shares and subtitle-less videos: no probe cost beyond a no-op for audio (gated behind `isVideoFile`), milliseconds for subtitle-less videos; no notification, no service linger.

### `InferenceService` / orchestrator: subtitle mode + integration fixes

- **New `requestType = "subtitles"` branch** in `TranscriptionOrchestrator.processRequest`. It calls `SubtitleExtractor.extractToText(filePath, trackIndex)`; on success the returned text flows through the **identical** result/notification/copy/share-back path as a successful ASR (no model loaded, no decode). On `null`/empty → fall back to ASR with a note ("Couldn't extract subtitles — transcribing audio").
- **Skip backend-load for subtitle mode.** `processRequest` currently calls `ensureBackendLoaded()` unconditionally before its mode `when` (`TranscriptionOrchestrator.kt:88`). Subtitle mode must **not** force model load — branch the subtitle path *before* `ensureBackendLoaded`, or move the load below the `when` so only the audio path loads. A subtitle-mode request that later falls back to ASR then loads the backend at that point.
- **`taskId` dedup.** `InferenceService.requestQueue` (`InferenceService.kt:80`) currently processes every `onStartCommand` without dedup, so a user tap racing the timeout could enqueue the same `taskId` twice. Add dedup: reject/merge a request whose `taskId` is already enqueued or processing. This also protects against duplicate shares.

### Timeout → ASR fallback (no lingering process; Android-12+-safe)

Mechanism: **WorkManager** (new dependency — `androidx.work:work-runtime-ktx`).

- On posting the "Subtitles found" notification, enqueue a **one-time, expedited** `WorkRequest` with `setInitialDelay(SUBTITLE_CHOICE_TIMEOUT_MINUTES = 5, MINUTES)` and a **unique name per `taskId`** (`subtitle-choice-$taskId`).
- Notification actions (*Use subtitles* / *Transcribe audio*) call `WorkManager.cancelUniqueWork("subtitle-choice-$taskId")` before starting the service, so a user tap prevents the timeout.
- **Android 12+ foreground-service-start restriction:** the timeout fires in a background context and must NOT call `startForegroundService(InferenceService)` (blocked from background). Instead, the timeout **`Worker` is expedited and self-promotes to foreground** via `setForeground(getForegroundInfo(...))`, then runs the ASR transcription **directly through the orchestrator** — not by starting the service. This requires a small refactor: extract the Service's "run one request + post result/error notification" path into a shared runner that **both `InferenceService` and the timeout `Worker` invoke**; the orchestrator's `TranscriptionListener` (which posts notifications) is the seam.
- Survives process death (WorkManager guarantee); **no process is alive during the 5-min wait.**

`SUBTITLE_CHOICE_TIMEOUT_MINUTES = 5` is a single named constant; tunable. If on-device testing shows expedited-work quota issues, the fallback is to convert the timeout into a *tap-to-transcribe* notification (foreground-context-on-tap, no restriction) — recorded as a contingency, not the default.

### Result, logs, badge

- The result flows through the existing log-insertion path → the **🎥 badge** (TASK-227, keyed on `filePath` extension) applies to subtitle-originated entries too, since the `filePath` is the copied video. (The source *was* a video, even though the text came from its subtitle track. Distinguishing "subtitled" from "ASR'd" entries would need a persisted column — out of scope.)
- `taskId` dedup (above) prevents double-logging on tap/timeout races.

## Error handling & race resolution

| Case | Behavior |
|---|---|
| `MediaExtractor` fails to open the video (probe) | Log; proceed to normal ASR (no notification, no worker). |
| Zero text tracks found | Normal ASR (no notification, no worker scheduled). |
| Extraction returns `null`/empty (flaky format / unreadable) | Fall back to ASR; notify the fallback; log the MIME that failed. |
| User taps *Use subtitles* / *Transcribe audio* | Cancels the timeout worker (`cancelUniqueWork`); starts service in the chosen mode. |
| User ignores notification (>5 min) | Worker fires → self-promotes to foreground → runs ASR via orchestrator → posts result notification; choice notification cancelled. |
| **Tap races the timeout** (e.g. tap at 4:59, worker at 5:00) | Whichever starts first wins; the orchestrator's `taskId` dedup makes the second a no-op. |
| User dismisses the notification | Treated as "ignored"; the timeout still fires → ASR. |
| User taps *Use subtitles* after the worker already dispatched ASR | `taskId` dedup rejects it; the ASR result already in flight is preserved. |

Never crashes; every path produces a result (subtitle text, or ASR text).

## Out of scope (v1)

- **Sidecar subtitles** (`.srt`/`.vtt`/`.ass` next to the video) — unreachable in the share flow.
- **Tasker flow** — continues ASR; subtitle auto-use for Tasker is a small follow-up.
- **Per-track picker UI** — best-track auto-pick only (app-language match → first).
- **ASS/SSA styling** — skipped unless it decodes as plain text.
- **Persisted `sourceMediaType` column** — the badge derives from `filePath` extension (TASK-227); no schema change.

## Testing

- **Unit-test `SubtitleExtractor`**: the timestamp-stripping parser with pure-string fixtures (SRT block, WebVTT cue, tx3g-style markup); `probe` MIME-whitelist logic with synthetic `MediaFormat` inputs where constructible.
- **Device test** (Realme RMX3853, debug-suffix build): share a video *with* embedded subtitles → "Subtitles found" notification → tap *Use subtitles* → subtitle text as result (🎥 badge in Logs). Share a subtitle-less video → straight ASR (no notification). Ignore the notification >5 min → ASR fallback via the worker. Tap within the race window → dedup (no double result).
- **WorkManager**: verify the unique-work cancel on tap, and the expedited-worker foreground promotion on timeout, survive a `force-stop`-and-restart during the wait.
- **No new R8/JNI surface** from `SubtitleExtractor` (plain Kotlin, existing `MediaExtractor` APIs already covered by keep rules). WorkManager is a standard AndroidX lib (no JNI); standard release R8 audit applies, no special keep rules expected.

## Open questions (for implementation, not blocking the design)

- Exact set of subtitle MIME strings `probe` treats as "text" — confirm against real files on-device (MKV subrip / MP4 mov-text / WebVTT MIMEs vary by sender/encoder). Single constant, easy to extend.
- Whether the expedited-work quota comfortably accommodates a one-shot 5-min-delayed foreground worker on the target device — verify on-device; contingency is the tap-to-transcribe fallback noted above.
