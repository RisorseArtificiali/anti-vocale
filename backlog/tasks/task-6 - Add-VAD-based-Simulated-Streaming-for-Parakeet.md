---
id: task-6
title: Add VAD-based Simulated Streaming for Parakeet
status: To Do
assignee: []
created_date: '2026-02-28 17:52'
labels:
  - streaming
  - VAD
  - real-time
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement Voice Activity Detection (VAD) based simulated streaming for real-time transcription with Parakeet.

**Scope:**
- Integrate Silero VAD model for speech detection
- Implement audio chunking based on VAD segments
- Process chunks through Parakeet transducer
- Handle partial results and final transcription assembly

**Technical Notes:**
- True streaming not yet supported for Parakeet (GitHub issue #2918)
- Simulated streaming = VAD detects speech → buffer audio → transcribe when silence detected
- Good enough for voice message transcription use case

**Reference:** https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html
<!-- SECTION:DESCRIPTION:END -->
