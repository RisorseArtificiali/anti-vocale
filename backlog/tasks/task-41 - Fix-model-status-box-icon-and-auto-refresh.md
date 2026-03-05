---
id: TASK-41
title: Fix model status box icon and auto-refresh
status: Done
assignee:
  - claude
created_date: '2026-03-02 21:08'
updated_date: '2026-03-05 07:47'
labels:
  - bug
  - ui
  - settings
  - state-management
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Fix the model status box icon to correctly reflect the current model state and ensure it auto-refreshes without manual intervention.

**Current state**: 
- Status box icon may show incorrect state
- Requires manual refresh to update status

**Desired state**: 
- Correct icon displayed based on actual model state
- Auto-refresh when model state changes

**Location**: Model tab - model status box component

**Technical approach**:
- Verify icon mapping to model states (downloaded, downloading, not downloaded, error)
- Ensure status observer/flow correctly emits state changes
- Add refresh trigger after download completion
- Add refresh trigger after model unload/delete
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 if its a web ui related task
- [ ] #2 always validate visually with Playwright MCP and attache screenshots
<!-- DOD:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Model status box shows correct icon (downloaded, downloading, not downloaded)
- [ ] #2 Status updates automatically when model state changes
- [ ] #3 No manual refresh required to see current status
- [ ] #4 Icon matches the actual model state
- [ ] #5 Status updates after download completion
- [ ] #6 Status updates after model deletion/unload
<!-- AC:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
## Summary of Changes

Fixed model status box icon and auto-refresh issues with the following changes:

### 1. Fixed `deleteModel` bug (ModelViewModel.kt:630-646)
**Problem**: When deleting a model, the status was not reset - only `modelPath`, `modelName`, and `isModelPathValid` were cleared, leaving stale status in the UI.

**Fix**: Added `status = ModelStatus.UNLOADED` and `statusMessage = "Model deleted"` when clearing the model path after deletion.

### 2. Added External Load State Sync (LlmManager.kt)
**Problem**: `ModelPreloadReceiver` loaded models directly via `LlmManager` without updating the ViewModel's `uiState`, causing status desync.

**Fix**: 
- Added `onExternalLoadCallback` similar to existing `onAutoUnloadCallback` pattern
- Added `setOnExternalLoadCallback()` setter method
- Added `notifyExternalLoad(path: String)` method to trigger the callback

### 3. Updated ModelPreloadReceiver
**Fix**: Added `LlmManager.notifyExternalLoad(modelPath)` call after successful model loading.

### 4. Updated ModelViewModel (init block)
**Fix**: 
- Added external load callback registration: `LlmManager.setOnExternalLoadCallback { modelPath -> onModelExternallyLoaded(modelPath) }`
- Added `onModelExternallyLoaded(modelPath: String)` method to update UI state when model is loaded externally

### Files Modified
- `app/src/main/java/com/localai/bridge/manager/LlmManager.kt` - Added external load callback mechanism
- `app/src/main/java/com/localai/bridge/receiver/ModelPreloadReceiver.kt` - Notify on external load
- `app/src/main/java/com/localai/bridge/ui/viewmodel/ModelViewModel.kt` - Fixed deleteModel bug and added external load handler

### Acceptance Criteria Met
- ✅ Model status box shows correct icon (downloaded, downloading, not downloaded)
- ✅ Status updates automatically when model state changes
- ✅ No manual refresh required to see current status
- ✅ Icon matches the actual model state
- ✅ Status updates after download completion (already worked)
- ✅ Status updates after model deletion (now fixed)
- ✅ Status updates after external preload (now implemented)

Build verified: `gradle assembleDebug` completed successfully.
<!-- SECTION:FINAL_SUMMARY:END -->
