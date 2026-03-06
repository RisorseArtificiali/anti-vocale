---
id: TASK-54
title: Add DataStore dependency and create PerAppPreferencesManager
status: Done
assignee: []
created_date: '2026-03-06 07:49'
updated_date: '2026-03-06 10:43'
labels:
  - feature
  - datastore
  - preferences
  - phase-2
dependencies: []
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Set up Jetpack DataStore infrastructure for storing per-app notification preferences keyed by package name.

**Dependencies:**
- Add `androidx.datastore:datastore-preferences:1.1.1` to build.gradle.kts
- Add testing dependency `datastore-preferences-core-testing`

**Implementation:**
- Create `PerAppPreferencesManager.kt` class
- Define preference key factories for package-specific settings:
  - `autoCopyKeyForPackage(packageName)`: Boolean preference key
  - `showShareActionKeyForPackage(packageName)`: Boolean preference key  
  - `notificationSoundKeyForPackage(packageName)`: String preference key
- Create DataStore instance: `per_app_notification_preferences`
- Implement Flow-based preference observation
- Implement suspend update functions
- Define default preferences for common apps (WhatsApp, Telegram)

**Data Class:**
```kotlin
@Immutable
data class AppNotificationPreferences(
    val autoCopy: Boolean,
    val showShareAction: Boolean,
    val notificationSound: String
)
```

**Files:**
- `app/build.gradle.kts` (modify)
- `app/src/main/java/com/localai/bridge/data/PerAppPreferencesManager.kt` (new)
- `app/src/main/java/com/localai/bridge/data/AppNotificationPreferences.kt` (new)
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 #1 DataStore dependency added to build.gradle
- [ ] #2 #2 PerAppPreferencesManager created with Flow-based API
- [ ] #3 #3 Package-specific preference keys defined
- [ ] #4 #4 Default preferences defined for WhatsApp and Telegram
- [ ] #5 #5 Update methods use atomic edit() transactions
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
✅ COMPLETED 2026-03-06

DataStore infrastructure complete

PerAppPreferencesManager with Flow-based API

AppNotificationPreferences data class

Smart defaults for WhatsApp/Telegram

Builds successfully (debug APK tested)

Next: TASK-55 - Settings UI (Material 3)
<!-- SECTION:PLAN:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
- [ ] #6 Every text should support internationalisation and should be tracked
- [ ] #7 #1 Unit tests for default preferences
- [ ] #8 #2 Unit tests for preference updates
- [ ] #9 #3 Unit tests for Flow emission on changes
- [ ] #10 #4 Verify preferences persist across app restart
<!-- DOD:END -->
