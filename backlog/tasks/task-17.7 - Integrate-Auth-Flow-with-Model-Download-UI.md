---
id: TASK-17.7
title: Integrate Auth Flow with Model Download UI
status: Done
assignee: []
created_date: '2026-03-01 12:18'
updated_date: '2026-03-04 18:25'
labels:
  - ui
  - viewmodel
  - integration
dependencies: []
parent_task_id: TASK-17
priority: medium
ordinal: 25000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Connect the authentication flow to the model download UI, triggering auth when needed for gated models.

**Implementation:**
- Add auth state to ModelViewModel
- Trigger auth flow when user tries to download gated model without token
- Store token and proceed with download after successful auth
- Handle edge cases (token expired, auth cancelled, etc.)
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 UI trigger for authentication when accessing gated model
- [ ] #2 Auth state observed in ViewModel
- [ ] #3 Download automatically proceeds after successful auth
- [ ] #4 Error handling for auth failures
<!-- AC:END -->
