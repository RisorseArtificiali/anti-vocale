---
id: TASK-71
title: Add Enhanced Transcription Mode with Beam Search
status: To Do
assignee: []
created_date: '2026-03-07 14:23'
labels:
  - enhancement
  - transcription
  - parakeet
  - settings
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add an "Enhanced Mode" setting for Parakeet transcription that uses beam search decoding with higher beam width instead of greedy search.

## Configuration Changes
- Switch `decodingMethod` from "greedy_search" to "modified_beam_search" (malsd_batch)
- Increase beam width from default to 20-30
- Optionally add temperature parameter (1.2-1.3) for challenging audio

## Implementation
1. Add `enhancedMode` preference in PreferencesManager
2. Add UI toggle in Settings tab
3. Modify SherpaOnnxBackend to read preference and configure decoder accordingly
4. Add beam search config parameters to OfflineRecognizerConfig

## Trade-offs
- **Enhanced ON**: Better accuracy for noisy/accented speech, ~2-3x slower
- **Enhanced OFF**: Faster transcription, good for clear audio (default)

## References
- sherpa-onnx docs: https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html
- Research shows beam width 20-30 with temp 1.2-1.3 improves accuracy for difficult audio
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
- [ ] #6 Every text should support internationalisation and should be tracked
<!-- DOD:END -->
