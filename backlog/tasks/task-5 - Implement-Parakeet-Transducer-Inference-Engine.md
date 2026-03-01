---
id: task-5
title: Implement Parakeet Transducer Inference Engine
status: To Do
assignee: []
created_date: '2026-02-28 17:52'
labels:
  - inference
  - transducer
  - core-feature
dependencies: []
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Create the inference code path for Parakeet TDT transducer models using sherpa-onnx.

**Scope:**
- Create `ParakeetTranscriber` class similar to existing Whisper transcriber
- Implement offline transcription using sherpa-onnx API
- Wire up encoder/decoder/joiner ONNX model loading
- Handle 25-language automatic detection

**Technical Notes:**
- Parakeet uses transducer architecture (encoder-decoder-joiner)
- Different from Whisper's encoder-decoder approach
- Requires sherpa-onnx transducer-specific API calls

**Depends on:** sherpa-onnx library integration + model download
<!-- SECTION:DESCRIPTION:END -->
