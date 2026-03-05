---
id: TASK-39
title: Fix Gemma model download scroll to error area
status: Done
assignee: []
created_date: '2026-03-02 21:07'
updated_date: '2026-03-04 18:26'
labels:
  - bug
  - ui
  - model-download
dependencies: []
priority: medium
ordinal: 62.5
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
When clicking on a Gemma model for download, the page incorrectly scrolls to the bottom error reporting area, even when there are no errors to display.

**Current state**: Clicking Gemma model scrolls to error area (bottom of page)
**Desired state**: No scroll to error area, or scroll to relevant section (download progress)

**Location**: Model tab / model download UI - likely in model selection click handler

**Technical approach**:
- Identify what triggers the scroll to error area
- Add condition to only scroll when errors exist
- Alternatively, remove auto-scroll behavior entirely
- Ensure download progress UI is visible when download starts
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 if its a web ui related task
- [ ] #2 always validate visually with Playwright MCP and attache screenshots
<!-- DOD:END -->



## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Clicking a Gemma model no longer scrolls to error area when no errors exist
- [ ] #2 Page scrolls to download progress section when download begins
- [ ] #3 Error area only scrolls into view when there are actual errors to display
- [ ] #4 Normal model selection (non-Gemma) unaffected
<!-- AC:END -->
