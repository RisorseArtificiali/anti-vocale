# Security design memo: TASK-274, exported Tasker receivers

**Date**: 2026-09-03 (overnight analysis, no code changed)
**Status**: maintainer decision pending; every claim carries file:line
**Pending verification**: two one-minute device probes (section 5; device was offline during analysis)

## 0. Headline finding that reframes the decision

The breakage premise of this task is stale. Everyone assumed a gate "would BREAK every existing Tasker automation", but the documented Tasker flow has been dead since 2026-07-29: commit `b54303d` ("Remove unused storage permissions (READ_MEDIA_AUDIO, READ/WRITE_EXTERNAL_STORAGE)") removed the only permission that let the app read the WhatsApp paths the tutorial feeds it. The commit message audited three file-access paths (SAF model import, share-intent content URIs, SAF audio picker) and missed the fourth: the Tasker receiver's path-based read. The tutorial (`docs/TASKER_TUTORIAL.md`) is dated 2026-06-02, two months before the removal, and is untracked in git. So:

- `TASKER_TUTORIAL.md:94` tells users to feed `/storage/emulated/0/Android/media/com.whatsapp/.../file.opus` as `file_path`.
- The app opens that path under its own uid via `MediaExtractor.setDataSource(inputPath)` (`AudioPreprocessor.kt:455`, `:282`).
- With `targetSdk = 36` (`app/build.gradle.kts:34`) and zero storage permissions (`AndroidManifest.xml:5-7`), the app uid cannot open other apps' media files by path on Android 11+; reading another app's audio requires `READ_MEDIA_AUDIO`, which is no longer declared (and a permission absent from the manifest cannot be granted, not even via `pm grant`, and is auto-revoked on update for users who had granted it).
- Therefore every shipped build containing `b54303d` (1.11.0 F-Droid onward, merged 2026-08-31) returns a preprocessing error for the tutorial's flow, and even the tutorial's own adb self-test (`TASKER_TUTORIAL.md:199-206`, `/sdcard/Download/test_voice.m4a`) fails the same way, because the receiver, not the shell, opens the file.

Acceptance criterion 3 of TASK-274 ("Tasker tutorial flow still works end-to-end") is currently unachievable regardless of which gate we pick. The morning decision is therefore not only "which gate", but "what is the Tasker API supposed to be now that path-based shared-storage access is gone". One option must be rejected preemptively: re-adding `READ_MEDIA_AUDIO` to save the tutorial would instantly turn the receiver into a transcript-exfiltration oracle over every voice note on the device (any app broadcasts `PROCESS_REQUEST` with a WhatsApp path and receives the text back, see section 1.3).

## 1. Threat model

### 1.1 Entry points (current state, `AndroidManifest.xml`)

| Component | Manifest | Guard |
|---|---|---|
| `TaskerRequestReceiver` (`PROCESS_REQUEST`) | `AndroidManifest.xml:79-86` | `exported="true"`, no `android:permission` |
| `ModelPreloadReceiver` (`PRELOAD_MODEL`) | `AndroidManifest.xml:89-96` | `exported="true"`, no `android:permission` |
| (Adjacent, not in task scope) `BenchmarkActivity` | `AndroidManifest.xml:406-413` | `exported="true"`, in the **main** source set, ships in release |

`TaskerTrampolineActivity` is correctly `exported="false"` (`AndroidManifest.xml:399-404`), so the fallback path is not directly forgeable by third parties.

### 1.2 What an arbitrary app can do through `TaskerRequestReceiver`

Extras honored (`TaskerRequestReceiver.kt:91-97`): `request_type` (defaults to `"text"` when absent), `prompt`, `file_path`, `task_id`, `backend_id` (one-shot override, validated by `isKnownBackendId`, lines 139-142), `subtitle_track_index` (declared line 57 but never forwarded by the receiver into the service intent, lines 110-116, so a `subtitles` request always degrades to ASR per the `trackIndex < 0` guard at `TranscriptionOrchestrator.kt:321-325`).

`isKnownBackendId` accepts `"llm"`, any `BundledCatalog` entry id, and any string starting with `external:` (dangling external ids fail loudly downstream, `TranscriptionOrchestrator.kt:560-565`). Consequences:

