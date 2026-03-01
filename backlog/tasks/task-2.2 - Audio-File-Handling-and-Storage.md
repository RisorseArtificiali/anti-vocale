---
id: task-2.2
title: Audio File Handling and Storage
status: Done
assignee: []
created_date: '2026-02-28 17:59'
updated_date: '2026-03-01 08:17'
labels:
  - storage
  - file-handling
dependencies:
  - task-2.1
parent_task_id: task-2
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement file handling for shared audio content.

**Scope:**
- ContentResolver for content:// URIs
- File copying to app-private storage
- Format detection and validation
- Unique filename generation

**Parent:** task-2
**Depends on:** task-2.1

**Optional Enhancement**: While sender app identification is unreliable, the content URI authority may sometimes hint at the source (e.g., `com.whatsapp.provider.media`). This can be logged for analytics but should not affect core functionality.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 [Content URI] App copies files from content:// URIs via ContentResolver
- [ ] #2 [File URI] App handles file:// URIs directly
- [ ] #3 [Formats] Supports .ogg, .m4a, .mp3, .wav, .aac formats
- [ ] #4 [Storage] Copied files are accessible to transcription service
<!-- AC:END -->
