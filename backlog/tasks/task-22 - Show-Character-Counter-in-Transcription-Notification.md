---
id: task-22
title: Show Character Counter in Transcription Notification
status: Done
assignee: []
created_date: '2026-03-01 19:44'
updated_date: '2026-03-01 20:17'
labels:
  - enhancement
  - notification
  - ux
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
The Android notification displaying the transcription cuts off long messages. Since notifications have character limits, add a counter to show how much of the message is visible.

**Current behavior:** Long transcripts are truncated with no indication of missing content.

**Proposed solution:**
- Display character counter like "150/500" showing visible vs total characters
- Or expand notification to show more text when possible
- Consider using big text style notification for longer content

**User story:** As a user, I want to know if the notification is showing the full transcript or just a portion of it.
<!-- SECTION:DESCRIPTION:END -->
