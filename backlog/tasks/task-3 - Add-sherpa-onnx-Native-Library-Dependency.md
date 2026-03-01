---
id: task-3
title: Add sherpa-onnx Native Library Dependency
status: To Do
assignee: []
created_date: '2026-02-28 17:52'
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
