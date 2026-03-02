---
id: TASK-4
title: Download and Integrate Parakeet TDT v3 ONNX Model
status: Done
assignee: []
created_date: '2026-02-28 17:52'
updated_date: '2026-03-02 18:08'
labels:
  - model-download
  - huggingface
  - parakeet
dependencies: []
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement model download for NVIDIA Parakeet TDT 0.6B v3 ONNX model from HuggingFace.

**Model:** `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8`

**Files to download (~640 MB total):**
- encoder.int8.onnx (622 MB)
- decoder.int8.onnx (12 MB)  
- joiner.int8.onnx (6.1 MB)
- tokens.txt (92 KB)

**Scope:**
- Extend existing HuggingFace download infrastructure (from task-1)
- Add model selection UI (Whisper vs Parakeet)
- Store models in app-specific storage

**Related:** task-1 (HuggingFace Model Download infrastructure)
<!-- SECTION:DESCRIPTION:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
ParakeetDownloader.kt downloads sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8 from GitHub releases (~640MB). Includes progress tracking, extraction with per-file progress, and error handling.
<!-- SECTION:FINAL_SUMMARY:END -->