1. **Free on-device LLM oracle.** `request_type` `"text"` routes the caller's arbitrary `prompt` to `processTextRequest` (`TranscriptionOrchestrator.kt:600-612`); with `backend_id="llm"` any app force-loads the Gemma backend (`ensureBackendLoaded`, `:359-409`) and receives the generated text through the reply broadcast. Content generation at the user's battery expense; the override self-cleans after the request (`:281-287`).
2. **Transcription of any path the app uid can read.** `processAudioRequest` does no path validation (`:623-645`); `validateInputFile` checks existence only (`AudioPreprocessor.kt:633-635`). What is readable today is exactly the app's own sandboxes (section 3), notably `files/shared_audio/` (`SharedAudioHandler.kt:109-115`), where user-shared voice notes persist for 24 hours (`cleanupOldFiles`, `:244`).
3. **Forced model loads.** `backend_id` accepts any catalog id, so a caller can repeatedly load the largest bundled model (memory pressure on low-RAM devices; unloaded after each request, `:281-287`).
4. **Queue/notification/DB spam.** Dedup is by `taskId` only (`InferenceService.kt:193-197`); unique ids enqueue unbounded work, each enqueue writes a Room row (`:205-215`, no automatic cap in `LogDao.kt`; only manual `deleteAll`), and each blocked start posts a high-priority notification in the 2201-2300 id band (`TaskerRequestReceiver.kt:76-80`).

**The interaction gate that limits 1-3 today:** the receiver calls `startForegroundService` directly (`TaskerRequestReceiver.kt:122`). A broadcast from another app never puts the app in a foreground-exempt state on Android 12+, so on a stock device this throws and falls back to a notification requiring one user tap (catches at `:124-132`, trampoline at `:149-190`). Two things erase that gate: (a) battery-optimization "Unrestricted", which our own tutorial recommends (`TASKER_TUTORIAL.md:218`) and which TASK-336 actively pushed users toward (commit `36f2ae4`); (b) any already-running transcription, after which further requests ride the live foreground service with zero interaction.

### 1.3 The exfiltration channel: the reply broadcast

This is the most important sink and it is wide open. On every completed request, success or error, the app fires an **implicit, undirected** broadcast:

- `InferenceService.sendSuccessReply` (`InferenceService.kt:599-606`) sends `net.dinglisch.android.tasker.ACTION_TASKER_INTENT` with `result_text` = the full transcript. `sendErrorReply` (`:608-615`) sends error text. Neither sets a package.
- The receiver's own early-error reply is equally open (`TaskerRequestReceiver.kt:100-105`, `:197-217`).
- The caller cannot direct the reply (no reply-to extra is honored anywhere), but it does not need to: an implicit broadcast is delivered to every dynamically registered `RECEIVER_EXPORTED` receiver for that action. Tasker receives it exactly this way (tutorial step 6, `TASKER_TUTORIAL.md:115-118`), so by construction any app can too. On Android 8+ other apps' manifest-declared receivers are excluded from implicit delivery, so interception requires the malware process to be alive at reply time; trivial for attacks the malware itself triggers, opportunistic for passive sniffing.
- Crucially, `sendSuccessReply` is called unconditionally at `InferenceService.kt:556`, before the `isShareRequest` branch. So transcripts of ordinary user share flows (taskId `share_<ms>`, `ShareReceiverActivity.kt:289`) also go out on this broadcast. A malicious listener passively collects the text of everything the user transcribes through the normal share UI, whenever its process is live.

So, can a malicious caller receive the transcription of a file it could not otherwise read? Yes, mechanically: it triggers the request, the app reads the file under its own uid, the transcript returns on an open broadcast. The practical constraint is the readable set (section 3): today that set is app-private only, and the interesting directory, `shared_audio/`, uses unguessable names (`shared_<epoch-ms>_<8-hex-uuid>.<ext>`, `SharedAudioHandler.kt:114`); there is no listing capability and no path oracle in error replies (errors are localized without paths, `TranscriptionOrchestrator.kt:96-99`, or generic extractor messages, `:723`). Enumerating 32 random bits through one broadcast per guess is infeasible. One adjacent trigger verified but out of scope: a malicious app can also fire `ACTION_SEND` directly at the exported `ShareReceiverActivity` with its own content URI, which copies attacker-chosen audio into `shared_audio` and broadcasts the resulting transcript (same sink); same unguarded-trigger class, should be swept into the same fix.

