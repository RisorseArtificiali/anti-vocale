---
id: task-18
title: Add Model Deletion with Confirmation Dialog
status: Done
assignee: []
created_date: '2026-03-01 18:28'
updated_date: '2026-03-01 19:25'
labels:
  - enhancement
  - ui
  - storage
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement a feature that allows users to delete downloaded models from the device. This should include:

1. A delete/trash button on each downloaded model card
2. A confirmation dialog to prevent accidental deletions
3. Proper cleanup of model files from storage
4. UI state update after deletion

**User story:** As a user, I want to remove downloaded models to free up storage space, with a confirmation step to prevent accidental deletion.

**Acceptance Criteria:**
- [ ] Delete button visible only on downloaded models
- [ ] Confirmation dialog appears before deletion
- [ ] Model files are completely removed from storage
- [ ] UI updates to show model as "not downloaded" after deletion
- [ ] No crashes if user tries to use a deleted model
<!-- SECTION:DESCRIPTION:END -->
