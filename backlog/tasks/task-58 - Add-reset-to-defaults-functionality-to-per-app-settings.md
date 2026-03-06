---
id: TASK-58
title: Add reset to defaults functionality to per-app settings
status: To Do
assignee: []
created_date: '2026-03-06 07:49'
labels:
  - feature
  - ui
  - settings
  - reset
  - phase-5
dependencies: []
priority: low
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement functionality to reset all per-app preferences or individual app preferences to default values.

**UI Components:**
- "Reset All to Defaults" button in PerAppSettingsScreen (with confirmation dialog)
- "Reset to Defaults" button in each app's preference panel
- Confirmation dialog explaining what will be reset

**Implementation:**
- Add `resetAllPreferences()` method to PerAppPreferencesManager
- Add `resetPreferencesForPackage(packageName)` method
- Clear DataStore preferences and re-apply defaults
- Show snackbar confirmation after reset

**Files:**
- `app/src/main/java/com/localai/bridge/ui/screens/PerAppSettingsScreen.kt` (modify)
- `app/src/main/java/com/localai/bridge/data/PerAppPreferencesManager.kt` (modify)
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 #1 'Reset All' button shows confirmation dialog
- [ ] #2 #2 Confirmed reset clears all per-app preferences
- [ ] #3 #3 Individual app reset clears only that app's preferences
- [ ] #4 #4 Defaults re-applied after reset
- [ ] #5 #5 Snackbar confirms reset action
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
- [ ] #6 Every text should support internationalisation and should be tracked
- [ ] #7 #1 Unit test: resetAllPreferences() clears DataStore
- [ ] #8 #2 Unit test: resetPreferencesForPackage() clears specific app
- [ ] #9 #3 UI test: Reset button shows confirmation
- [ ] #10 #4 Manual test: Reset all → verify preferences cleared
<!-- DOD:END -->
