---
id: TASK-42
title: Add manual model unload option
status: Done
assignee: []
created_date: '2026-03-02 21:08'
updated_date: '2026-03-06 13:48'
labels:
  - enhancement
  - ux
  - model-management
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add the ability to manually unload the model on demand from the Settings or Model tab.

**Current state**: Model stays loaded in memory until app is closed
**Desired state**: User can manually unload to free memory or prepare for model switch

**Location**: 
- `ModelScreen.kt` or `SettingsScreen.kt` - add Unload button
- `ModelManager.kt` - implement unload function
- `TranscriptionService.kt` - handle case when model not loaded

**Technical approach**:
- Add "Unload Model" button near the model status display
- Button should be disabled/hidden when no model is loaded
- Show confirmation dialog before unloading
- Call model manager to release model resources
- Update status display after unloading
- Next transcription should trigger model reload
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 if its a web ui related task
- [ ] #2 always validate visually with Playwright MCP and attache screenshots
<!-- DOD:END -->



## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Unload Model button visible when a model is loaded
- [ ] #2 Button disabled or hidden when no model is loaded
- [ ] #3 Tapping unload releases model from memory
- [ ] #4 Status display updates to show no model loaded
- [ ] #5 Confirmation dialog shown before unloading
- [ ] #6 Subsequent transcription triggers model reload as needed
<!-- AC:END -->
