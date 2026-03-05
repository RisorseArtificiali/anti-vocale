---
id: TASK-8
title: Fix "Need a Model" Box Visibility and Scrolling
status: Done
assignee: []
created_date: '2026-02-28 18:03'
updated_date: '2026-03-04 18:25'
labels:
  - bug
  - ui
  - scroll
dependencies: []
priority: high
ordinal: 34000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
The "Need a Model" box on the main screen is partially cut off and there's no scroll mechanism to view the full content.

## User Story

As a user, I want to see the complete "Need a Model" section without it being cut off, so I can understand my options for getting a model.

## Problem

- The "Need a Model" box extends beyond the visible screen area
- No vertical scrolling is available on the main screen
- Users cannot access the full content or any buttons below the fold
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 [Visibility] All content in 'Need a Model' box is fully visible without scrolling
- [ ] #2 [Scroll] Page is scrollable if content exceeds screen height
- [ ] #3 [Responsive] Layout adapts to different screen sizes
- [ ] #4 [Access] All buttons in the box are tappable
<!-- AC:END -->
