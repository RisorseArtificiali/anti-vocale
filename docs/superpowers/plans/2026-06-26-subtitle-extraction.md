# Subtitle Extraction Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax.

**Goal:** When a shared video contains embedded subtitle tracks, let the user choose (via a notification) to extract the subtitle text instead of ASR, with a 5-min timeout that falls back to ASR.

**Architecture:** `SubtitleExtractor` (new, pure utility) probes video files for text-MIME subtitle tracks via `MediaExtractor` and reads them to plain text. `ShareReceiverActivity` probes after copying; on a hit it posts a "Subtitles found" notification with two actions instead of starting ASR. A new WorkManager timeout worker fires ASR if the user ignores the prompt. Subtitle extraction is a new `requestType = "subtitles"` branch in the orchestrator that feeds the existing result pipeline (no model load). A `taskId` dedup prevents tap/timeout double-runs.

**Tech Stack:** Kotlin, Android `MediaExtractor`, Jetpack WorkManager (`androidx.work:work-runtime-ktx` — new dep), Hilt + hilt-work, Compose-free notification UI, JUnit unit tests.

**Spec:** `docs/superpowers/specs/2026-06-26-subtitle-extraction-design.md`

---

## File Structure

**Create:**
- `app/src/main/java/com/antivocale/app/transcription/SubtitleExtractor.kt` — `probe()` + `extractToText()` + `SubtitleTrack` data class + timestamp-stripping parser. Pure `object`.
- `app/src/main/java/com/antivocale/app/work/SubtitleChoiceTimeoutWorker.kt` — expedited one-shot worker; cancels choice notification, runs ASR via orchestrator, posts result.
- `app/src/test/java/com/antivocale/app/transcription/SubtitleExtractorParserTest.kt` — pure-string parser fixtures (SRT/WebVTT/tx3g).