### 1.4 What an arbitrary app can do through `ModelPreloadReceiver`

No attacker-chosen path: it loads only the saved `preferencesManager.modelPath` (`ModelPreloadReceiver.kt:70`), honoring one extra, `silent`. Attack value: force-load a multi-GB Gemma model (memory pressure can make lmkd kill other apps' processes), and spam `resetKeepAliveTimer` (`:62`) to pin the model in RAM indefinitely, a pure battery drain. Unlike `PROCESS_REQUEST`, this needs **no foreground service and no user interaction at all** (`goAsync` + coroutine, `:54-57`), so it is the only fully zero-interaction abuse primitive in this memo. Its replies are harmless: `PRELOAD_RESULT` is pinned with `setPackage(context.packageName)` (`:134`), which incidentally means Tasker can never receive it either (a doc inconsistency, not a security issue).

### 1.5 Adjacent finding: `BenchmarkActivity`

Exported in the **main** source set (`AndroidManifest.xml:406-413`) despite its "debug-only" KDoc. Any foreground app can start it with `backend`, `vad`, `progressive`, `provider`, `file_path` extras, and it **overwrites four persisted user preferences** (`BenchmarkActivity.kt:57-60`: backend selection, VAD, progressive, inference provider) before starting a transcription of the chosen path. That is a silent integrity primitive (for example, forcing the NNAPI provider to steer the device into the issue #26 crash-reset path, or switching the user's model). TASK-274 does not name it; it is the same class and belongs in the fix or a sibling task.

### 1.6 Honest severity ratings

- **Confidentiality (transcript exfiltration):** MEDIUM as a design flaw, LOW practical exploitability today. The sink broadcasts every transcript to the world (including share flows); what keeps it quiet is that the readable set collapsed to app-private paths with unguessable names. It becomes HIGH the moment path-read is restored (re-adding `READ_MEDIA_AUDIO`, see section 0).
- **Passive collection of the user's own transcriptions** (share flows and legitimate Tasker flows): MEDIUM. No guessing required; only liveness at reply time.
- **Integrity:** MEDIUM, entirely via `BenchmarkActivity` preference clobbering. The receivers themselves mutate no persistent user state (Room log rows aside).
- **Availability (battery/CPU/memory, notification and DB spam, free LLM/ASR oracle):** LOW to MEDIUM. Fully silent only under battery-unrestricted or with one social-engineered tap; `ModelPreloadReceiver` abuse is zero-interaction but data-harmless.

## 2. Options

### (a) Default-off user toggle (the task's candidate)

- **Mechanism:** boolean preference; both receivers refuse external callers unless set. Clean seam exists: `PreferencesManager` boolean flows (`data/PreferencesManager.kt:30-46`, e.g. `forceModelLoad`) with save counterparts, a toggle row pattern in `SettingsTab.kt:1359-1360`, and `ModelPreloadReceiver` already injects `PreferencesManager` and reads `.first()` in a coroutine (`ModelPreloadReceiver.kt:70`); `TaskerRequestReceiver` is plain today (Hilt-scoping note at `TaskerRequestReceiver.kt:135-138`) and would need `@AndroidEntryPoint` plus a DataStore read.
- **Security gain:** full (default-deny) once off.
- **Tasker breakage:** the nominal cost is "every automation breaks until the user flips a setting", but per section 0 the documented automation is already broken; real remaining users are those on pre-1.11.0 builds or feeding app-internal paths (the run-as test harness in the device-automation playbook).
- **Sketch:** `PreferencesManager` +2 lines, `PreferencesManagerImpl` +8, `SettingsViewModel` +6, `SettingsTab` +15, receiver checks +30 (incl. Hilt), strings across 10 locales. Roughly 70 lines plus localization.
- **Reversibility:** trivial (default flip).

### (b) First-use consent (one-time, remembers caller)

- **Mechanism:** capture the sender package at receive time (`getSentFromPackage()` on API 34+, `Binder.getCallingUid()` + `PackageManager.getPackagesForUid` down to `minSdk = 26`, `app/build.gradle.kts:33`); unknown sender posts a one-tap allow/block notification (the exact UX already built for the FGS fallback, `TaskerRequestReceiver.kt:149-192`); the decision is persisted.
- **Security gain:** default-deny with automatic enrollment of legitimate automations; shell/adb senders return an unidentifiable package, which must be an explicit decision (the tutorial's own adb snippets depend on it).
- **Tasker breakage:** one-time per install. The triggering request is lost (drop-and-notify), but Tasker retries per event, so the next voice note succeeds. Automation fails silently until the first consent, which is discoverable friction, not breakage.
- **Sketch:** caller capture +10, consent notification +60, decision store +40 (single-key DataStore JSON, the `ExternalModelStore` pattern), drop-path handling +20. Roughly 130 lines plus strings.
- **Reversibility:** clear the store; re-prompt.

### (c) Per-caller allowlist in settings

- **Mechanism:** (b)'s store surfaced as a settings list with remove; consent writes into it. Manual add needs either a `<queries>` addition (the manifest already queries SEND apps, `AndroidManifest.xml:17-36`, and Tasker does not appear as a SEND app) or user-typed package names, so treat "seen callers" as the primary source and keep manual entry minimal.
- **Security gain:** equals (b), adds revocation and visibility.
- **Tasker breakage:** none beyond (b).
- **Sketch:** +80-120 lines of SettingsTab/ViewModel on top of (b).
- **Reversibility:** per-entry.

### (d) `android:permission` with a normal-protection custom permission

Rejected mechanically. A `normal` custom permission is auto-granted at install to any app that merely declares `<uses-permission>` for it, so it filters only lazy attackers and provides no boundary against malware; meanwhile Tasker will never declare our permission, so every legitimate automation dies. A `dangerous` variant still requires the requester to declare it (Tasker will not) for the same total breakage. `signature` was already ruled out in the task context. Verdict: all Tasker breakage, no security gain.

### (e) Keep exported, validate paths only

Weakest. Path validation does nothing about the text/LLM oracle, the forced model loads, the preload pinning, the queue/DB/notification spam, or the open reply sink. It is a component of the fix (section 3), never the fix.

### (f) Found in the code: pin the reply sink (strictly better than any trigger-only option)

- **Mechanism:** the receiver records the sender package with the request; `InferenceService.sendSuccessReply`/`sendErrorReply` call `setPackage(requesterPackage)` when known, and suppress the broadcast entirely for `source == "share"` requests (their consumer is the result notification, `InferenceService.kt:557-573`; nothing legitimate listens for `share_*` taskIds). `PendingRequest` already carries per-request provenance fields (`InferenceService.kt:131-141`); add `requesterPackage`.
- **Security gain:** converts broadcast-to-the-world into point-to-point. Kills passive sniffing of user share transcripts and of Tasker-initiated transcripts, and makes caller-triggered exfiltration return only to the caller (still closed further by (b)/(c) gating the trigger).
- **Tasker breakage:** none. Tasker is pinned as the known sender; this also fixes the latent inconsistency that `PRELOAD_RESULT` is already self-pinned and undeliverable (`ModelPreloadReceiver.kt:134`).
- **Sketch:** +1 field, plumb through the service intent, +12 in the two reply functions, +4 suppression, tests. Roughly 40-50 lines.
- **Reversibility:** trivial.

### (g) Rate limiting

Defense-in-depth after (b)/(c): an in-memory per-caller window (+25 lines) resets when the receiver process dies; a persisted window (+50). Worth doing for the notification/DB spam vector, not a gate on its own.

### (h) `BenchmarkActivity`

Either move it to a debug source set (file plus manifest move) or guard with a build-type check and `finish()`. Roughly 10 lines. Should not ride TASK-274 silently; the maintainer should decide whether it is TASK-274 scope or a sibling task.

## 3. Path validation design (independent of the gate)

Legitimately transcribable-by-path sources in the current app:

1. `context.filesDir/shared_audio/**`: every share-flow audio lands here (`SharedAudioHandler.kt:42`, `:109-115`) and the share pipeline then feeds those exact paths back into `InferenceService` (`ShareReceiverActivity.kt:221`, `:375`).
2. `context.cacheDir/**`: preprocessing intermediates (`TranscriptionOrchestrator.kt:707`).
3. Nothing else. Model dirs, `databases/`, DataStore, and `/data/local/tmp` are either not audio or not readable; SAF-picked audio enters as content URIs, which this path-based API never accepted.

The tutorial's documented source (WhatsApp dir, `TASKER_TUTORIAL.md:94`) is **not** readable by the app anymore (section 0), so an allowlist modeled on shared-storage directories would authorize paths the app cannot open while breaking nothing for attackers and everything for nobody; the correct allowlist for today's reality is:

- Canonicalize (`File(path).canonicalFile`) to defeat `..` traversal, then require the path to be under `filesDir/shared_audio` or `cacheDir`. Explicitly exclude `databases/`, model dirs, and `filesDir` generally; this satisfies acceptance criterion 2 ("no path traversal into app-private or model dirs") with the necessary carve-out that `shared_audio` is itself app-private but is the API's only legitimate data source.
- Documented flows preserved: only the run-as harness and any flow feeding the app's own staged audio, which is exactly the set that works today.
- The tutorial itself needs a separate redesign decision (a `content_uri` extra accepting caller-granted URIs is the natural shape: the sender's read grant travels with the URI, which restores automation access to WhatsApp notes without any storage permission and without making the app a path oracle). That is a feature decision beyond TASK-274's security scope and should be its own task if the maintainer wants Tasker automation back.

## 4. Recommendation

Layered, ordered by value per line of code:

1. **(f) Pin the reply sink now.** It is the only confidentiality fix, it is small, it has zero Tasker breakage, and it closes the passive-collection leak of user share transcripts that the receiver gate alone would not touch (a gated trigger still replies to the world).
2. **(b)+(c) First-use consent writing into a per-caller allowlist**, for both receivers. Default-deny is the right posture for an app whose whole value proposition is privacy; the consent notification reuses the fallback-notification machinery. Frame it in settings as "External automation: off / ask / allowed apps" so (a)'s toggle falls out as the master switch of the same screen.
3. **(g) a light per-caller rate limit** and the **section-3 path allowlist** as AC #2 requires.
4. **(h) `BenchmarkActivity`**: decide scope (TASK-274 or sibling), but fix it in the same release; it is the only exported component that mutates persistent user state.
5. **Do not** re-add `READ_MEDIA_AUDIO` to rescue the tutorial; if Tasker automation matters as a feature, do the content-URI redesign as its own task.

**Morning decision question (one sentence):** approve shipping the reply-pinning fix plus a default-deny consent gate with per-caller allowlist and the app-private path allowlist, accepting that any surviving external automations (the run-as harness and pre-1.11.0 holdouts) break until the user consents, given that the tutorial's documented flow has in fact been broken for every user since 2026-07-29 (`b54303d`) and the only fix that would restore it, re-adding `READ_MEDIA_AUDIO`, would hand any app a transcript oracle over every audio file in shared storage?

## 5. Pending verification (device offline during analysis)

Two one-minute probes to confirm the section 0 claim on the real device (needs the debug build for `run-as`; the permission set is identical across flavors, same manifest, same `targetSdk`):

```bash
adb shell run-as com.antivocale.app.debug cat /sdcard/Download/<any-file>   # expect: permission denied (app uid cannot read shared storage)
adb shell am broadcast -a com.antivocale.app.PROCESS_REQUEST --es request_type audio \
  --es task_id secprobe --es file_path /sdcard/Download/<audio-file> -p com.antivocale.app.debug
# then: adb logcat -s TaskerRequestReceiver InferenceService AudioPreprocessor
# expect: enqueue succeeds (exists() passes on FUSE), then "Audio preprocessing failed" / extractor IOException at setDataSource
```

Control case that should succeed: a path under `files/shared_audio/` or the internal `files/` dir. If the `/sdcard` probe unexpectedly succeeds, escalate the confidentiality rating in section 1.3 from LOW to HIGH and treat the path allowlist as the primary fix rather than a component.
