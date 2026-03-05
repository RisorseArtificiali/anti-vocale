---
id: TASK-33
title: Show audio duration in transcription notification
status: Done
assignee: []
created_date: '2026-03-02 21:03'
updated_date: '2026-03-04 17:43'
labels:
  - enhancement
  - notifications
  - ux
dependencies: []
priority: low
ordinal: 1000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Display the total audio duration in the transcription notification so users know how long the source audio is.

**Current state**: No indication of audio length in notification
**Desired state**: Shows duration like "Duration: 2:34" in notification

**Location**: `TranscriptionService.kt` - notification content

**Technical approach**:
- Extract duration from audio file metadata or calculate from audio data
- Display in notification content text or sub-text
- Format consistently (MM:SS for under 1 hour, HH:MM:SS for longer)
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 if its a web ui related task
- [ ] #2 always validate visually with Playwright MCP and attache screenshots
<!-- DOD:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Audio duration visible in transcription notification
- [x] #2 Duration extracted correctly from various audio formats (OGG/Opus, MP3, WAV, M4A)
- [x] #3 Format is human-readable (MM:SS or HH:MM:SS)
- [x] #4 Duration shown before transcription starts
- [x] #5 Works for single files and multi-chunk audio
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
## Implementation Complete (2026-03-04)

**Changes:**

- Added `durationSeconds` param to `createNotification()`

- Added `formatDuration()` helper (MM:SS or HH:MM:SS)

- Updated notification calls in `processAudioRequest()` to pass duration

- Duration shown in notification subtext

**Build:** ✅ Successful
<!-- SECTION:NOTES:END -->
