# Scout Report: Voxscribe vs Anti-Vocale (Competitive / Cross-Pollination)

**Date**: 2026-07-30
**Scope**: Voxscribe (`org.y20k.voxscribe`), a newly-accepted F-Droid app, as a reference for ideas adaptable to Anti-Vocale
**Source**: https://codeberg.org/y20k/voxscribe (MIT), F-Droid MR !43161 (merged 2026-07-29)

## Executive Summary

Voxscribe is NOT a direct competitor. It is an offline voice-input KEYBOARD (push-to-talk into any text field), whereas Anti-Vocale transcribes voice messages shared from chat apps. Different problem, different input modality (live mic vs. shared file). An F-Droid user could want both. However, Voxscribe implements three patterns worth examining for Anti-Vocale; two are adaptable, one is not (native-API-dependent). Backlogs created for the two adaptable ones.

## Architecture comparison (verified)

| | Voxscribe | Anti-Vocale |
|---|---|---|
| Purpose | Voice-input keyboard (IME) | Voice-message transcription |
| Input | Microphone (live recording) | Shared audio file (chat apps) |
| Engine | whisper.cpp (ggml, native C++) | sherpa-onnx (ONNX Runtime) |
| Models | Whisper only (ggml), user-supplied | 6 backends (Parakeet, Whisper Distil/Turbo/Small, Qwen3-ASR, Nemotron, Gemma) |
| Model delivery | Manual GGML download, user points app at file | Integrated HuggingFace download |
| Permissions | MICROPHONE only (no INTERNET, no storage) | FOREGROUND_SERVICE, POST_NOTIFICATIONS, INTERNET, etc. (storage perms removed 2026-07-29) |
| Repo size | ~989 KiB (whisper.cpp is a git submodule) | Larger (mature, multi-backend) |
| Maturity | v1.0.1, 43 commits (July 2026) | v1.8.4, mature release history |
| License | MIT | Apache-2.0 |
| F-Droid category | System (keyboard) | Multimedia |
| Native code | whisper.cpp submodule (submodules: true) | sherpa-onnx built from source (srclibs, build-android-*.sh) |
| ABI split | Author asked F-Droid to add it (inline abiFilters in recipe) | Done by us (per-ABI versionCodes 301/302/304) |
| Reproducible | Yes (author-declared) | Yes (verified) |

## Adaptable ideas

### 1. Resident model context + skip-reload (ADAPTABLE - TASK-303)

Voxscribe keeps the native whisper context resident and tracks `loadedModelUri`. On `ensureModelLoaded`, if the same model URI is already loaded, it reuses it and skips the multi-second reload. Source: `WhisperBridge.kt`.

Verified against Anti-Vocale: grep found no equivalent "is this model already loaded, skip reload" pattern in the transcription layer. sherpa-onnx `OfflineRecognizer` construction is the seconds-long cost. If Anti-Vocale reloads the model unnecessarily (between consecutive transcriptions, or on app re-entry), reusing the resident recognizer would cut latency. **Must measure current reload behavior before implementing** (TASK-303 is research-first).

### 2. Fast model-file validity check at import (ADAPTABLE - TASK-304)

Voxscribe validates a picked model file by reading its header and checking the ggml magic number (`ModelState.VALID` = readable AND starts with ggml magic). Instant, specific failure before any slow native load. Source: `ModelHelper.kt`.

Verified against Anti-Vocale: existing validation is per-DIRECTORY for downloaded models (`WhisperModelManager.validateModelDirectory`, etc.), but the manual "Select Model from Device" SAF import does not appear to do a cheap header/magic check on the picked file before handing it to the backend. A wrong/corrupt file fails late, during native load. TASK-304: add a lightweight check (file-size + extension + magic where known) at import, multi-format aware.

## Not adaptable

### 3. Model load via ParcelFileDescriptor (NOT adaptable as-is)

Voxscribe loads the model directly from a SAF `ParcelFileDescriptor` (`ModelHelper.openModelFileDescriptor`), avoiding a copy. Anti-Vocate copies the picked model to `filesDir` first (`copyModelToAppStorage`).

Not directly transferable: sherpa-onnx JNI accepts a filesystem path string, not an fd. Adopting fd-loading would require a native shim around sherpa-onnx. Not worth a backlog unless sherpa-onnx ever exposes an fd-based API.

## Trend note (no backlog)

whisper.cpp (ggml) as an alternative to ONNX Runtime is lighter as a binary but supports only Whisper-family models (no Parakeet TDT, Qwen3-ASR, Nemotron). Switching would sacrifice Anti-Vocale's multi-backend breadth. Already noted as a landscape trend in the 2026-07-28 scout report (CrispASR/ggml unification). Not actionable today.

## Verdict for positioning

Anti-Vocale's F-Droid proposition is strong and differentiated: multi-backend, integrated download, voice-message workflow. Voxscribe confirms the on-device-ASR segment is active on F-Droid (good signal for adoption) but serves a different need (dictation keyboard). No strategy change needed.

## Backlogs created
- TASK-303: resident model-context reuse (research-first)
- TASK-304: fast model-file validity check at import (multi-format)
