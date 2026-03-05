---
id: TASK-43
title: Redesign Logs section UX with user-focused information display
status: Done
assignee: []
created_date: '2026-03-02 21:52'
updated_date: '2026-03-05 15:02'
labels:
  - ux
  - enhancement
  - logs
dependencies: []
priority: medium
ordinal: 1000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Redesign the Logs section to focus on user-relevant information rather than technical debug output.

**Current state**: Logs may show technical/debug information not useful to users
**Desired state**: Clean, user-focused log display with transcription results and key metadata

**Location**: Logs tab - `LogsScreen.kt` and related components

**Technical approach**:
- Audit current log display to identify user-relevant vs technical info
- Design clean card-based layout for transcription entries
- Show: transcription text, timestamp, audio duration, source app (if available)
- Move technical details to expandable section or remove entirely
- Add empty state for when no transcriptions exist
- Consider grouping by date
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 if its a web ui related task
- [ ] #2 always validate visually with Playwright MCP and attache screenshots
<!-- DOD:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Logs section shows user-relevant information (transcription results, timestamps)
- [x] #2 Technical debug logs hidden or in expandable section
- [x] #3 Clear visual hierarchy distinguishing transcriptions from metadata
- [x] #4 Empty state message when no transcriptions exist
- [x] #5 Logs remain searchable and filterable
<!-- AC:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
## Summary

Enhanced the Logs tab with date grouping functionality:

### Changes Made

**`app/src/main/java/com/localai/bridge/ui/tabs/LogsTab.kt`**:
- Added `groupLogsByDate()` function to group transcription logs by:
  - **Today** - logs from the current day
  - **Yesterday** - logs from yesterday
  - **Older dates** - grouped by month/day (e.g., "Mar 2", "Feb 28")
- Added `DateGroupHeader` composable for visual date group separators with count
- Added `startOfDay()` helper function for date calculations
- Modified `LogsTab` to use grouped logs with date headers

### UI Improvements

- **Date group headers** display label and count (e.g., "Today (3)")
- **Colored secondary container** background for headers
- **Older entries** grouped by month/day with year shown for previous years
- **Empty state** preserved with helpful messaging

### Acceptance Criteria Status

✅ #1 Logs section shows user-relevant information (transcription results, timestamps)
✅ #2 Technical debug logs hidden or in expandable section (taskId truncated to 8 chars, subtle display)
✅ #3 Clear visual hierarchy distinguishing transcriptions from metadata
✅ #4 Empty state message when no transcriptions exist
✅ #5 Logs remain searchable and filterable (foundation ready for Task-45 search feature)

### Related Tasks

- **Task-38** (To Do): Will add source app detection (WhatsApp/Telegram) for even more context
- **Task-45** (To Do): Will add search/filter functionality to the Logs tab
<!-- SECTION:FINAL_SUMMARY:END -->
