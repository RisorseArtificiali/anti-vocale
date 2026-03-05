---
id: TASK-34
title: Add Share action to result notification
status: Done
assignee: []
created_date: '2026-03-02 21:03'
updated_date: '2026-03-04 18:25'
labels:
  - enhancement
  - notifications
  - ux
dependencies: []
priority: medium
ordinal: 4000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add a "Share" action button to the transcription result notification to allow forwarding the text directly to other apps (WhatsApp, Telegram, etc.).

**Current state**: Result notification only shows transcription text with Copy action
**Desired state**: Share action that opens Android share sheet with transcription text

**Location**: `TranscriptionService.kt` - result notification builder

**Technical approach**:
- Add share action using `addAction()` with a share PendingIntent
- Create an `ACTION_SEND` intent with `Intent.EXTRA_TEXT` containing transcription
- Use `Intent.createChooser()` to let user pick destination app
- Add alongside existing "Copy" action (both can coexist)
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 if its a web ui related task
- [ ] #2 always validate visually with Playwright MCP and attache screenshots
<!-- DOD:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Share action button visible in result notification
- [x] #2 Tapping share opens Android share chooser
- [x] #3 Share intent contains transcription text as EXTRA_STREAM or EXTRA_TEXT
- [x] #4 User can select any app to share to (WhatsApp, Telegram, Email, etc.)
- [x] #5 Works alongside existing Copy action
- [x] #6 Notification remains visible after sharing
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
## Implementation Complete (2026-03-04)

**Files modified:** strings.xml, NotificationActionReceiver.kt, InferenceService.kt

**Build:** ✅ Successful

**Notification now has:** Copy | Share actions
<!-- SECTION:NOTES:END -->
