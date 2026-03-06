---
id: TASK-67
title: >-
  Fix race condition: Result notification not shown due to premature service
  shutdown
status: Done
assignee: []
created_date: '2026-03-06 17:58'
updated_date: '2026-03-06 22:09'
labels:
  - bug
  - notification
  - race-condition
dependencies: []
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
## Problem
Transcription result notifications are not being created because the service calls `stopSelf()` before the `showResultNotification()` coroutine completes.

## Root Cause
In `InferenceService.processQueue()`:
- `processRequest()` runs synchronously in a coroutine
- At the end, if `isShareRequest` is true, `showResultNotification()` is called
- `showResultNotification()` launches a NEW coroutine with `serviceScope.launch {}`
- The `processQueue()` coroutine IMMEDIATELY continues to `stopSelf()`
- Service destruction cancels all `serviceScope` coroutines
- The `showResultNotification()` coroutine is cancelled before it can create the notification

## Solution Options
1. **Wait for notification job**: Store the Job returned by `serviceScope.launch` in `showResultNotification()` and call `job.join()` before `stopSelf()`
2. **Make showResultNotification suspend**: Change `showResultNotification()` to be a suspend function that doesn't launch its own coroutine
3. **Use runBlocking**: Use `runBlocking` for notification creation to ensure it completes before the service stops

## Files to Modify
- `app/src/main/java/com/antivocale/app/service/InferenceService.kt`
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Code compiles without errors or warnings
- [x] #2 Feature tested on physical device or emulator
- [x] #3 No regressions in existing functionality
- [x] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
- [ ] #6 Every text should support internationalisation and should be tracked
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Converted showResultNotification() and autoCopyIfEnabled() from fire-and-forget coroutines (serviceScope.launch) to suspend functions. They now complete inline within processRequest() before processQueue() calls stopSelf(), eliminating the JobCancellationException. Verified on device: auto-copy, result notification, and service destruction now happen in correct order. Commit: b2b17a8
<!-- SECTION:FINAL_SUMMARY:END -->
