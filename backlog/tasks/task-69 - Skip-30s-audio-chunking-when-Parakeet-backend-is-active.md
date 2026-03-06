---
id: TASK-69
title: Skip 30s audio chunking when Parakeet backend is active
status: To Do
assignee: []
created_date: '2026-03-06 23:39'
labels:
  - performance
  - audio
  - parakeet
dependencies: []
references:
  - app/src/main/java/com/antivocale/app/audio/AudioPreprocessor.kt
  - app/src/main/java/com/antivocale/app/service/InferenceService.kt
  - app/src/main/java/com/antivocale/app/transcription/TranscriptionBackend.kt
  - app/src/main/java/com/antivocale/app/transcription/SherpaOnnxBackend.kt
priority: low
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Currently `AudioPreprocessor` splits all audio into 30-second chunks (`MAX_CHUNK_DURATION_SECONDS = 30` at `AudioPreprocessor.kt:29`) regardless of which backend is active. This is unnecessarily restrictive for Parakeet TDT 0.6B v3, which can transcribe up to **24 minutes of audio in a single pass**.

## Problem

Chunking causes:
- Potential mid-sentence splits, leading to broken words/phrases when results are joined
- Extra processing overhead from creating multiple WAV chunks and sequential inference calls
- Loss of cross-chunk context that Parakeet could otherwise use for better accuracy

## Proposed Solution

Make chunking backend-aware. Options:

### Option A: Backend declares max audio length
Add a property to `TranscriptionBackend` interface (e.g., `val maxAudioDurationSeconds: Int`) and have `AudioPreprocessor` or `InferenceService` use it instead of the hardcoded 30s constant.

- `SherpaOnnxBackend` (Parakeet): return `600` (10 min, matching existing `MAX_DURATION_SECONDS` limit)
- `WhisperBackend`: return `30` (Whisper's native limit)
- `LlmTranscriptionBackend` (Gemma): return `30`
- Future `CanaryBackend`: return `30` (recommended by onnx-asr docs)

### Option B: Simple backend check in InferenceService
Check the active backend type before calling `AudioPreprocessor.processAudio()` and pass a max chunk duration parameter. Less clean but simpler.

## Files to modify
- `AudioPreprocessor.kt` — make `MAX_CHUNK_DURATION_SECONDS` a parameter instead of a constant
- `InferenceService.kt` — pass appropriate chunk duration based on active backend
- `TranscriptionBackend.kt` — optionally add `maxAudioDurationSeconds` property

## Constraints
- Keep the existing `MAX_DURATION_SECONDS = 600` (10 min) hard limit for all backends
- Keep the existing `MAX_FILE_SIZE_BYTES = 100MB` limit
- Whisper and Gemma must still chunk at 30s
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Parakeet backend processes audio up to 10 minutes without chunking
- [ ] #2 Whisper and Gemma backends still chunk at 30 seconds
- [ ] #3 No regression in transcription quality for any backend
- [ ] #4 Long audio (>30s) transcribes correctly with Parakeet in a single pass
<!-- AC:END -->
