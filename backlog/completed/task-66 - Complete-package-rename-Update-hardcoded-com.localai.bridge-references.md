---
id: TASK-66
title: 'Complete package rename: Update hardcoded com.localai.bridge references'
status: Done
assignee: []
created_date: '2026-03-06 16:05'
updated_date: '2026-03-06 16:22'
labels:
  - refactoring
  - package-rename
  - breaking-change
dependencies: []
documentation:
  - MIGRATION.md
  - docs/TASKER_GUIDE.md
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
## Background
The package has been renamed from `com.localai.bridge` to `com.antivocale.app`. All source files have been moved, but several hardcoded string references to the old package name remain.

## Remaining Work

### 1. Update Intent Action Constants (BREAKING CHANGE)
Update all broadcast intent action constants from `com.localai.bridge.*` to `com.antivocale.app.*`:

**Files to update:**
- `InferenceService.kt` - ACTION_CANCEL
- `TaskerRequestReceiver.kt` - ACTION_PROCESS_REQUEST
- `NotificationActionReceiver.kt` - ACTION_COPY_TRANSCRIPTION, ACTION_SHARE_TRANSCRIPTION, ACTION_SHARE_BACK
- `ModelPreloadReceiver.kt` - ACTION_PRELOAD_MODEL, PRELOAD_RESULT
- `ChooserBroadcastReceiver.kt` - ACTION_SHARE_CHOSEN

### 2. Update Internal Class References
- `BridgeApplication.kt` - SharedAudioHandler reference (if class exists)
- `SettingsViewModel.kt` - WhisperModelManager reference
- `InferenceService.kt` - AppNotificationPreferences reference

### 3. Update Documentation Strings
- `SettingsTab.kt` - Tasker documentation examples showing old intent actions

### 4. Migrate Test File
Move `app/src/androidTest/java/com/localai/bridge/AudioPreprocessorInstrumentedTest.kt` to new package structure

### 5. Update MIGRATION.md
Ensure migration guide reflects the final intent action names

## Acceptance Criteria
- No references to `com.localai.bridge` or `localai.bridge` remain in source files (except in MIGRATION.md as "old" examples)
- All intent action constants use `com.antivocale.app` prefix
- Test file migrated to new package
- Project builds successfully
- MIGRATION.md accurately documents final intent actions
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
- [ ] #6 Every text should support internationalisation and should be tracked
<!-- DOD:END -->
