---
id: task-13
title: Fix multi-chunk audio transcription only showing first chunk result
status: To Do
assignee: []
created_date: '2026-03-01 08:15'
labels:
  - bug
  - audio
  - transcription
dependencies: []
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
**Bug Report:**

When transcribing long audio messages that require multiple chunks (e.g., 3 chunks), only the first chunk's transcription appears in the logs and notification, despite all chunks being processed.

**Observed behavior:**
- Long audio split into 3 chunks
- UI showed "Processing chunk 3/3" (all chunks were processed)
- Only first part of transcription appeared in logs and notification

**Expected behavior:**
- All chunk transcriptions should be concatenated and displayed

**Suspected location:**
- `InferenceService.kt` - `processAudioRequest()` method
- Check how `results` list is accumulated and joined

**Investigation needed:**
1. Verify chunk results are being added to the list
2. Check if there's a race condition in coroutine processing
3. Verify `results.joinToString(" ")` is being called correctly
4. Check if logs/notification are using partial results instead of final combined result
<!-- SECTION:DESCRIPTION:END -->
