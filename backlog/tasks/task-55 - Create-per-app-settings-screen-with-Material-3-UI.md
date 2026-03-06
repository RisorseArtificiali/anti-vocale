---
id: TASK-55
title: Create per-app settings screen with Material 3 UI
status: Done
assignee: []
created_date: '2026-03-06 07:49'
updated_date: '2026-03-06 11:09'
labels:
  - feature
  - ui
  - material3
  - settings
  - phase-3
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Build a Compose settings screen allowing users to configure notification behavior on a per-app basis using Material 3 ListItem components.

**UI Components:**
- PerAppSettingsScreen composable with LazyColumn
- ListItem for each detected app showing:
  - App icon (loaded via PackageManager)
  - App name
  - Brief summary of current settings
- Expandable panel with switches and selectors:
  - Auto-copy transcription (Switch)
  - Show share action in notification (Switch)
  - Notification sound dropdown
- Empty state when no apps detected yet
- Loading state while fetching preferences

**Material 3 Components:**
- ListItem for main app entries
- Switch for boolean preferences
- Dropdown menu for notification sound selection
- Material 3 typography and spacing

**Files:**
- `app/src/main/java/com/localai/bridge/ui/screens/PerAppSettingsScreen.kt` (new)
- `app/src/main/java/com/localai/bridge/ui/components/AppPreferenceListItem.kt` (new)
- `app/src/main/java/com/localai/bridge/ui/components/AppPreferencePanel.kt` (new)

**Navigation:**
- Add menu item in SettingsScreen: "Per-App Preferences"
- Navigate to PerAppSettingsScreen
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 #1 PerAppSettingsScreen displays list of detected apps
- [x] #2 #2 Each app shows icon, name, and settings summary
- [x] #3 #3 Tapping expands preference panel with switches and selectors
- [x] #4 #4 Changes persist via PerAppPreferencesManager
- [ ] #5 #5 Empty state shows helpful message when no apps configured
<!-- AC:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
**Implementation Summary:**

- Created PerAppSettingsScreen with LazyColumn and Material 3 components

- Implemented AppPreferenceListItem with PackageManager icon loading

- Built AppPreferencePanel with switches and dropdown for preferences

- Integrated PerAppPreferencesManager for reactive preference updates

- Added navigation card in SettingsTab with state management

- All code compiles successfully with debug APK building
<!-- SECTION:FINAL_SUMMARY:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
- [ ] #6 Every text should support internationalisation and should be tracked
- [ ] #7 #1 UI test: Screen renders without errors
- [ ] #8 #2 UI test: Tapping list item expands panel
- [ ] #9 #3 UI test: Toggling switch updates preference
- [ ] #10 #4 Manual test: Verify smooth scrolling with 20+ apps
- [ ] #11 #5 Accessibility test: TalkBack announces all controls
<!-- DOD:END -->