**Modify:**
- `app/build.gradle.kts` — add `androidx.work:work-runtime-ktx` + `androidx.hilt:hilt-work`.
- `app/src/main/java/com/antivocale/app/BridgeApplication.kt` — implement `Configuration.Provider` to inject `HiltWorkerFactory`.
- `app/src/main/java/com/antivocale/app/receiver/ShareReceiverActivity.kt` — probe branch + choice notification + enqueue/cancel timeout worker.
- `app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt` — `requestType = "subtitles"` branch that **skips `ensureBackendLoaded`**; extraction→text→result path; ASR fallback on `null`.
- `app/src/main/java/com/antivocale/app/service/InferenceService.kt` — `taskId` dedup in `requestQueue`; extract the notification-posting `TranscriptionListener` into a reusable factory.
- `app/src/main/res/values/strings.xml` + `values-it/strings.xml` — `subtitles_found_*` strings.
- `app/src/main/AndroidManifest.xml` — disable WorkManager default initializer (use Hilt's) via `<provider>` tools:node override (standard hilt-work setup).

---

## Chunk 1: Foundation (dependency, extractor, parser)

### Task 1: Add WorkManager + hilt-work, wire HiltWorkerFactory

**Files:** Modify `app/build.gradle.kts`; Modify `app/src/main/java/com/antivocale/app/BridgeApplication.kt`; Modify `app/src/main/AndroidManifest.xml`.

- [ ] **Step 1:** In `app/build.gradle.kts`, add to `dependencies`:
  ```kotlin
  implementation("androidx.work:work-runtime-ktx:2.10.0")
  implementation("androidx.hilt:hilt-work:1.2.0")
  ksp("androidx.hilt:hilt-compiler:1.2.0")
  ```
  (Pin to the androidx.hilt version already used elsewhere if present — check existing `hilt-android`/`hilt-compiler` lines and match.)
- [ ] **Step 2:** Make `BridgeApplication` implement `androidx.work.Configuration.Provider`; inject `dagger.hilt.android.HiltWorkerFactory` and return `Configuration.Builder().setWorkerFactory(workerFactory).build()`. Annotate the class `@HiltAndroidApp` (already is).
- [ ] **Step 3:** In `AndroidManifest.xml`, disable WorkManager's default initializer so Hilt's wins:
  ```xml
  <provider android:name="androidx.startup.InitializationProvider"
      android:authorities="${applicationId}.androidx-startup"
      android:exported="false" tools:node="merge">
      <meta-data android:name="androidx.work.WorkManagerInitializer"
          android:value="androidx.startup"
          tools:node="remove" />
  </provider>
  ```
- [ ] **Step 4:** `./gradlew assembleDebug` — expect BUILD SUCCESSFUL (validates deps + Hilt-work wiring).
- [ ] **Step 5:** Commit: `feat(subtitles): add WorkManager + hilt-work for the choice-timeout worker`.

### Task 2: SubtitleExtractor — probe stub + SubtitleTrack

**Files:** Create `app/src/main/java/com/antivocale/app/transcription/SubtitleExtractor.kt`.

- [ ] **Step 1:** Create the file with the data class and the `object`, signatures only:
  ```kotlin
  package com.antivocale.app.transcription

  import android.media.MediaExtractor
  import android.media.MediaFormat

  data class SubtitleTrack(val trackIndex: Int, val language: String?, val mime: String)

  object SubtitleExtractor {
      /** MIME prefixes/tags we treat as readable text subtitle tracks. Tunable. */
      private val TEXT_SUBTITLE_MIME_HINTS = setOf(
          "application/x-subrip", "application/x-srt", "text/vtt", "application/webvtt"
      )

      fun isTextSubtitleMime(mime: String?): Boolean = TODO()

      fun probe(videoPath: String): List<SubtitleTrack> = TODO()

      fun extractToText(videoPath: String, trackIndex: Int): String? = TODO()
  }
  ```
- [ ] **Step 2:** Implement `isTextSubtitleMime` — true if `mime` is non-null and (in the hint set OR starts with `text/` OR equals the MP4 mov-text family seen on-device). Keep the whitelist as the single tunable constant.
- [ ] **Step 3:** Commit (extractor skeleton): `feat(subtitles): SubtitleExtractor skeleton + text-MIME predicate`.

### Task 3: Timestamp-stripping parser (TDD)

**Files:** Create `app/src/test/java/com/antivocale/app/transcription/SubtitleExtractorParserTest.kt`; complete `extractToText` internal parser in `SubtitleExtractor.kt`.

- [ ] **Step 1: Write failing tests** for a pure function `internal fun stripTimestampsAndMarkup(raw: String, mime: String): String` covering:
  - SRT block: `"1\n00:00:01,000 --> 00:00:02,000\nHello world\n"` → `"Hello world"`
  - WebVTT cue: `"WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello world\n"` → `"Hello world"`
  - tx3g-style styling: removes `<...>` markup tags.
  - Multiple cues → joined with spaces/newlines, no empty lines, no indices.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*.SubtitleExtractorParserTest"` → expect FAIL (function not implemented).
- [ ] **Step 3: Implement** `stripTimestampsAndMarkup` (regex for `-->` cue headers, SRT index lines, `\d\d:\d\d:\d\d[,.]\d{3}` timestamps, `<[^>]>` tags; collapse blank lines).
- [ ] **Step 4: Run** → expect PASS.
- [ ] **Step 5:** Commit: `feat(subtitles): timestamp/markup stripping parser + tests`.

### Task 4: probe() and extractToText() against MediaExtractor

**Files:** Complete `SubtitleExtractor.kt`.

- [ ] **Step 1:** Implement `probe(videoPath)`: `MediaExtractor().setDataSource(videoPath)`; for each track, read `KEY_MIME`; if `isTextSubtitleMime`, add `SubtitleTrack(i, language = format.getString(KEY_LANGUAGE), mime)`. Catch all `Exception` → return `emptyList()` (probe never throws).
- [ ] **Step 2:** Implement `extractToText(videoPath, trackIndex)`: `selectTrack`, loop `readSampleData` + `advance()` until end-of-stream, decode bytes to String (UTF-8 for text MIMEs), pass through `stripTimestampsAndMarkup`; return `null` if blank or any `Exception`.
- [ ] **Step 3:** Smoke: in a scratch debug-only call or a logged device test — confirm `probe` on a known-subtitled file returns ≥1 track and `extractToText` returns non-blank text. (Full device test in Chunk 3.)
- [ ] **Step 4:** Commit: `feat(subtitles): SubtitleExtractor.probe + extractToText via MediaExtractor`.

---

## Chunk 2: Pipeline integration

### Task 5: taskId dedup in InferenceService.requestQueue

**Files:** Modify `app/src/main/java/com/antivocale/app/service/InferenceService.kt`.

- [ ] **Step 1:** In `onStartCommand`/`enqueue`, drop a request if `requestQueue.any { it.taskId == request.taskId }` (or a pending/processing set). Add the active `taskId` to a `ConcurrentHashMap`-backed `inFlight` set on poll, remove on completion.
- [ ] **Step 2:** Verify with a unit/integration check or a code trace: a duplicate `startForegroundService` with the same `taskId` processes once. (If an existing `InferenceServiceNotificationTest` exists, add a dedup assertion there; else a focused unit test on the queue.)
- [ ] **Step 3:** Commit: `fix(service): dedup transcription requests by taskId`.

### Task 6: Subtitle mode in the orchestrator (skip backend load)

**Files:** Modify `app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt`.

- [ ] **Step 1:** In `processRequest` (currently `ensureBackendLoaded` at line 88 before the `when`), branch first on `requestType == "subtitles"`: call a new `processSubtitleRequest(taskId, filePath, trackIndex, listener)` and `return` **before** `ensureBackendLoaded`.
- [ ] **Step 2:** Implement `processSubtitleRequest`: `val text = SubtitleExtractor.extractToText(filePath, trackIndex)`; if non-null → `listener.onSuccess(taskId, text, ...)` (same shape ASR success uses); else → log the MIME, fall back to the audio path (`ensureBackendLoaded` + normal ASR) with `listener.onInfo(... "Couldn't extract subtitles — transcribing audio")` if such a hook exists, else proceed silently to ASR.
- [ ] **Step 3:** Ensure `PendingRequest`/`processRequest` carries `trackIndex` (add the field, default -1, threaded from the intent extra `EXTRA_SUBTITLE_TRACK_INDEX`).
- [ ] **Step 4:** `./gradlew assembleDebug` + run existing orchestrator tests → no regressions.
- [ ] **Step 5:** Commit: `feat(subtitles): orchestrator subtitle mode (no model load) + ASR fallback`.

### Task 7: Extract reusable notification-posting listener

**Files:** Modify `InferenceService.kt` (and possibly a new `TranscriptionNotificationListener.kt`).

- [ ] **Step 1:** Move the `TranscriptionListener` implementation that posts result/error/progress notifications out of the inline `processQueue` call site into a reusable class/factory (e.g. `TranscriptionNotificationListener(context, ...)`), so both `InferenceService` and the timeout `Worker` (Chunk 3) can construct it.
- [ ] **Step 2:** `InferenceService` constructs it as before; behavior unchanged.
- [ ] **Step 3:** Commit: `refactor(service): extract TranscriptionNotificationListener for reuse`.

---

## Chunk 3: Share flow + choice notification + timeout worker

### Task 8: Choice notification + probe branch in ShareReceiverActivity

**Files:** Modify `ShareReceiverActivity.kt`; Modify `strings.xml` + `values-it/strings.xml`.

- [ ] **Step 1:** Add strings: `subtitles_found_title` ("Subtitles found" / "Sottotitoli trovati"), `subtitles_found_text` ("Use the embedded subtitles, or transcribe the audio?" / param `%1$s` = language or "unknown"), `action_use_subtitles`, `action_transcribe_audio`.
- [ ] **Step 2:** After `copyToAppStorage`, if `SharedAudioHandler.isVideoFile(localPath)`: `val tracks = SubtitleExtractor.probe(localPath)`. If non-empty: pick best track (match `PreferencesManager` transcription language → else `tracks.first()`); post the choice notification via the existing notification helper with two actions:
  - *Use subtitles* → `Intent(InferenceService).putExtra(EXTRA_REQUEST_TYPE, "subtitles").putExtra(EXTRA_SUBTITLE_TRACK_INDEX, track.trackIndex)...` as `PendingIntent`.
  - *Transcribe audio* → normal `Intent(InferenceService)` as today.
  Both actions **also** `cancelUniqueWork("subtitle-choice-$taskId")` via the receiver/intent that handles the tap (or by having the service cancel on receipt).
  Then `finish()` — do NOT start ASR.
  If empty: start `InferenceService` normally (unchanged).
- [ ] **Step 3:** `./gradlew assembleDebug`.
- [ ] **Step 4:** Commit: `feat(subtitles): probe branch + choice notification in share flow`.

### Task 9: SubtitleChoiceTimeoutWorker (expedited, foreground, ASR fallback)

**Files:** Create `app/src/main/java/com/antivocale/app/work/SubtitleChoiceTimeoutWorker.kt`; modify `ShareReceiverActivity.kt` to enqueue.

- [ ] **Step 1:** Create `SubtitleChoiceTimeoutWorker(appContext, params)` extending `CoroutineWorker`. Inputs: `filePath`, `taskId`, `sourcePackage`, `backendOverride`. In `doWork()`: call `setForeground(foregroundInfo("Transcribing audio…"))` (Android-12+-safe); construct `TranscriptionNotificationListener`; call `TranscriptionOrchestrator.processRequest(requestType="audio", filePath=..., ...)` (inject orchestrator via `@HiltWorker` + `HiltWorkerFactory`, or construct with its deps). On completion, return `Result.success()`.
- [ ] **Step 2:** In `ShareReceiverActivity`, when posting the choice notification, `WorkManager.getInstance(this).enqueueUniqueWork("subtitle-choice-$taskId", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SubtitleChoiceTimeoutWorker>().setInitialDelay(5, MINUTES).setInputData(...).build())`.
- [ ] **Step 3:** Service-side / receiver-side: on either action, `WorkManager.cancelUniqueWork("subtitle-choice-$taskId")` before starting the service.
- [ ] **Step 4:** `./gradlew assembleDebug` + install debug-suffix build on device.
- [ ] **Step 5:** Commit: `feat(subtitles): 5-min choice-timeout worker (ASR fallback)`.

### Task 10: Device verification + final wiring

- [ ] **Step 1:** Install debug-suffix build (`./scripts/install.sh` once device is connected — recall wireless adb auto-discovers via mDNS; no port needed unless mDNS is stale).
- [ ] **Step 2:** Share a video **with** embedded subtitles → expect "Subtitles found" notification (with language) → tap *Use subtitles* → subtitle text as result, 🎥 badge in Logs.
- [ ] **Step 3:** Share a **subtitle-less** video → straight ASR, no notification.
- [ ] **Step 4:** Share a subtitled video and **ignore** >5 min → worker fires → ASR result.
- [ ] **Step 5:** Share + tap inside the race window → `taskId` dedup → single result (no double notification/log).
- [ ] **Step 6:** `adb logcat` confirms no foreground-service-start crash on timeout; worker survives a `force-stop`-during-wait (re-wake) on a second try if needed.
- [ ] **Step 7:** Release R8 audit (`./gradlew assembleRelease`) — confirm `SubtitleExtractor` + worker survive (covered by existing `com.antivocale.app.**` keep rule; no new rule expected). Standard per CLAUDE.md pre-release R8 audit.
- [ ] **Step 8:** Commit any fixes; final commit references the spec.

---

## Notes for the implementer

- **Wireless adb** on the Realme: auto-discovers via mDNS once paired — check `adb devices` first, don't ask for a port unless mDNS is stale (it sometimes advertises a refused port; reconnect via the freshly discovered one).
- **Build/install** is always `./scripts/install.sh` (never `./gradlew installDebug`).
- **Debug-suffix build** (`com.antivocale.app.debug`) is already set up and installs alongside the Play Store version — use it for device testing without wiping models.
- **The `taskId` dedup (Task 5) is load-bearing for the tap/timeout race** — do it before Task 9.
- **`ensureBackendLoaded` skip (Task 6)** is the subtle one: subtitle mode must not force model load. Verify the subtitle branch returns before line 88's load.
- **WorkManager + Hilt (Task 1)** needs `Configuration.Provider` in `BridgeApplication` and the manifest `<provider tools:node="remove">` — skip either and DI into the worker breaks at runtime.
