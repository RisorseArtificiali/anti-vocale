---
id: TASK-63
title: Add Quick Share Back toggle to per-app settings
status: Done
assignee: []
created_date: '2026-03-06 08:07'
updated_date: '2026-03-06 13:23'
labels:
  - feature
  - ux
  - settings
  - share-back
  - phase-3
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add a "Quick Share Back" toggle switch to each app's preference panel allowing users to choose between one-tap direct sharing vs. traditional share sheet picker.

**Toggle Behavior:**
- Quick Share Back ON: "Send to [App]" button opens app directly with pre-filled text
- Quick Share Back OFF: Regular "Share" button shows Android share sheet to pick destination

**UI Implementation:**
- Add toggle switch in AppPreferencePanel
- Label: "Quick Share Back"
- Description: "One-tap to send transcriptions back to this app"
- Save as boolean preference in PerAppPreferencesManager

**Default Settings:**
- WhatsApp: Quick Share Back ON (most users want quick return to chat)
- Telegram: Quick Share Back ON (same workflow)
- Other apps: Quick Share Back OFF (share sheet gives more flexibility)

**Preference Storage:**
Extend AppNotificationPreferences data class:
```kotlin
data class AppNotificationPreferences(
    val autoCopy: Boolean,
    val showShareAction: Boolean,
    val notificationSound: String,
    val quickShareBack: Boolean = true // NEW
)
```

**Files:**
- `app/src/main/java/com/localai/bridge/ui/components/AppPreferencePanel.kt` (modify)
- `app/src/main/java/com/localai/bridge/data/AppNotificationPreferences.kt` (modify)
- `app/src/main/java/com/localai/bridge/data/PerAppPreferencesManager.kt` (modify)
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 #1 'Quick Share Back' toggle added to per-app preference panel
- [x] #2 #2 When ON, Share Back button bypasses share sheet
- [x] #3 #3 When OFF, regular share button shows share sheet
- [x] #4 #4 Default to ON for WhatsApp and Telegram
- [x] #5 #5 Setting persists across app restarts
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Code compiles without errors or warnings
- [x] #2 Feature tested on physical device or emulator
- [x] #3 No regressions in existing functionality
- [x] #4 Edge cases handled appropriately
- [x] #5 UI follows Material Design guidelines
- [x] #6 Every text should support internationalisation and should be tracked
- [x] #7 #1 UI test: Toggle appears in each app's preference panel
- [x] #8 #2 UI test: Toggling ON enables Quick Share Back
- [x] #9 #3 UI test: Toggling OFF disables Quick Share Back
- [x] #10 #4 Manual test: WhatsApp with Quick Share ON → direct app open
- [x] #11 #5 Manual test: WhatsApp with Quick Share OFF → share sheet appears
<!-- DOD:END -->
