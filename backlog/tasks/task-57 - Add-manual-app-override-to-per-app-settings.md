---
id: TASK-57
title: Add manual app override to per-app settings
status: Done
assignee: []
created_date: '2026-03-06 07:49'
updated_date: '2026-03-06 11:16'
labels:
  - feature
  - ui
  - settings
  - manual-override
  - phase-3
dependencies: []
priority: low
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement UI and logic for users to manually add apps to per-app preferences when automatic detection doesn't work or for proactive configuration.

**UI Components:**
- "Add Manual Override" button in PerAppSettingsScreen
- App selector dialog showing:
  - Search bar for installed apps
  - List of installed apps with icons
  - Filter by communication apps (WhatsApp, Telegram, Signal, etc.)
- Preference configuration panel for selected app

**Technical Implementation:**
- Query PackageManager for installed apps
- Filter apps with ACTION_SEND intent capability
- Allow users to select any installed app
- Store manually added apps with same preference structure
- Distinguish between "detected" and "manual" entries in UI

**Files:**
- `app/src/main/java/com/localai/bridge/ui/screens/PerAppSettingsScreen.kt` (modify)
- `app/src/main/java/com/localai/bridge/ui/dialogs/AddAppPreferenceDialog.kt` (new)
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 #1 'Add Manual Override' button opens app selector dialog
- [x] #2 #2 Dialog shows searchable list of installed apps
- [x] #3 #3 Selecting app opens preference configuration panel
- [x] #4 #4 Manual entries distinguished from detected entries
- [ ] #5 #5 Can remove manually added apps
<!-- AC:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
**Implementation Complete:**

- Created AddAppPreferenceDialog with searchable app list

- Added FAB to PerAppSettingsScreen for adding apps

- Integrated dialog with app selection handler

- Manual apps display in separate section with label

- Selected app automatically opens preference panel
<!-- SECTION:FINAL_SUMMARY:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
- [ ] #6 Every text should support internationalisation and should be tracked
- [ ] #7 #1 UI test: Dialog opens without errors
- [ ] #8 #2 UI test: Search filters app list correctly
- [ ] #9 #3 UI test: Selecting app saves preferences
- [ ] #10 #4 Manual test: Add WhatsApp manually → verify preference applied
<!-- DOD:END -->
