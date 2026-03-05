---
id: TASK-31
title: Add elapsed time counter to transcription notification
status: Done
assignee: []
created_date: '2026-03-02 21:03'
updated_date: '2026-03-04 18:25'
labels:
  - enhancement
  - notifications
  - ux
dependencies: []
priority: low
ordinal: 500
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add an elapsed time counter to the transcription notification showing how long the current transcription has been running.

**Current state**: No indication of how long transcription has been running
**Desired state**: Shows elapsed time like "00:45" or "1:23" in notification

**Location**: `TranscriptionService.kt` - notification builder

**Technical approach**:
- Start a timer when transcription begins
- Use `setChronometer()` or update notification text periodically
- Stop timer when transcription completes
- Consider using `setUsesChronometer(true)` with `setWhen()` for system-managed timing
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 if its a web ui related task
- [ ] #2 always validate visually with Playwright MCP and attache screenshots
<!-- DOD:END -->



## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Elapsed time visible in transcription notification
- [ ] #2 Timer starts when transcription begins
- [ ] #3 Timer updates in real-time (at least every second)
- [ ] #4 Timer stops when transcription completes
- [ ] #5 Format is human-readable (MM:SS or HH:MM:SS)
- [ ] #6 Timer resets for subsequent transcriptions
<!-- AC:END -->
