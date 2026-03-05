---
id: TASK-7
title: Add Model Selection UI (Whisper vs Parakeet)
status: Done
assignee: []
created_date: '2026-02-28 17:52'
updated_date: '2026-03-04 18:25'
labels:
  - UI
  - settings
  - model-selection
dependencies: []
priority: medium
ordinal: 35000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Create user interface for selecting between Whisper and Parakeet ASR models.

**Scope:**
- Add model selection preference in Settings
- Show model capabilities (languages, size, speed)
- Display download status for each model
- Allow switching between models

**UX Considerations:**
- Whisper: Multilingual, larger, slower but more accurate
- Parakeet v3: 25 European languages, faster, automatic language detection
- Show estimated storage requirements
<!-- SECTION:DESCRIPTION:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Model selection UI implemented in ModelTab.kt (Parakeet section) and SettingsTab.kt (shows active model). Users can switch between Gemma (LLM) and Parakeet (sherpa-onnx) backends.
<!-- SECTION:FINAL_SUMMARY:END -->
