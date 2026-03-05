---
id: TASK-40
title: Remove redundant size badges from Whisper models
status: Done
assignee: []
created_date: '2026-03-02 21:08'
updated_date: '2026-03-04 18:26'
labels:
  - bug
  - ui
  - cleanup
dependencies: []
priority: low
ordinal: 250
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Remove redundant size badges from Whisper model selection cards. The size information is already displayed elsewhere or not useful for model selection.

**Current state**: Whisper models show size badges that duplicate other information
**Desired state**: Cleaner model cards without redundant size badges

**Location**: Model tab - Whisper model card UI components

**Technical approach**:
- Identify where size badges are rendered for Whisper models
- Remove or conditionally hide size badge component
- Ensure layout remains balanced without the badge
- Consider if size info should remain in expanded/details view
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 if its a web ui related task
- [ ] #2 always validate visually with Playwright MCP and attache screenshots
<!-- DOD:END -->



## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Size badges removed from Whisper model cards
- [ ] #2 Model names remain clear and identifiable
- [ ] #3 Other model information (status, language) still visible
- [ ] #4 UI remains consistent with Parakeet model cards
<!-- AC:END -->
