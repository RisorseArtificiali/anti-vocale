---
id: task-23
title: Remove Non-Multimodal Gemma Model from Selection
status: Done
assignee: []
created_date: '2026-03-01 19:44'
updated_date: '2026-03-01 20:04'
labels:
  - enhancement
  - models
  - cleanup
dependencies: []
priority: low
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Remove the non-multimodal Gemma model variant from the selectable model list, as only multimodal models are needed for audio transcription.

**Tasks:**
- Remove the non-multimodal variant from ModelDownloader.ModelVariant enum
- Update any related UI or logic
- Ensure only multimodal models are downloadable/selectable

**User story:** As a user, I only want to see models that are relevant for audio transcription.
<!-- SECTION:DESCRIPTION:END -->
