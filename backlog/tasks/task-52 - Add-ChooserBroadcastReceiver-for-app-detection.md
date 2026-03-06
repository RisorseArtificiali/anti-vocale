---
id: TASK-52
title: Add ChooserBroadcastReceiver for app detection
status: Done
assignee: []
created_date: '2026-03-06 07:47'
updated_date: '2026-03-06 10:29'
labels:
  - feature
  - broadcast-receiver
  - app-detection
  - phase-1
dependencies: []
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement BroadcastReceiver to capture EXTRA_CHOSEN_COMPONENT from Android share chooser, enabling detection of which app (WhatsApp, Telegram, etc.) initiated the share action.

**Technical Implementation:**
- Create `ChooserBroadcastReceiver.kt` class
- Extract package name from ComponentInfo string format
- Broadcast detected package name via local intent to app
- Register receiver in AndroidManifest with exported="false"
- Handle malformed component info gracefully

**Files:**
- `app/src/main/java/com/localai/bridge/receiver/ChooserBroadcastReceiver.kt` (new)
- `app/src/main/AndroidManifest.xml` (modify)

**Key Methods:**
- `onReceive()`: Extract EXTRA_CHOSEN_COMPONENT
- `extractPackageName()`: Parse "ComponentInfo{com.whatsapp/...}" → "com.whatsapp"
- Broadcast detected package with `ACTION_SHARE_CHOSEN` local intent
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 #1 BroadcastReceiver registered in manifest with exported=false
- [ ] #2 #2 Correctly extracts package name from ComponentInfo format
- [ ] #3 #3 Handles malformed/invalid component info without crashing
- [ ] #4 #4 Broadcasts detected package via local intent restricted to app package
- [ ] #5 #5 Unit tests cover extraction logic with valid and invalid inputs
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## Progress Update (2026-03-06)

✅ Created ChooserBroadcastReceiver.kt

- Parses EXTRA_CHOSEN_COMPONENT from share chooser

- Extracts package names: ComponentInfo{com.whatsapp/...} → com.whatsapp

- Broadcasts detected package via local intent

- Registered in AndroidManifest.xml (exported=false)

- Created comprehensive unit tests

- ⚠️ Build verification pending (Java toolchain needs JDK 17, currently using 25)

Next: TASK-53 - Integrate PendingIntent with ShareReceiverActivity

✅ COMPLETED 2026-03-06

Implementation complete with all tests passing

Extracted to ComponentInfoParser utility for clean testing

All 9 unit tests: 0 failures, 0 errors

Builds successfully with JDK 17

Next: TASK-53 - PendingIntent integration
<!-- SECTION:PLAN:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
- [ ] #6 Every text should support internationalisation and should be tracked
- [ ] #7 #1 Unit tests pass for package name extraction
- [ ] #8 #2 Manual testing on Android 14 device with WhatsApp share
- [ ] #9 #3 Manual testing on Android 14 device with Telegram share
- [ ] #10 #4 No ANR or crashes when BroadcastReceiver fires
<!-- DOD:END -->
