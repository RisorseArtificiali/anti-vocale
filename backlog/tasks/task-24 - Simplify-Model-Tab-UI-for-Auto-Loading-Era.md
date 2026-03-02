---
id: task-24
title: Simplify Model Tab UI for Auto-Loading Era
status: Done
assignee: []
created_date: '2026-03-02 06:42'
updated_date: '2026-03-02 10:37'
labels:
  - ui
  - refactor
  - ux-improvement
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
## Overview
Simplify the Model Tab UI now that models auto-load on app start. Remove manual load/unload buttons and simplify the UX to focus on model selection and download.

## Current Problems
1. **Manual Load/Unload buttons** - Unnecessary since models should auto-load
2. **Complex Gallery model section** - Duplicate of download section functionality
3. **Too many cards** - Status, Path, Memory, Previous Model, Gallery - overwhelms users
4. **Confusing UX** - Users shouldn't think about "loading" models

## Proposed Simplifications

### Remove
- "Load Model" / "Unload Model" buttons
- "Previous Model Restore" card (auto-save is sufficient)
- Separate "Import from Google AI Gallery" section (redundant with download section)
- Memory Usage card (not critical for users)

### Simplify
- Status Card: Show only current model name and status (Ready/Downloading/Error)
- Select Model: Keep as secondary option (most users will download)
- Gallery detection: Integrate into download section as "Already downloaded" badge

### Keep
- Download Models section (primary way to get models)
- Model deletion with confirmation
- Token configuration prompt

## Implementation Notes

### Files to Modify
1. **`app/src/main/java/com/localai/bridge/ui/tabs/ModelTab.kt`**
   - Remove `PendingAction.LOAD_MODEL` - no longer needed
   - Remove Load/Unload button row (lines ~380-420)
   - Remove Previous Model Restore Card (lines ~280-320)
   - Remove Memory Info Card (lines ~325-340)
   - Remove separate Gallery section (lines ~440-500)
   - Simplify Status Card to show model name + status only

2. **`app/src/main/java/com/localai/bridge/ui/viewmodel/ModelViewModel.kt`**
   - Remove `previousModelPath`/`previousModelName` from UiState
   - Remove `restorePreviousModel()` function
   - Remove `loadPreviousModelPath()` function
   - Consider auto-loading model on ViewModel init if path is valid

3. **`app/src/main/java/com/localai/bridge/data/PreferencesManager.kt`**
   - Remove `previousModelPath` property (keep only `modelPath`)

### UI Flow After Simplification
```
┌─────────────────────────────┐
│  Status Card                │
│  ✅ Gemma 3n E2B (Ready)    │
└─────────────────────────────┘

┌─────────────────────────────┐
│  Download Models            │
│  ┌───────────────────────┐  │
│  │ Gemma 3n E2B ★        │  │
│  │ 400MB | Recommended   │  │
│  │ [Use] [Delete]        │  │
│  └───────────────────────┘  │
│  ┌───────────────────────┐  │
│  │ Gemma 3n E4B          │  │
│  │ 800MB                 │  │
│  │ [Download] [View]     │  │
│  └───────────────────────┘  │
└─────────────────────────────┘

┌─────────────────────────────┐
│  Or select from device...   │
│  [Browse Files]             │
└─────────────────────────────┘
```

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 No manual Load/Unload buttons
- [ ] #2 Model auto-loads when selected/downloaded
- [ ] #3 Previous model restore removed
- [ ] #4 Gallery import section removed or integrated
- [ ] #5 Status Card shows model name + status only
- [ ] #6 Download section is the primary UX
- [ ] #7 Build passes and app runs correctly
<!-- SECTION:DESCRIPTION:END -->

<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
### Files to Modify
1. **`app/src/main/java/com/localai/bridge/ui/tabs/ModelTab.kt`**
   - Remove `PendingAction.LOAD_MODEL` - no longer needed
   - Remove Load/Unload button row (lines ~380-420)
   - Remove Previous Model Restore Card (lines ~280-320)
   - Remove Memory Info Card (lines ~325-340)
   - Remove separate Gallery section (lines ~440-500)
   - Simplify Status Card to show model name + status only

2. **`app/src/main/java/com/localai/bridge/ui/viewmodel/ModelViewModel.kt`**
   - Remove `previousModelPath`/`previousModelName` from UiState
   - Remove `restorePreviousModel()` function
   - Remove `loadPreviousModelPath()` function
   - Consider auto-loading model on ViewModel init if path is valid

3. **`app/src/main/java/com/localai/bridge/data/PreferencesManager.kt`**
   - Remove `previousModelPath` property (keep only `modelPath`)

### UI Flow After Simplification
```
┌─────────────────────────────┐
│  Status Card                │
│  ✅ Gemma 3n E2B (Ready)    │
└─────────────────────────────┘

┌─────────────────────────────┐
│  Download Models            │
│  ┌───────────────────────┐  │
│  │ Gemma 3n E2B ★        │  │
│  │ 400MB | Recommended   │  │
│  │ [Use] [Delete]        │  │
│  └───────────────────────┘  │
│  ┌───────────────────────┐  │
│  │ Gemma 3n E4B          │  │
│  │ 800MB                 │  │
│  │ [Download] [View]     │  │
│  └───────────────────────┘  │
└─────────────────────────────┘

┌─────────────────────────────┐
│  Or select from device...   │
│  [Browse Files]             │
└─────────────────────────────┘
```
<!-- SECTION:NOTES:END -->
