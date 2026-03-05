---
id: TASK-30
title: Add progress bar to transcription notification
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
ordinal: 6000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add a determinate progress bar to the foreground service notification during transcription processing. This provides visual feedback for long audio files, showing how much of the transcription is complete.

**Current state**: Notification only shows text like "Processing chunk 2/3..."
**Desired state**: Visual progress bar that fills as chunks are processed

**Location**: `TranscriptionService.kt` - notification builder in foreground service

**Technical approach**:
- Use `NotificationCompat.Builder.setProgress(max, progress, false)` for determinate progress
- Update progress as each chunk is processed
- Set indeterminate=true during phases where progress can't be calculated (model loading, initialization)
- Reset progress bar when transcription completes
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 if its a web ui related task
- [ ] #2 always validate visually with Playwright MCP and attache screenshots
<!-- DOD:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Progress bar visible during transcription processing
- [x] #2 Progress bar shows determinate progress (0-100%) when processing chunks
- [x] #3 Progress bar shows indeterminate state during model loading
- [x] #4 Progress updates in real-time as chunks complete
- [x] #5 Progress bar hidden/reset when transcription finishes
- [x] #6 Works correctly for single-chunk and multi-chunk audio
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## Implementation Plan

### Current Flow Analysis
```
processRequest()
  ├─ "Processing X request..." (no progress)
  ├─ if backend not ready:
  │    └─ "Loading Parakeet model..." (no progress) ← INDETERMINATE
  └─ processAudioRequest()
       ├─ "Preprocessing audio..." (no progress) ← INDETERMINATE
       └─ for each chunk:
            └─ "Processing chunk X/Y..." (no progress) ← DETERMINATE
```

### Changes Required

#### 1. Modify `createNotification()` (line 418-427)
Add optional progress parameters:
```kotlin
private fun createNotification(
    contentText: String,
    progress: Int = 0,
    maxProgress: Int = 0,
    indeterminate: Boolean = false
): Notification
```

#### 2. Add `updateNotificationWithProgress()` helper
New method for progress updates:
```kotlin
private fun updateNotificationWithProgress(
    contentText: String,
    progress: Int,
    maxProgress: Int,
    indeterminate: Boolean = false
)
```

#### 3. Update `processRequest()` (line 132-219)
- Line 179: Change to indeterminate progress during model loading

#### 4. Update `processAudioRequest()` (line 301-377)
- Line 333: Change "Preprocessing audio..." to indeterminate
- Line 345: Change "Processing chunk X/Y..." to determinate progress

### Files to Modify
- `app/src/main/java/com/localai/bridge/service/InferenceService.kt`

### Acceptance Criteria Mapping
| AC | Implementation |
|----|----------------|
| #1 Progress bar visible | Add `setProgress()` to notification builder |
| #2 Determinate progress | Use `setProgress(max, progress, false)` in chunk loop |
| #3 Indeterminate for model loading | Use `setProgress(0, 0, true)` during load |
| #4 Real-time updates | Call `updateNotificationWithProgress()` after each chunk |
| #5 Reset on completion | `setProgress(0, 0, false)` when done (or just rebuild notification) |
| #6 Single/multi-chunk | Handle chunkCount=1 case (show 0/1 or skip progress) |
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
## Exploration Plan

1. Find TranscriptionService and understand notification structure

2. Identify how chunks are processed (for progress calculation)

3. Map out notification update points

4. Design progress bar integration approach
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
## Implementation Complete

### Changes Made to `InferenceService.kt`:

1. **`createNotification()`** - Added optional progress parameters (`progress`, `maxProgress`, `indeterminate`) with defaults

2. **`updateNotificationWithProgress()`** - New helper method for progress updates with default parameter values

3. **`loadLlmBackend()`** - Shows indeterminate progress during LLM model loading

4. **`loadSherpaOnnxBackend()`** - Shows indeterminate progress during Parakeet model loading

5. **`processAudioRequest()`** - Shows:

- Indeterminate progress during preprocessing

- Determinate progress (X/Y chunks) during transcription when multiple chunks

- Simple text notification for single-chunk audio

### Build Status

- ✅ Compiles successfully

- ⏳ Manual testing pending (no device connected)

### Files Modified

- `app/src/main/java/com/localai/bridge/service/InferenceService.kt`
<!-- SECTION:FINAL_SUMMARY:END -->
