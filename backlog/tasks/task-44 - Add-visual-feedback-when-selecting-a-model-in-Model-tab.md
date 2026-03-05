---
id: TASK-44
title: Add visual feedback when selecting a model in Model tab
status: Done
assignee: []
created_date: '2026-03-02 21:52'
updated_date: '2026-03-04 18:26'
labels:
  - ux
  - enhancement
  - model-selection
dependencies: []
priority: low
ordinal: 125
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add visual feedback when a user selects/taps a model in the Model tab, making it clear which model is currently selected or being interacted with.

**Current state**: Tapping a model has no immediate visual feedback
**Desired state**: Clear visual indication of model selection (highlight, ripple, animation)

**Location**: Model tab - model card/list components

**Technical approach**:
- Add selection state to model card
- Apply visual feedback: background highlight, border, or checkmark
- Use Material ripple effect for tap feedback
- Consider subtle animation for selection change
- Ensure selected model is visually distinct from unselected
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 if its a web ui related task
- [ ] #2 always validate visually with Playwright MCP and attache screenshots
<!-- DOD:END -->



## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Visual feedback (highlight, animation) when selecting a model
- [ ] #2 Selected model is clearly distinguishable from others
- [ ] #3 Feedback is immediate (no delay)
- [ ] #4 Selection state persists visually until another model is selected
- [ ] #5 Works for both Whisper and Parakeet models
<!-- AC:END -->
