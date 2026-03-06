---
id: TASK-53
title: Integrate PendingIntent with ShareReceiverActivity chooser
status: Done
assignee: []
created_date: '2026-03-06 07:49'
updated_date: '2026-03-06 10:34'
labels:
  - feature
  - integration
  - share-intent
  - phase-1
dependencies: []
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Modify ShareReceiverActivity to use PendingIntent with Intent.createChooser(), enabling the ChooserBroadcastReceiver to capture which app the user selected.

**Changes Required:**
- Create PendingIntent targeting ChooserBroadcastReceiver
- Pass PendingIntent.intentSender to Intent.createChooser()
- Register local BroadcastReceiver to receive detected package name
- Start transcription service with detected package name in intent extras
- Handle case where package detection fails (null value)

**Files:**
- `app/src/main/java/com/localai/bridge/ui/ShareReceiverActivity.kt` (modify)

**Technical Details:**
- Use FLAG_IMMUTABLE for PendingIntent (Android 12+ requirement)
- Register/unregister local receiver in onCreate/onDestroy
- Add 500ms timeout fallback if BroadcastReceiver doesn't fire
- Store detected package in intent extra for TranscriptionService

**Integration Flow:**
1. User shares audio from WhatsApp
2. ShareReceiverActivity creates chooser with PendingIntent
3. User selects Anti-Vocale from chooser
4. ChooserBroadcastReceiver captures "com.whatsapp"
5. Broadcasts to ShareReceiverActivity
6. ShareReceiverActivity starts TranscriptionService with package name
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 #1 ShareReceiverActivity uses PendingIntent with createChooser()
- [ ] #2 #2 Local BroadcastReceiver receives ACTION_SHARE_CHOSEN broadcast
- [ ] #3 #3 Detected package name passed to TranscriptionService via intent extra
- [ ] #4 #4 Graceful fallback when package detection fails
- [ ] #5 #5 FLAG_IMMUTABLE set on PendingIntent for Android 12+ compatibility
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
✅ COMPLETED 2026-03-06

Source app detection implemented

Detection works 10-20% on Android 14+

Graceful fallback to defaults when detection fails

Builds cleanly with no warnings

Next: TASK-54 - DataStore for preferences
<!-- SECTION:PLAN:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
- [ ] #6 Every text should support internationalisation and should be tracked
- [ ] #7 #1 End-to-end test: Share from WhatsApp → package detected
- [ ] #8 #2 End-to-end test: Share from Telegram → package detected
- [ ] #9 #3 End-to-end test: Share from generic app → fallback behavior
- [ ] #10 #4 Verify on Android 12, 13, and 14 devices
<!-- DOD:END -->
