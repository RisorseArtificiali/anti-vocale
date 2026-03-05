---
id: TASK-45
title: Add search functionality to Logs tab
status: To Do
assignee: []
created_date: '2026-03-03 05:36'
updated_date: '2026-03-04 15:58'
labels:
  - enhancement
  - logs
  - ux
dependencies: []
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement full-text search within transcriptions in the Logs tab to allow users to find specific content in their transcription history.

**Current state**: Users must scroll through all logs to find specific transcriptions
**Desired state**: Search bar that filters logs by transcription content

**Location**: 
- `LogsScreen.kt` - add search bar UI
- `LogsViewModel.kt` - implement search filtering logic

**Technical approach**:
- Add search bar to Logs tab header (TopAppBar or above list)
- Filter logs in real-time as user types
- Search should match against `LogEntry.result` field (transcription text)
- Show "no results" message when search yields no matches
- Add clear button (X) to reset filter
- Consider highlighting matched text in results (optional enhancement)
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 if its a web ui related task
- [ ] #2 always validate visually with Playwright MCP and attache screenshots
<!-- DOD:END -->



## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Search bar visible in Logs tab header
- [ ] #2 Typing filters logs to show only matching transcriptions
- [ ] #3 Search matches against transcription text
- [ ] #4 Empty state shows when no matches found
- [ ] #5 Clear button resets search and shows all logs
- [ ] #6 Search works offline (no network required)
<!-- AC:END -->
