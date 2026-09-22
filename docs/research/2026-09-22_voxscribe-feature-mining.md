# Voxscribe feature mining (TASK-303 / TASK-304)

Date: 2026-09-22
Source: codeberg.org/y20k/voxscribe @ e102c3a (2026-09-17, v1.0.3), MIT (LICENSE.md).
Method: full-tree read by a research subagent; every load-bearing citation below was
independently spot-checked against the clone at /tmp/research-mining/voxscribe.
Our side of the comparison (load path, import validation) was traced in this repo and
verified line by line (see TASK-303/304 notes in the backlog).

Voxscribe is a whisper.cpp transcription keyboard (IME). It is much smaller than
Anti-Vocale (13 Kotlin files, 2.2k lines) and shares only the "load a model, decode
audio" core, which makes it a clean pattern reference rather than a feature source.

## Lead 1: resident context reuse (TASK-303): WE ALREADY HAVE IT

Voxscribe keeps the native context resident and keys reuse on model identity:
`WhisperBridge.kt:45` (`contextPtr`), `:49` (`loadedModelUri`), gate at `:69-73`
(`contextPtr != 0L && storedUri == loadedModelUri` -> "Reusing the loaded model"),
loads timed with elapsedRealtime (`:76-88`), all native access serialized on a
`limitedParallelism(1)` dispatcher (`:41`), teardown queued so an in-flight decode
finishes first (`:52-53`, `:130-133`).

Anti-Vocale verdict (verified this session): **already implemented, with richer
semantics**. Resident-with-TTL: singleton recognizer fields
(`SherpaBackend.kt:342-343`, `ExternalSherpaBackend.kt:113-115`), reuse decision at
`TranscriptionOrchestrator.kt:913-919` (load only if `!hasBackend || !backendReady ||
backendMismatch`), double-guarded by the `isInitialized` short-circuit
(`SherpaBackend.kt:383-386`) and same-id unload skip
(`TranscriptionBackendManager.kt:100-103`); idle unload via `NativeKeepAlive`
(default 5 min, `PreferencesManager.kt:168`) bracketed by beginWork/endWork so it
never fires mid-decode. TASK-303 closed as already-implemented.

The one Voxscribe behavior we LACK: their reuse key includes the model identity
(URI), ours keys only on backend id. Consequence found and filed as **TASK-626**:
switching variant within one entry id leaves the OLD variant resident (silent
stale-model transcriptions until idle unload).

## Lead 2: magic-number import validation (TASK-304): GAP CONFIRMED, SURGICAL

Voxscribe reads exactly 4 bytes and compares little-endian against
`GGML_MODEL_MAGIC = 0x67676d6c` (`Keys.kt:47-49`, `ModelHelper.kt:81-101`), with a
four-state enum (`VALID / NOT_SELECTED / UNREADABLE / INVALID_FORMAT`) consumed in
two places (settings summary + IME banner), and an invalid pick never replaces the
working model (`SettingsFragment.kt:353-388`).

Our verdict (verified): the cheap header gate already EXISTS on the catalog path
(`DownloadedModelIntegrity`: ONNX first byte 0x08 + 1 MB / 64 B floors) but is wired
nowhere else: the SAF picker is a raw copy with zero checks
(`ModelViewModel.copyModelToAppStorage`, `ModelViewModel.kt:865-881`), external
imports never call it (`ExternalModelImporter.kt:349-481`), and external load time
checks existence + metadata only (`ExternalSherpaBackend.kt:158-184`). LLM
`.litertlm`/`.task` files: nothing at import, 1 MB floor at load
(`LlmManager.kt:279-287`, commit bc608a78). Slot-in: `DownloadedModelIntegrity`
into `ExternalSherpaBackend.initialize`'s pre-native block and/or the importer's
registration tail, together with the TASK-482 verdict cache.

## Other patterns worth keeping on the radar

- **fd-based SAF load without copying** (`whisper_jni.cpp:49-99`): loads the picked
  model straight from the detached fd; documents the FUSE `/proc/self/fd` re-open
  denial trap. Relevant only if we ever decode external models directly from SAF
  URIs instead of importing into our store.
- **One post-processing seam** (`TranscriptProcessor.process`, `WhisperBridge.kt:110-117`):
  annotation stripping + normalization in a single named seam between "text produced"
  and "text delivered". Ours (repetition-loop, punctuation) should stay consolidated
  in one seam too.
- **Transient "Nothing recognized" state** (`VoiceInputService.kt:445-453`): empty
  result = subtle auto-clearing hint, not a red error. Cheap perceived-quality win
  for our empty-transcript notifications/History rows.
- **State-dependent gating** (`VoiceInputService.kt:679-691`): mic enabled only when
  permission + model-usable + not-busy, re-evaluated per panel show; per-state banner
  messages from one enum (`SettingsFragment.kt:323-338`).
- **Release-native-even-in-debug builds** (`app/build.gradle.kts:13-19`) and a
  written version-coupled assumptions list for engine bumps: both rhyme with our
  four-sync-point `.sherpa-version` discipline.
- **IME voice subtype** (`method.xml`, `overridesImplicitlyEnabledSubtype`): a tiny
  IME front-end would put Anti-Vocale on other keyboards' mic keys (FUTO/HeliBoard
  hand off; Gboard does not). New surface, not a port; noted as a strategic option.

Explicit NOT_FOUND (verified by full-tree grep): model download/management, live
partial results, waveform, playback sync, notifications, WorkManager/foreground
services, history/database, export/sharing, translations, Tasker integration, tests.

## Shortlist (value x cheapness for us)

1. TASK-626 fix (residency keyed on model identity), from lead 1's blind spot.
2. TASK-304 + TASK-482 combined integrity gate (magic + floors + verdict cache).
3. "Nothing recognized" transient state on empty results.
4. Single post-processing seam audit (repetition + punctuation consolidation).
5. State-dependent gating for enqueue surfaces (share/Tasker origins).
