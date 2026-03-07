---
id: TASK-72
title: Research and Implement Audio Noise Reduction Pre-processing
status: To Do
assignee: []
created_date: '2026-03-07 14:23'
labels:
  - research
  - enhancement
  - audio-processing
  - transcription
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Research and implement audio noise reduction as a pre-processing step before transcription to improve accuracy for voice messages recorded in noisy environments.

## Research Phase
1. Evaluate noise reduction libraries for Android/Kotlin:
   - RNNoise (deep learning-based, lightweight)
   - WebRTC Noise Suppression
   - Noisereduce.js port
   - Sherpa-onnx built-in VAD enhancement
2. Benchmark latency impact on mobile devices
3. Test effectiveness with Italian voice messages

## Implementation Phase
1. Add noise reduction step in AudioPreprocessor
2. Add "Noise Reduction" toggle in Settings
3. Configure for typical WhatsApp/Telegram voice message scenarios
4. Handle edge cases (already clean audio, extreme noise)

## Requirements
- Must not significantly increase processing time (>500ms overhead acceptable)
- Should work offline without external dependencies
- Prefer native/Kotlin solution over JNI if performance is comparable

## References
- RNNoise: https://github.com/xiph/rnnoise
- WebRTC NS: https://webrtc.github.io/webrtc-org/native-code/native-apis/
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
