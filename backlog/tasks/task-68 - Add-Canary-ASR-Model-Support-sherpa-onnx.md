---
id: TASK-68
title: Add Canary ASR Model Support (sherpa-onnx)
status: To Do
assignee: []
created_date: '2026-03-06 23:36'
labels:
  - model-download
  - sherpa-onnx
  - canary
  - feature
dependencies: []
references:
  - app/src/main/java/com/antivocale/app/transcription/WhisperBackend.kt
  - app/src/main/java/com/antivocale/app/transcription/SherpaOnnxBackend.kt
  - >-
    app/src/main/java/com/antivocale/app/transcription/TranscriptionBackendManager.kt
  - app/src/main/java/com/antivocale/app/transcription/ParakeetDownloader.kt
  - app/src/main/java/com/antivocale/app/ui/viewmodel/ModelViewModel.kt
  - app/src/main/java/com/antivocale/app/ui/tabs/ModelTab.kt
documentation:
  - >-
    https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OfflineRecognizer.kt
  - 'https://huggingface.co/nvidia/canary-180m-flash'
  - 'https://huggingface.co/istupakov/canary-1b-v2-onnx'
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add NVIDIA Canary as a third ASR model option alongside Parakeet TDT and Whisper.

## Context

Canary uses an **encoder-decoder transformer** architecture (like Whisper but with a different config), NOT a transducer (like Parakeet). The sherpa-onnx Kotlin API already has `OfflineCanaryModelConfig` with fields: `encoder`, `decoder`, `srcLang`, `tgtLang`, `usePnc`. The current sherpa-onnx AAR (v1.12.28, released Feb 28 2026) includes Canary support (merged June 2025 via PR #2272).

## Model Options (pick one or offer both)

### Option A: Canary 180M Flash (recommended for mobile)
- **Pre-converted for sherpa-onnx**: `sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8`
- **Download URL**: `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2`
- **Size**: ~200MB estimated (int8 quantized)
- **Languages**: English, Spanish, German, French
- **Pros**: Small, fast, ready to use with sherpa-onnx
- **Cons**: Only 4 languages (vs Parakeet's 25)

### Option B: Canary 1B v2 (best accuracy)
- **ONNX conversion exists**: `istupakov/canary-1b-v2-onnx` on HuggingFace
- **Int8 files**: encoder-model.int8.onnx (859MB) + decoder-model.int8.onnx (170MB) + vocab.txt (208KB) = ~1GB total
- **Languages**: 25 languages
- **WER**: 5.63% — tops HuggingFace Open ASR Leaderboard
- **Pros**: Best accuracy, many languages
- **Cons**: ~1GB on device, istupakov conversion may not be compatible with sherpa-onnx (different file naming: `encoder-model.int8.onnx` vs sherpa-onnx expected `encoder.int8.onnx`, `vocab.txt` vs `tokens.txt`). Needs testing.

## Implementation Steps

### 1. Backend Integration (sherpa-onnx changes)
- Create `CanaryBackend.kt` following `WhisperBackend.kt` pattern
- Use `OfflineCanaryModelConfig(encoder, decoder, srcLang, tgtLang, usePnc=true)` instead of `OfflineWhisperModelConfig`
- Update `TranscriptionBackendManager.kt` to support `"canary"` backend type

### 2. Model Management
- Create `CanaryModelManager.kt` following `ParakeetModelManager.kt` pattern
- Create `CanaryDownloader.kt` following `ParakeetDownloader.kt` pattern
- Required files: `encoder.int8.onnx`, `decoder.int8.onnx`, `tokens.txt` (for sherpa-onnx compatible model)

### 3. ViewModel & Preferences
- Add `CanaryUiState` data class in `ModelViewModel.kt`
- Add download/use/delete methods following Parakeet pattern
- Add `CANARY_MODEL_PATH` preference key in `PreferencesManager.kt`
- Add `canaryModelPath` flow, `saveCanaryModelPath()`, `clearCanaryModelPath()`

### 4. UI
- Add Canary model card in `ModelTab.kt` following Whisper/Parakeet card pattern
- Add string resources in `values/strings.xml` and `values-it/strings.xml`

## Key Risk
- If using Canary 1B v2 from istupakov: the ONNX export may not be compatible with sherpa-onnx's `OfflineCanaryModelConfig`. The file names differ and the model graph structure might not match. **Test with 180M Flash first** since it's officially pre-converted by the sherpa-onnx project.

## References
- sherpa-onnx Kotlin API: https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OfflineRecognizer.kt
- Canary 180M Flash: https://huggingface.co/nvidia/canary-180m-flash
- Canary 1B v2 ONNX: https://huggingface.co/istupakov/canary-1b-v2-onnx
- sherpa-onnx Canary support issue: https://github.com/k2-fsa/sherpa-onnx/issues/2148
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 CanaryBackend.kt created using OfflineCanaryModelConfig with sherpa-onnx
- [ ] #2 TranscriptionBackendManager routes 'canary' backend type correctly
- [ ] #3 CanaryDownloader downloads and extracts model from sherpa-onnx GitHub releases
- [ ] #4 Model card in ModelTab.kt with download/use/delete functionality
- [ ] #5 User can switch between Parakeet, Whisper, and Canary backends
- [ ] #6 String resources added for both English and Italian
- [ ] #7 Tested on device: model downloads, loads, and transcribes audio correctly
<!-- AC:END -->
