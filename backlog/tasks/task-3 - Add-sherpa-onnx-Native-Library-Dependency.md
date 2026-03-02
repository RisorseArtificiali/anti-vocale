---
id: TASK-3
title: Add sherpa-onnx Native Library Dependency
status: Done
assignee: []
created_date: '2026-02-28 17:52'
updated_date: '2026-03-02 18:08'
labels:
  - android
  - native
  - infrastructure
dependencies: []
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Integrate the sherpa-onnx native library into the Android project for ONNX-based ASR inference.

**Scope:**
- Add sherpa-onnx AAR/jar dependency to the project
- Configure native library loading (arm64-v8a, armeabi-v7a)
- Set up JNI bindings if needed

**Reference:** https://github.com/k2-fsa/sherpa-onnx

**Depends on:** None (can be done in parallel with model download task)
<!-- SECTION:DESCRIPTION:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
sherpa-onnx native library integrated via AAR dependency. SherpaOnnxBackend.kt implements the transducer inference using sherpa-onnx API.
<!-- SECTION:FINAL_SUMMARY:END -->
