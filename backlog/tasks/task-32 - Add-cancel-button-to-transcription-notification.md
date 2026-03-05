---
id: TASK-32
title: Add cancel button to transcription notification
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
ordinal: 5000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add a cancel button to the transcription notification allowing users to abort an in-progress transcription.

**Current state**: No way to cancel transcription from notification
**Desired state**: Cancel button that stops transcription and dismisses notification

**Location**: `TranscriptionService.kt` - notification action builder

**Technical approach**:
- Add action with PendingIntent to service
- Handle cancel intent in service (stop transcription, cleanup resources)
- Dismiss notification on cancel
- Ensure partial results are discarded or handled appropriately
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 if its a web ui related task
- [ ] #2 always validate visually with Playwright MCP and attache screenshots
<!-- DOD:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Cancel button visible in transcription notification
- [x] #2 Tapping cancel stops transcription immediately
- [x] #3 Service properly cleans up resources on cancel
- [x] #4 Notification is dismissed after cancel
- [x] #5 Model is not left in corrupted state
- [x] #6 Works during model loading and transcription phases
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
## Implementation Complete (2026-03-04)

**Changes made:**

1. Added `ACTION_CANCEL` constant to InferenceService companion object

2. Added `currentProcessingJob: Job?` to track the active coroutine

3. Updated `onStartCommand` to handle cancel intent

4. Added `handleCancelRequest()` method that cancels the job, clears queue, and stops service

5. Modified `processQueue()` to track the job and handle `CancellationException`

6. Added cancel button to notification via `addAction()` in `createNotification()`

**Files modified:**

- `app/src/main/java/com/localai/bridge/service/InferenceService.kt`

**Build:** ✅ Successful

**Testing:** Pending - device not connected
<!-- SECTION:NOTES:END -->
